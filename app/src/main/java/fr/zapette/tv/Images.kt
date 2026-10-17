package fr.zapette.tv

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Image requests shared by the whole app.
 *
 * Channel logos are always decoded at the same small size and cached under
 * their URL: whatever size a screen draws them (22 to 96 dp), the same bitmap
 * is reused from memory, so scrolling never decodes a logo again.
 */
object Images {
    private const val LOGO_PX = 160

    /** Width every card picture is decoded at. The ambient backdrop reuses it, from memory. */
    const val CARD_PX = 480

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

    /** Loads pictures into memory now: the first cards of a screen are ready when it opens. */
    suspend fun warm(context: Context, urls: List<String>, widthPx: Int = CARD_PX) = coroutineScope {
        urls.filter { it.isNotBlank() }.distinct()
            .map { url -> async { context.imageLoader.execute(picture(context, url, widthPx)) } }
            .awaitAll()
    }

    /**
     * Downloads pictures to the disk cache only, without filling the memory (that would
     * push out the pictures on screen). Decoded at 1 px: costs almost nothing.
     */
    fun prefetch(context: Context, urls: List<String>) {
        urls.filter { it.isNotBlank() }.distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).size(1).memoryCachePolicy(CachePolicy.DISABLED).build()
            )
        }
    }
}
