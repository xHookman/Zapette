package fr.zapette.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.PluginItem
import fr.zapette.tv.data.Programme
import fr.zapette.tv.data.ResumeEntry
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(12.dp)

/** Requests focus on this element when it is the one to restore. */
@Composable
fun Modifier.restoreFocus(isTarget: Boolean, onRestored: () -> Unit = {}): Modifier {
    // Only the item that must take the focus pays for a requester and an effect
    // (this modifier is on every card of every list)
    if (!isTarget) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(isTarget) {
        if (isTarget) {
            delay(40)
            if (runCatching { requester.requestFocus() }.isSuccess) onRestored()
        }
    }
    return this.focusRequester(requester)
}

private val bottomShade = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))

/** "En ce moment": what is on air on a channel, as a picture card. */
@Composable
fun LiveCard(
    channel: Channel,
    programme: Programme?,
    now: Long,
    width: Dp,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(width)) {
        FocusSurface(onClick = onClick, onFocus = onFocus, shape = CardShape, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            val image = programme?.icon?.takeIf { it.isNotBlank() } ?: channel.fanart
            ZImage(image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(bottomShade))
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(channel.logo, size = 38.dp, background = Color(0xCC0E1015))
                Spacer(Modifier.width(8.dp))
                Text("${channel.number}", style = ZType.Number, color = Color.White)
            }
            if (programme != null) {
                ProgressLine(
                    programme.progress(now),
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    height = 3.dp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SingleLine(programme?.title?.ifBlank { null } ?: channel.label, ZType.Label, Z.Text)
        SingleLine(
            if (programme != null) "${channel.label} · ${Fmt.remaining(programme.stop, now)}" else "En direct",
            ZType.Small, Z.Muted,
        )
    }
}

/** Tile of the channels grid: logo, number and what is on air. */
@Composable
fun ChannelTile(
    channel: Channel,
    programme: Programme?,
    now: Long,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(onClick = onClick, onFocus = onFocus, shape = CardShape, modifier = modifier, focusedScale = 1.05f) { focused ->
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logo, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SingleLine(channel.label, ZType.Label, Z.Text)
                    Text("${channel.number}", style = ZType.Number.copy(fontSize = 13.sp), color = if (focused) Z.Live else Z.Faint)
                }
            }
            Spacer(Modifier.height(12.dp))
            SingleLine(programme?.title ?: "Programme non communiqué", ZType.Body.copy(fontSize = 14.sp), if (programme != null) Z.Text else Z.Faint)
            Spacer(Modifier.height(8.dp))
            if (programme != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(programme.progress(now), Modifier.weight(1f), height = 3.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(Fmt.time(programme.stop), style = ZType.Small, color = Z.Muted)
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Generic add-on item (programme, episode, folder…). */
@Composable
fun MediaCard(
    item: PluginItem,
    width: Dp = Dp.Unspecified,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    resumeProgress: Float = 0f,
) {
    val logo = item.hasLogoArt
    Column(if (width == Dp.Unspecified) modifier.fillMaxWidth() else modifier.width(width)) {
        FocusSurface(
            onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, shape = CardShape,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            container = if (logo) Z.Surface2 else Z.Surface,
        ) { focused ->
            when {
                item.kind == "search" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Search, null, tint = if (focused) Z.Live else Z.Muted, modifier = Modifier.size(40.dp))
                }
                logo -> ZImage(item.thumb, Modifier.fillMaxSize().padding(18.dp), ContentScale.Fit, placeholder = Color.Transparent)
                item.landscape != null -> ZImage(item.landscape, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize().background(Z.Surface2), contentAlignment = Alignment.Center) {
                    Text(
                        item.title.take(1).uppercase(), style = ZType.Display, color = Z.Faint,
                    )
                }
            }
            if (item.isPlayable && focused) {
                Box(
                    Modifier.align(Alignment.Center).size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
            item.info.duration?.takeIf { it > 0 && item.isPlayable }?.let {
                Text(
                    Fmt.minutes(it), style = ZType.Small, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .clip(RoundedCornerShape(5.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (resumeProgress > 0f) {
                ProgressLine(resumeProgress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 4.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.title, style = ZType.Label, color = Z.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val sub = subtitleOf(item)
        if (sub.isNotBlank()) SingleLine(sub, ZType.Small, Z.Muted)
    }
}

fun subtitleOf(item: PluginItem): String {
    val parts = mutableListOf<String>()
    item.info.tvshowtitle?.takeIf { it.isNotBlank() && it != item.title }?.let { parts += it }
    val se = buildString {
        item.info.season?.takeIf { it > 0 }?.let { append("S$it") }
        item.info.episode?.takeIf { it > 0 }?.let { append(" E$it") }
    }.trim()
    if (se.isNotEmpty()) parts += se
    val date = item.info.aired ?: item.info.premiered ?: item.info.date
    date?.takeIf { it.length >= 10 }?.let { d ->
        // 2024-05-21 or 21.05.2024
        parts += if (d[4] == '-') "${d.substring(8, 10)}/${d.substring(5, 7)}/${d.substring(0, 4)}" else d.take(10)
    }
    if (parts.isEmpty()) item.label2.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
fun ResumeCard(entry: ResumeEntry, width: Dp, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(width)) {
        FocusSurface(onClick = onClick, onLongClick = onLongClick, onFocus = onFocus, shape = CardShape, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ZImage(entry.image, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(bottomShade))
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(26.dp))
            Text(
                "${Fmt.clock(entry.duration - entry.position)} restantes", style = ZType.Small, color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            )
            ProgressLine(entry.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), height = 4.dp)
        }
        Spacer(Modifier.height(10.dp))
        SingleLine(entry.label, ZType.Label, Z.Text)
        if (entry.subtitle.isNotBlank()) SingleLine(entry.subtitle, ZType.Small, Z.Muted)
    }
}

/** "Ce soir": prime time programme of a channel. */
@Composable
fun TonightCard(channel: Channel, programme: Programme, width: Dp, onClick: () -> Unit, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(width)) {
        FocusSurface(onClick = onClick, onFocus = onFocus, shape = CardShape, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ZImage(programme.icon.ifBlank { channel.fanart }, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(bottomShade))
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logo, size = 34.dp, background = Color(0xCC0E1015))
                Spacer(Modifier.width(8.dp))
                Text(Fmt.time(programme.start), style = ZType.Number, color = Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))
        SingleLine(programme.title, ZType.Label, Z.Text)
        SingleLine(listOf(programme.prettyCategory, channel.label).filter { it.isNotBlank() }.joinToString(" · "), ZType.Small, Z.Muted)
    }
}

/** Logo tile used for replay services and menus made of channel logos. */
@Composable
fun LogoTile(item: PluginItem, width: Dp, onClick: () -> Unit, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(onClick = onClick, onFocus = onFocus, shape = CardShape, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ZImage(item.thumb, Modifier.fillMaxSize().padding(20.dp), ContentScale.Fit, placeholder = Color.Transparent)
        }
        Spacer(Modifier.height(8.dp))
        SingleLine(item.title, ZType.Small, Z.Muted)
    }
}

@Composable
fun EmptyState(title: String, message: String = "", modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(Z.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = ZType.Title, color = Z.Text)
        if (message.isNotBlank()) Text(message, style = ZType.Body, color = Z.Muted)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}
