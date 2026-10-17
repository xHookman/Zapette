@file:OptIn(UnstableApi::class)

package fr.zapette.tv.ui.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import fr.zapette.tv.data.Channel
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/** The video picture: a Media3 PlayerView without its own controls (the overlays are ours). */
@Composable
fun VideoView(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@Composable
fun LoadingOverlay(channel: Channel?) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (channel != null) {
                ChannelLogo(channel.logo, size = 96.dp, background = Color(0x33FFFFFF))
                Spacer(Modifier.height(20.dp))
            }
            Spinner(size = 40.dp)
        }
    }
}

@Composable
fun ErrorOverlay(message: String, hint: String, channel: Channel? = null) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(Z.Surface).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (channel != null) ChannelLogo(channel.logo, size = 72.dp)
            else Icon(Icons.Filled.Warning, null, tint = Z.Live, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text("Lecture impossible", style = ZType.Title, color = Z.Text)
            Spacer(Modifier.height(8.dp))
            Text(message, style = ZType.Body, color = Z.Muted, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Text(hint, style = ZType.Small, color = Z.Faint)
        }
    }
}
