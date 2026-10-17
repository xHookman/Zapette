package fr.zapette.tv

import android.content.Context
import coil.request.ImageRequest

/**
 * Image requests shared by the whole app.
 *
 * Channel logos are always decoded at the same small size and cached under
 * their URL: whatever size a screen draws them (22 to 96 dp), the same bitmap
 * is reused from memory, so scrolling never decodes a logo again.
 */
object Images {
    private const val LOGO_PX = 160

    fun logo(context: Context, url: String): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .size(LOGO_PX)
        .memoryCacheKey("logo:$url")
        .crossfade(false)
        .build()

    /** Programme / episode pictures: decoded at the drawn width, never at full resolution. */
    fun picture(context: Context, url: String, widthPx: Int): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, widthPx * 9 / 16)
        .crossfade(!Perf.lite)
        .build()
}
