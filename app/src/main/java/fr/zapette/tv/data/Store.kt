package fr.zapette.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/** Small persistent state of the app (not the add-on settings). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("zapette", Context.MODE_PRIVATE)

    var lastChannelId: String?
        get() = prefs.getString("last_channel", null)
        set(value) = prefs.edit().putString("last_channel", value).apply()

    var previousChannelId: String?
        get() = prefs.getString("previous_channel", null)
        set(value) = prefs.edit().putString("previous_channel", value).apply()

    /** Open the app directly on the last watched channel, like a TV set. */
    var startOnLive: Boolean
        get() = prefs.getBoolean("start_on_live", true)
        set(value) = prefs.edit().putBoolean("start_on_live", value).apply()

    /** Skeleton of the live channels (fr_live, be_live, ch_live…). */
    var liveCountry: String
        get() = prefs.getString("live_country", "fr_live") ?: "fr_live"
        set(value) = prefs.edit().putString("live_country", value).apply()

    /** Show the info banner automatically when changing channel. */
    var bannerOnZap: Boolean
        get() = prefs.getBoolean("banner_on_zap", true)
        set(value) = prefs.edit().putBoolean("banner_on_zap", value).apply()

    /** Back from a live channel keeps it playing in a corner while browsing the menus. */
    var miniOnBack: Boolean
        get() = prefs.getBoolean("mini_on_back", true)
        set(value) = prefs.edit().putBoolean("mini_on_back", value).apply()

    /** Old-TV "snow" effect when changing channel. */
    var zapEffect: Boolean
        get() = prefs.getBoolean("zap_effect", true)
        set(value) = prefs.edit().putBoolean("zap_effect", value).apply()

    /** Soft hiss played with the snow effect. */
    var zapSound: Boolean
        get() = prefs.getBoolean("zap_sound", true)
        set(value) = prefs.edit().putBoolean("zap_sound", value).apply()

    /** Light mode for weak TVs: auto | on | off (applied at next start). */
    var perfMode: String
        get() = prefs.getString("perf_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("perf_mode", value).apply()

    // ------------------------------------------------------------- resume
    private val _resume = MutableStateFlow(loadResume())
    val resume: StateFlow<List<ResumeEntry>> = _resume.asStateFlow()

    private fun loadResume(): List<ResumeEntry> = runCatching {
        ZapetteJson.decodeFromString(
            ListSerializer(ResumeEntry.serializer()), prefs.getString("resume", "[]") ?: "[]"
        )
    }.getOrDefault(emptyList())

    fun resumePosition(url: String): Long = _resume.value.firstOrNull { it.url == url }?.position ?: 0L

    fun saveResume(entry: ResumeEntry) {
        val finished = entry.duration > 0 && entry.position > entry.duration - 90_000 ||
            entry.duration > 0 && entry.progress > 0.95f
        val others = _resume.value.filterNot { it.url == entry.url }
        val list = if (finished || entry.position < 30_000) others else (listOf(entry) + others).take(40)
        _resume.value = list
        prefs.edit().putString("resume", ZapetteJson.encodeToString(ListSerializer(ResumeEntry.serializer()), list))
            .apply()
    }

    fun removeResume(url: String) {
        val list = _resume.value.filterNot { it.url == url }
        _resume.value = list
        prefs.edit().putString("resume", ZapetteJson.encodeToString(ListSerializer(ResumeEntry.serializer()), list))
            .apply()
    }
}
