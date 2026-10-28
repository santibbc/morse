package com.morsebt.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

const val SAMPLE_RATE = 44100
private const val AMPLITUDE = 0.6 * Short.MAX_VALUE

/**
 * Reproduce mensajes Morse completos con un tono senoidal generado en tiempo real.
 * Los mensajes se ponen en cola y suenan uno detrás de otro.
 */
class MorsePlayer {

    @Volatile var wpm = 15
    @Volatile var frequency = 650

    /** Se llama en el hilo principal cada vez que el tono empieza o termina. */
    var onToneChanged: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val token = Any()
    @Volatile private var generation = 0

    fun play(morse: String) {
        val gen = generation
        executor.execute { if (gen == generation) render(morse, gen) }
    }

    /** Detiene lo que suena y vacía la cola. */
    fun stopAll() {
        generation++
        main.removeCallbacksAndMessages(token)
        main.post { onToneChanged?.invoke(false) }
    }

    fun release() {
        stopAll()
        executor.shutdownNow()
    }

    private fun render(morse: String, gen: Int) {
        val elements = MorseCode.timeline(morse)
        if (elements.isEmpty()) return

        val unitFrames = (SAMPLE_RATE * (1.2 / wpm)).toInt()   // 1200 ms / PPM
        val freq = frequency
        val ramp = (SAMPLE_RATE * 0.005).toInt()                // 5 ms para evitar "clics"
        val phaseStep = 2 * PI * freq / SAMPLE_RATE

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = buildTrack(minBuf * 2, lowLatency = false)

        var interrupted = false
        try {
            track.play()
            // Pequeño silencio inicial para que el primer punto no se corte
            val lead = ShortArray(SAMPLE_RATE / 20)
            writeFully(track, lead, gen)
            var frameCursor = lead.size.toLong()
            val startMs = SystemClock.uptimeMillis()

            for ((on, units) in elements) {
                if (gen != generation) { interrupted = true; break }
                val frames = unitFrames * units
                val buf = ShortArray(frames)
                if (on) {
                    var phase = 0.0
                    for (i in 0 until frames) {
                        val env = when {
                            i < ramp -> i.toDouble() / ramp
                            i > frames - ramp -> (frames - i).toDouble() / ramp
                            else -> 1.0
                        }
                        buf[i] = (sin(phase) * env * AMPLITUDE).toInt().toShort()
                        phase += phaseStep
                    }
                    val at = startMs + frameCursor * 1000 / SAMPLE_RATE
                    val end = at + frames * 1000L / SAMPLE_RATE
                    postTone(true, at, gen)
                    postTone(false, end, gen)
                }
                if (!writeFully(track, buf, gen)) { interrupted = true; break }
                frameCursor += frames
            }

            // Esperar a que termine de sonar lo que está en el búfer
            if (!interrupted) {
                val deadline = startMs + frameCursor * 1000 / SAMPLE_RATE + 2000
                while (gen == generation &&
                    track.playbackHeadPosition.toLong() < frameCursor &&
                    SystemClock.uptimeMillis() < deadline
                ) {
                    Thread.sleep(15)
                }
                if (gen != generation) interrupted = true
            }
        } catch (_: Exception) {
            interrupted = true
        } finally {
            try {
                if (interrupted) { track.pause(); track.flush() }
                track.stop()
            } catch (_: Exception) { }
            track.release()
        }
    }

    private fun writeFully(track: AudioTrack, buf: ShortArray, gen: Int): Boolean {
        var off = 0
        while (off < buf.size) {
            if (gen != generation) return false
            val n = track.write(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun postTone(on: Boolean, atUptime: Long, gen: Int) {
        main.postAtTime({ if (gen == generation) onToneChanged?.invoke(on) }, token, atUptime)
    }
}

fun buildTrack(bufferBytes: Int, lowLatency: Boolean): AudioTrack {
    val builder = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufferBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
    if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
    return builder.build()
}
