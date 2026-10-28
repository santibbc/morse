package com.morsebt.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Conexión Bluetooth clásica (RFCOMM / SPP) entre dos teléfonos.
 * Un teléfono "espera conexión" (servidor) y el otro "se conecta" (cliente).
 *
 * Protocolo: una línea de texto UTF-8 por evento, terminada en '\n'
 *   M:<morse>   mensaje completo (ej. "M:.... --- .-.. .-")
 *   K:1 / K:0   manipulador presionado / soltado (tono en vivo)
 *
 * Los permisos se verifican en MainActivity antes de llamar a esta clase.
 */
@SuppressLint("MissingPermission")
class BluetoothService(
    private val adapter: BluetoothAdapter,
    private val listener: Listener
) {
    enum class State { NONE, LISTENING, CONNECTING, CONNECTED }

    interface Listener {
        fun onStateChanged(state: State, deviceName: String?)
        fun onLineReceived(line: String)
        fun onBluetoothError(message: String)
    }

    companion object {
        private const val SERVICE_NAME = "MorseBT"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Volatile var state = State.NONE
        private set

    @Synchronized
    fun listen() {
        cancelAll()
        acceptThread = AcceptThread().also { it.start() }
        setState(State.LISTENING, null)
    }

    @Synchronized
    fun connect(device: BluetoothDevice) {
        cancelAll()
        connectThread = ConnectThread(device).also { it.start() }
        setState(State.CONNECTING, nameOf(device))
    }

    @Synchronized
    fun stop() {
        cancelAll()
        setState(State.NONE, null)
    }

    fun write(line: String) {
        val t = synchronized(this) { connectedThread } ?: return
        writer.execute { t.write(line) }
    }

    fun shutdown() {
        stop()
        writer.shutdown()
    }

    // ---------------------------------------------------------------------

    private fun nameOf(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    private fun setState(s: State, name: String?) {
        state = s
        main.post { listener.onStateChanged(s, name) }
    }

    private fun cancelAll() {
        connectThread?.cancel(); connectThread = null
        connectedThread?.cancel(); connectedThread = null
        acceptThread?.cancel(); acceptThread = null
    }

    @Synchronized
    private fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        connectThread = null            // no cancelar: cerraría el socket que acabamos de abrir
        acceptThread?.cancel(); acceptThread = null
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket).also { it.start() }
        setState(State.CONNECTED, nameOf(device))
    }

    private fun fail(message: String) {
        main.post { listener.onBluetoothError(message) }
        synchronized(this) {
            cancelAll()
            setState(State.NONE, null)
        }
    }

    // --- Servidor: espera a que el otro teléfono se conecte ----------------

    private inner class AcceptThread : Thread("BT-Accept") {
        @Volatile private var canceled = false
        private val server: BluetoothServerSocket? = try {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        } catch (_: Exception) {
            null
        }

        override fun run() {
            val srv = server
            if (srv == null) {
                if (!canceled) fail("No se pudo abrir el servidor Bluetooth")
                return
            }
            try {
                val socket = srv.accept()
                synchronized(this@BluetoothService) {
                    if (canceled) socket.close() else connected(socket, socket.remoteDevice)
                }
            } catch (_: IOException) {
                if (!canceled) fail("Se dejó de esperar conexiones")
            } finally {
                try { srv.close() } catch (_: IOException) { }
            }
        }

        fun cancel() {
            canceled = true
            try { server?.close() } catch (_: IOException) { }
        }
    }

    // --- Cliente: se conecta al teléfono elegido ---------------------------

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread("BT-Connect") {
        @Volatile private var canceled = false
        @Volatile private var socket: BluetoothSocket? = null

        override fun run() {
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                synchronized(this@BluetoothService) {
                    if (canceled) {
                        s.close()
                    } else {
                        connected(s, device)
                    }
                }
            } catch (_: IOException) {
                try { socket?.close() } catch (_: IOException) { }
                if (!canceled) {
                    fail("No se pudo conectar con ${nameOf(device)}. " +
                        "¿El otro teléfono tiene la app abierta en \"Esperar conexión\"?")
                }
            }
        }

        fun cancel() {
            canceled = true
            try { socket?.close() } catch (_: IOException) { }
        }
    }

    // --- Conexión activa: lee y escribe líneas -----------------------------

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread("BT-Connected") {
        @Volatile private var canceled = false
        private val out: OutputStream? = try { socket.outputStream } catch (_: IOException) { null }

        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val clean = line.trim()
                    if (clean.isNotEmpty()) main.post { listener.onLineReceived(clean) }
                }
                if (!canceled) fail("El otro teléfono cerró la conexión")
            } catch (_: IOException) {
                if (!canceled) fail("Se perdió la conexión Bluetooth")
            }
        }

        fun write(line: String) {
            try {
                val o = out ?: throw IOException("sin salida")
                o.write((line + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (_: IOException) {
                if (!canceled) fail("Error al enviar por Bluetooth")
            }
        }

        fun cancel() {
            canceled = true
            try { socket.close() } catch (_: IOException) { }
        }
    }
}
