package fr.zapette.tv.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.zapette.tv.Images
import fr.zapette.tv.Perf
import fr.zapette.tv.ZapetteApplication
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.PluginItem
import fr.zapette.tv.data.ResumeEntry
import fr.zapette.tv.live.LivePlayback
import fr.zapette.tv.live.LiveTv
import fr.zapette.tv.ui.components.liveCardImage
import fr.zapette.tv.ui.components.tonightCardImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's state, shared by every screen.
 *
 * It only coordinates; the work is done by:
 *  - [live]      channels, TV guide, clock
 *  - [playback]  the live TV player (full screen and corner mini-player)
 *  - [messages]  toasts, choices, text input, "please wait"
 *  - [updates]   updates, cache, account checks
 *  - [engine]    Catch-up TV & More running in Python
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val services = app as ZapetteApplication
    val engine = services.engine
    val store = services.store
    val messages = Messages(viewModelScope)
    val live = LiveTv(app, engine, store, viewModelScope)
    val playback = LivePlayback(app, engine, store, live, viewModelScope) { subtitlesOn }
    val updates = Updates(
        app, store, engine, messages, viewModelScope,
        onAddonChanged = ::reloadAddonContent,
        onCacheCleared = { live.reloadGuide() },
    )

    // --------------------------------------------------------------- start-up
    sealed interface Boot {
        data class Loading(val message: String) : Boot
        data object Ready : Boot
        data class Failed(val message: String) : Boot
    }

    var boot by mutableStateOf<Boot>(Boot.Loading("Démarrage du moteur…"))
        private set

    init {
        start()
    }

    fun start() = viewModelScope.launch {
        boot = Boot.Loading("Préparation de Catch-up TV & More…")
        try {
            engine.start()
            subtitlesOn = engine.getSetting("active_subtitle") == "true"
            boot = Boot.Loading("Chargement des chaînes…")
            if (!live.loadChannels()) messages.toast("Chaînes indisponibles", "Vérifiez la connexion internet.", error = true)
        } catch (t: Throwable) {
            boot = Boot.Failed(t.message ?: t.toString())
            return@launch
        }

        // Like a TV set: start on the last channel watched. Otherwise the home screen
        // opens first, with its rows and their first pictures already loaded.
        val last = live.channel(store.lastChannelId)?.takeIf { store.startOnLive }
        if (last == null) {
            boot = Boot.Loading("Préparation de l'accueil…")
            prepareHome()
        }
        boot = Boot.Ready
        if (last != null) watch(last)

        // The guide is parsed in Python: let the first channel start first
        live.startGuide(waitFirstMs = if (last != null) 4_000 else 0)
        launch { live.loadCountries() }
        launch {
            if (replayRoot == null) setUpReplayTab()
            if (last != null) {
                // Back from the channel, the home screen must be ready too
                delay(8_000)
                replayRoot?.let { load(it)?.join() }
                Images.warm(getApplication(), homePictures(firstCards = true))
            }
            // The other cards: downloaded now, decoded when they come on screen
            Images.prefetch(getApplication(), homePictures(firstCards = false))
        }
        updates.checkApp(manual = false)
    }

    /**
     * Loads what the home screen shows (today's guide, the replay menu) and the
     * pictures of its first cards. Bounded in time: a slow network never blocks the start.
     */
    private suspend fun prepareHome() {
        // Started apart: a timeout stops waiting for them, not the loading itself
        val guide = viewModelScope.async { live.loadToday() }
        val replay = viewModelScope.async {
            setUpReplayTab()
            replayRoot?.let { load(it)?.join() }
        }
        withTimeoutOrNull(PREPARE_MS) {
            guide.await()
            replay.await()
        }
        withTimeoutOrNull(PICTURES_MS) { Images.warm(getApplication(), homePictures(firstCards = true)) }
    }

    /** Pictures of the home rows: the cards visible at first, or all the others. */
    private fun homePictures(firstCards: Boolean): List<String> {
        fun <T> part(list: List<T>) = if (firstCards) list.take(FIRST_CARDS) else list.drop(FIRST_CARDS)
        val channels = live.channels.value
        return part(channels).map { liveCardImage(it, live.nowPlaying(it)) } +
            part(live.tonight(channels)).map { (c, p) -> tonightCardImage(c, p) } +
            part(replayRoot?.items.orEmpty()).mapNotNull { it.thumb }
    }

    // ----------------------------------------------------------- presentation
    /** New installation: the presentation of the remote keys is shown before anything else. */
    var introShown by mutableStateOf(!store.introSeen && store.lastChannelId == null)
        private set

    /** From the settings. The corner channel stops: its picture would be hidden anyway. */
    fun showIntro() {
        playback.stop()
        introShown = true
    }

    fun closeIntro() {
        store.introSeen = true
        introShown = false
        restoreFocus = true
        // First start: without their account, TF1, M6… do not play
        if (!store.accountsOffered) {
            store.accountsOffered = true
            messages.ask(
                Choice(
                    "TF1, M6 et quelques autres chaînes demandent un compte gratuit. Le renseigner maintenant ?",
                    listOf("Renseigner mes comptes", "Plus tard"),
                ) { if (it == 0) openAccounts() }
            )
        }
    }

    /** Settings page the settings screen must open on (read once). */
    var settingsTarget: SettingsTarget? = null

    enum class SettingsTarget { Accounts }

    /** Settings → Comptes (the add-on's page with the logins). */
    fun openAccounts() {
        stack.clear()
        settingsTarget = SettingsTarget.Accounts
        selectTab(Tab.Settings)
    }

    private suspend fun reloadAddonContent() {
        live.loadChannels()
        setUpReplayTab()
        exploreRoot = BrowseState(ROOT_URL, "Explorer")
        live.reloadGuide()
    }

    fun changeCountry(countryId: String) = viewModelScope.launch {
        live.changeCountry(countryId)
        setUpReplayTab()
    }

    // ------------------------------------------------------------- subtitles
    /** Subtitles on/off, shared with the add-on's "active_subtitle" setting. */
    var subtitlesOn by mutableStateOf(false)
        private set

    fun setSubtitles(on: Boolean) {
        subtitlesOn = on
        viewModelScope.launch { runCatching { engine.setSetting("active_subtitle", if (on) "true" else "false") } }
    }

    /** The add-on setting was changed from its own settings page. */
    fun syncSubtitles(value: String) {
        subtitlesOn = value.equals("true", true)
    }

    // ------------------------------------------------------------- navigation
    var tab by mutableStateOf(Tab.Home)
        private set
    val stack = mutableStateListOf<Route>()

    /** Replay tab: the replay menu of the selected country. */
    var replayRoot by mutableStateOf<BrowseState?>(null)
        private set

    /** Explorer tab: the add-on's root menu. */
    var exploreRoot by mutableStateOf(BrowseState(ROOT_URL, "Explorer"))
        private set

    private var tabJob: Job? = null

    private suspend fun setUpReplayTab() {
        val url = runCatching { engine.menuUrl(store.liveCountry.replace("_live", "_replay")) }.getOrNull() ?: return
        replayRoot = BrowseState(url, "Replay")
    }

    fun selectTab(t: Tab) {
        tabJob?.cancel()
        tab = t
    }

    /** Focus passing over the tabs: show only the tab where it stops. */
    fun previewTab(t: Tab) {
        tabJob?.cancel()
        if (t == tab) return
        tabJob = viewModelScope.launch {
            delay(if (Perf.lite) 350 else 220)
            tab = t
        }
    }

    private fun push(route: Route) {
        stack.add(route)
    }

    private fun pop() {
        if (stack.isEmpty()) return
        val left = stack.removeAt(stack.lastIndex)
        if (left is Route.Player && left.request is PlayerRequest.Live) {
            playback.leaveFullScreen()
            // "OK OK" can't be guessed: recalled the first times the mini-player appears
            if (playback.mini && store.miniHints < 3) {
                store.miniHints++
                messages.toast("La chaîne continue dans le coin", "OK deux fois rapidement : retour en plein écran")
            }
        }
        if (stack.isEmpty()) restoreFocus = true
    }

    /** Back key. Returns false when the app should close. */
    fun onBack(): Boolean = when {
        messages.dismissChoice() -> true
        stack.isNotEmpty() -> { pop(); true }
        playback.mini -> { playback.stop(); true }
        tab != Tab.Home -> { tab = Tab.Home; restoreFocus = true; true }
        else -> false
    }

    // ------------------------------------------------------------------ focus
    /**
     * True when the tab content should take the focus back (start, return from the
     * player or a folder). False while the user moves in the top bar.
     */
    var restoreFocus by mutableStateOf(true)
    var homeFocusKey: String? = null
    var liveFocusId: String? = null
    var guideFocusId: String? = null
    val homeList = LazyListState()
    val liveGrid = LazyGridState()
    val guideChannels = LazyListState()
    private val rows = HashMap<String, LazyListState>()
    fun row(key: String): LazyListState = rows.getOrPut(key) { LazyListState() }

    /** Text block describing the focused card on the home screen. */
    var hero by mutableStateOf<Hero?>(null)

    private var backdropShown by mutableStateOf<String?>(null)
    private var backdropJob: Job? = null

    /**
     * Ambient picture of the focused item, applied once the focus rests on it
     * (loading big pictures at every D-pad step makes weak TVs stutter).
     */
    var backdrop: String?
        get() = backdropShown
        set(value) {
            backdropJob?.cancel()
            if (Perf.lite || value == null) {
                backdropShown = null
            } else if (value != backdropShown) {
                backdropJob = viewModelScope.launch {
                    delay(400)
                    backdropShown = value
                }
            }
        }

    // ------------------------------------------------------------------ live
    fun watch(channel: Channel) {
        playback.ensurePlayer()   // the video layer exists from the first frame
        push(Route.Player(PlayerRequest.Live(channel.id)))
    }

    /** Full screen again on the channel of the corner mini-player. */
    fun expandMini() {
        playback.channel?.let { watch(it) }
    }

    fun onAppStop() = playback.onAppStop()
    fun onAppStart() = playback.onAppStart()

    // ------------------------------------------------------------ add-on items
    fun open(item: PluginItem, parentTitle: String = "") {
        when {
            item.isPlayable -> play(item, parentTitle)
            item.kind == "action" -> runAction(item.url, item.selectedJson())
            else -> push(Route.Browse(BrowseState(item.url, item.title, item.selectedJson())))
        }
    }

    private fun openUrl(url: String, title: String) = push(Route.Browse(BrowseState(url, title)))

    fun play(item: PluginItem, parentTitle: String = "") {
        val subtitle = item.info.tvshowtitle?.takeIf { it != item.title }
            ?: parentTitle.takeIf { it.isNotBlank() && it != item.title } ?: ""
        push(Route.Player(PlayerRequest.Vod(item.url, item.title, subtitle, item.landscape ?: "", item.selectedJson())))
    }

    fun resume(entry: ResumeEntry, fromStart: Boolean = false) {
        if (fromStart) store.removeResume(entry.url)
        push(Route.Player(PlayerRequest.Vod(entry.url, entry.label, entry.subtitle, entry.image, entry.selected)))
    }

    fun resumeMenu(entry: ResumeEntry) = messages.ask(
        Choice(entry.label, listOf("Reprendre", "Recommencer depuis le début", "Retirer de la liste")) {
            when (it) {
                0 -> resume(entry)
                1 -> resume(entry, fromStart = true)
                2 -> store.removeResume(entry.url)
            }
        }
    )

    /** Loads an add-on folder, or appends its next page. */
    fun load(state: BrowseState, more: Boolean = false): Job? {
        val url = if (more) state.nextPage?.url ?: return null else state.url
        if (more) state.loadingMore = true else {
            state.loading = true
            state.error = null
        }
        return viewModelScope.launch {
            val result = engine.invoke(url, if (more) "" else state.selected)
            messages.show(result.notifications)
            state.loading = false
            state.loadingMore = false
            state.loaded = true
            val isTop = (stack.lastOrNull() as? Route.Browse)?.state === state
            when (result.kind) {
                "directory" -> {
                    val items = result.items.filter { it.kind != "next_page" }
                    state.items = if (more) state.items + items else items
                    state.nextPage = result.items.firstOrNull { it.kind == "next_page" }
                    if (!more && result.category.isNotBlank()) state.title = result.category
                    if (!more && state.items.isEmpty() && state.nextPage == null) state.error = NOTHING_HERE
                    runBuiltins(result.builtins, state)
                }
                "resolved" -> result.stream?.let {
                    if (isTop) pop()
                    push(Route.Player(PlayerRequest.Direct(it, state.title)))
                }
                "empty" -> if (!more) {
                    val message = result.notifications.lastOrNull()?.message?.takeIf { it.isNotBlank() }
                    // e.g. a search cancelled on the keyboard: just go back
                    if (message == null && isTop && state.items.isEmpty()) pop() else state.error = message ?: NOTHING_HERE
                }
                "error" -> if (more) messages.toast("Erreur", result.error ?: "", error = true) else state.error = result.error
                else -> if (!runBuiltins(result.builtins, state) && !more && isTop) pop()
            }
        }
    }

    /** Runs a script entry of the add-on (RunPlugin…) and applies what it asks Kodi to do. */
    fun runAction(url: String, selected: String = "", current: BrowseState? = null) = viewModelScope.launch {
        val result = messages.whileBusy("Un instant…") { engine.invoke(url, selected) }
        messages.show(result.notifications)
        when (result.kind) {
            "resolved" -> result.stream?.let { push(Route.Player(PlayerRequest.Direct(it, it.label))) }
            "directory" -> if (result.items.isNotEmpty()) {
                push(Route.Browse(BrowseState(url, result.category.ifBlank { "Résultats" }).apply {
                    items = result.items.filter { it.kind != "next_page" }
                    nextPage = result.items.firstOrNull { it.kind == "next_page" }
                    loaded = true
                }))
            }
            "error" -> messages.toast("Erreur", result.error ?: "", error = true)
        }
        runBuiltins(result.builtins, current)
    }

    /** Long press on an item: the add-on's context menu (favourites, hide…). */
    fun showContextMenu(item: PluginItem, current: BrowseState?) {
        val openLabel = when {
            item.isPlayable -> "Lire"
            item.isFolder -> "Ouvrir"
            else -> null
        }
        val options = listOfNotNull(openLabel) + item.context.map { it.label }
        if (item.context.isEmpty()) return
        messages.ask(Choice(item.title, options) { index ->
            val entryIndex = if (openLabel != null) index - 1 else index
            if (entryIndex < 0) open(item, current?.title ?: "")
            else runBuiltins(listOf(item.context[entryIndex].action), current)
        })
    }

    /**
     * Applies the Kodi commands (xbmc.executebuiltin) the add-on asked for.
     * Returns true when at least one was handled.
     */
    private fun runBuiltins(builtins: List<String>, current: BrowseState?): Boolean {
        var handled = false
        for (builtin in builtins) {
            val name = builtin.substringBefore('(').trim().lowercase()
            val argument = builtin.substringAfter('(', "").substringBeforeLast(')')
            val firstArgument = argument.substringBefore(',').trim()
            handled = true
            when (name) {
                "container.refresh" -> current?.let { load(it) }
                "container.update", "activatewindow" -> {
                    val url = argument.split(',').map { it.trim() }.firstOrNull { it.startsWith("plugin://") }
                    if (url != null) {
                        if (argument.contains("replace", true) && (stack.lastOrNull() as? Route.Browse)?.state === current) pop()
                        openUrl(url, current?.title ?: "")
                    }
                }
                "action" -> if (firstArgument.startsWith("back", true) && stack.lastOrNull() is Route.Browse) pop()
                "playmedia" -> push(Route.Player(PlayerRequest.Vod(firstArgument, current?.title ?: "")))
                "runplugin", "runaddon" -> runAction(firstArgument, "", current)
                "addon.opensettings" -> {
                    stack.clear()
                    tab = Tab.Settings
                }
                else -> handled = false
            }
        }
        return handled
    }

    override fun onCleared() {
        playback.stop()
        super.onCleared()
    }

    private companion object {
        const val ROOT_URL = "plugin://plugin.video.catchuptvandmore/"
        const val PREPARE_MS = 6_000L
        const val PICTURES_MS = 3_000L
        /** Cards visible on screen when a home row appears. */
        const val FIRST_CARDS = 6
        const val NOTHING_HERE = "Aucun contenu disponible ici pour le moment."
    }
}
