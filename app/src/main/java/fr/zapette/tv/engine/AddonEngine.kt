package fr.zapette.tv.engine

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Runs Catch-up TV & More inside an embedded Python interpreter.
 *
 * All add-on calls go through one dedicated thread (the add-on and codequick
 * keep global state, exactly like inside Kodi). The EPG has its own thread so
 * that downloading the guide never delays a channel change.
 */
class AddonEngine(private val context: Context) {

    val host = PythonHost()
    val installer = AddonInstaller(context)

    private val addonThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "catchup-addon").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()
    private val epgThread = Executors.newSingleThreadExecutor { r -> Thread(r, "catchup-epg") }
        .asCoroutineDispatcher()

    private lateinit var bridge: PyObject

    @Volatile
    var addonVersion: String = ""
        private set

    /** Extracts the add-ons and boots Python. Safe to call more than once. */
    suspend fun start() = withContext(addonThread) {
        if (::bridge.isInitialized) return@withContext
        installer.ensureInstalled()
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val module = Python.getInstance().getModule("zapette_kodi.bridge")
        val json = module.callAttr(
            "init", installer.home.absolutePath, "fr_fr", TimeZone.getDefault().id, host
        ).toString()
        Log.i(TAG, "Engine ready: $json")
        bridge = module
        addonVersion = installer.installedVersion()
    }

    private fun call(name: String, vararg args: Any?): String = bridge.callAttr(name, *args).toString()

    /** Runs the add-on for a plugin:// url (menu, playable item, script…). */
    suspend fun invoke(url: String, selectedJson: String = ""): PluginResult = withContext(addonThread) {
        val raw = try {
            call("invoke", url, selectedJson)
        } catch (t: Throwable) {
            Log.e(TAG, "invoke failed", t)
            return@withContext PluginResult(kind = "error", url = url, error = t.message ?: t.toString())
        }
        ZapetteJson.decodeFromString(PluginResult.serializer(), raw)
    }

    suspend fun menuUrl(menuId: String): String = withContext(addonThread) { call("menu_url", menuId) }

    suspend fun liveChannels(countryId: String): List<Channel> = withContext(addonThread) {
        ZapetteJson.decodeFromString(ListSerializer(Channel.serializer()), call("live_channels", countryId))
    }

    suspend fun liveCountries(): List<Country> = withContext(addonThread) {
        ZapetteJson.decodeFromString(ListSerializer(Country.serializer()), call("live_countries"))
    }

    suspend fun epg(countryId: String, dayDelta: Int): Map<String, List<Programme>> = withContext(epgThread) {
        val raw = runCatching { call("epg", countryId, dayDelta) }.getOrElse {
            Log.w(TAG, "EPG failed", it); "{}"
        }
        ZapetteJson.decodeFromString(
            MapSerializer(String.serializer(), ListSerializer(Programme.serializer())), raw
        )
    }

    suspend fun settingsSchema(): List<SettingCategory> = withContext(addonThread) {
        ZapetteJson.decodeFromString(ListSerializer(SettingCategory.serializer()), call("settings_schema"))
    }

    suspend fun setSetting(id: String, value: String) = withContext(addonThread) { call("set_setting", id, value) }

    suspend fun getSetting(id: String): String = withContext(addonThread) { call("get_setting", id) }

    suspend fun clearCache() = withContext(addonThread) { call("clear_cache") }

    /** Downloads the latest add-on from GitHub and reloads it. Returns the new version. */
    suspend fun updateAddon(): String {
        withContext(Dispatchers.IO) { installer.updateFromGitHub() }
        return reload()
    }

    suspend fun revertAddon(): String {
        withContext(Dispatchers.IO) { installer.reinstallBundled() }
        return reload()
    }

    private suspend fun reload(): String = withContext(addonThread) {
        call("reload_addon")
        addonVersion = installer.installedVersion()
        addonVersion
    }

    suspend fun remoteVersion(): String? = withContext(Dispatchers.IO) { installer.remoteVersion() }

    companion object {
        private const val TAG = "AddonEngine"
    }
}
