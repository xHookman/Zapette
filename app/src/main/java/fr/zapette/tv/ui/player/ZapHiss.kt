package fr.zapette.tv.ui.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.random.Random

/**
 * The quiet crackle of an untuned TV, played under the snow effect.
 *
 * One second of filtered noise is generated once and looped by the system
 * (no asset, no CPU work while it plays). Volume fades in and out to avoid clicks.
 */
object ZapHiss {
    private const val RATE = 22050
    private const val MAX_VOLUME = 0.11f   // discreet: under the level of the TV programmes

    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var volume = 0f
    private var target = 0f

    private val fader = object : Runnable {
        override fun run() {
            val t = track ?: return
            val step = if (target > volume) 0.02f else 0.015f
            volume = if (abs(target - volume) <= step) target else volume + if (target > volume) step else -step
            runCatching { t.setVolume(volume) }
            when {
                volume != target -> handler.postDelayed(this, 16)
                volume == 0f -> runCatching { t.pause() }
            }
        }
    }

    fun start() {
        val t = track ?: create()?.also { track = it } ?: return
        target = MAX_VOLUME
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }
        handler.removeCallbacks(fader)
        handler.post(fader)
    }

    fun stop() {
        target = 0f
        handler.removeCallbacks(fader)
        handler.post(fader)
    }

    private fun create(): AudioTrack? = runCatching {
        val samples = noise()
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
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build().apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
                setVolume(0f)
            }
    }.getOrNull()

    /** White noise, softened (low-pass) and without rumble (high-pass), with rare tiny crackles. */
    private fun noise(): ShortArray {
        val r = Random(42)
        val out = ShortArray(RATE)
        var low = 0f
        var high = 0f
        var lastLow = 0f
        var crackle = 0f
        for (i in out.indices) {
            val white = r.nextFloat() * 2f - 1f
            low += 0.45f * (white - low)               // soften the hiss
            high = 0.97f * (high + low - lastLow)       // remove the rumble
            lastLow = low
            if (r.nextInt(2600) == 0) crackle = (if (r.nextBoolean()) 1f else -1f) * (0.4f + r.nextFloat() * 0.4f)
            crackle *= 0.86f
            val v = (high * 0.75f + crackle).coerceIn(-1f, 1f)
            out[i] = (v * Short.MAX_VALUE * 0.9f).toInt().toShort()
        }
        // Smooth the loop seam
        val fade = 220
        for (i in 0 until fade) {
            val k = i / fade.toFloat()
            out[i] = (out[i] * k).toInt().toShort()
            out[out.size - 1 - i] = (out[out.size - 1 - i] * k).toInt().toShort()
        }
        return out
    }
}
