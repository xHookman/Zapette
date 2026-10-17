package fr.zapette.tv.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.exp
import kotlin.random.Random

/**
 * The hiss of an untuned analog TV, heard under the snow effect.
 *
 * Why it sounds real rather than "simulated":
 *  - the noise is generated live, never looped, so no pattern can be heard;
 *  - it has the colour of a TV loudspeaker: no deep bass, softened top end;
 *  - its level drifts very slightly, like the automatic gain of a tuner;
 *  - it starts and stops with the picture. The only ramp is 6 ms, the time
 *    needed to avoid a click: too short to be heard as a fade.
 *
 * A small background thread writes the sound; it sleeps when the hiss is off
 * and goes away after a while without zapping.
 */
object ZapHiss {
    private const val RATE = 48_000                 // native rate of most TVs: no resampling
    private const val CHUNK = RATE / 100            // 10 ms written at a time
    private const val LEVEL = 0.10f                 // well under the level of a programme
    private const val RAMP_SAMPLES = RATE * 6 / 1000
    private const val IDLE_MS = 60_000L             // free the audio output after a minute unused

    private val lock = Object()
    @Volatile private var on = false
    private var worker: Thread? = null

    fun start() = synchronized(lock) {
        on = true
        if (worker?.isAlive != true) {
            worker = Thread(::run, "zap-hiss").apply { isDaemon = true; start() }
        }
        lock.notifyAll()
    }

    fun stop() {
        on = false
    }

    // ------------------------------------------------------------- the thread
    private fun run() {
        val track = createTrack() ?: return
        val noise = TvNoise()
        val buffer = ShortArray(CHUNK)
        var gain = 0f
        try {
            track.play()
            while (true) {
                if (!on && gain == 0f) {
                    // Silent: stop the output and wait for the next zap
                    track.pause()
                    track.flush()
                    synchronized(lock) {
                        if (!on) lock.wait(IDLE_MS)
                        if (!on) {
                            worker = null
                            return
                        }
                    }
                    track.play()
                }
                val target = if (on) 1f else 0f
                for (i in buffer.indices) {
                    gain = step(gain, target)
                    buffer[i] = (noise.next() * gain * LEVEL * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)    // blocks: paces the loop in real time
            }
        } catch (_: Throwable) {
            synchronized(lock) { worker = null }
        } finally {
            runCatching { track.release() }
        }
    }

    private fun step(gain: Float, target: Float): Float {
        val delta = 1f / RAMP_SAMPLES
        return when {
            gain < target -> minOf(target, gain + delta)
            gain > target -> maxOf(target, gain - delta)
            else -> gain
        }
    }

    private fun createTrack(): AudioTrack? = runCatching {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, CHUNK * 2 * 2))
            .apply { if (Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .build()
    }.getOrNull()

    /**
     * Noise as it comes out of a TV: random samples (close to a bell curve, like
     * thermal noise), band-limited to what a TV loudspeaker reproduces
     * (≈ 250 Hz – 6 kHz), with a slow, slight drift of level.
     */
    private class TvNoise {
        private val random = Random(System.nanoTime())
        private val highPass = coefficient(250.0)
        private val lowPass = coefficient(6_000.0)
        private var lowState = 0f
        private var bassState = 0f
        private var drift = 1f
        private var driftTarget = 1f
        private var count = 0

        fun next(): Float {
            // Sum of three uniform values: roughly gaussian, between -1.5 and 1.5
            val white = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) / 1.5f
            lowState += lowPass * (white - lowState)          // soften the top end
            bassState += highPass * (lowState - bassState)    // what the bass would be…
            val band = lowState - bassState                   // …removed
            if (++count == RATE / 20) {                       // a new drift target every 50 ms
                count = 0
                driftTarget = 0.92f + random.nextFloat() * 0.16f
            }
            drift += (driftTarget - drift) * 0.0005f
            return (band * drift).coerceIn(-1f, 1f)
        }

        /** One-pole filter coefficient for a cut-off frequency. */
        private fun coefficient(hz: Double): Float = (1.0 - exp(-2.0 * PI * hz / RATE)).toFloat()
    }
}
