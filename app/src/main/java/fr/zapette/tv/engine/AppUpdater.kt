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
 * Updates Zapette itself from the GitHub Releases published by the CI:
 * the versions ("v1.4", one APK each) and, in developer mode, the test build
 * of the dev branch (pre-release "test", replaced at each test).
 */
class AppUpdater(private val context: Context, private val withTestBuilds: () -> Boolean) {

    data class Release(
        val versionName: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        val notes: String,
    ) {
        val isNewer get() = newerThan(versionName, BuildConfig.VERSION_NAME)
    }

    /** Newest version available (or test build, in developer mode); null when GitHub can't be reached. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val base = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases"
        val found = listOfNotNull(
            release("$base/latest"),
            if (withTestBuilds()) release("$base/tags/$TEST_TAG") else null,
        )
        found.maxWithOrNull { a, b -> if (newerThan(a.versionName, b.versionName)) 1 else -1 }
    }

    private fun release(url: String): Release? = runCatching {
        val json = Json.parseToJsonElement(get(url)).jsonObject
        // One APK per release, for every device: "zapette-1.4.apk", "zapette-1.5-test.31.apk"
        val asset = (json["assets"] as? JsonArray).orEmpty().map { it.jsonObject }
            .singleOrNull { it.str("name").endsWith(".apk") } ?: return@runCatching null
        // Only APKs published in this app's own GitHub releases
        val expected = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/"
        if (!asset.str("browser_download_url").startsWith(expected)) return@runCatching null
        Release(
            versionName = asset.str("name").removePrefix("zapette-").removeSuffix(".apk"),
            apkUrl = asset.str("browser_download_url"),
            apkName = asset.str("name"),
            size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
            notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.getOrNull()

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
                    val location = conn.getHeaderField("Location") ?: error("Redirection invalide")
                    url = URL(URL(url), location).toString()
                    // Never leave HTTPS (GitHub serves release files from its own CDN)
                    if (!url.startsWith("https://")) error("Redirection non sécurisée refusée")
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
                            if (done > MAX_APK_BYTES) error("Fichier anormalement gros")
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
        if (a.isEmpty() || b.isEmpty()) return "signature illisible"
        if (a != b) {
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
        instanceFollowRedirects = false   // redirections are followed by hand, HTTPS only
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

    private companion object {
        /** Tag of the test build published by the dev branch. */
        const val TEST_TAG = "test"

        /**
         * Version order: "1.10" after "1.9"; a test build "1.5-test.31" prepares 1.5,
         * so it comes after 1.4 and before 1.5; test builds follow their build number.
         * A local build ("dev") is never updated.
         */
        fun newerThan(candidate: String, installed: String): Boolean {
            if (installed == "dev") return false
            val a = parse(candidate) ?: return false
            val b = parse(installed) ?: return true
            for (i in 0 until maxOf(a.first.size, b.first.size)) {
                val x = a.first.getOrElse(i) { 0 }
                val y = b.first.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            // Same version: the release comes after its test builds
            val testA = a.second ?: Int.MAX_VALUE
            val testB = b.second ?: Int.MAX_VALUE
            return testA > testB
        }

        /** "1.5-test.31" → ([1, 5], 31); "1.4" → ([1, 4], null). */
        private fun parse(name: String): Pair<List<Int>, Int?>? {
            val version = name.substringBefore("-test.")
            val numbers = version.split('.').map { it.toIntOrNull() ?: return null }
            val test = if ("-test." in name) name.substringAfter("-test.").toIntOrNull() ?: return null else null
            return numbers to test
        }

        const val MAX_APK_BYTES = 400L * 1024 * 1024
    }
}
