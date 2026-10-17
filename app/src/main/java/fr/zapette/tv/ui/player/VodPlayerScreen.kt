package fr.zapette.tv.ui.player

import android.content.Context
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import fr.zapette.tv.data.ResumeEntry
import fr.zapette.tv.data.Stream
import fr.zapette.tv.player.PlaybackErrors
import fr.zapette.tv.player.StreamPlayer
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.PlayerRequest
import fr.zapette.tv.ui.components.Clock
import fr.zapette.tv.ui.components.Fmt
import fr.zapette.tv.ui.components.LiveBadge
import fr.zapette.tv.ui.components.PauseGlyph
import fr.zapette.tv.ui.components.ProgressLine
import fr.zapette.tv.ui.isBack
import fr.zapette.tv.ui.isOk
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Plays one replay programme (or a stream the add-on already resolved).
 * Owns its player; remembers the position to resume later.
 */
private class VodSession(
    private val context: Context,
    private val vm: AppViewModel,
    private val request: PlayerRequest,
    private val scope: CoroutineScope,
) {
    val player: ExoPlayer = StreamPlayer.create(context)

    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    private var image = ""

    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    /** A live stream opened from the add-on (no timeline, no resume). */
    var isLive by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)

    var controls by mutableStateOf(true)
    /** "+10 s" shown a moment in the middle of the screen after a seek. */
    var seekFlash by mutableStateOf<String?>(null)

    private var stream: Stream? = null
    private var formatRechecked = false
    private var controlsTimer: Job? = null
    private var flashTimer: Job? = null

    private val resumeUrl = (request as? PlayerRequest.Vod)?.url

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> loading = false
                    Player.STATE_BUFFERING -> loading = true
                    Player.STATE_ENDED -> {
                        resumeUrl?.let { vm.store.removeResume(it) }
                        vm.onBack()
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) hideControlsLater()
            }

            override fun onPlayerError(e: PlaybackException) {
                val current = stream
                when {
                    e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    e.errorCode in PlaybackErrors.MANIFEST && !formatRechecked && current != null -> {
                        formatRechecked = true
                        scope.launch {
                            val fix = PlaybackErrors.redetect(current)
                            if (fix.stream != null) play(fix.stream, player.currentPosition) else fail(fix.message)
                        }
                    }
                    else -> fail(PlaybackErrors.message(e))
                }
            }
        })
    }

    /** Resolves the item with the add-on (if needed) and starts playing. */
    suspend fun start() {
        when (request) {
            is PlayerRequest.Vod -> {
                title = request.title
                subtitle = request.subtitle
                image = request.image
                val result = vm.engine.invoke(request.url, request.selected)
                vm.messages.show(result.notifications)
                val resolved = result.stream
                if (result.kind != "resolved" || resolved == null) {
                    fail(result.error ?: result.notifications.lastOrNull()?.message?.takeIf { it.isNotBlank() } ?: "Lecture impossible.")
                    return
                }
                if (title.isBlank()) title = resolved.label
                if (image.isBlank()) image = resolved.art["fanart"] ?: resolved.art["thumb"] ?: ""
                val from = vm.store.resumePosition(request.url)
                play(resolved, from)
                if (from > 0) vm.messages.toast("Reprise à ${Fmt.clock(from)}", "Maintenez OK sur la carte pour recommencer.")
            }
            is PlayerRequest.Direct -> {
                title = request.title.ifBlank { request.stream.label }
                play(request.stream)
            }
            is PlayerRequest.Live -> error("live channels are played by LivePlayback")
        }
        showControls()
    }

    private fun play(newStream: Stream, fromMs: Long = 0) {
        stream = newStream
        error = null
        loading = true
        StreamPlayer.load(context, player, newStream, fromMs, vm.subtitlesOn)
    }

    private fun fail(message: String) {
        loading = false
        error = message
    }

    fun retry() {
        val s = stream ?: return
        formatRechecked = false
        play(s, player.currentPosition)
    }

    /** Polled twice a second while the screen is shown (the timeline). */
    fun refreshTimeline() {
        position = player.currentPosition
        duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        isLive = player.isCurrentMediaItemLive
    }

    // ------------------------------------------------------------- controls
    fun showControls() {
        controls = true
        hideControlsLater()
    }

    private fun hideControlsLater() {
        controlsTimer?.cancel()
        controlsTimer = scope.launch {
            delay(4_500)
            if (player.isPlaying) controls = false
        }
    }

    fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
        showControls()
    }

    fun seekBy(ms: Long) {
        if (isLive) return
        val end = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + ms).coerceIn(0, end)
        player.seekTo(target)
        position = target
        seekFlash = (if (ms > 0) "+" else "−") + "${abs(ms) / 1000} s"
        flashTimer?.cancel()
        flashTimer = scope.launch {
            delay(900)
            seekFlash = null
        }
        showControls()
    }

    // --------------------------------------------------------------- resume
    fun saveResume() {
        val r = request as? PlayerRequest.Vod ?: return
        if (isLive || duration <= 0) return
        vm.store.saveResume(
            ResumeEntry(
                url = r.url, label = r.title.ifBlank { title }, subtitle = r.subtitle, image = image,
                position = player.currentPosition, duration = duration,
                updatedAt = System.currentTimeMillis(), selected = r.selected,
            )
        )
    }

    fun release() {
        saveResume()
        player.release()
    }
}

