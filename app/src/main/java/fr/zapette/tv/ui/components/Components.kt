package fr.zapette.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import fr.zapette.tv.Images
import fr.zapette.tv.Perf
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the focused element is on screen (the mini-player moves out of its way). */
object FocusTracker {
    /** On only while the mini-player is shown. */
    var enabled by mutableStateOf(false)

    /** Changes at each focus move (the only state written: no work while scrolling). */
    var tick by mutableIntStateOf(0)

    internal var coordinates: LayoutCoordinates? = null

    /** Current position of the focused element, read on demand. */
    fun rect(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

/**
 * The single focusable building block of the app: a surface that lifts and
 * gets a white ring when focused (the Android TV focus language).
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.06f,
    container: Color = Z.Surface2,
    focusedContainer: Color = container,
    ring: Boolean = true,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .then(
                if (FocusTracker.enabled) Modifier.onGloballyPositioned { coordinates[0] = it } else Modifier
            )
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) {
                        if (FocusTracker.enabled) {
                            FocusTracker.coordinates = coordinates[0]
                            FocusTracker.tick++
                        }
                        onFocus?.invoke()
                    }
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = focusedScale * 0.97f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Z.Text,
            focusedContainerColor = focusedContainer,
            focusedContentColor = Z.Text,
            pressedContainerColor = focusedContainer,
            pressedContentColor = Z.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (ring) Border(BorderStroke(3.dp, Z.Focus), inset = 0.dp, shape = shape)
            else Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(),
    ) {
        content(focused)
    }
}

/** A pill-shaped text button for actions (Regarder, Réessayer…). */
@Composable
fun ZButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    onFocus: (() -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        focusedScale = 1.05f,
        container = if (primary) Z.Live else Z.Surface3,
        focusedContainer = if (primary) Z.Live else Color.White,
        ring = false,
        onFocus = onFocus,
    ) { focused ->
        val fg = if (focused && !primary) Color.Black else Color.White
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Text(text, style = ZType.Label, color = fg, maxLines = 1)
        }
    }
}

/**
 * A programme or episode picture, decoded at [widthPx] (never at full resolution,
 * which is what makes weak TVs stutter while scrolling).
 */
@Composable
fun ZImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = Z.Surface2,
    alpha: Float = 1f,
    widthPx: Int = Images.CARD_PX,
) {
    Box(modifier.background(placeholder)) {
        if (!url.isNullOrBlank()) {
            val context = LocalContext.current
            val request = remember(url, widthPx) { Images.picture(context, url, widthPx) }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                alpha = alpha,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Channel logo on a soft rounded tile (logos are transparent PNGs, always decoded at one size). */
@Composable
fun ChannelLogo(url: String, modifier: Modifier = Modifier, size: Dp = 56.dp, background: Color = Color(0x22FFFFFF)) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            val context = LocalContext.current
            val request = remember(url) { Images.logo(context, url) }
            AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.padding(size * 0.14f).matchParentSize(),
            )
        }
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "EN DIRECT") {
    // A slow blink (one change per second) instead of a 60 fps animation
    var on by remember { mutableStateOf(true) }
    if (!Perf.lite) LaunchedEffect(Unit) {
        while (true) { delay(900); on = !on }
    }
    val dot = if (on) 1f else 0.35f
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Z.Live).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = dot)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = ZType.Small, color = Color.White)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Z.Surface3, textColor: Color = Z.Text) {
    Text(
        text, style = ZType.Small, color = textColor, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = Z.Live, track: Color = Color(0x33FFFFFF), height: Dp = 4.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 44.dp, color: Color = Color.White) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() * 0.09f
        drawArc(
            color = color.copy(alpha = 0.15f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke),
        )
        drawArc(
            color = color, startAngle = angle, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.padding(start = Z.Gutter, end = Z.Gutter, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = ZType.Section, color = Z.Text)
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = ZType.Label, color = Z.Muted)
        }
    }
}

@Composable
fun SingleLine(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A wall clock, as on every TV channel banner. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L * (60 - (now / 1000) % 60))
        }
    }
    Text(Fmt.time(now / 1000), style = ZType.Clock, color = Z.Text, modifier = modifier)
}

/** Pause glyph (not part of material-icons-core). */
@Composable
fun PauseGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        Box(Modifier.width(10.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

object Fmt {
    private val time = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val day = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)

    fun time(epochSec: Long): String = synchronized(time) { time.format(Date(epochSec * 1000)) }
    fun day(epochSec: Long): String = synchronized(day) { day.format(Date(epochSec * 1000)) }
        .replaceFirstChar { it.uppercase() }

    fun range(start: Long, stop: Long) = "${time(start)} – ${time(stop)}"

    fun remaining(stop: Long, now: Long): String {
        val min = ((stop - now) / 60).coerceAtLeast(0)
        return when {
            min < 1 -> "se termine"
            min < 60 -> "reste $min min"
            else -> "reste ${min / 60} h ${"%02d".format(min % 60)}"
        }
    }

    fun minutes(seconds: Int): String {
        val m = seconds / 60
        return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
