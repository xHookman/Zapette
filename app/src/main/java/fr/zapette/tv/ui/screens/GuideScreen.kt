@file:OptIn(ExperimentalComposeUiApi::class)

package fr.zapette.tv.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.Programme
import fr.zapette.tv.live.LiveTv
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.EmptyState
import fr.zapette.tv.ui.components.Fmt
import fr.zapette.tv.ui.components.FocusSurface
import fr.zapette.tv.ui.components.LiveBadge
import fr.zapette.tv.ui.components.ProgressLine
import fr.zapette.tv.ui.components.SingleLine
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.components.restoreFocus
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * The TV guide, which is also the channel list: channels on the left, the day's
 * schedule of the focused channel on the right.
 *
 * Kept light for weak TVs: the schedule only appears once the focus rests on a
 * channel, and moving in the channel list never rebuilds it.
 */
@Composable
fun GuideScreen(vm: AppViewModel) {
    val channels by vm.live.channels.collectAsState()
    if (channels.isEmpty()) {
        EmptyState("Guide indisponible", "Aucune chaîne n'est configurée.")
        return
    }
    var focusedId by remember { mutableStateOf(vm.guideFocusId ?: vm.store.lastChannelId ?: channels.first().id) }
    var day by remember { mutableIntStateOf(0) }

    // The schedule follows the focus once it rests on a channel (and not at once
    // when the tab opens: switching tab stays instant)
    var scheduleId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedId) {
        delay(SCHEDULE_DELAY_MS)
        scheduleId = focusedId
    }

    LaunchedEffect(Unit) { vm.backdrop = null }

    // Coming down from the tabs, the focus goes to the channel list, on the current channel
    val channelFocus = remember { ChannelFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .focusProperties { enter = { channelFocus.target() } }
            .focusGroup()
    ) {
        GuideHeader(day) { day = it }
        Row(Modifier.fillMaxSize().padding(start = Z.Gutter, end = Z.Gutter)) {
            ChannelPane(vm, channels, focusedId, channelFocus) { focusedId = it }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val shown = channels.firstOrNull { it.id == scheduleId }
                if (shown != null) key(shown.id, day) { SchedulePane(vm, shown, day) }
            }
        }
    }
}

/** Remembers whether the focused channel's row is on screen, to send the focus there. */
private class ChannelFocus {
    val requester = FocusRequester()
    var attached = false

    fun target(): FocusRequester = if (attached) requester else FocusRequester.Default
}

@Composable
private fun GuideHeader(day: Int, onDay: (Int) -> Unit) {
    val dayStart = remember(day) { dayStart(day) }
    Row(
        Modifier.padding(start = Z.Gutter, end = Z.Gutter, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Guide TV", style = ZType.Headline, color = Z.Text)
        Spacer(Modifier.width(14.dp))
        Text(Fmt.day(dayStart + 43_200), style = ZType.Label, color = Z.Muted)
        Spacer(Modifier.weight(1f))
        DayChip("Aujourd'hui", day == 0) { onDay(0) }
        Spacer(Modifier.width(8.dp))
        DayChip("Demain", day == 1) { onDay(1) }
    }
}

@Composable
private fun ChannelPane(
    vm: AppViewModel,
    channels: List<Channel>,
    focusedId: String,
    channelFocus: ChannelFocus,
    onFocused: (String) -> Unit,
) {
    val now by vm.live.now.collectAsState()
    LazyColumn(
        state = vm.guideChannels,
        modifier = Modifier.width(310.dp).fillMaxHeight(),
        contentPadding = PaddingValues(bottom = 48.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(channels, key = { it.id }, contentType = { "channel" }) { c ->
            val isFocused = c.id == focusedId
            if (isFocused) {
                DisposableEffect(Unit) {
                    channelFocus.attached = true
                    onDispose { channelFocus.attached = false }
                }
            }
            GuideChannelRow(
                c, vm.live.nowPlaying(c, now), selected = isFocused,
                onClick = { vm.watch(c) },
                onFocus = {
                    onFocused(c.id)
                    vm.guideFocusId = c.id
                },
                modifier = (if (isFocused) Modifier.focusRequester(channelFocus.requester) else Modifier)
                    .restoreFocus(vm.restoreFocus && isFocused) { vm.restoreFocus = false },
            )
        }
    }
}

/** The day's programmes of one channel, opened on the one on air. */
@Composable
private fun SchedulePane(vm: AppViewModel, channel: Channel, day: Int) {
    val guide by vm.live.guide.collectAsState()
    val now by vm.live.now.collectAsState()
    val programmes = remember(guide, channel.xmltvId, day) {
        val start = dayStart(day)
        guide[channel.xmltvId].orEmpty().filter { it.stop > start && it.start < start + 86_400 }
    }
    if (programmes.isEmpty()) {
        Column(Modifier.padding(top = 24.dp)) {
            if (guide.isEmpty()) {
                Spinner(size = 28.dp)
                Spacer(Modifier.height(12.dp))
                Text("Chargement du guide…", style = ZType.Body, color = Z.Muted)
            } else {
                Text("Pas de programme communiqué pour ${channel.label}.", style = ZType.Body, color = Z.Muted)
            }
        }
        return
    }
    val onAir = programmes.indexOfFirst { it.stop > now }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (onAir - 1).coerceAtLeast(0))
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 48.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(programmes, key = { it.start }, contentType = { "programme" }) { p ->
            ProgrammeRow(p, now, onClick = {
                val nowSec = LiveTv.nowSeconds()
                when {
                    p.isOnAt(nowSec) -> vm.watch(channel)
                    // Already broadcast: from its start, if the channel still has it
                    p.stop <= nowSec -> vm.watchFrom(channel, p.start, p.title)
                    else -> vm.messages.toast(p.title, "Diffusion à ${Fmt.time(p.start)} sur ${channel.label}")
                }
            })
        }
    }
}

