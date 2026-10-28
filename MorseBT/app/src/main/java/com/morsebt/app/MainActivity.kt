package com.morsebt.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), BluetoothService.Listener {

    companion object {
        private const val PREFS = "morse_bt"
        private const val KEY_WPM = "wpm"
        private const val KEY_FREQ = "freq"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_PLAY_ON_SEND = "play_on_send"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var btAdapter: BluetoothAdapter? = null
    private var bt: BluetoothService? = null
    private val player = MorsePlayer()
    private val keyer = LiveKeyer()
    private lateinit var localDecoder: KeyDecoder
    private lateinit var remoteDecoder: KeyDecoder

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    // Vistas
    private lateinit var lamp: View
    private lateinit var statusText: TextView
    private lateinit var listenButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var settingsPanel: View
    private lateinit var wpmLabel: TextView
    private lateinit var freqLabel: TextView
    private lateinit var autoplaySwitch: SwitchCompat
    private lateinit var playOnSendSwitch: SwitchCompat
    private lateinit var messageList: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var liveRx: TextView
    private lateinit var input: EditText
    private lateinit var morsePreview: TextView
    private lateinit var keySymbols: TextView
    private lateinit var keyButton: MaterialButton

    // Estado
    private var wpm = 15
    private var connectionState = BluetoothService.State.NONE
    private var localKeyDown = false
    private var remoteKeyDown = false
    private var playerToneOn = false
    private val remoteLiveText = StringBuilder()
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.isNotEmpty() && result.values.all { it }) {
                continuePending()
            } else {
                pendingAction = null
                toast("Sin el permiso de dispositivos cercanos la app no puede usar Bluetooth")
            }
        }

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (btAdapter?.isEnabled == true) {
                continuePending()
            } else {
                pendingAction = null
                toast("El Bluetooth está apagado")
            }
        }

    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        btAdapter?.let { bt = BluetoothService(it, this) }

        player.onToneChanged = { on ->
            playerToneOn = on
            updateLamp()
        }

        setupDecoders()
        setupSettings()
        setupChat()
        setupComposer()
        setupKey()

        listenButton.setOnClickListener { withBluetooth { bt?.listen() } }
        connectButton.setOnClickListener { withBluetooth { showDevicePicker() } }
        disconnectButton.setOnClickListener { bt?.stop() }

        onStateChanged(BluetoothService.State.NONE, null)
    }

    override fun onResume() {
        super.onResume()
        keyer.start()
    }

    override fun onPause() {
        keyUp()
        setRemoteKey(false)
        keyer.stop()
        super.onPause()
    }

    override fun onDestroy() {
        bt?.shutdown()
        player.release()
        super.onDestroy()
    }

    private fun bindViews() {
        lamp = findViewById(R.id.lamp)
        statusText = findViewById(R.id.statusText)
        listenButton = findViewById(R.id.listenButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        wpmLabel = findViewById(R.id.wpmLabel)
        freqLabel = findViewById(R.id.freqLabel)
        autoplaySwitch = findViewById(R.id.autoplaySwitch)
        playOnSendSwitch = findViewById(R.id.playOnSendSwitch)
        messageList = findViewById(R.id.messageList)
        emptyHint = findViewById(R.id.emptyHint)
        liveRx = findViewById(R.id.liveRx)
        input = findViewById(R.id.input)
        morsePreview = findViewById(R.id.morsePreview)
        keySymbols = findViewById(R.id.keySymbols)
        keyButton = findViewById(R.id.keyButton)

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            settingsPanel.isVisible = !settingsPanel.isVisible
        }
    }

    // --- Ajustes ----------------------------------------------------------

    private fun setupSettings() {
        wpm = prefs.getInt(KEY_WPM, 15)
        val freq = prefs.getInt(KEY_FREQ, 650)
        applyWpm(wpm)
        applyFreq(freq)

        findViewById<SeekBar>(R.id.wpmSeek).apply {
            max = 35
            progress = wpm - 5
            setOnSeekBarChangeListener(onSeek { applyWpm(it + 5) })
        }
        findViewById<SeekBar>(R.id.freqSeek).apply {
            max = 12
            progress = (freq - 400) / 50
            setOnSeekBarChangeListener(onSeek { applyFreq(400 + it * 50) })
        }

        autoplaySwitch.isChecked = prefs.getBoolean(KEY_AUTOPLAY, true)
        autoplaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTOPLAY, checked).apply()
        }
        playOnSendSwitch.isChecked = prefs.getBoolean(KEY_PLAY_ON_SEND, true)
        playOnSendSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_PLAY_ON_SEND, checked).apply()
        }
    }

    private fun applyWpm(value: Int) {
        wpm = value
        player.wpm = value
        wpmLabel.text = "Velocidad: $value PPM  (punto = ${1200 / value} ms)"
        prefs.edit().putInt(KEY_WPM, value).apply()
    }

    private fun applyFreq(value: Int) {
        player.frequency = value
        keyer.frequency = value
        freqLabel.text = "Tono: $value Hz"
        prefs.edit().putInt(KEY_FREQ, value).apply()
    }

    private fun unitMs(): Long = 1200L / wpm

    private fun onSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // --- Chat ---------------------------------------------------------------

    private fun setupChat() {
        chatAdapter = ChatAdapter(messages) { m ->
            player.stopAll()
            player.play(m.morse)
        }
        messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageList.adapter = chatAdapter
    }

    private fun addMessage(m: ChatMessage) {
        messages.add(m)
        chatAdapter.notifyItemInserted(messages.size - 1)
        messageList.scrollToPosition(messages.size - 1)
        emptyHint.isVisible = false
    }

    // --- Escribir y enviar ------------------------------------------------

    private fun setupComposer() {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val morse = MorseCode.encode(s?.toString().orEmpty())
                morsePreview.text = if (morse.isEmpty()) "" else MorseCode.pretty(morse)
            }
        })

        findViewById<ImageButton>(R.id.playButton).setOnClickListener {
            val morse = MorseCode.encode(input.text.toString())
            if (morse.isEmpty()) {
                toast("Escribe algo para escucharlo")
            } else {
                player.stopAll()
                player.play(morse)
            }
        }

        findViewById<ImageButton>(R.id.sendButton).setOnClickListener { sendMessage() }
    }

    private fun sendMessage() {
        val morse = MorseCode.encode(input.text.toString())
        if (morse.isEmpty()) {
            toast("Escribe un mensaje primero")
            return
        }
        val isConnected = connectionState == BluetoothService.State.CONNECTED
        if (isConnected) bt?.write("M:$morse")

        addMessage(ChatMessage(MorseCode.decode(morse), morse, outgoing = true, delivered = isConnected))
        input.setText("")
        localDecoder.reset()

        if (playOnSendSwitch.isChecked) player.play(morse)
        if (!isConnected) {
            Snackbar.make(messageList, "Sin conexión: el mensaje solo sonó en este teléfono", Snackbar.LENGTH_SHORT).show()
        }
    }

    // --- Manipulador (telégrafo) -------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupKey() {
        keyButton.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { keyDown(); true }
                MotionEvent.ACTION_UP -> { keyUp(); v.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { keyUp(); true }
                else -> true
            }
        }
    }

    private fun keyDown() {
        if (localKeyDown) return
        localKeyDown = true
        keyButton.isPressed = true
        keyButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        keyer.localDown = true
        localDecoder.down()
        if (connectionState == BluetoothService.State.CONNECTED) bt?.write("K:1")
        updateLamp()
    }

    private fun keyUp() {
        if (!localKeyDown) return
        localKeyDown = false
        keyButton.isPressed = false
        keyer.localDown = false
        localDecoder.up()
        if (connectionState == BluetoothService.State.CONNECTED) bt?.write("K:0")
        updateLamp()
    }

    private fun setupDecoders() {
        // Lo que tecleas con el manipulador se convierte en letras dentro del campo de texto
        localDecoder = KeyDecoder({ unitMs() }, object : KeyDecoder.Listener {
            override fun onSymbolsChanged(symbols: String) {
                keySymbols.text = MorseCode.pretty(symbols)
            }

            override fun onCharDecoded(c: Char?, symbols: String) {
                if (c == null) {
                    toast("No reconozco ${MorseCode.pretty(symbols)}")
                } else {
                    input.append(c.toString())
                    input.setSelection(input.text.length)
                }
            }

            override fun onWordGap() {
                val t = input.text
                if (t.isNotEmpty() && !t.endsWith(" ")) {
                    input.append(" ")
                    input.setSelection(input.text.length)
                }
            }
        })

        // Lo que el otro teléfono teclea en vivo se descifra aquí
        remoteDecoder = KeyDecoder({ unitMs() }, object : KeyDecoder.Listener {
            override fun onSymbolsChanged(symbols: String) = renderLiveRx(symbols)

            override fun onCharDecoded(c: Char?, symbols: String) {
                remoteLiveText.append(c ?: '?')
                renderLiveRx("")
            }

            override fun onWordGap() {
                remoteLiveText.append(' ')
                renderLiveRx("")
            }
        })
    }

    private fun renderLiveRx(symbols: String) {
        if (remoteLiveText.length > 60) remoteLiveText.delete(0, remoteLiveText.length - 60)
        val pending = if (symbols.isEmpty()) "" else " " + MorseCode.pretty(symbols)
        val txt = remoteLiveText.toString() + pending
        liveRx.isVisible = txt.isNotBlank()
        liveRx.text = "EN VIVO ▸ $txt"
    }

    private fun setRemoteKey(down: Boolean) {
        if (remoteKeyDown == down) return
        remoteKeyDown = down
        keyer.remoteDown = down
        if (down) remoteDecoder.down() else remoteDecoder.up()
        updateLamp()
    }

    private fun updateLamp() {
        val on = localKeyDown || remoteKeyDown || playerToneOn
        lamp.alpha = if (on) 1f else 0.18f
    }

    // --- Bluetooth ----------------------------------------------------------

    private fun withBluetooth(action: () -> Unit) {
        pendingAction = action
        continuePending()
    }

    private fun continuePending() {
        val adapter = btAdapter
        if (adapter == null) {
            pendingAction = null
            toast("Este teléfono no tiene Bluetooth")
            return
        }
        if (!hasBluetoothPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        if (!adapter.isEnabled) {
            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun showDevicePicker() {
        val devices = btAdapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No hay teléfonos emparejados")
                .setMessage("Primero empareja los dos teléfonos en Ajustes › Bluetooth. Luego vuelve aquí y pulsa Conectar.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val names = devices.map { "${it.name ?: "Sin nombre"}\n${it.address}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Conectar con…")
            .setItems(names) { _, i -> bt?.connect(devices[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onStateChanged(state: BluetoothService.State, deviceName: String?) {
        val wasConnected = connectionState == BluetoothService.State.CONNECTED
        connectionState = state

        statusText.text = when (state) {
            BluetoothService.State.NONE -> "Desconectado"
            BluetoothService.State.LISTENING -> "Esperando… en el otro teléfono pulsa Conectar y elige este"
            BluetoothService.State.CONNECTING -> "Conectando con ${deviceName ?: "…"}"
            BluetoothService.State.CONNECTED -> "Conectado con ${deviceName ?: "el otro teléfono"}"
        }

        val idle = state == BluetoothService.State.NONE
        listenButton.isVisible = idle
        connectButton.isVisible = idle
        disconnectButton.isVisible = !idle
        disconnectButton.text = if (state == BluetoothService.State.CONNECTED) "Desconectar" else "Cancelar"

        if (wasConnected && state != BluetoothService.State.CONNECTED) {
            setRemoteKey(false)
            remoteDecoder.reset()
            remoteLiveText.clear()
            renderLiveRx("")
        }
        if (state == BluetoothService.State.CONNECTED) {
            toast("Conectado con ${deviceName ?: "el otro teléfono"}")
        }
    }

    override fun onLineReceived(line: String) {
        when {
            line.startsWith("M:") -> {
                val morse = line.substring(2)
                    .filter { it == '.' || it == '-' || it == ' ' || it == '/' }
                    .trim()
                if (morse.isEmpty()) return
                setRemoteKey(false)
                remoteDecoder.reset()
                remoteLiveText.clear()
                renderLiveRx("")
                addMessage(ChatMessage(MorseCode.decode(morse), morse, outgoing = false, delivered = true))
                if (autoplaySwitch.isChecked) player.play(morse)
            }
            line == "K:1" -> setRemoteKey(true)
            line == "K:0" -> setRemoteKey(false)
        }
    }

    override fun onBluetoothError(message: String) = toast(message)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
