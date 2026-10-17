package fr.zapette.tv.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.Perf
import fr.zapette.tv.data.Channel
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Analog "snow": one 256×256 tile of fine grain, repeated over the screen at
 * almost native resolution and shifted at random on every frame. Each grain is
 * slightly smeared horizontally like a real CRT, and a darker scanline every
 * other line gives the old-TV texture. Costs one bitmap-shader rectangle per frame.
 */
private object Snow {
    private const val SIZE = 256

    val tile: Bitmap by lazy {
        val r = Random(20261017)
        val px = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val scan = if (y % 2 == 0) 1f else 0.78f
            // occasional brighter / darker horizontal streak
            val streak = when (r.nextInt(40)) { 0 -> 1.3f; 1 -> 0.65f; else -> 1f }
            var previous = 128f
            for (x in 0 until SIZE) {
                val raw = r.nextInt(256).toFloat()
                val smeared = raw * 0.8f + previous * 0.2f   // slight horizontal smear
                previous = smeared
                val v = (smeared - 128f) * 1.25f + 128f      // contrast: black and white specks
                val g = (v * scan * streak).toInt().coerceIn(0, 255)
                px[y * SIZE + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }
}

private val Osd = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 64.sp,
    color = Color(0xFF6CFF6C),
    shadow = Shadow(Color(0xFF0A3A0A), blurRadius = 6f),
)

/**
 * Old-TV channel change: snow and the channel number in a green on-screen
 * display, shown until the first picture of the new channel.
 */
@Composable
fun ZapStatic(channel: Channel?, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    if (!Perf.lite) LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            frame++
        }
    }
    val paint = remember {
        Paint().apply {
            shader = BitmapShader(Snow.tile, Shader.TileMode.REPEAT, Shader.TileMode.MIRROR)
            isFilterBitmap = true   // soft grain, no square "blocks"
        }
    }
    val matrix = remember { Matrix() }
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Reading `frame` here only redraws the canvas, without recomposition
            val r = Random(frame)
            val grain = (size.height / 720f).coerceAtLeast(1f) * 1.2f
            matrix.reset()
            matrix.setScale(grain, grain)
            matrix.postTranslate(r.nextFloat() * 256f * grain, r.nextFloat() * 256f * grain)
            paint.shader.setLocalMatrix(matrix)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }

        }
        if (channel != null) {
            Column(Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 56.dp)) {
                Text("%02d".format(channel.number), style = Osd)
                Text(channel.label.uppercase(), style = Osd.copy(fontSize = 22.sp))
            }
        }
    }
}
