package fr.zapette.tv.ui

import fr.zapette.tv.data.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Toast(val id: Long, val title: String, val message: String, val error: Boolean)

/** A list to pick from (context menus, settings, audio tracks…). */
data class Choice(
    val title: String,
    val options: List<String>,
    val selected: Int = -1,
    /** Multiple selection: the answer goes to [onMulti]. */
    val multi: Boolean = false,
    val onMulti: (List<Int>) -> Unit = {},
    val onPick: (Int) -> Unit = {},
)

/** A text to type (settings). */
data class TextAsk(val title: String, val initial: String, val hidden: Boolean, val onDone: (String) -> Unit)

/** Everything the app tells or asks the user outside of the screens themselves. */
class Messages(private val scope: CoroutineScope) {
    private val _toasts = MutableStateFlow<List<Toast>>(emptyList())
    val toasts: StateFlow<List<Toast>> = _toasts.asStateFlow()

    private val _choice = MutableStateFlow<Choice?>(null)
    val choice: StateFlow<Choice?> = _choice.asStateFlow()

    private val _textAsk = MutableStateFlow<TextAsk?>(null)
    val textAsk: StateFlow<TextAsk?> = _textAsk.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** A short operation the user waits for ("Téléchargement…"). */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private var lastToastId = 0L

    fun toast(title: String, message: String = "", error: Boolean = false) {
        if (title.isBlank() && message.isBlank()) return
        val toast = Toast(++lastToastId, title, message, error)
        _toasts.value = (_toasts.value + toast).takeLast(3)
        scope.launch {
            delay(if (error) 6_000 else 4_000)
            _toasts.value = _toasts.value.filterNot { it.id == toast.id }
        }
    }

    /** Notifications sent by the add-on (xbmcgui.Dialog().notification). */
    fun show(notifications: List<Notification>) {
        notifications.forEach { toast(it.heading, it.message, error = it.kind == "error") }
    }

    fun ask(choice: Choice) {
        _choice.value = choice
    }

    fun dismissChoice(): Boolean {
        val open = _choice.value != null
        _choice.value = null
        return open
    }

    fun askText(ask: TextAsk?) {
        _textAsk.value = ask
    }

    fun busy(message: String?) {
        _busy.value = message
    }

    suspend fun <T> whileBusy(message: String, block: suspend () -> T): T {
        _busy.value = message
        try {
            return block()
        } finally {
            _busy.value = null
        }
    }
}
