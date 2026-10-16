package fr.zapette.tv.engine

import android.content.Context
import fr.zapette.tv.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages the fake Kodi installation used by the Python engine:
 *
 *   files/kodi/addons/plugin.video.catchuptvandmore   (Catch-up TV & More)
 *   files/kodi/addons/script.module.codequick         (its framework)
 *   files/kodi/userdata/addon_data/...                (settings, cache, EPG)
 *
 * The add-ons are bundled in the APK (one zip per add-on in assets/addons) and Catch-up TV
 * & More can be updated at any time from GitHub, without updating the app.
 */
class AddonInstaller(private val context: Context) {

    val home: File = File(context.filesDir, "kodi")
    private val addonsDir = File(home, "addons")

    fun addonDir(id: String) = File(addonsDir, id)

    private fun stampFile(id: String) = File(addonDir(id), ".zapette_source")

    /** "bundle:<versionCode>" or "github:<timestamp>" */
    fun source(id: String = CATCHUP_ID): String = runCatching { stampFile(id).readText() }.getOrDefault("")

    /** Extracts the bundled add-ons when missing or when the app was updated. */
    fun ensureInstalled() {
        addonsDir.mkdirs()
        val bundleStamp = "bundle:${BuildConfig.VERSION_CODE}"
        for (id in listOf(CODEQUICK_ID, CATCHUP_ID)) {
            val dir = addonDir(id)
            val current = source(id)
            val installed = File(dir, "addon.xml").exists()
            // An add-on updated from GitHub is kept until the user reverts it.
            val keep = installed && (current == bundleStamp || current.startsWith("github:"))
            if (keep) continue
            context.assets.open("addons/$id.zip").use { input -> installZip(id, input, bundleStamp) }
        }
    }

    /** Reverts Catch-up TV & More to the version shipped with the app. */
    fun reinstallBundled() {
        context.assets.open("addons/$CATCHUP_ID.zip").use { input ->
            installZip(CATCHUP_ID, input, "bundle:${BuildConfig.VERSION_CODE}")
        }
    }

    /** Version declared in addon.xml of the installed add-on. */
    fun installedVersion(id: String = CATCHUP_ID): String {
        val xml = runCatching { File(addonDir(id), "addon.xml").readText() }.getOrDefault("")
        return VERSION_RE.find(xml.substringAfter("<addon"))?.groupValues?.get(1) ?: "?"
    }

    /** Latest version available on GitHub (master branch). */
    fun remoteVersion(): String? = runCatching {
        val xml = download(REMOTE_ADDON_XML).decodeToString()
        VERSION_RE.find(xml.substringAfter("<addon"))?.groupValues?.get(1)
    }.getOrNull()

    /** Downloads and installs the latest Catch-up TV & More from GitHub. */
    fun updateFromGitHub() {
        val bytes = download(REMOTE_ZIP)
        installZip(CATCHUP_ID, bytes.inputStream(), "github:${System.currentTimeMillis()}")
    }

    private fun download(url: String): ByteArray {
        var target = url
        repeat(5) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Zapette/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    target = conn.getHeaderField("Location") ?: error("Redirection invalide")
                    return@repeat
                }
                if (code != 200) error("Téléchargement impossible (HTTP $code)")
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
        error("Trop de redirections")
    }

    /**
     * Extracts a zip into addons/<id>. A single top-level folder (GitHub
     * archives: "<repo>-master/") is stripped automatically.
     */
    private fun installZip(id: String, input: InputStream, stamp: String) {
        val tmp = File(addonsDir, ".$id.tmp").apply { deleteRecursively(); mkdirs() }
        val canonicalTmp = tmp.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(tmp, entry.name)
                require(out.canonicalPath.startsWith(canonicalTmp)) { "Archive invalide" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
        var root = tmp
        val children = tmp.listFiles().orEmpty()
        if (!File(tmp, "addon.xml").exists() && children.size == 1 && children[0].isDirectory) {
            root = children[0]
        }
        require(File(root, "addon.xml").exists()) { "addon.xml introuvable dans l'archive" }
        // Do not ship development folders
        listOf("tools", ".github", "resources/screenshots", "tests").forEach { File(root, it).deleteRecursively() }
        File(root, ".zapette_source").writeText(stamp)

        val target = addonDir(id)
        val old = File(addonsDir, ".$id.old").apply { deleteRecursively() }
        if (target.exists() && !target.renameTo(old)) target.deleteRecursively()
        if (!root.renameTo(target)) {
            root.copyRecursively(target, overwrite = true)
        }
        tmp.deleteRecursively()
        old.deleteRecursively()
    }

    companion object {
        const val CATCHUP_ID = "plugin.video.catchuptvandmore"
        const val CODEQUICK_ID = "script.module.codequick"
        private const val REMOTE_ZIP =
            "https://github.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore/archive/refs/heads/master.zip"
        private const val REMOTE_ADDON_XML =
            "https://raw.githubusercontent.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore/master/addon.xml"
        private val VERSION_RE = Regex("version=\"([^\"]+)\"")
    }
}
