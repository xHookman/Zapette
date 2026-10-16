package fr.zapette.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val ZapetteJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/** One entry of a Kodi directory listing produced by the add-on. */
@Serializable
data class PluginItem(
    val url: String,
    /** folder | playable | action | next_page | search */
    val kind: String = "folder",
    val label: String = "",
    val label2: String = "",
    val art: Map<String, String> = emptyMap(),
    val info: ItemInfo = ItemInfo(),
    val params: JsonObject = JsonObject(emptyMap()),
    val context: List<ContextEntry> = emptyList(),
) {
    val isFolder get() = kind == "folder" || kind == "search" || kind == "next_page"
    val isPlayable get() = kind == "playable"
    val title get() = info.title?.takeIf { it.isNotBlank() } ?: label

    fun param(key: String): String? = (params[key] as? JsonPrimitive)?.contentOrNull

    /** Best landscape image, then poster/thumb. */
    val landscape get() = art["landscape"] ?: art["fanart"] ?: art["thumb"] ?: art["poster"] ?: art["icon"]
    val thumb get() = art["thumb"] ?: art["landscape"] ?: art["poster"] ?: art["icon"] ?: art["fanart"]
    val backdrop get() = art["fanart"] ?: art["landscape"] ?: art["thumb"]

    /** Logos of the add-on's resource images are transparent PNGs: draw them with "fit". */
    val hasLogoArt get() = thumb?.contains("resource.images.catchuptvandmore") == true

    /** Context passed back to Python so ListItem.* info labels work like in Kodi. */
    fun selectedJson(): String = ZapetteJson.encodeToString(
        SelectedItem.serializer(),
        SelectedItem(label = label, url = url, art = art, info = mapOf(
            "title" to (info.title ?: label), "plot" to (info.plot ?: ""),
        ))
    )
}

@Serializable
data class SelectedItem(
    val label: String,
    val url: String,
    val art: Map<String, String>,
    val info: Map<String, String>,
)

@Serializable
data class ItemInfo(
    val title: String? = null,
    val plot: String? = null,
    val plotoutline: String? = null,
    val tvshowtitle: String? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val genre: String? = null,
    val aired: String? = null,
    val premiered: String? = null,
    val date: String? = null,
    val mediatype: String? = null,
    val studio: String? = null,
    val mpaa: String? = null,
)

@Serializable
data class ContextEntry(val label: String, val action: String)

@Serializable
data class Notification(val heading: String = "", val message: String = "", val kind: String = "info")

/** Result of one plugin:// invocation. */
@Serializable
data class PluginResult(
    /** directory | resolved | action | empty | error */
    val kind: String,
    val url: String = "",
    val category: String = "",
    val content: String = "",
    val items: List<PluginItem> = emptyList(),
    val stream: Stream? = null,
    val error: String? = null,
    val builtins: List<String> = emptyList(),
    val notifications: List<Notification> = emptyList(),
    @SerialName("update_listing") val updateListing: Boolean = false,
)

@Serializable
data class Stream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** hls | dash | ss | auto */
    val manifest: String = "auto",
    val mimetype: String = "",
    val drm: Drm? = null,
    val subtitles: List<String> = emptyList(),
    val label: String = "",
    val art: Map<String, String> = emptyMap(),
    val info: ItemInfo = ItemInfo(),
    @SerialName("is_live") val isLive: Boolean = false,
)

@Serializable
data class Drm(
    val type: String = "com.widevine.alpha",
    @SerialName("license_url") val licenseUrl: String = "",
    @SerialName("license_headers") val licenseHeaders: Map<String, String> = emptyMap(),
    /** Body template, Kodi syntax: R{SSM} raw challenge, B{SSM} base64, b{SSM} url-encoded base64 */
    val challenge: String = "R{SSM}",
    /** Response format, Kodi syntax: "" raw, B base64, JBfield json+base64, Jfield json */
    val response: String = "",
    @SerialName("server_certificate") val serverCertificate: String = "",
)

@Serializable
data class Channel(
    val id: String,
    val label: String,
    val url: String,
    val logo: String = "",
    val fanart: String = "",
    @SerialName("xmltv_id") val xmltvId: String = "",
    val group: String = "",
    val number: Int = 0,
)

@Serializable
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String = "",
    val subtitle: String = "",
    val desc: String = "",
    val category: String = "",
    val icon: String = "",
    val episode: String = "",
    val rating: String = "",
) {
    fun isOnAt(nowSec: Long) = nowSec in start until stop
    fun progress(nowSec: Long): Float =
        if (stop <= start) 0f else ((nowSec - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

    /** "programme-indetermine" → "Programme indéterminé"-like readable category */
    val prettyCategory: String
        get() = category.replace('-', ' ').replaceFirstChar { it.uppercase() }
            .replace("indetermine", "indéterminé")
}

@Serializable
data class Country(val id: String, val label: String, val thumb: String = "")

@Serializable
data class SettingCategory(val label: String, val entries: List<SettingEntry>)

@Serializable
data class SettingEntry(
    /** section | bool | text | number | enum | labelenum | select | slider | action */
    val type: String,
    val label: String = "",
    val id: String = "",
    val value: String = "",
    val default: String = "",
    val hidden: Boolean = false,
    val options: List<SettingOption> = emptyList(),
    val action: String = "",
)

/** Result of a login test: ok | invalid | network | unsupported */
@Serializable
data class AccountCheck(val status: String, val message: String = "")

@Serializable
data class SettingOption(val value: String, val label: String)

/** Something the user watched, used for the "Reprendre" row. */
@Serializable
data class ResumeEntry(
    val url: String,
    val label: String,
    val subtitle: String = "",
    val image: String = "",
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val selected: String = "",
) {
    val progress get() = if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
}
