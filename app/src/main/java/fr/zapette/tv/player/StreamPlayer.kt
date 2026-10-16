@file:OptIn(UnstableApi::class)

package fr.zapette.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import fr.zapette.tv.data.Stream
import java.util.UUID

/** Builds an ExoPlayer able to play what Kodi's inputstream.adaptive plays. */
object StreamPlayer {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun create(context: Context): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // A 720p TV must not decode 1080p (weak TV chips struggle)
                    .setViewportSizeToPhysicalDisplaySize(context, true)
                    .apply { if (fr.zapette.tv.Perf.lite) setMaxVideoSize(1280, 720) }
                    .setPreferredAudioLanguage("fr")
                    .setPreferredTextLanguage("fr")
                    .setSelectUndeterminedTextLanguage(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)   // subtitles follow the user's choice
            )
        }
        // Short start buffer: zapping must feel instant
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    fun load(context: Context, player: ExoPlayer, stream: Stream, startPositionMs: Long = 0, subtitlesOn: Boolean = false) {
        val headers = stream.headers.toMutableMap()
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: DEFAULT_UA
        headers.keys.filter { it.equals("User-Agent", true) }.forEach { headers.remove(it) }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSource = DefaultDataSource.Factory(context, http)

        val item = MediaItem.Builder()
            .setUri(Uri.parse(stream.url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(stream.label).build())

        when (stream.manifest) {
            "dash" -> item.setMimeType(MimeTypes.APPLICATION_MPD)
            "hls" -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            "ss" -> item.setMimeType(MimeTypes.APPLICATION_SS)
        }

        if (stream.subtitles.isNotEmpty()) {
            item.setSubtitleConfigurations(stream.subtitles.map { url ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                    .setMimeType(subtitleMime(url))
                    .setLanguage("fr")
                    .setSelectionFlags(if (subtitlesOn) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            })
        }

        val factory = DefaultMediaSourceFactory(dataSource)
        val drm = stream.drm
        if (drm != null && (drm.licenseUrl.isNotBlank() || drm.type == "org.w3.clearkey")) {
            val uuid = drmUuid(drm.type)
            val callback = KodiLicenseCallback(drm, stream.headers, http)
            val manager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true)
                .build(callback)
            item.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(drm.licenseUrl.ifBlank { null })
                    .setMultiSession(true)
                    .build()
            )
            factory.setDrmSessionManagerProvider { manager }
        }

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        applySubtitles(player, subtitlesOn)

        val source = factory.createMediaSource(item.build())
        if (startPositionMs > 0) {
            player.setMediaSource(source, startPositionMs)      // resume a replay
        } else {
            // Default position: the live point for live channels. Position 0 would be the
            // oldest moment of the timeshift buffer (several hours back on France TV).
            player.setMediaSource(source, true)
        }
        player.prepare()
        player.playWhenReady = true
    }

    /** What the stream URL really returns: its manifest type, or the start of the answer. */
    data class Sniff(val manifest: String?, val httpCode: Int, val body: String)

    /**
     * Downloads the beginning of the stream URL to find its real format, when the
     * format announced by the add-on turns out to be wrong. Blocking: call off the main thread.
     */
    fun sniff(stream: Stream): Sniff {
        var url = stream.url
        repeat(5) {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                var ua = DEFAULT_UA
                stream.headers.forEach { (k, v) -> if (k.equals("User-Agent", true)) ua = v else setRequestProperty(k, v) }
                setRequestProperty("User-Agent", ua)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    url = java.net.URL(java.net.URL(url), conn.getHeaderField("Location") ?: return Sniff(null, code, "")).toString()
                    return@repeat
                }
                val input = if (code in 200..299) conn.inputStream else conn.errorStream
                val head = input?.use { s ->
                    val buf = ByteArray(4096)
                    val n = s.read(buf).coerceAtLeast(0)
                    String(buf, 0, n, Charsets.UTF_8)
                }.orEmpty()
                val manifest = when {
                    head.trimStart().startsWith("#EXTM3U") -> "hls"
                    head.contains("<MPD", ignoreCase = true) -> "dash"
                    head.contains("<SmoothStreamingMedia", ignoreCase = true) -> "ss"
                    else -> null
                }
                return Sniff(manifest, code, head.replace(Regex("\\s+"), " ").trim().take(200))
            } finally {
                conn.disconnect()
            }
        }
        return Sniff(null, 0, "")
    }

    /** Shows or hides subtitles (French first) on a player, at any time. */
    fun applySubtitles(player: ExoPlayer, on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .setPreferredTextLanguage("fr")
            .setSelectUndeterminedTextLanguage(true)
            .build()
    }

    private fun drmUuid(type: String): UUID = when {
        type.contains("playready", true) -> C.PLAYREADY_UUID
        type.contains("clearkey", true) -> C.CLEARKEY_UUID
        else -> C.WIDEVINE_UUID
    }

    private fun subtitleMime(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.TEXT_VTT
        }
    }
}
