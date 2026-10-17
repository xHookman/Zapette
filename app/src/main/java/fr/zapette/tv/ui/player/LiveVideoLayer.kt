package fr.zapette.tv.ui.player

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
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.FocusTracker
import fr.zapette.tv.ui.components.SingleLine
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.delay

val MiniWidth = 224.dp
val MiniHeight = 126.dp
private val MiniMargin = 24.dp
private val MiniTop = 88.dp       // under the tab bar

/**
 * The live video. A single view slides between full screen and the corner
 * mini-player, so the stream is never interrupted.
 *
 * The mini-player sits bottom-right and moves top-right while the focused
 * element is under it.
 */
@Composable
fun LiveVideoLayer(vm: AppViewModel, fullscreen: Boolean) {
    val playback = vm.playback
    val mini = !fullscreen && playback.mini
    SideEffect { FocusTracker.enabled = mini }
    val player = playback.player ?: return
    if (!fullscreen && !mini) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val miniX = maxWidth - MiniMargin - MiniWidth
        val bottomY = maxHeight - MiniMargin - MiniHeight

        // Dodge the focused element, once the list has finished scrolling to it
        LaunchedEffect(FocusTracker.tick, fullscreen) {
            if (fullscreen) return@LaunchedEffect
            delay(250)
            val focus = FocusTracker.rect() ?: return@LaunchedEffect
            fun area(y: Dp) = with(density) { Rect(miniX.toPx(), y.toPx(), (miniX + MiniWidth).toPx(), (y + MiniHeight).toPx()) }
            when {
                focus.overlaps(area(bottomY)) -> playback.miniAtBottom = false
                focus.overlaps(area(MiniTop)) -> playback.miniAtBottom = true
            }
        }

        val spec = tween<Dp>(320, easing = FastOutSlowInEasing)
        val x by animateDpAsState(if (fullscreen) 0.dp else miniX, spec, label = "x")
        val y by animateDpAsState(
            when {
                fullscreen -> 0.dp
                playback.miniAtBottom -> bottomY
                else -> MiniTop
            },
            spec, label = "y",
        )
        val w by animateDpAsState(if (fullscreen) maxWidth else MiniWidth, spec, label = "w")
        val h by animateDpAsState(if (fullscreen) maxHeight else MiniHeight, spec, label = "h")

        val shape = RoundedCornerShape(if (fullscreen) 0.dp else 8.dp)
        Box(
            Modifier
                .offset(x, y)
                .size(w, h)
                .background(Color.Black, shape)
                .then(if (fullscreen) Modifier else Modifier.border(2.dp, Color(0x80FFFFFF), shape))
        ) {
            VideoView(player, Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 2.dp))
            if (!fullscreen) playback.channel?.let { channel ->
                Row(
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
