package fr.zapette.tv.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.Perf
import fr.zapette.tv.data.Channel
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Pre-computed "snow" frames: tiny bitmaps drawn stretched, so the effect costs almost nothing. */
private object Snow {
    private const val W = 160
    private const val H = 90

    val frames: List<ImageBitmap> by lazy { (0 until 4).map { frame(it) } }

    private fun frame(seed: Int): ImageBitmap {
        val random = Random(seed * 7919 + 17)
        val pixels = IntArray(W * H)
        for (y in 0 until H) {
            // CRT scanlines: one darker row out of two, plus a few bright bands
            val rowGain = (if (y % 2 == 0) 1f else 0.6f) * (if (random.nextInt(14) == 0) 1.25f else 1f)
            for (x in 0 until W) {
                val v = (random.nextInt(256) * rowGain).toInt().coerceIn(0, 255)
                pixels[y * W + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(pixels, W, H, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

private val Vignette = Brush.radialGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))

private val Osd = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 64.sp,
    color = Color(0xFF6CFF6C),
    shadow = Shadow(Color(0xFF0A3A0A), blurRadius = 6f),
)

/**
 * Old-TV channel change: snow with scanlines and the channel number in a green
 * on-screen display, shown until the first picture of the new channel.
 */
@Composable
fun ZapStatic(channel: Channel?, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    if (!Perf.lite) LaunchedEffect(Unit) {
        while (true) {
            delay(45)
            frame = (frame + 1) % 4
        }
    }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading `frame` here only redraws the canvas, without recomposition
            drawImage(
                Snow.frames[frame],
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
                alpha = 0.9f,
            )
            drawRect(Vignette)
        }
        if (channel != null) {
            Column(Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 56.dp)) {
                Text("%02d".format(channel.number), style = Osd)
                Text(channel.label.uppercase(), style = Osd.copy(fontSize = 22.sp))
            }
        }
    }
}
