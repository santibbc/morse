package com.morsebt.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Convierte pulsaciones (presionar/soltar) en puntos, rayas y letras según
 * la velocidad configurada. Debe usarse desde el hilo principal.
 *
 *   pulsación < 2 unidades  -> punto
 *   pulsación >= 2 unidades -> raya
 *   silencio de 3 unidades  -> fin de letra
 *   silencio de 7 unidades  -> espacio entre palabras
 */
class KeyDecoder(
    private val unitMs: () -> Long,
    private val listener: Listener
) {
    interface Listener {
        fun onSymbolsChanged(symbols: String)
        fun onCharDecoded(c: Char?, symbols: String)
        fun onWordGap()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val symbols = StringBuilder()
    private var downAt = 0L
    private var isDown = false
    private var wordPending = false

    private val letterTimeout = Runnable { finishLetter() }
    private val wordTimeout = Runnable {
        if (wordPending) {
            wordPending = false
            listener.onWordGap()
        }
    }

    fun down() {
        if (isDown) return
        isDown = true
        downAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(letterTimeout)
        handler.removeCallbacks(wordTimeout)
    }

    fun up() {
        if (!isDown) return
        isDown = false
        val duration = SystemClock.uptimeMillis() - downAt
        val unit = unitMs()
        if (duration >= 10) {   // ignorar rebotes
            symbols.append(if (duration < unit * 2) '.' else '-')
            listener.onSymbolsChanged(symbols.toString())
        }
        handler.postDelayed(letterTimeout, unit * 3)
        handler.postDelayed(wordTimeout, unit * 7)
    }

    fun reset() {
        handler.removeCallbacks(letterTimeout)
        handler.removeCallbacks(wordTimeout)
        symbols.clear()
        isDown = false
        wordPending = false
        listener.onSymbolsChanged("")
    }

    private fun finishLetter() {
        if (symbols.isEmpty()) return
        val s = symbols.toString()
        symbols.clear()
        listener.onCharDecoded(MorseCode.decodeLetter(s), s)
        listener.onSymbolsChanged("")
        wordPending = true
    }
}
