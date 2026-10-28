package com.morsebt.app

import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tono en vivo para el manipulador (telégrafo): suena mientras se mantiene
 * presionado el botón local o mientras el otro teléfono mantiene el suyo.
 * Escribe audio de forma continua en bloques pequeños para tener poca latencia.
 */
class LiveKeyer {

    @Volatile var localDown = false
    @Volatile var remoteDown = false
    @Volatile var frequency = 650

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "LiveKeyer").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            buildTrack(minBuf, lowLatency = true)
        } catch (_: Exception) {
            running = false
            return
        }
        val chunk = ShortArray(128)
        val envStep = 1.0 / (SAMPLE_RATE * 0.005)   // rampa de 5 ms
        val amp = 0.6 * Short.MAX_VALUE
        var env = 0.0
        var phase = 0.0
        try {
            track.play()
            while (running) {
                val target = if (localDown || remoteDown) 1.0 else 0.0
                val phaseStep = 2 * PI * frequency / SAMPLE_RATE
                for (i in chunk.indices) {
                    env = if (env < target) min(target, env + envStep) else max(target, env - envStep)
                    chunk[i] = if (env > 0.0) (sin(phase) * env * amp).toInt().toShort() else 0
                    phase += phaseStep
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                if (track.write(chunk, 0, chunk.size) < 0) break
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) { }
            track.release()
        }
    }
}