/** Midnight of today + [day], in epoch seconds. */
private fun dayStart(day: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, day)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

private const val SCHEDULE_DELAY_MS = 350L

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, onFocus = onClick,
        shape = RoundedCornerShape(18.dp),
        container = if (selected) Color(0x24FFFFFF) else Color.Transparent,
        focusedContainer = Color.White, ring = false, focusedScale = 1.04f,
    ) { focused ->
        Text(
            label, style = ZType.Label, color = if (focused) Color.Black else if (selected) Z.Text else Z.Muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GuideChannelRow(
    c: Channel,
    current: Programme?,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick, onFocus = onFocus, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), focusedScale = 1.03f,
        container = if (selected) Z.Surface2 else Color.Transparent, focusedContainer = Z.Surface3,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${c.number}", style = ZType.Number, color = if (selected) Z.Live else Z.Faint,
                modifier = Modifier.width(30.dp),
            )
            ChannelLogo(c.logo, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SingleLine(c.label, ZType.Label, Z.Text)
                SingleLine(current?.title ?: "—", ZType.Small, Z.Muted)
            }
        }
    }
}

@Composable
private fun ProgrammeRow(p: Programme, now: Long, onClick: () -> Unit) {
    val onAir = p.isOnAt(now)
    val past = p.stop <= now
    FocusSurface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(if (past) 0.55f else 1f),
        shape = RoundedCornerShape(12.dp), focusedScale = 1.02f,
        container = if (onAir) Z.Surface2 else Color.Transparent, focusedContainer = Z.Surface3,
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.width(74.dp)) {
                Text(Fmt.time(p.start), style = ZType.Number.copy(fontSize = 17.sp), color = if (onAir) Z.Live else Z.Text)
                Text(Fmt.minutes(((p.stop - p.start)).toInt()), style = ZType.Small, color = Z.Faint)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.title, style = ZType.Label.copy(fontSize = 16.sp), color = Z.Text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onAir) {
                        Spacer(Modifier.width(10.dp))
                        LiveBadge(text = "EN COURS")
                    }
                }
                val meta = listOf(p.subtitle, p.prettyCategory, p.episode, p.rating).filter { it.isNotBlank() }
                if (meta.isNotEmpty()) SingleLine(meta.joinToString(" · "), ZType.Small, Z.Muted)
                if (onAir) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(p.progress(now), Modifier.width(220.dp), height = 3.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(Fmt.remaining(p.stop, now), style = ZType.Small, color = Z.Muted)
                    }
                }
                if (focused && p.desc.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.desc, style = ZType.Body.copy(fontSize = 14.sp), color = Z.Muted,
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
