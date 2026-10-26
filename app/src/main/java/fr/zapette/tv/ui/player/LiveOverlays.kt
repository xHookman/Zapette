package fr.zapette.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.data.Channel
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.Clock
import fr.zapette.tv.ui.components.FocusSurface
import fr.zapette.tv.ui.components.Fmt
import fr.zapette.tv.ui.components.LiveBadge
import fr.zapette.tv.ui.components.Pill
import fr.zapette.tv.ui.components.ProgressLine
import fr.zapette.tv.ui.components.SingleLine
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.components.ZImage
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.delay

/** Where the picture is in the channel's recording window. */
data class TimeShift(val delayMs: Long = 0, val windowMs: Long = 0, val paused: Boolean = false) {
    val live get() = delayMs == 0L && !paused
}

private val BannerShade = Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0xB3000000), 1f to Color(0xF2000000))

/** Bottom banner: channel, programme on air with its progress, and what comes next. */
@Composable
fun LiveBanner(vm: AppViewModel, channel: Channel?, visible: Boolean, now: Long, timeShift: TimeShift, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible && channel != null, modifier = modifier,
        enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
    ) {
        val c = channel ?: return@AnimatedVisibility
        // What is on screen: earlier than now when the user went back in time
        val at = now - timeShift.delayMs / 1000
        val current = vm.live.nowPlaying(c, at)
        val next = vm.live.nextUp(c, at)
        val tuning = vm.playback.channelId != c.id || vm.playback.loading
        Box(Modifier.fillMaxWidth().background(BannerShade)) {
            Row(
                Modifier.fillMaxWidth().padding(start = Z.Gutter, end = Z.Gutter, top = 72.dp, bottom = 36.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ChannelLogo(c.logo, size = 72.dp, background = Color(0x26FFFFFF))
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.number}", style = ZType.Number, color = Z.Text)
                        Spacer(Modifier.width(8.dp))
                        Text(c.label, style = ZType.Label, color = Z.Muted)
                        Spacer(Modifier.width(10.dp))
                        if (timeShift.live) LiveBadge() else Pill(delayText(timeShift), color = Z.Gold, textColor = Color.Black)
                        if (vm.subtitlesOn) {
                            Spacer(Modifier.width(8.dp))
                            Pill("Sous-titres")
                        }
                        if (tuning) {
                            Spacer(Modifier.width(10.dp))
                            Spinner(size = 16.dp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(current?.title ?: c.label, style = ZType.Headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current != null) {
                        val meta = listOf(current.subtitle, current.prettyCategory, Fmt.range(current.start, current.stop))
                            .filter { it.isNotBlank() }.joinToString("  ·  ")
                        Text(meta, style = ZType.Label, color = Z.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(current.progress(at), Modifier.width(420.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(Fmt.remaining(current.stop, at), style = ZType.Small, color = Z.Muted)
                        }
                    }
                    if (next != null) {
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Text("À suivre  ", style = ZType.Small, color = Z.Live)
                            SingleLine("${Fmt.time(next.start)}  ${next.title}", ZType.Small, Z.Text)
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Clock()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (timeShift.live) "OK chaînes   ▲ infos   ◀ ▶ reculer, avancer" else "▼ revenir au direct   ◀ ▶ reculer, avancer",
                        style = ZType.Small, color = Z.Faint,
                    )
                }
            }
        }
    }
}

/** Right panel (▶): picture and summary of the programme, then the next ones. */
@Composable
fun ProgrammeSheet(vm: AppViewModel, channel: Channel, now: Long) {
    val current = vm.live.nowPlaying(channel, now)
    val upcoming = vm.live.programmes(channel).filter { it.start >= (current?.stop ?: now) }.take(5)
    Column(Modifier.width(470.dp).fillMaxHeight().background(Color(0xF0101217)).padding(28.dp)) {
        if (current != null) {
            ZImage(
                current.icon.ifBlank { channel.fanart },
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)), widthPx = 800,
            )
            Spacer(Modifier.height(16.dp))
            Text(current.title, style = ZType.Title, color = Z.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOf(current.prettyCategory, current.episode, current.rating, Fmt.range(current.start, current.stop))
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, style = ZType.Small, color = Z.Muted)
            Spacer(Modifier.height(10.dp))
            Text(
                current.desc.ifBlank { "Pas de résumé." }, style = ZType.Body.copy(fontSize = 14.sp), color = Z.Muted,
                maxLines = 7, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(channel.label, style = ZType.Title, color = Z.Text)
            Text("Programme non communiqué", style = ZType.Body, color = Z.Muted)
        }
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("ENSUITE", style = ZType.Small.copy(letterSpacing = 1.4.sp), color = Z.Live)
            Spacer(Modifier.height(6.dp))
            upcoming.forEach { p ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(Fmt.time(p.start), style = ZType.Number.copy(fontSize = 14.sp), color = Z.Text, modifier = Modifier.width(56.dp))
                    SingleLine(p.title, ZType.Body.copy(fontSize = 14.sp), Z.Muted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("OK : audio et sous-titres", style = ZType.Small, color = Z.Faint)
    }
}

/** Left panel (◀ or OK): every channel with its programme, the current one focused. */
@Composable
fun ChannelList(vm: AppViewModel, channels: List<Channel>, tunedId: String?, now: Long, onPick: (Channel) -> Unit) {
    val start = channels.indexOfFirst { it.id == tunedId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    Column(Modifier.width(470.dp).fillMaxHeight().background(Color(0xF00B0C10))) {
        Row(Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chaînes", style = ZType.Title, color = Z.Text)
            Spacer(Modifier.weight(1f))
            Clock()
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.id }, contentType = { "channel" }) { c ->
                val programme = vm.live.nowPlaying(c, now)
                val tuned = c.id == tunedId
                FocusSurface(
                    onClick = { onPick(c) },
                    modifier = Modifier.fillMaxWidth().then(if (c.id == channels.getOrNull(start)?.id) Modifier.focusRequester(focus) else Modifier),
                    shape = RoundedCornerShape(12.dp), focusedScale = 1.02f,
                    container = if (tuned) Z.LiveSoft else Color.Transparent, focusedContainer = Z.Surface3,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.number}", style = ZType.Number, color = if (tuned) Z.Live else Z.Faint, modifier = Modifier.width(34.dp))
                        ChannelLogo(c.logo, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            SingleLine(programme?.title ?: c.label, ZType.Label, Z.Text)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SingleLine(c.label, ZType.Small, Z.Muted, Modifier.weight(1f, fill = false))
                                if (programme != null) {
                                    Spacer(Modifier.width(8.dp))
                                    ProgressLine(programme.progress(now), Modifier.width(70.dp), height = 3.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
}

/** Channel number being typed (top right). */
@Composable
fun NumberBox(typed: String, match: Channel?, modifier: Modifier) {
    Row(
        modifier.padding(top = 32.dp, end = Z.Gutter).clip(RoundedCornerShape(18.dp)).background(Color(0xE6101217))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(typed, style = ZType.Display.copy(fontSize = 44.sp, fontFeatureSettings = "tnum"), color = Color.White)
        if (match != null) {
            Spacer(Modifier.width(16.dp))
            ChannelLogo(match.logo, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Text(match.label, style = ZType.Label, color = Z.Muted)
        }
    }
}

private fun delayText(t: TimeShift): String =
    (if (t.paused) "En pause" else "Différé") + if (t.delayMs > 0) "  −" + Fmt.clock(t.delayMs) else ""

/** Shown while the picture is not live: the user must always know it. */
@Composable
fun DelayedPill(t: TimeShift, modifier: Modifier) {
    Row(
        modifier.padding(top = 32.dp, end = Z.Gutter).clip(RoundedCornerShape(12.dp)).background(Color(0xE6101217))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Z.Gold))
        Spacer(Modifier.width(10.dp))
        Text(delayText(t), style = ZType.Label, color = Color.White)
        Spacer(Modifier.width(14.dp))
        Text("▼ direct", style = ZType.Small, color = Z.Muted)
    }
}

/** "−30 s" in the middle of the screen after a jump. */
@Composable
fun SeekFlash(text: String, modifier: Modifier) {
    Text(
        text, style = ZType.Headline, color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99000000))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
