@file:OptIn(UnstableApi::class)

package fr.zapette.tv.ui.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.FocusTracker
import fr.zapette.tv.ui.components.SingleLine
import fr.zapette.tv.ui.theme.ZType

val MiniWidth = 224.dp
val MiniHeight = 126.dp
private val MiniMargin = 24.dp
/** Top position of the mini-player: under the tab bar. */
private val MiniTop = 88.dp

/**
 * The live video, drawn by a single view that slides between full screen and
 * the corner mini-player: the stream is never interrupted.
 *
 * The mini-player sits bottom-right and moves to the top-right while the
 * focused element is under it, so it never hides what the user is selecting.
 */
@Composable
fun LiveVideoLayer(vm: AppViewModel, fullscreen: Boolean) {
    val mini = !fullscreen && vm.miniLive
    SideEffect { FocusTracker.enabled = mini }
    val player = vm.currentLivePlayer() ?: return
    if (!fullscreen && !vm.miniLive) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val miniX = maxWidth - MiniMargin - MiniWidth
        val bottomY = maxHeight - MiniMargin - MiniHeight

        // Dodge the focused element
        val focus = FocusTracker.rect
        LaunchedEffect(focus, fullscreen) {
            if (fullscreen || focus == null) return@LaunchedEffect
            fun rect(y: Dp) = with(density) {
                Rect(miniX.toPx(), y.toPx(), (miniX + MiniWidth).toPx(), (y + MiniHeight).toPx())
            }
            val bottom = rect(bottomY)
            val top = rect(MiniTop)
            vm.miniAtBottom = when {
                focus.overlaps(bottom) -> false
                focus.overlaps(top) -> true
                else -> vm.miniAtBottom
            }
        }

        val spec = tween<Dp>(320, easing = FastOutSlowInEasing)
        val x by animateDpAsState(if (fullscreen) 0.dp else miniX, spec, label = "x")
        val y by animateDpAsState(
            when {
                fullscreen -> 0.dp
                vm.miniAtBottom -> bottomY
                else -> MiniTop
            }, spec, label = "y",
        )
        val w by animateDpAsState(if (fullscreen) maxWidth else MiniWidth, spec, label = "w")
        val h by animateDpAsState(if (fullscreen) maxHeight else MiniHeight, spec, label = "h")

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, player) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> player.pause()
                    Lifecycle.Event.ON_START -> { player.seekToDefaultPosition(); player.play() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        val shape = RoundedCornerShape(if (fullscreen) 0.dp else 8.dp)
        Box(
            Modifier
                .offset(x, y)
                .size(w, h)
                .background(Color.Black, shape)
                .then(if (fullscreen) Modifier else Modifier.border(2.dp, Color(0x80FFFFFF), shape))
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setKeepContentOnPlayerReset(true)
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        keepScreenOn = true
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 2.dp),
            )
            if (!fullscreen) {
                val channel = vm.channel(vm.liveChannelId)
                if (channel != null) Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xCC000000)))
                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(channel.logo, size = 22.dp, background = Color(0x33FFFFFF))
                    Spacer(Modifier.width(6.dp))
                    SingleLine("${channel.number}  ${channel.label}", ZType.Small, Color.White)
                }
            }
        }
    }
}
