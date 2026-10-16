package fr.zapette.tv.engine

import android.util.Log
import fr.zapette.tv.data.ZapetteJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Object handed to Python (zapette_kodi.runtime.host).
 *
 * The add-on runs on the engine thread; when it opens a Kodi dialog
 * (xbmcgui.Dialog().select(...), Keyboard...), the call lands here, the
 * request is published to the UI and the Python thread waits for the answer.
 *
 * Method names and signatures must match what the Python side calls.
 */
class PythonHost {

    sealed interface Request {
        val heading: String

        data class Select(
            override val heading: String,
            val options: List<Option>,
            val preselect: Int,
            val multi: Boolean,
            val answer: CompletableDeferred<String>,
        ) : Request

        data class YesNo(
            override val heading: String,
            val message: String,
            val noLabel: String,
            val yesLabel: String,
            val answer: CompletableDeferred<Boolean>,
        ) : Request

        data class Message(
            override val heading: String,
            val message: String,
            val answer: CompletableDeferred<Unit>,
        ) : Request

        data class Input(
            override val heading: String,
            val default: String,
            val hidden: Boolean,
            val kind: String,
            val answer: CompletableDeferred<String?>,
        ) : Request
    }

    @Serializable
    data class Option(val label: String = "", val label2: String = "", val thumb: String = "")

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    /** Message of a Kodi DialogProgress currently opened by the add-on. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private fun <T> ask(request: Request, deferred: CompletableDeferred<T>): T {
        _request.value = request
        return try {
            runBlocking { deferred.await() }
        } finally {
            _request.compareAndSet(request, null)
        }
    }

    // ------------------------------------------------------------ Python API

    fun notify(heading: String, message: String, kind: String) {
        // Notifications are recorded in the PluginResult and shown by the UI.
    }

    /** Returns the selected index ("-1" when cancelled) or a JSON list for multi-select. */
    fun select(heading: String, optionsJson: String, preselect: Int, multi: Boolean): String {
        val options = runCatching {
            ZapetteJson.decodeFromString(ListSerializer(Option.serializer()), optionsJson)
        }.getOrDefault(emptyList())
        val deferred = CompletableDeferred<String>()
        return ask(Request.Select(heading, options, preselect, multi, deferred), deferred)
    }

    fun yesno(heading: String, message: String, nolabel: String, yeslabel: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        return ask(Request.YesNo(heading, message, nolabel, yeslabel, deferred), deferred)
    }

    fun ok(heading: String, message: String) {
        val deferred = CompletableDeferred<Unit>()
        ask(Request.Message(heading, message, deferred), deferred)
    }

    /** Returns the text or null when cancelled. */
    fun input(heading: String, default: String, hidden: Boolean, kind: String): String? {
        val deferred = CompletableDeferred<String?>()
        return ask(Request.Input(heading, default, hidden, kind, deferred), deferred)
    }

    /** DialogProgress of the add-on; returns true when the user cancelled. */
    fun progress(action: String, heading: String, message: String, percent: Int): Boolean {
        _progress.value = if (action == "close") null else listOf(heading, message).filter { it.isNotBlank() }
            .joinToString(" · ").ifBlank { "Chargement…" }
        return false
    }

    fun log(level: Int, message: String) {
        when {
            level >= 3 -> Log.e(TAG, message)
            level == 2 -> Log.w(TAG, message)
            else -> if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
        }
    }

    /** Cancels any pending dialog (e.g. the screen that triggered it was closed). */
    fun cancelPending() {
        when (val r = _request.value) {
            is Request.Select -> r.answer.complete(if (r.multi) "[]" else "-1")
            is Request.YesNo -> r.answer.complete(false)
            is Request.Message -> r.answer.complete(Unit)
            is Request.Input -> r.answer.complete(null)
            null -> Unit
        }
    }

    companion object {
        private const val TAG = "CatchupAddon"
    }
}
