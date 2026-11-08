package fr.zapette.tv.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** The colours of one look. */
class Palette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val live: Color,
    val liveSoft: Color,
    val focus: Color,
    val gold: Color,
    val scrim: Color,
)

/** The looks the user can pick in the settings. */
enum class Look(
    val id: String,
    val label: String,
    val palette: Palette,
    /** Typewriter-style letters, as on an old set. */
    val monospace: Boolean = false,
    /** Lines and a vignette over the picture of the whole app, as on a cathode-ray screen. */
    val scanlines: Boolean = false,
) {
    Modern(
        "modern", "Moderne",
        Palette(
            bg = Color(0xFF07080B), surface = Color(0xFF12141A), surface2 = Color(0xFF1B1E26),
            surface3 = Color(0xFF262A34), line = Color(0x1FFFFFFF), text = Color(0xFFF5F6F8),
            muted = Color(0xFFA2A8B4), faint = Color(0xFF6B7180), live = Color(0xFFFF4B2B),
            liveSoft = Color(0x33FF4B2B), focus = Color(0xFFFFFFFF), gold = Color(0xFFFFC857),
            scrim = Color(0xE6050608),
        ),
    ),
    Wii(
        "wii", "Wii",
        Palette(
            bg = Color(0xFFEAF2FA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFDDE9F6),
            surface3 = Color(0xFFCBDCEF), line = Color(0x331E4D7A), text = Color(0xFF1B2C40),
            muted = Color(0xFF53657C), faint = Color(0xFF8798AE), live = Color(0xFF2A9BE0),
            liveSoft = Color(0x332A9BE0), focus = Color(0xFF0B6FC2), gold = Color(0xFFF2B01E),
            scrim = Color(0xE6EAF2FA),
        ),
    ),
    OldTv(
        "old_tv", "Vieille télé",
        Palette(
            bg = Color(0xFF0C0A05), surface = Color(0xFF1A1609), surface2 = Color(0xFF262010),
            surface3 = Color(0xFF353014), line = Color(0x33FFB84D), text = Color(0xFFFFB84D),
            muted = Color(0xFFC48A2E), faint = Color(0xFF7A5A22), live = Color(0xFFFF7A1A),
            liveSoft = Color(0x33FF7A1A), focus = Color(0xFFFFD27A), gold = Color(0xFFFFD27A),
            scrim = Color(0xE60C0A05),
        ),
        monospace = true,
        scanlines = true,
    );

    companion object {
        fun fromId(id: String): Look = values().firstOrNull { it.id == id } ?: Modern
    }
}

/**
 * Colours of the current look. Screens read them while they compose; [ZapetteTheme]
 * rebuilds the whole tree when the look changes, so nothing keeps an old colour.
 */
object Z {
    private var p = Look.Modern.palette

    /** Set by [ZapetteTheme] before anything is composed. */
    internal fun apply(look: Look) {
        p = look.palette
    }

    val Bg: Color get() = p.bg
    val Surface: Color get() = p.surface
    val Surface2: Color get() = p.surface2
    val Surface3: Color get() = p.surface3
    val Line: Color get() = p.line
    val Text: Color get() = p.text
    val Muted: Color get() = p.muted
    val Faint: Color get() = p.faint
    val Live: Color get() = p.live
    val LiveSoft: Color get() = p.liveSoft
    val Focus: Color get() = p.focus
    val Gold: Color get() = p.gold
    val Scrim: Color get() = p.scrim

    /** Text and panels drawn over the picture (the banner, the programme sheet, the channel list): always dark-glass, whatever the look. */
    val OnVideo = Color(0xFFF5F6F8)
    val OnVideoMuted = Color(0xFFC9CED8)
    val OnVideoSurface = Color(0xFF262A34)

    val Gutter = 48.dp      // TV overscan-safe horizontal margin
    val GutterTop = 28.dp
}

/** Text styles of the current look. Rebuilt by [ZapetteTheme] together with [Z]. */
object ZType {
    private var faces = Faces(FontFamily.SansSerif)

    val Display: TextStyle get() = faces.display
    val Headline: TextStyle get() = faces.headline
    val Title: TextStyle get() = faces.title
    val Section: TextStyle get() = faces.section
    val Body: TextStyle get() = faces.body
    val Label: TextStyle get() = faces.label
    val Small: TextStyle get() = faces.small
    val Number: TextStyle get() = faces.number
    val Clock: TextStyle get() = faces.clock

    internal fun apply(look: Look) {
        faces = Faces(if (look.monospace) FontFamily.Monospace else FontFamily.SansSerif)
    }

    private class Faces(family: FontFamily) {
        val display = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp)
        val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
        val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
        val section = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp)
        val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
        val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
        val small = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
        val number = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
        val clock = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, fontFeatureSettings = "tnum")
    }
}

/** Whether the blurred picture behind the screens is drawn (Android 12 and later only). */
val ZapetteBlurSupported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 31

@Composable
fun ZapetteTheme(look: Look, content: @Composable () -> Unit) {
    Z.apply(look)
    ZType.apply(look)
    key(look) {
        val p = look.palette
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = p.live,
                onPrimary = Color.White,
                background = p.bg,
                onBackground = p.text,
                surface = p.surface,
                onSurface = p.text,
                surfaceVariant = p.surface2,
                onSurfaceVariant = p.muted,
                border = p.focus,
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
                if (look.scanlines) ScanlineOverlay()
            }
        }
    }
}

/** Thin dark lines and a darker edge, drawn over everything: the picture of an old set. */
@Composable
private fun ScanlineOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 4.dp.toPx()
        val line = step / 2
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, line))
            y += step
        }
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                center = center,
                radius = size.maxDimension * 0.7f,
            ),
        )
    }
}
