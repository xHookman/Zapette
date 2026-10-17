package fr.zapette.tv.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import fr.zapette.tv.Images
import fr.zapette.tv.Perf
import fr.zapette.tv.ui.components.AppDialogs
import fr.zapette.tv.ui.components.BusyOverlay
import fr.zapette.tv.ui.components.Toasts
import fr.zapette.tv.ui.components.TopBar
import fr.zapette.tv.ui.components.ZImage
import fr.zapette.tv.ui.player.LivePlayerScreen
import fr.zapette.tv.ui.player.NumberBox
import fr.zapette.tv.ui.player.LiveVideoLayer
import fr.zapette.tv.ui.player.VodPlayerScreen
import fr.zapette.tv.ui.screens.BrowseScreen
import fr.zapette.tv.ui.screens.GuideScreen
import fr.zapette.tv.ui.screens.HomeScreen
import fr.zapette.tv.ui.screens.IntroScreen
import fr.zapette.tv.ui.screens.LiveScreen
import fr.zapette.tv.ui.screens.SettingsScreen
import fr.zapette.tv.ui.screens.SplashScreen
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZapetteTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ZapetteTheme {
                Box(Modifier.fillMaxSize().background(Z.Bg)) {
                    when (val boot = vm.boot) {
                        // The first-start presentation replaces the screens until it is closed
                        AppViewModel.Boot.Ready -> if (vm.introShown) IntroScreen(vm::closeIntro) else MainScreen(vm, onExit = ::finish)
                        else -> SplashScreen(boot, onRetry = { vm.start() })
                    }
                    AppDialogs(vm)
                    Toasts(vm)
                    BusyOverlay(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppStart()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppStop()
    }
}

/**
 * Layers, from bottom to top:
 *  1. the tabs (when nothing is stacked above them)
 *  2. the stacked screen: an add-on folder or the replay player
 *  3. the live video: full screen, or the corner mini-player
 *  4. the live player's overlays (banner, channel list…)
 */
@Composable
private fun MainScreen(vm: AppViewModel, onExit: () -> Unit) {
    val top = vm.stack.lastOrNull()
    val liveOnTop = (top as? Route.Player)?.takeIf { it.request is PlayerRequest.Live }
    val miniShown = vm.playback.mini && top !is Route.Player

    BackHandler { if (!vm.onBack()) onExit() }

    // Remote keys that work everywhere outside the player
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var typedNumber by remember { mutableStateOf("") }
    val numbers = remember {
        NumberEntry(scope, onTyping = { typedNumber = it }) { number ->
            val channel = if (number == 0) vm.playback.channel else vm.live.channelNumber(number)
            if (channel != null) vm.watch(channel) else vm.messages.toast("Aucune chaîne n°$number")
        }
    }
    val doubleOk = remember { DoubleOk(scope, view) { vm.expandMini() } }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (top is Route.Player) return@onPreviewKeyEvent false
            val e = event.nativeKeyEvent
            if (miniShown) {
                if (doubleOk.handle(e)) return@onPreviewKeyEvent true
                val play = e.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    e.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                if (play && event.type == KeyEventType.KeyDown) {
                    vm.expandMini()
                    return@onPreviewKeyEvent true
                }
            } else {
                doubleOk.reset()
            }
            val digit = e.digit()
            if (digit >= 0 && event.type == KeyEventType.KeyDown) numbers.type(digit)
            digit >= 0
        }
    ) {
        if (top == null) Tabs(vm)

        when (top) {
            is Route.Browse -> Box(Modifier.fillMaxSize().background(Z.Bg)) {
                Backdrop(vm)
                key(top) { BrowseScreen(vm, top.state, embedded = false) }
            }
            is Route.Player -> when (val request = top.request) {
                is PlayerRequest.Live -> Unit   // drawn below, above the video layer
                else -> key(top) { VodPlayerScreen(vm, request) }
            }
            null -> Unit
        }

        // One video view for live TV, full screen or in the corner: the stream is never cut
        LiveVideoLayer(vm, fullscreen = liveOnTop != null)
        if (liveOnTop != null) {
            key(liveOnTop) { LivePlayerScreen(vm, (liveOnTop.request as PlayerRequest.Live).channelId) }
        }

        if (typedNumber.isNotEmpty()) {
            NumberBox(typedNumber, typedNumber.toIntOrNull()?.let { vm.live.channelNumber(it) }, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun Tabs(vm: AppViewModel) {
    Backdrop(vm)
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        // No cross-fade: switching tab must be instant, even on a weak TV
        Box(Modifier.fillMaxSize()) {
            key(vm.tab) {
                when (vm.tab) {
                    Tab.Home -> HomeScreen(vm)
                    Tab.Live -> LiveScreen(vm)
                    Tab.Guide -> GuideScreen(vm)
                    Tab.Replay -> vm.replayRoot?.let { BrowseScreen(vm, it, embedded = true) }
                    Tab.Explore -> BrowseScreen(vm, vm.exploreRoot, embedded = true)
                    Tab.Settings -> SettingsScreen(vm)
                }
            }
        }
    }
}

private val BackdropFadeH = Brush.horizontalGradient(0.25f to Z.Bg, 0.6f to Z.Bg.copy(alpha = 0.6f), 1f to Color.Transparent)
private val BackdropFadeV = Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Z.Bg)

/**
 * Ambient picture of the focused item on the right side of the screen, faded into
 * the background. Reads [AppViewModel.backdrop] itself, so a new picture only redraws this.
 */
@Composable
private fun Backdrop(vm: AppViewModel) {
    val url = vm.backdrop
    if (url == null || Perf.lite) return
    Box(
        Modifier.fillMaxSize().drawWithContent {
            drawContent()
            drawRect(BackdropFadeH)
            drawRect(BackdropFadeV)
        }
    ) {
        ZImage(
            url, Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.75f).align(Alignment.TopEnd),
            contentScale = ContentScale.Crop, placeholder = Color.Transparent, alpha = 0.55f, widthPx = Images.CARD_PX,   // same bitmap as the focused card: no decoding
        )
    }
}
