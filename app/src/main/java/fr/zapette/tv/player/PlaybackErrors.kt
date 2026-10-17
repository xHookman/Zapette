package fr.zapette.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import fr.zapette.tv.data.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns player errors into French messages, and recovers from the ones that can be fixed. */
object PlaybackErrors {

    /** A live stream may drop for a moment: worth one silent retry. */
    val NETWORK = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )

    /** The add-on announced the wrong format (DASH instead of HLS…). */
    val MANIFEST = setOf(
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )

    fun message(e: PlaybackException): String {
        val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            http == 401 || http == 403 ->
                "Accès refusé par la chaîne ($http). Le flux est peut-être géo-restreint ou réservé aux abonnés."
            http == 404 || http == 410 -> "Ce flux n'est plus disponible ($http)."
            http != null -> "Le serveur de la chaîne a répondu une erreur $http."
            e.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                "Ce programme est protégé (DRM) et la licence n'a pas pu être obtenue sur cet appareil."
            e.errorCode in NETWORK -> "Connexion au flux impossible. Vérifiez votre accès internet."
            e.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "Ce format vidéo n'est pas pris en charge par votre appareil."
            else -> "Erreur de lecture (${e.errorCodeName})."
        }
    }

    /** Result of [redetect]: a stream to play again, or the message to show. */
    class Fix(val stream: Stream?, val message: String)

    /** Looks at what the stream URL really returns and corrects the announced format. */
    suspend fun redetect(stream: Stream): Fix {
        val found = withContext(Dispatchers.IO) { runCatching { StreamPlayer.sniff(stream) }.getOrNull() }
        return when {
            found?.manifest != null && found.manifest != stream.manifest -> Fix(stream.copy(manifest = found.manifest), "")
            found != null && found.manifest == null -> Fix(
                null,
                "Le flux envoyé par la chaîne n'est pas lisible (réponse HTTP ${found.httpCode})" +
                    if (found.body.isNotBlank()) " :\n« ${redact(found.body)} »" else ".",
            )
            else -> Fix(null, "Le flux envoyé par la chaîne n'est pas lisible (format ${stream.manifest.uppercase()}).")
        }
    }

    /** Masks tokens that a channel may echo in an error page. */
    private fun redact(text: String) = text.replace(
        Regex("((?:token|password|signature|authorization)\"?\\s*[=:]\\s*\"?)[^\"&\\s,}]+", RegexOption.IGNORE_CASE),
        "$1***",
    )
}
