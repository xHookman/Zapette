package fr.zapette.tv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.zapette.tv.data.PluginItem
import fr.zapette.tv.data.Stream

/** The tabs of the top bar. */
enum class Tab(val label: String) {
    Home("Accueil"), Guide("Guide TV"), Replay("Replay"), Explore("Explorer"), Settings("Réglages")
}

/** What a player screen must play. */
sealed interface PlayerRequest {
    /** A live channel (shared player, can continue in the corner mini-player). */
    data class Live(val channelId: String) : PlayerRequest

    /** A replay item of the add-on, resolved when the player opens. */
    data class Vod(
        val url: String,
        val title: String,
        val subtitle: String = "",
        val image: String = "",
        val selected: String = "",
    ) : PlayerRequest

    /** A stream the add-on already resolved while opening a folder. */
    data class Direct(val stream: Stream, val title: String) : PlayerRequest
}

/** Screens stacked above the tabs. */
sealed interface Route {
    class Browse(val state: BrowseState) : Route
    data class Player(val request: PlayerRequest) : Route
}

/** One directory of the add-on, kept while it is on the back stack (focus and scroll are restored). */
class BrowseState(val url: String, title: String, val selected: String = "") {
    var title by mutableStateOf(title)
    var items by mutableStateOf<List<PluginItem>>(emptyList())
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)
    var nextPage by mutableStateOf<PluginItem?>(null)
    var focusedIndex by mutableIntStateOf(0)
    var focused by mutableStateOf<PluginItem?>(null)
    val grid = LazyGridState()
}

/** Text block at the top of the home screen describing the focused card. */
data class Hero(
    val kicker: String = "",
    val title: String,
    val meta: String = "",
    val description: String = "",
    val logo: String = "",
    val live: Boolean = false,
    val progress: Float? = null,
)
