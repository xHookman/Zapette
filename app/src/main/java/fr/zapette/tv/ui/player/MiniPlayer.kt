@file:OptIn(UnstableApi::class)

package fr.zapette.tv.ui.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.SingleLine
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/**
 * The live channel left with "Retour", still playing in the top-right corner
 * while the user browses the menus. Not focusable: the remote keeps driving the menus.
 */
@Composable
fun MiniPlayer(vm: AppViewModel, modifier: Modifier = Modifier) {
    val player = vm.currentLivePlayer() ?: return
    val channel = vm.channel(vm.liveChannelId)
    val now by vm.now.collectAsState()
    val programme = channel?.let { vm.nowPlaying(it, now) }

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

    Column(modifier.padding(top = 18.dp, end = Z.Gutter).width(352.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(10.dp))
                .background(Color.Black)
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
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
            if (channel != null) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xD9000000)))
                        .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(channel.logo, size = 30.dp, background = Color(0x33FFFFFF))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        SingleLine("${channel.number}  ${channel.label}", ZType.Small, Color.White)
                        programme?.let { SingleLine(it.title, ZType.Small, Z.Muted) }
                    }
                }
            }
        }
        Text(
            "Lecture : plein écran  ·  Retour : fermer",
            style = ZType.Small, color = Z.Faint,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}
