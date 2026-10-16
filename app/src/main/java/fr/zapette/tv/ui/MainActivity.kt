package fr.zapette.tv.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import fr.zapette.tv.ui.components.AppDialogs
import fr.zapette.tv.ui.components.BusyOverlay
import fr.zapette.tv.ui.components.TopBar
import fr.zapette.tv.ui.components.Toasts
import fr.zapette.tv.ui.components.ZImage
import fr.zapette.tv.ui.player.MiniPlayer
import fr.zapette.tv.ui.player.PlayerScreen
import fr.zapette.tv.ui.screens.BrowseScreen
import fr.zapette.tv.ui.screens.GuideScreen
import fr.zapette.tv.ui.screens.HomeScreen
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
                ZapetteRoot(vm, onExit = { finish() })
            }
        }
    }
}

@Composable
fun ZapetteRoot(vm: AppViewModel, onExit: () -> Unit) {
    val boot by vm.boot.collectAsState()
    Box(Modifier.fillMaxSize().background(Z.Bg)) {
        when (val b = boot) {
            is AppViewModel.Boot.Ready -> MainScaffold(vm, onExit)
            else -> SplashScreen(b, onRetry = { vm.start() })
        }
        AppDialogs(vm)
        Toasts(vm)
        BusyOverlay(vm)
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel, onExit: () -> Unit) {
    val top = vm.stack.lastOrNull()

    BackHandler(enabled = true) {
        if (!vm.onBack()) onExit()
    }

    // Typing a channel number anywhere outside the player zaps to it, like a TV
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var typedJob by remember { mutableStateOf<Job?>(null) }
    fun commit() {
        val n = typed.toIntOrNull()
        typed = ""
        val c = vm.channels.value.firstOrNull { it.number == n }
        if (c != null) vm.watch(c) else if (n != null) vm.toast("Aucune chaîne n°$n")
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            val digit = when (code) {
                in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> code - android.view.KeyEvent.KEYCODE_0
                in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> code - android.view.KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            // Play key: the channel playing in the corner goes back to full screen
            if (vm.miniLive && top !is Route.Player && e.type == KeyEventType.KeyDown && (
                    code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            ) {
                vm.expandMini()
                return@onPreviewKeyEvent true
            }
            if (digit < 0 || top is Route.Player) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown) {
                typed = (typed + digit).takeLast(3)
                typedJob?.cancel()
                typedJob = scope.launch { delay(1300); commit() }
            }
            true
        }
    ) {
        // Tab screens; their focus/scroll state lives in the ViewModel so it
        // survives while a browse screen or the player is on top.
        if (top == null) {
            Backdrop(vm.backdrop)
            Column(Modifier.fillMaxSize()) {
                TopBar(vm)
                Box(Modifier.fillMaxSize()) {
                    Crossfade(targetState = vm.tab, label = "tab") { tab ->
                        when (tab) {
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
        AnimatedContent(
            targetState = top,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "route",
        ) { route ->
            when (route) {
                is Route.Browse -> Box(Modifier.fillMaxSize().background(Z.Bg)) {
                    Backdrop(vm.backdrop)
                    BrowseScreen(vm, route.state, embedded = false)
                }
                is Route.Player -> PlayerScreen(vm, route.request, onClose = { vm.onBack() })
                null -> Unit
            }
        }
        if (vm.miniLive && top !is Route.Player) {
            MiniPlayer(vm, Modifier.align(Alignment.TopEnd))
        }
        if (typed.isNotEmpty()) {
            val match = typed.toIntOrNull()?.let { n -> vm.channels.value.firstOrNull { it.number == n } }
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = Z.Gutter)
                    .clip(RoundedCornerShape(18.dp)).background(Z.Surface2).padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(typed, style = ZType.Display, color = Z.Text)
                if (match != null) {
                    Spacer(Modifier.width(16.dp))
                    ChannelLogo(match.logo, size = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(match.label, style = ZType.Label, color = Z.Muted)
                }
            }
        }
    }
}

/** Ambient artwork on the right side of the screen, faded into the background. */
@Composable
fun Backdrop(url: String?) {
    Crossfade(targetState = url, label = "backdrop") { u ->
        if (u != null) {
            Box(Modifier.fillMaxSize()) {
                ZImage(
                    u, Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.75f).align(Alignment.TopEnd),
                    contentScale = ContentScale.Crop, placeholder = Color.Transparent, alpha = 0.55f,
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0.25f to Z.Bg, 0.6f to Z.Bg.copy(alpha = 0.6f), 1f to Color.Transparent
                        )
                    )
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to Z.Bg)
                    )
                )
            }
        }
    }
}
