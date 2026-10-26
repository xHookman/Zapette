package fr.zapette.tv.ui.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import fr.zapette.tv.data.Channel
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.Fmt
import fr.zapette.tv.ui.NumberEntry
import fr.zapette.tv.ui.digit
import fr.zapette.tv.ui.isBack
import fr.zapette.tv.ui.isOk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen live TV: the overlays and the remote control.
 * The video itself is drawn by [LiveVideoLayer]; tuning is done by LivePlayback.
 *
 * Remote: CH+/CH− zap · digits = channel number (0 = previous) · OK = banner, then channel list
 * · ◀ ▶ go back / forward in time (hold to go faster) · Play/Pause · ▼ back to live
 * · ▲ programme sheet · Guide = channel list · Subtitles / Menu = subtitles, audio · Back = leave.
 */
@Composable
fun LivePlayerScreen(vm: AppViewModel, channelId: String) {
    val playback = vm.playback
    val live = vm.live
    val channels by live.channels.collectAsState()
    val now by live.now.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var banner by remember { mutableStateOf(false) }
    var channelList by remember { mutableStateOf(false) }
    var programmeSheet by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var bannerTimer by remember { mutableStateOf<Job?>(null) }
    var timeShift by remember { mutableStateOf(TimeShift()) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var flashTimer by remember { mutableStateOf<Job?>(null) }

    fun showBanner() {
        banner = true
        bannerTimer?.cancel()
        bannerTimer = scope.launch {
            delay(5_000)
            if (!channelList && !programmeSheet) banner = false
        }
    }

    fun tune(channel: Channel) {
        playback.tune(channel)
        if (vm.store.bannerOnZap) showBanner()
    }

    /** Time-shift state, read from the player twice a second. */
    fun readTimeShift() {
        timeShift = TimeShift(
            delayMs = playback.delayMs(),
            windowMs = playback.windowMs(),
            paused = playback.player?.let { !it.playWhenReady } == true,
        )
    }

    fun seek(ms: Long) {
        if (!playback.seekBy(ms)) {
            vm.messages.toast("Pas de retour en arrière", "Cette chaîne ne permet pas de revenir dans le temps.")
            return
        }
        seekFlash = (if (ms > 0) "+" else "−") + Fmt.minutesSeconds(kotlin.math.abs(ms))
        flashTimer?.cancel()
        flashTimer = scope.launch {
            delay(900)
            seekFlash = null
        }
        readTimeShift()
        showBanner()
    }

    fun zap(step: Int) {
        programmeSheet = false
        playback.zap(step)
        showBanner()
    }

    val numbers = remember {
        NumberEntry(scope, onTyping = { typed = it }) { number ->
            val channel = if (number == 0) live.channel(vm.store.previousChannelId) else live.channelNumber(number)
            if (channel != null) tune(channel) else vm.messages.toast("Aucune chaîne n°$number")
        }
    }

    LaunchedEffect(channelId) {
        runCatching { focus.requestFocus() }
        live.channel(channelId)?.let { playback.open(it) }
        showBanner()
    }
    LaunchedEffect(Unit) {
        while (true) {
            readTimeShift()
            delay(500)
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.isBack()) {
            when {
                numbers.cancel() -> Unit
                channelList -> {
                    channelList = false
                    runCatching { focus.requestFocus() }
                }
                programmeSheet -> programmeSheet = false
                banner -> banner = false
                else -> vm.onBack()
            }
            return true
        }
        val digit = e.digit()
        if (digit >= 0) {
            numbers.type(digit)
            return true
        }
        if (channelList) return false            // the list handles the arrows itself
        val player = playback.player
        // Held key: 10 s, then 30 s, then 1 min per step
        val step = when {
            e.repeatCount > 6 -> 60_000L
            e.repeatCount > 0 -> 30_000L
            else -> 10_000L
        }
        when {
            e.isOk() -> when {
                playback.error != null -> playback.retry()
                programmeSheet -> player?.let { openTrackMenu(vm, it) }
                banner -> channelList = true
                else -> showBanner()
            }
            else -> when (e.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(+1)
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
                // Time-shift, as in Kodi. The arrows never zap (on purpose)
                KeyEvent.KEYCODE_DPAD_LEFT -> seek(-step)
                KeyEvent.KEYCODE_DPAD_RIGHT -> seek(step)
                KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-30_000)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30_000)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (playback.togglePause()) {
                        readTimeShift()
                        showBanner()
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if ((timeShift.delayMs > 0 || timeShift.paused) && typed.isEmpty()) {
                        playback.backToLive()
                        readTimeShift()
                    }
                    showBanner()
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> {
                    programmeSheet = !programmeSheet
                    if (programmeSheet) {
                        banner = true
                        bannerTimer?.cancel()
                    } else {
                        showBanner()
                    }
                }
                KeyEvent.KEYCODE_GUIDE -> {
                    programmeSheet = false
                    channelList = true
                }
                KeyEvent.KEYCODE_LAST_CHANNEL -> playback.previousChannel()
                KeyEvent.KEYCODE_MENU -> player?.let { openTrackMenu(vm, it) }
                KeyEvent.KEYCODE_CAPTIONS -> player?.let { toggleSubtitles(vm, it) }
                else -> return false
            }
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val e = event.nativeKeyEvent
                if (event.type == KeyEventType.KeyDown) onKey(e) else e.isBack() || (!channelList && e.keyCode != KeyEvent.KEYCODE_UNKNOWN)
            }
            .focusable()
    ) {
        val shown = live.channel(playback.shownId)
        val error = playback.error
        when {
            error != null -> ErrorOverlay(error, "OK pour réessayer  ·  CH+ CH− ou ◀ pour changer de chaîne", shown)
            playback.snow -> ZapStatic(shown)
            playback.loading -> LoadingOverlay(shown)
        }

        LiveBanner(vm, shown, visible = banner, now = now, timeShift = timeShift, modifier = Modifier.align(Alignment.BottomCenter))

        // Not live: always said, even without the banner
        if (timeShift.delayMs > 0 || timeShift.paused) {
            DelayedPill(timeShift, Modifier.align(Alignment.TopEnd))
        }
        seekFlash?.let { SeekFlash(it, Modifier.align(Alignment.Center)) }

        AnimatedVisibility(
            programmeSheet && shown != null, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) { shown?.let { ProgrammeSheet(vm, it, now) } }

        AnimatedVisibility(
            channelList, modifier = Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelList(vm, channels, playback.channelId, now) { channel ->
                channelList = false
                runCatching { focus.requestFocus() }
                if (channel.id != playback.channelId) tune(channel) else showBanner()
            }
        }

        if (typed.isNotEmpty()) NumberBox(typed, live.channelNumber(typed.toIntOrNull() ?: -1), Modifier.align(Alignment.TopEnd))
    }
}
