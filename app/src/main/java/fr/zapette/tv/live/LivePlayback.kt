package fr.zapette.tv.live

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.Store
import fr.zapette.tv.data.Stream
import fr.zapette.tv.data.selectedJson
import fr.zapette.tv.engine.AddonEngine
import fr.zapette.tv.player.PlaybackErrors
import fr.zapette.tv.player.StreamPlayer
import fr.zapette.tv.player.ZapHiss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays live TV, like the tuner of a TV set.
 *
 * There is a single player for live channels. It survives the full-screen
 * player: leaving it with "Retour" keeps the channel running in the corner
 * mini-player, and opening the same channel again is instant.
 *
 * The screens only read the state below and call [open], [tune], [zap]…
 */
class LivePlayback(
    private val context: Context,
    private val engine: AddonEngine,
    private val store: Store,
    private val live: LiveTv,
    private val scope: CoroutineScope,
    private val subtitlesOn: () -> Boolean,
) {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    /** Channel being played. */
    var channelId by mutableStateOf<String?>(null)
        private set

    /** Channel shown in the banner: ahead of [channelId] while the user zaps quickly. */
    var shownId by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Old-TV snow while the channel changes. */
    var snow by mutableStateOf(false)
        private set

    /** The channel keeps playing in the corner while the user browses. */
    var mini by mutableStateOf(false)
        private set

    /** Corner of the mini-player: bottom-right, or top-right while the focus is under it. */
    var miniAtBottom by mutableStateOf(true)

    private var tuneJob: Job? = null
    private var snowJob: Job? = null
    private var snowSince = 0L
    private var stream: Stream? = null
    private var formatRechecked = false
    private var retried = false
    private var stoppedAt = 0L

    val channel: Channel? get() = live.channel(channelId)

    fun ensurePlayer(): ExoPlayer =
        player ?: StreamPlayer.create(context).also {
            it.addListener(listener)
            player = it
        }

    // ------------------------------------------------------------------ tuning
    /** Full screen on [channel]. Instant when it is the one already playing in the corner. */
    fun open(channel: Channel) {
        mini = false
        val p = ensurePlayer()
        if (channel.id == channelId && error == null && p.playbackState != Player.STATE_IDLE) {
            shownId = channel.id
            p.play()
        } else {
            tune(channel)
        }
    }

    /**
     * Switches to [channel]. With [settle], waits a moment first, so that pressing
     * CH+ several times only loads the channel the user stops on.
     */
    fun tune(channel: Channel, settle: Boolean = false, isRetry: Boolean = false) {
        shownId = channel.id
        error = null
        retried = isRetry
        resetTimeShift()
        startSnow()
        tuneJob?.cancel()
        tuneJob = scope.launch {
            if (settle) delay(450)
            val p = ensurePlayer()
            channelId = channel.id
            live.rememberWatched(channel)
            loading = true
            p.stop()
            val result = engine.invoke(channel.url, channel.selectedJson())
            if (!isActive) return@launch
            val resolved = result.stream
            if (result.kind == "resolved" && resolved != null) {
                play(resolved)
            } else {
                fail(result.error ?: result.notifications.lastOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "${channel.label} est indisponible pour le moment.")
            }
        }
    }

    /** Next / previous channel in TV order. */
    fun zap(step: Int) {
        val list = live.channels.value
        if (list.isEmpty()) return
        val from = list.indexOfFirst { it.id == (shownId ?: channelId) }.coerceAtLeast(0)
        tune(list[(from + step + list.size) % list.size], settle = true)
    }

    fun previousChannel() {
        live.channel(store.previousChannelId)?.let { tune(it) }
    }

    fun retry() {
        live.channel(shownId ?: channelId)?.let { tune(it) }
    }

    /**
     * Leaving the full screen: the channel continues in the corner when it plays
     * well and the option is on; otherwise everything stops.
     */
    fun leaveFullScreen() {
        if (store.miniOnBack && error == null && channelId != null && player != null) mini = true else stop()
    }

    fun stop() {
        tuneJob?.cancel()
        endSnowNow()
        player?.release()
        player = null
        channelId = null
        shownId = null
        stream = null
        mini = false
        error = null
        loading = false
    }

    // --------------------------------------------------------------- time-shift
    /**
     * Going back in time on the channel playing, as with Kodi: possible when the
     * channel keeps a window of the last minutes or hours (most HLS/DASH lives do).
     */
    val canTimeShift: Boolean
        get() = player?.let { it.isCurrentMediaItemLive && it.isCurrentMediaItemSeekable } == true

    // The delay is counted from what the user did (jumps, pause), not read from the
    // player: the player's own position drifts by a few seconds around the live point,
    // and "live" must be shown exactly when the user comes back to it.
    private var shiftMs = 0L          // behind live because of the jumps
    private var pausedAt = 0L         // when the pause started (0: playing)

    val paused: Boolean get() = pausedAt != 0L

    /** How far behind the live point the picture is, in ms (0 = live). */
    fun delayMs(): Long {
        if (!canTimeShift) return 0
        val pause = if (paused) SystemClock.elapsedRealtime() - pausedAt else 0
        return (shiftMs + pause).coerceAtMost(windowMs())
    }

    /** Length of the window the user can go back into, in ms. */
    fun windowMs(): Long = player?.duration?.takeIf { it > 0 && canTimeShift } ?: 0

    /** Moves back (negative) or forward in the channel. Returns false when the channel does not allow it. */
    fun seekBy(ms: Long): Boolean {
        val p = player ?: return false
        if (!canTimeShift) return false
        val delay = delayMs() - ms
        if (delay <= 0) {
            backToLive()
            return true
        }
        p.seekTo((p.currentPosition + ms).coerceIn(0, windowMs()))
        shiftMs = delay.coerceAtMost(windowMs())
        if (paused) pausedAt = SystemClock.elapsedRealtime()
        return true
    }

    fun backToLive() {
        resetTimeShift()
        player?.seekToDefaultPosition()
        player?.play()
    }

    /** Pause on a live channel: it then continues from where it stopped. */
    fun togglePause(): Boolean {
        val p = player ?: return false
        if (!canTimeShift) return false
        if (paused) {
            shiftMs = delayMs()
            pausedAt = 0
            p.play()
        } else {
            pausedAt = SystemClock.elapsedRealtime()
            p.pause()
        }
        return true
    }

    private fun resetTimeShift() {
        shiftMs = 0
        pausedAt = 0
    }

    // -------------------------------------------------------- app in background
    /** The TV goes to standby / another app opens: stop the stream (it would be stale anyway). */
    fun onAppStop() {
        if (player == null) return
        stoppedAt = System.currentTimeMillis()
        tuneJob?.cancel()
        endSnowNow()
        player?.stop()
    }

    /** Back on screen: tune in again, to show what is on air now. */
    fun onAppStart() {
        if (stoppedAt == 0L) return
        stoppedAt = 0L
        channel?.let { tune(it) }
    }

    // ---------------------------------------------------------------- playback
    private fun play(newStream: Stream, formatFixed: Boolean = false) {
        stream = newStream
        formatRechecked = formatFixed
        StreamPlayer.load(context, ensurePlayer(), newStream, 0, subtitlesOn())
    }

    private fun fail(message: String) {
        loading = false
        error = message
        endSnowNow()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> loading = false
                Player.STATE_BUFFERING -> loading = true
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() = endSnow()

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            val current = stream
            when {
                // Paused too long behind the live point: jump back to live
                e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    resetTimeShift()
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                e.errorCode in PlaybackErrors.MANIFEST && !formatRechecked && current != null -> {
                    formatRechecked = true
                    scope.launch {
                        val fix = PlaybackErrors.redetect(current)
                        if (fix.stream != null) play(fix.stream, formatFixed = true) else fail(fix.message)
                    }
                }
                // A live stream may drop for a moment: one silent retry (new address and tokens)
                e.errorCode in PlaybackErrors.NETWORK && !retried -> channel?.let { tune(it, isRetry = true) }
                else -> fail(PlaybackErrors.message(e))
            }
        }
    }

    // -------------------------------------------------------------- snow + hiss
    private fun startSnow() {
        if (!store.zapEffect) return
        snowJob?.cancel()
        snowSince = System.currentTimeMillis()
        if (!snow) {
            snow = true
            if (store.zapSound) ZapHiss.start()
        }
    }

    /** Ends the snow with the first picture, but keeps it on screen at least a short moment. */
    private fun endSnow() {
        if (!snow) return
        snowJob?.cancel()
        snowJob = scope.launch {
            delay(MIN_SNOW_MS - (System.currentTimeMillis() - snowSince))
            endSnowNow()
        }
    }

    private fun endSnowNow() {
        snowJob?.cancel()
        if (snow) {
            snow = false
            ZapHiss.stop()
        }
    }

    private companion object {
        const val MIN_SNOW_MS = 380L
    }
}
