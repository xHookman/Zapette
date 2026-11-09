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
import androidx.compose.runtime.mutableIntStateOf
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
 * Remote: the zapping arrows (◀ ▶ or ▲ ▼, see the setting) change channel · the other pair, or
 * OK, opens the banner · with the banner open: ◀ ▶ move its progress bar (OK goes there),
 * ▼ goes to its buttons (subtitles, audio, restart) · digits = channel number (0 = previous)
 * · OK held or Info = programme sheet · Play/Pause · Guide = channel list
 * · Menu = audio and subtitles · Subtitles key = subtitles · Back = leave.
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
    // OK acts on release, to tell a press from a hold. Only a press that started here
    // counts (the OK that opened the channel is released on this screen)
    var okDown by remember { mutableStateOf(false) }
    var okHeld by remember { mutableStateOf(false) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    var flashTimer by remember { mutableStateOf<Job?>(null) }
    // The banner's focus: its progress bar (◀ ▶ move it) or its buttons (▼ goes there)
    var onButtons by remember { mutableStateOf(false) }
    var buttonIndex by remember { mutableIntStateOf(0) }
    // Where OK would take the picture, while the bar is being moved (null: nothing pending)
    var scrubTo by remember { mutableStateOf<Long?>(null) }

    fun showBanner() {
        banner = true
        bannerTimer?.cancel()
        bannerTimer = scope.launch {
            delay(5_000)
            if (!channelList && !programmeSheet) {
                banner = false
                scrubTo = null
                onButtons = false
            }
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
            paused = playback.paused,
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

    /** Programme sheet (OK held, or Info). It replaces the banner: both at once overlapped. */
    fun toggleSheet() {
        programmeSheet = !programmeSheet
        if (programmeSheet) {
            banner = false
            bannerTimer?.cancel()
        } else {
            showBanner()
        }
    }

    /** The banner's buttons: subtitles and audio, then restart the programme when it is not live. */
    fun bannerButtons(): List<Pair<String, () -> Unit>> {
        val player = playback.player ?: return emptyList()
        val channel = live.channel(playback.channelId)
        val programme = channel?.let { live.nowPlaying(it, now - timeShift.delayMs / 1000) }
        return listOfNotNull(
            (if (vm.subtitlesOn) "Sous-titres : activés" else "Sous-titres : désactivés") to { toggleSubtitles(vm, player) },
            "Audio et langues" to { openTrackMenu(vm, player) },
            if (programme != null && playback.canTimeShift) {
                "Reprendre au début" to { playback.goBackTo(programme.start * 1000, programme.title) }
            } else null,
        )
    }

    /** Moves the bar: [delta] > 0 goes back in time, < 0 towards live. Applied by OK. */
    fun moveBar(delta: Long) {
        if (!playback.canTimeShift) {
            vm.messages.toast("Pas de retour en arrière", "Cette chaîne ne permet pas de revenir dans le temps.")
            return
        }
        val from = scrubTo ?: timeShift.delayMs
        scrubTo = (from + delta).coerceIn(0L, timeShift.windowMs.coerceAtLeast(0L))
    }

    /** OK on the bar: the picture goes where the bar was moved to. */
    fun applyScrub() {
        val target = scrubTo ?: return
        scrubTo = null
        val current = playback.delayMs()
        // seekBy: positive moves towards live, so the difference from the current delay
        if (target != current) playback.seekBy(current - target)
        readTimeShift()
        showBanner()
    }

    /** OK released: a short press shows the banner, then the channel list. */
    fun okPress() {
        val player = playback.player
        when {
            playback.error != null -> playback.retry()
            programmeSheet -> player?.let { openTrackMenu(vm, it) }
            banner && onButtons -> {
                bannerButtons().getOrNull(buttonIndex)?.second?.invoke()
                showBanner()
            }
            banner && scrubTo != null -> applyScrub()
            banner -> channelList = true
            else -> showBanner()
        }
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
                scrubTo != null -> scrubTo = null
                channelList -> {
                    channelList = false
                    runCatching { focus.requestFocus() }
                }
                programmeSheet -> programmeSheet = false
                banner -> {
                    banner = false
                    onButtons = false
                }
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
        val horizontalZap = vm.store.zapKeys != "vertical"
        val arrow = e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        val upDown = e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        when {
            // OK is handled on release, to tell a press from a hold (see okPress)
            e.isOk() -> when {
                e.repeatCount == 0 -> okDown = true
                okDown && !okHeld -> {
                    okHeld = true
                    toggleSheet()
                }
            }
            // Banner open: its arrows work on it, whichever pair zaps
            banner && !programmeSheet && (arrow || upDown) -> {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (onButtons) buttonIndex = (buttonIndex - 1).coerceAtLeast(0) else moveBar(step)
                    KeyEvent.KEYCODE_DPAD_RIGHT ->
                        if (onButtons) buttonIndex = (buttonIndex + 1).coerceAtMost((bannerButtons().size - 1).coerceAtLeast(0))
                        else moveBar(-step)
                    KeyEvent.KEYCODE_DPAD_UP -> onButtons = false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (!onButtons && bannerButtons().isNotEmpty()) {
                        onButtons = true
                        buttonIndex = 0
                    }
                }
                showBanner()
            }
            else -> when (e.keyCode) {
                // The zapping pair changes channel (the other one opens the banner)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (horizontalZap) zap(-1) else showBanner()
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (horizontalZap) zap(+1) else showBanner()
                KeyEvent.KEYCODE_DPAD_UP -> if (horizontalZap) showBanner() else zap(+1)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (horizontalZap) showBanner() else zap(-1)
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(+1)
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
                KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-30_000)
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30_000)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (playback.togglePause()) {
                        readTimeShift()
                        showBanner()
                    }
                }
                KeyEvent.KEYCODE_INFO -> toggleSheet()
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
                when {
                    event.type == KeyEventType.KeyDown -> onKey(e)
                    // Release of OK: a press if it was not held
                    e.isOk() && !channelList -> {
                        if (okDown && !okHeld) okPress()
                        okDown = false
                        okHeld = false
                        true
                    }
                    else -> e.isBack() || (!channelList && e.keyCode != KeyEvent.KEYCODE_UNKNOWN)
                }
            }
            .focusable()
    ) {
        val shown = live.channel(playback.shownId)
        val error = playback.error
        when {
            error != null -> ErrorOverlay(error, "OK pour réessayer  ·  ▲ ▼ pour changer de chaîne", shown)
            playback.snow -> ZapStatic(shown)
            playback.loading -> LoadingOverlay(shown)
        }

        val buttons = bannerButtons()
        val hints = when {
            onButtons -> listOf("◀ ▶ choisir", "OK valider", "▲ barre de progression", "Retour : fermer le bandeau")
            else -> listOf(
                "◀ ▶ reculer, avancer",
                if (scrubTo != null) "OK aller là" else "OK chaînes",
                "▼ boutons",
                if (scrubTo != null) "Retour : annuler le saut" else "Retour : fermer le bandeau",
            )
        }
        LiveBanner(
            vm, shown, visible = banner && !programmeSheet, now = now, timeShift = timeShift,
            onButtons = onButtons, buttonIndex = buttonIndex, buttons = buttons.map { it.first },
            scrubTo = scrubTo, hints = hints,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Not live: always said, even without the banner
        if ((timeShift.delayMs > 0 || timeShift.paused) && typed.isEmpty()) {
            DelayedPill(timeShift, Modifier.align(Alignment.TopEnd))
        }
        seekFlash?.let { SeekFlash(it, Modifier.align(Alignment.Center)) }

        AnimatedVisibility(
            programmeSheet && shown != null, modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) { shown?.let { ProgrammeSheet(vm, it, now - timeShift.delayMs / 1000) } }

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
