package fr.zapette.tv.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import fr.zapette.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates Zapette itself from the GitHub Releases published by the CI
 * (one release per build, tag "v1.<build number>", one APK per processor type).
 */
class AppUpdater(private val context: Context) {

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        val notes: String,
    ) {
        val isNewer get() = versionCode > BuildConfig.VERSION_CODE
    }

    /** Latest published release, or null when GitHub can't be reached. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val text = get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            val json = Json.parseToJsonElement(text).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val assets = (json["assets"] as? JsonArray).orEmpty().map { it.jsonObject }
                .filter { it.str("name").endsWith(".apk") }
            val asset = pickAsset(assets) ?: return@runCatching null
            // Only APKs published in this app's own GitHub releases
            val expected = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/"
            if (!asset.str("browser_download_url").startsWith(expected)) return@runCatching null
            Release(
                versionName = tag.removePrefix("v"),
                versionCode = code,
                apkUrl = asset.str("browser_download_url"),
                apkName = asset.str("name"),
                size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.getOrNull()
    }

    /** The APK built for this device's processor, else the universal one. */
    private fun pickAsset(assets: List<JsonObject>): JsonObject? {
        for (abi in Build.SUPPORTED_ABIS) {
            assets.firstOrNull { it.str("name").contains(abi) }?.let { return it }
        }
        return assets.firstOrNull { it.str("name").contains("universal") } ?: assets.firstOrNull()
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    suspend fun download(release: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "zapette.apk")
        var url = release.apkUrl
        repeat(6) {
            val conn = open(url)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    url = conn.getHeaderField("Location") ?: error("Redirection invalide")
                    return@repeat
                }
                if (code != 200) error("Téléchargement impossible (HTTP $code)")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
                return@withContext file
            } finally {
                conn.disconnect()
            }
        }
        error("Trop de redirections")
    }

    /** Android 8+: Zapette must be allowed to install apps ("sources inconnues"). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows Zapette to install apps. */
    fun openInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Checks that the downloaded file really is a newer Zapette, signed like the
     * installed one, before handing it to the system installer.
     */
    fun verify(file: File, release: Release): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        if (release.size > 0 && file.length() != release.size) return "fichier incomplet"
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "fichier APK invalide"
        if (archive.packageName != context.packageName) return "ce n'est pas Zapette"
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
        else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archiveCode <= BuildConfig.VERSION_CODE) return "version plus ancienne"
        val installed = pm.getPackageInfo(context.packageName, flags)
        val a = signatures(archive)
        val b = signatures(installed)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            return "signature différente : désinstallez Zapette puis installez l'APK depuis GitHub"
        }
        return null
    }

    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Hands the APK to the system installer. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Zapette/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
    }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) error("GitHub HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }
}
