package fr.zapette.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Zapette palette: night-ink background, warm "on air" coral, crisp white type. */
object Z {
    val Bg = Color(0xFF07080B)
    val Surface = Color(0xFF12141A)
    val Surface2 = Color(0xFF1B1E26)
    val Surface3 = Color(0xFF262A34)
    val Line = Color(0x1FFFFFFF)
    val Text = Color(0xFFF5F6F8)
    val Muted = Color(0xFFA2A8B4)
    val Faint = Color(0xFF6B7180)
    val Live = Color(0xFFFF4B2B)
    val LiveSoft = Color(0x33FF4B2B)
    val Focus = Color(0xFFFFFFFF)
    val Gold = Color(0xFFFFC857)
    val Scrim = Color(0xE6050608)

    val Gutter = 48.dp      // TV overscan-safe horizontal margin
    val GutterTop = 28.dp
}

object ZType {
    private val sans = FontFamily.SansSerif
    val Display = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp)
    val Headline = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
    val Title = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val Section = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp)
    val Body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val Label = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val Small = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
    val Number = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.sp,
        fontFeatureSettings = "tnum")
    val Clock = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, fontFeatureSettings = "tnum")
}

@Composable
fun ZapetteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Z.Live,
            onPrimary = Color.White,
            background = Z.Bg,
            onBackground = Z.Text,
            surface = Z.Surface,
            onSurface = Z.Text,
            surfaceVariant = Z.Surface2,
            onSurfaceVariant = Z.Muted,
            border = Z.Focus,
        ),
        content = content,
    )
}