/**
 * Full-screen replay player.
 *
 * Remote: OK / Play-Pause = pause · ◀ ▶ = −/+10 s (hold to go faster) · Rewind / Fast-forward = 30 s
 * · ▼ or Menu = audio and subtitles · Subtitles key · Back = leave (the position is kept).
 */
@Composable
fun VodPlayerScreen(vm: AppViewModel, request: PlayerRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val session = remember { VodSession(context, vm, request, scope) }

    LaunchedEffect(Unit) {
        vm.playback.stop()          // one video at a time: the corner channel stops
        runCatching { focus.requestFocus() }
        session.start()
    }

    // Timeline, and the resume position saved every 15 s (in case the TV is unplugged)
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            session.refreshTimeline()
            if (++ticks % 30 == 0) session.saveResume()
            delay(500)
        }
    }

    // The TV goes to standby / another app opens: pause and keep the position
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                session.saveResume()
                session.player.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.release()
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val player = session.player
        val step = when {
            e.repeatCount > 6 -> 60_000L
            e.repeatCount > 0 -> 30_000L
            else -> 10_000L
        }
        when {
            e.isBack() -> vm.onBack()
            e.isOk() -> when {
                session.error != null -> session.retry()
                !session.controls -> session.showControls()
                else -> session.playPause()
            }
            else -> when (e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> session.playPause()
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); session.showControls() }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); session.showControls() }
                KeyEvent.KEYCODE_DPAD_LEFT -> session.seekBy(-step)
                KeyEvent.KEYCODE_DPAD_RIGHT -> session.seekBy(step)
                KeyEvent.KEYCODE_MEDIA_REWIND -> session.seekBy(-30_000)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> session.seekBy(30_000)
                KeyEvent.KEYCODE_DPAD_UP -> session.showControls()
                KeyEvent.KEYCODE_DPAD_DOWN -> if (session.controls) openTrackMenu(vm, player) else session.showControls()
                KeyEvent.KEYCODE_MENU -> openTrackMenu(vm, player)
                KeyEvent.KEYCODE_CAPTIONS -> toggleSubtitles(vm, player)
                else -> return false
            }
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val e = event.nativeKeyEvent
                if (event.type == KeyEventType.KeyDown) onKey(e) else e.keyCode != KeyEvent.KEYCODE_UNKNOWN
            }
            .focusable()
    ) {
        VideoView(session.player, Modifier.fillMaxSize())

        val error = session.error
        when {
            error != null -> ErrorOverlay(error, "OK pour réessayer  ·  Retour pour quitter")
            session.loading -> LoadingOverlay(null)
        }

        VodControls(session, Modifier.align(Alignment.BottomCenter))

        session.seekFlash?.let {
            Text(
                it, style = ZType.Headline, color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                    .background(Color(0x99000000)).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

private val ControlsShade = Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color(0xA6000000), 1f to Color(0xF2000000))

/** Title, play state and timeline at the bottom; always shown while paused. */
@Composable
private fun VodControls(session: VodSession, modifier: Modifier) {
    val visible = session.controls || (!session.playing && !session.loading && session.error == null)
    AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth().background(ControlsShade)) {
            Column(Modifier.fillMaxWidth().padding(start = Z.Gutter, end = Z.Gutter, top = 90.dp, bottom = 34.dp)) {
                if (session.subtitle.isNotBlank()) {
                    Text(session.subtitle.uppercase(), style = ZType.Small.copy(letterSpacing = 1.2.sp), color = Z.Live)
                }
                Text(session.title, style = ZType.Headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (session.playing) PauseGlyph(Modifier.height(22.dp))
                        else Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    if (session.isLive) {
                        LiveBadge()
                        Spacer(Modifier.weight(1f))
                    } else {
                        val position = session.position
                        val duration = session.duration
                        Text(Fmt.clock(position), style = ZType.Number, color = Color.White)
                        Spacer(Modifier.width(14.dp))
                        ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), height = 6.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("−" + Fmt.clock(duration - position), style = ZType.Number, color = Z.Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    Text(
                        if (session.isLive) "OK lecture/pause   ▼ audio et sous-titres"
                        else "OK lecture/pause   ◀ ▶ ±10 s (maintenir pour accélérer)   ▼ audio et sous-titres",
                        style = ZType.Small, color = Z.Faint,
                    )
                    Spacer(Modifier.weight(1f))
                    Clock()
                }
            }
        }
    }
}
