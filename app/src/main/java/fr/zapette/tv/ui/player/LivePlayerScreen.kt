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
 * · ◀ channel list · ▶ programme sheet · Subtitles / Menu = subtitles, audio · Back = leave.
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
                // The arrows never zap (on purpose): they only show the banner
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!programmeSheet) showBanner()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> {
                    programmeSheet = false
                    channelList = true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                    programmeSheet = !programmeSheet
                    if (programmeSheet) {
                        banner = true
                        bannerTimer?.cancel()
                    } else {
                        showBanner()
                    }
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

        LiveBanner(vm, shown, visible = banner, now = now, modifier = Modifier.align(Alignment.BottomCenter))

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
