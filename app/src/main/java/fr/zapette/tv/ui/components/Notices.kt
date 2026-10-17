package fr.zapette.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.Route
import fr.zapette.tv.ui.player.MiniHeight
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/* Short messages: toasts (bottom right) and the "please wait" pill (top). */


@Composable
fun Toasts(vm: AppViewModel) {
    val toasts by vm.messages.toasts.collectAsState()
    // Stay above the mini-player when it sits in the bottom-right corner
    val miniBelow = vm.playback.mini && vm.playback.miniAtBottom && vm.stack.lastOrNull() !is Route.Player
    Box(
        Modifier.fillMaxSize().padding(end = Z.Gutter, bottom = if (miniBelow) 32.dp + MiniHeight + 12.dp else 32.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
            toasts.forEach { t ->
                key(t.id) {
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }
                    AnimatedVisibility(visible, enter = slideInHorizontally { it / 2 } + fadeIn(), exit = fadeOut()) {
                        Row(
                            Modifier
                                .width(420.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Z.Surface2)
                                .border(1.dp, Z.Line, RoundedCornerShape(14.dp))
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(10.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (t.error) Z.Live else Z.Gold)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                if (t.title.isNotBlank()) Text(t.title, style = ZType.Label, color = Z.Text, maxLines = 2)
                                if (t.message.isNotBlank()) Text(
                                    t.message, style = ZType.Body.copy(fontSize = 13.sp), color = Z.Muted,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusyOverlay(vm: AppViewModel) {
    val busy by vm.messages.busy.collectAsState()
    val progress by vm.engine.host.progress.collectAsState()
    val message = progress ?: busy
    AnimatedVisibility(message != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Z.Surface3).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner(size = 18.dp)
                Spacer(Modifier.width(12.dp))
                Text(message ?: "", style = ZType.Label, color = Z.Text)
            }
        }
    }
}
