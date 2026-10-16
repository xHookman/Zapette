@file:OptIn(UnstableApi::class)

package fr.zapette.tv.player

import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import fr.zapette.tv.data.Drm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * License requests the way Kodi's inputstream.adaptive performs them, from the
 * add-on's "license_key" property:  url | headers | body template | response
 *
 *  body template:  R{SSM} raw challenge, B{SSM} base64, b{SSM} url-encoded base64
 *  response:       "" raw, B base64, J<path> json field, JB<path> json field + base64
 */
class KodiLicenseCallback(
    private val drm: Drm,
    private val defaultHeaders: Map<String, String>,
    provisioningFactory: DataSource.Factory,
) : MediaDrmCallback {

    private val provisioning = HttpMediaDrmCallback(null, provisioningFactory)

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray =
        provisioning.executeProvisionRequest(uuid, request)

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val url = drm.licenseUrl.ifBlank { request.licenseServerUrl }
        try {
            val body = buildBody(request.data)
            val response = post(url, body)
            return decodeResponse(response)
        } catch (e: Exception) {
            throw MediaDrmCallbackException(
                DataSpec(Uri.parse(url)), Uri.parse(url), emptyMap(), 0, e
            )
        }
    }

    private fun buildBody(challenge: ByteArray): ByteArray {
        val template = drm.challenge.ifBlank { "R{SSM}" }
        if (template == "R{SSM}") return challenge
        val b64 = Base64.encodeToString(challenge, Base64.NO_WRAP)
        val text = template
            .replace("B{SSM}", b64)
            .replace("b{SSM}", URLEncoder.encode(b64, "UTF-8"))
            .replace("R{SSM}", String(challenge, Charsets.ISO_8859_1))
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun post(url: String, body: ByteArray): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
        val headers = LinkedHashMap<String, String>()
        defaultHeaders["User-Agent"]?.let { headers["User-Agent"] = it }
        headers.putAll(drm.licenseHeaders)
        var contentType = "application/octet-stream"
        for ((k, v) in headers) {
            when {
                k.equals("Content-Type", true) -> if (v.isNotBlank()) contentType = v
                // Host is computed by the HTTP stack
                k.equals("Host", true) -> Unit
                else -> conn.setRequestProperty(k, v)
            }
        }
        conn.setRequestProperty("Content-Type", contentType)
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().decodeToString().take(300) } ?: ""
                error("Serveur de licence HTTP $code $err")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeResponse(bytes: ByteArray): ByteArray {
        var format = drm.response
        if (format.isBlank() || format == "R") return bytes
        if (format == "B") return Base64.decode(bytes, Base64.DEFAULT)
        if (format.startsWith("J")) {
            format = format.substring(1)
            val base64 = format.startsWith("B")
            if (base64) format = format.substring(1)
            val root = Json.parseToJsonElement(bytes.decodeToString())
            val value = findField(root, format) ?: error("Champ '$format' absent de la licence")
            return if (base64) Base64.decode(value, Base64.DEFAULT) else value.toByteArray(Charsets.ISO_8859_1)
        }
        if (format.startsWith("B")) return Base64.decode(bytes, Base64.DEFAULT)
        return bytes
    }

    /** Field lookup by dotted path, or by name anywhere in the document. */
    private fun findField(element: JsonElement, path: String): String? {
        if (path.isEmpty()) return (element as? JsonPrimitive)?.contentOrNull
        var current: JsonElement? = element
        for (part in path.split('.', '/')) {
            current = (current as? JsonObject)?.get(part)
        }
        (current as? JsonPrimitive)?.contentOrNull?.let { return it }
        return deepFind(element, path.substringAfterLast('.'))
    }

    private fun deepFind(element: JsonElement, name: String): String? = when (element) {
        is JsonObject -> (element[name] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { deepFind(it, name) }
        is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { deepFind(it, name) }
        else -> null
    }
}
