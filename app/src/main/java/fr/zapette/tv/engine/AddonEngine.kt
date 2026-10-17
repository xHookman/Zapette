package fr.zapette.tv.engine

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import fr.zapette.tv.data.AccountCheck
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.Country
import fr.zapette.tv.data.PluginResult
import fr.zapette.tv.data.Programme
import fr.zapette.tv.data.SettingCategory
import fr.zapette.tv.data.ZapetteJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Runs Catch-up TV & More inside an embedded Python interpreter
 * (Python side: app/src/main/python/zapette_kodi/bridge.py).
 *
 * All add-on calls go through one thread, as in Kodi (the add-on keeps global
 * state). The TV guide has its own thread, so parsing it never delays a channel.
 */
class AddonEngine(private val context: Context) {

    val host = PythonHost()
    val installer = AddonInstaller(context)

    private val addonThread = Executors.newSingleThreadExecutor { Thread(it, "catchup-addon") }.asCoroutineDispatcher()
    private val guideThread = Executors.newSingleThreadExecutor { Thread(it, "catchup-guide") }.asCoroutineDispatcher()
    private val guideCache = File(context.cacheDir, "guide")

    private lateinit var bridge: PyObject

    /** Extracts the add-ons if needed and starts Python. Safe to call again. */
    suspend fun start() = withContext(addonThread) {
        if (::bridge.isInitialized) return@withContext
        installer.ensureInstalled()
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val module = Python.getInstance().getModule("zapette_kodi.bridge")
        module.callAttr("init", installer.home.absolutePath, "fr_fr", TimeZone.getDefault().id, host)
        bridge = module
    }

    private fun call(name: String, vararg args: Any?): String = bridge.callAttr(name, *args).toString()

    // ----------------------------------------------------------------- add-on
    /** Runs the add-on for a plugin:// url (folder, playable item, script…). */
    suspend fun invoke(url: String, selectedJson: String = ""): PluginResult = withContext(addonThread) {
        try {
            ZapetteJson.decodeFromString(PluginResult.serializer(), call("invoke", url, selectedJson))
        } catch (t: Throwable) {
            Log.e(TAG, "invoke failed", t)
            PluginResult(kind = "error", url = url, error = t.message ?: t.toString())
        }
    }

    suspend fun menuUrl(menuId: String): String = withContext(addonThread) { call("menu_url", menuId) }

    suspend fun liveChannels(countryId: String): List<Channel> = withContext(addonThread) {
        ZapetteJson.decodeFromString(ListSerializer(Channel.serializer()), call("live_channels", countryId))
    }

    suspend fun liveCountries(): List<Country> = withContext(addonThread) {
        ZapetteJson.decodeFromString(ListSerializer(Country.serializer()), call("live_countries"))
    }

    // -------------------------------------------------------------- TV guide
    /**
     * Guide of one day (0 today, 1 tomorrow). Parsed once per day by Python, then
     * kept as JSON on disk: restarting the app does not parse it again.
     */
    suspend fun guide(countryId: String, dayDelta: Int): Map<String, List<Programme>> = withContext(guideThread) {
        val day = Date(System.currentTimeMillis() + dayDelta * 86_400_000L)
        val file = File(guideCache, "${countryId}_${SimpleDateFormat("yyyyMMdd", Locale.US).format(day)}.json")
        val json = if (file.exists()) {
            file.readText()
        } else {
            runCatching { call("epg", countryId, dayDelta) }
                .onFailure { Log.w(TAG, "guide failed", it) }
                .getOrDefault("{}")
                .also { if (it.length > 2) saveGuide(file, it) }
        }
        runCatching { ZapetteJson.decodeFromString(GUIDE, json) }.getOrDefault(emptyMap())
    }

    private fun saveGuide(file: File, json: String) {
        guideCache.mkdirs()
        // keep only the files of today and tomorrow
        val keep = (0..1).map { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis() + it * 86_400_000L)) }
        guideCache.listFiles()?.filter { f -> keep.none { f.name.contains(it) } }?.forEach { it.delete() }
        file.writeText(json)
    }

    // ---------------------------------------------------------------- settings
    suspend fun settingsSchema(): List<SettingCategory> = withContext(addonThread) {
        ZapetteJson.decodeFromString(ListSerializer(SettingCategory.serializer()), call("settings_schema"))
    }

    suspend fun setSetting(id: String, value: String) {
        withContext(addonThread) { call("set_setting", id, value) }
    }

    suspend fun getSetting(id: String): String = withContext(addonThread) { call("get_setting", id) }

    /** Logs in to a service (tf1plus, 6play…) with the saved credentials. */
    suspend fun checkAccount(service: String): AccountCheck = withContext(addonThread) {
        runCatching { ZapetteJson.decodeFromString(AccountCheck.serializer(), call("check_account", service)) }
            .getOrElse { AccountCheck("network", it.message ?: "") }
    }

    /** Web cache of the add-on and TV guides. */
    suspend fun clearCache() {
        withContext(addonThread) { call("clear_cache") }
        withContext(guideThread) { guideCache.deleteRecursively() }
    }

    // ---------------------------------------------------------- add-on update
    /** Installs the latest add-on from GitHub. Returns the new version. */
    suspend fun updateAddon(): String {
        withContext(Dispatchers.IO) { installer.updateFromGitHub() }
        return reload()
    }

    /** Back to the add-on bundled with the app. */
    suspend fun revertAddon(): String {
        withContext(Dispatchers.IO) { installer.reinstallBundled() }
        return reload()
    }

    private suspend fun reload(): String = withContext(addonThread) {
        call("reload_addon")
        withContext(guideThread) { guideCache.deleteRecursively() }
        installer.installedVersion()
    }

    suspend fun remoteVersion(): String? = withContext(Dispatchers.IO) { installer.remoteVersion() }

    private companion object {
        const val TAG = "AddonEngine"
        val GUIDE = MapSerializer(String.serializer(), ListSerializer(Programme.serializer()))
    }
}
