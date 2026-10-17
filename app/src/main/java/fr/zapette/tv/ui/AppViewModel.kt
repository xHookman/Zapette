package fr.zapette.tv.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.zapette.tv.BuildConfig
import fr.zapette.tv.Perf
import fr.zapette.tv.ZapetteApplication
import fr.zapette.tv.engine.AppUpdater
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.Country
import fr.zapette.tv.data.Notification
import fr.zapette.tv.data.PluginItem
import fr.zapette.tv.data.PluginResult
import fr.zapette.tv.data.Programme
import fr.zapette.tv.data.ResumeEntry
import fr.zapette.tv.data.SelectedItem
import fr.zapette.tv.data.Stream
import fr.zapette.tv.data.ZapetteJson
import androidx.media3.exoplayer.ExoPlayer
import fr.zapette.tv.player.StreamPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

enum class Tab(val label: String) {
    Home("Accueil"), Live("Chaînes"), Guide("Guide TV"), Replay("Replay"), Explore("Explorer"), Settings("Réglages")
}

sealed interface PlayerRequest {
    data class Live(val channelId: String) : PlayerRequest
    data class Vod(val url: String, val title: String, val subtitle: String, val image: String, val selected: String) :
        PlayerRequest
    /** A stream already resolved by a folder callback. */
    data class Direct(val stream: Stream, val title: String) : PlayerRequest
}

/** State of one add-on directory, kept while it is on the back stack (focus & scroll restore). */
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

sealed interface Route {
    class Browse(val state: BrowseState) : Route
    data class Player(val request: PlayerRequest) : Route
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

/** App-level text input (settings). */
data class TextAsk(
    val title: String,
    val initial: String,
    val hidden: Boolean,
    val onDone: (String) -> Unit,
)

data class Toast(val id: Long, val title: String, val message: String, val kind: String = "info")

/** App-level choice dialog (context menus, settings lists…). */
data class Choice(
    val title: String,
    val options: List<String>,
    val selected: Int = -1,
    /** Multiple selection: the answer goes to [onMulti]. */
    val multi: Boolean = false,
    val onMulti: (List<Int>) -> Unit = {},
    val onPick: (Int) -> Unit = {},
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val zapette = app as ZapetteApplication
    val engine = zapette.engine
    val store = zapette.store

    sealed interface Boot {
        data class Loading(val message: String) : Boot
        data object Ready : Boot
        data class Failed(val message: String) : Boot
    }

    private val _boot = MutableStateFlow<Boot>(Boot.Loading("Démarrage du moteur…"))
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val epg: StateFlow<Map<String, List<Programme>>> = _epg.asStateFlow()

    private val _now = MutableStateFlow(nowSec())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries.asStateFlow()

    private val _toasts = MutableStateFlow<List<Toast>>(emptyList())
    val toasts: StateFlow<List<Toast>> = _toasts.asStateFlow()

    private val _choice = MutableStateFlow<Choice?>(null)
    val choice: StateFlow<Choice?> = _choice.asStateFlow()

    private val _textAsk = MutableStateFlow<TextAsk?>(null)
    val textAsk: StateFlow<TextAsk?> = _textAsk.asStateFlow()

    fun askText(ask: TextAsk?) {
        _textAsk.value = ask
    }

    private val _busy = MutableStateFlow<String?>(null)
    /** Short blocking operation in progress (e.g. running an add-on action). */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val resume: StateFlow<List<ResumeEntry>> get() = store.resume

    // ------------------------------------------------------------ navigation
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Route>()

    /** Root of the Replay tab (replay menu of the selected country). */
    var replayRoot by mutableStateOf<BrowseState?>(null)
        private set
    /** Root of the Explorer tab (the add-on's root menu). */
    var exploreRoot by mutableStateOf(BrowseState("plugin://plugin.video.catchuptvandmore/", "Explorer"))
        private set

    /** Key of the last focused element on the home screen, for focus restoration. */
    var homeFocusKey: String? = null
    var liveFocusId: String? = null
    var guideFocusId: String? = null

    /** Backdrop image of the browsing screens (Google TV style ambient art). */
    private var backdropState by mutableStateOf<String?>(null)
    private var backdropJob: Job? = null

    /**
     * Ambient artwork of the focused item. Applied only once the focus rests on an
     * item (not at every D-pad step): loading big pictures while scrolling is what
     * makes weak TVs stutter. Disabled in light mode.
     */
    var backdrop: String?
        get() = backdropState
        set(value) {
            backdropJob?.cancel()
            if (Perf.lite || value == null) {
                backdropState = null
                return
            }
            if (value == backdropState) return
            backdropJob = viewModelScope.launch {
                delay(400)
                backdropState = value
            }
        }

    var hero by mutableStateOf<Hero?>(null)

    // ------------------------------------------------------------ app update
    private val updater = AppUpdater(app)

    /** Latest Zapette release found on GitHub (null until checked). */
    var appRelease by mutableStateOf<AppUpdater.Release?>(null)
        private set

    fun checkAppUpdate(manual: Boolean) {
        viewModelScope.launch {
            if (manual) _busy.value = "Recherche d'une mise à jour de Zapette…"
            val release = updater.latest()
            _busy.value = null
            if (release != null) appRelease = release
            when {
                release == null -> if (manual) toast("Mise à jour", "GitHub est injoignable pour le moment.", "error")
                !release.isNewer -> if (manual) toast("Zapette est à jour", "Version ${BuildConfig.VERSION_NAME}")
                manual -> _choice.value = Choice(
                    "Zapette ${release.versionName} disponible (installée : ${BuildConfig.VERSION_NAME})",
                    listOf("Installer maintenant", "Plus tard"),
                ) { if (it == 0) installAppUpdate(release) }
                else -> toast("Zapette ${release.versionName} disponible", "Réglages → Mettre à jour Zapette")
            }
        }
    }

    fun installAppUpdate(release: AppUpdater.Release) {
        viewModelScope.launch {
            if (!updater.canInstall()) {
                toast(
                    "Autorisation nécessaire",
                    "Autorisez Zapette à installer des applications, puis relancez la mise à jour.",
                )
                if (!updater.openInstallPermission()) toast(
                    "Autorisation nécessaire",
                    "Paramètres → Applications → Zapette → Installer des applications inconnues.", "error",
                )
                return@launch
            }
            _busy.value = "Téléchargement de Zapette ${release.versionName}…"
            val file = runCatching {
                updater.download(release) { pct -> _busy.value = "Téléchargement de Zapette ${release.versionName}… $pct %" }
            }
            _busy.value = null
            file.onSuccess { apk ->
                val problem = updater.verify(apk, release)
                if (problem != null) {
                    apk.delete()
                    toast("Mise à jour refusée", problem, "error")
                } else {
                    runCatching { updater.install(apk) }.onFailure { e -> toast("Installation impossible", e.message ?: "", "error") }
                }
            }
                .onFailure { toast("Téléchargement impossible", it.message ?: "", "error") }
        }
    }

    // --------------------------------------------------------- account check
    /** Logs in with the saved credentials and tells the user if they are accepted. */
    suspend fun checkAccount(service: String, name: String) {
        _busy.value = "Vérification du compte $name…"
        val result = engine.checkAccount(service)
        _busy.value = null
        when (result.status) {
            "ok" -> toast("Compte $name valide", "Identifiant et mot de passe acceptés.")
            "invalid" -> toast("Compte $name refusé", "Identifiant ou mot de passe incorrect.", "error")
            "network" -> toast(
                "Vérification impossible ($name)",
                "Le service ne répond pas : connexion internet, ou accès réservé à la France.", "error",
            )
            else -> toast("Compte $name enregistré", "La vérification n'est pas disponible pour ce service.")
        }
    }

    // ------------------------------------------------------------ live player
    private var sharedLivePlayer by mutableStateOf<ExoPlayer?>(null)

    /** Channel loaded in the shared live player. */
    var liveChannelId: String? = null

    /** The live channel keeps playing in a corner while the user browses. */
    var miniLive by mutableStateOf(false)

    /** Mini-player bottom-right (default) or top-right (when the focus is under it). */
    var miniAtBottom by mutableStateOf(true)

    /** App-wide player for live TV: it survives the full screen for the corner mini-player. */
    fun livePlayer(): ExoPlayer =
        sharedLivePlayer ?: StreamPlayer.create(getApplication<Application>()).also { sharedLivePlayer = it }

    fun currentLivePlayer(): ExoPlayer? = sharedLivePlayer

    fun stopLive() {
        miniLive = false
        liveChannelId = null
        sharedLivePlayer?.release()
        sharedLivePlayer = null
    }

    // ---------------------------------------------------------- app background
    private var stoppedAt = 0L

    /** Incremented when the live channel must be tuned again (watched by the player screen). */
    var liveRestartTick by mutableIntStateOf(0)
        private set

    /** App leaves the screen (TV standby, Home button): stop the live stream entirely. */
    fun onAppStop() {
        stoppedAt = System.currentTimeMillis()
        sharedLivePlayer?.stop()
    }

    /**
     * Back on screen: the live stream is fetched again (new address and tokens), so
     * the picture is the programme on air now and not where it stopped yesterday.
     */
    fun onAppStart() {
        if (stoppedAt == 0L) return
        stoppedAt = 0L
        val player = sharedLivePlayer ?: return
        val top = stack.lastOrNull()
        if (top is Route.Player && top.request is PlayerRequest.Live) {
            liveRestartTick++
        } else if (miniLive) {
            val channel = channel(liveChannelId) ?: return stopLive()
            viewModelScope.launch {
                val result = engine.invoke(channel.url, selectedJson(channel))
                val stream = result.stream
                if (result.kind == "resolved" && stream != null && sharedLivePlayer === player) {
                    StreamPlayer.load(getApplication<Application>(), player, stream, 0, subtitlesOn)
                } else if (sharedLivePlayer === player) {
                    stopLive()
                }
            }
        }
    }

    fun selectedJson(c: Channel): String = ZapetteJson.encodeToString(
        SelectedItem.serializer(),
        SelectedItem(c.label, c.url, mapOf("thumb" to c.logo, "fanart" to c.fanart), mapOf("title" to c.label, "plot" to "")),
    )

    /** Full screen again on the channel playing in the corner. */
    fun expandMini() {
        val id = liveChannelId ?: return
        livePlayer()
        stack.add(Route.Player(PlayerRequest.Live(id)))
    }

    override fun onCleared() {
        sharedLivePlayer?.release()
        sharedLivePlayer = null
        super.onCleared()
    }

    /** Subtitles on/off, shared with the add-on's "active_subtitle" setting. */
    var subtitlesOn by mutableStateOf(false)
        private set

    fun setSubtitles(on: Boolean) {
        subtitlesOn = on
        viewModelScope.launch { runCatching { engine.setSetting("active_subtitle", if (on) "true" else "false") } }
    }

    /** Called when the add-on setting was changed from its own settings page. */
    fun syncSubtitles(value: String) {
        subtitlesOn = value.equals("true", true)
    }

    /**
     * True when the tab content should take the focus back (app start, return
     * from the player or a browse screen). False while the user moves in the
     * top bar, so switching tabs never steals the focus.
     */
    var restorePending by mutableStateOf(true)
    val homeListState = LazyListState()
    val liveGridState = LazyGridState()
    val guideChannelsState = LazyListState()
    private val rowStates = HashMap<String, LazyListState>()
    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { LazyListState() }

    private var epgJob: Job? = null

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = nowSec()
                // Once a minute, on the minute: progress bars and "reste x min" stay exact
                // while screens are recomposed 4x less often than before
                delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
            }
        }
        start()
    }

    fun start() {
        viewModelScope.launch {
            _boot.value = Boot.Loading("Préparation de Catch-up TV & More…")
            try {
                engine.start()
                subtitlesOn = runCatching { engine.getSetting("active_subtitle") == "true" }.getOrDefault(false)
                _boot.value = Boot.Loading("Chargement des chaînes…")
                loadChannels()
                _boot.value = Boot.Ready
                checkAppUpdate(manual = false)
            } catch (t: Throwable) {
                _boot.value = Boot.Failed(t.message ?: t.toString())
                return@launch
            }
            val last = store.lastChannelId
            if (store.startOnLive && last != null && _channels.value.any { it.id == last }) {
                livePlayer()
                stack.add(Route.Player(PlayerRequest.Live(last)))
            }
            refreshEpg()
            launch { _countries.value = runCatching { engine.liveCountries() }.getOrDefault(emptyList()) }
            launch { setupReplayRoot() }
        }
    }

    private suspend fun loadChannels() {
        _channels.value = runCatching { engine.liveChannels(store.liveCountry) }.getOrElse {
            toast("Chaînes indisponibles", it.message ?: "")
            emptyList()
        }
    }

    private suspend fun setupReplayRoot() {
        val replayMenu = store.liveCountry.replace("_live", "_replay")
        val url = runCatching { engine.menuUrl(replayMenu) }.getOrNull() ?: return
        replayRoot = BrowseState(url, "Replay")
    }

    fun changeCountry(countryId: String) {
        if (countryId == store.liveCountry) return
        store.liveCountry = countryId
        store.lastChannelId = null
        store.previousChannelId = null
        viewModelScope.launch {
            loadChannels()
            _epg.value = emptyMap()
            refreshEpg()
            setupReplayRoot()
        }
    }

    /**
     * Loads today's guide, then tomorrow's a bit later. The guide files are daily,
     * so they are only parsed again when the date changes: parsing runs in Python
     * and would otherwise compete with channel changes on slow TV boxes.
     */
    fun refreshEpg() {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            // Let the first channel start before parsing the guide
            if (stack.any { it is Route.Player }) delay(4_000)
            var loadedDay = ""
            while (true) {
                val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                if (day != loadedDay || _epg.value.isEmpty()) {
                    val country = store.liveCountry
                    val today = engine.epg(country, 0)
                    if (today.isNotEmpty()) {
                        _epg.value = merge(if (day != loadedDay) emptyMap() else _epg.value, today)
                        loadedDay = day
                    }
                    delay(20_000)
                    val tomorrow = engine.epg(country, 1)
                    if (tomorrow.isNotEmpty()) _epg.value = merge(_epg.value, tomorrow)
                }
                delay(10 * 60_000L)
            }
        }
    }

    private fun merge(a: Map<String, List<Programme>>, b: Map<String, List<Programme>>): Map<String, List<Programme>> {
        val keys = a.keys + b.keys
        return keys.associateWith { k ->
            ((a[k].orEmpty()) + (b[k].orEmpty())).distinctBy { it.start }.sortedBy { it.start }
        }
    }

    // ------------------------------------------------------------- EPG utils
    fun programmes(channel: Channel): List<Programme> = _epg.value[channel.xmltvId].orEmpty()

    fun nowPlaying(channel: Channel, at: Long = _now.value): Programme? =
        programmes(channel).firstOrNull { it.isOnAt(at) }

    fun nextUp(channel: Channel, at: Long = _now.value): Programme? =
        programmes(channel).firstOrNull { it.start >= at }

    /** "Ce soir": the programme on air at 21:10 today (or tomorrow after midnight). */
    fun primeTime(channel: Channel): Programme? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 10); set(Calendar.SECOND, 0)
        }
        val t = cal.timeInMillis / 1000
        return programmes(channel).firstOrNull { it.isOnAt(t) }
    }

    private var channelIndex: Pair<List<Channel>, Map<String, Channel>> = emptyList<Channel>() to emptyMap()

    /** Channel by id (indexed: called a lot while zapping and drawing). */
    fun channel(id: String?): Channel? {
        if (id == null) return null
        val list = _channels.value
        if (channelIndex.first !== list) channelIndex = list to list.associateBy { it.id }
        return channelIndex.second[id]
    }

    // ------------------------------------------------------------- actions
    fun selectTab(t: Tab) {
        tabJob?.cancel()
        tab = t
    }

    private var tabJob: Job? = null

    /** Focus moving over the tabs: only show the tab the focus stops on. */
    fun previewTab(t: Tab) {
        tabJob?.cancel()
        if (t == tab) return
        tabJob = viewModelScope.launch {
            delay(if (Perf.lite) 350 else 220)
            tab = t
        }
    }

    fun watch(channel: Channel) {
        livePlayer()   // created before the screen so the video layer is there from the first frame
        stack.add(Route.Player(PlayerRequest.Live(channel.id)))
    }

    fun rememberChannel(channel: Channel) {
        if (store.lastChannelId != channel.id) {
            store.previousChannelId = store.lastChannelId
            store.lastChannelId = channel.id
        }
    }

    fun open(item: PluginItem, parentTitle: String = "") {
        when {
            item.isPlayable -> play(item, parentTitle)
            item.kind == "action" -> runAction(item.url, item.selectedJson())
            else -> stack.add(Route.Browse(BrowseState(item.url, item.title, item.selectedJson())))
        }
    }

    fun openUrl(url: String, title: String) {
        stack.add(Route.Browse(BrowseState(url, title)))
    }

    fun play(item: PluginItem, parentTitle: String = "") {
        val subtitle = listOfNotNull(
            item.info.tvshowtitle?.takeIf { it != item.title },
            parentTitle.takeIf { it.isNotBlank() && it != item.title },
        ).firstOrNull() ?: ""
        stack.add(
            Route.Player(
                PlayerRequest.Vod(item.url, item.title, subtitle, item.landscape ?: "", item.selectedJson())
            )
        )
    }

    fun resumeEntry(entry: ResumeEntry) {
        stack.add(Route.Player(PlayerRequest.Vod(entry.url, entry.label, entry.subtitle, entry.image, entry.selected)))
    }

    /** Loads (or appends the next page of) an add-on directory. */
    fun load(state: BrowseState, more: Boolean = false) {
        val url = if (more) state.nextPage?.url ?: return else state.url
        if (more) state.loadingMore = true else { state.loading = true; state.error = null }
        viewModelScope.launch {
            val result = engine.invoke(url, if (more) "" else state.selected)
            showNotifications(result.notifications)
            if (more) state.loadingMore = false else state.loading = false
            state.loaded = true
            when (result.kind) {
                "directory" -> {
                    val pages = result.items.filter { it.kind == "next_page" }
                    val items = result.items.filter { it.kind != "next_page" }
                    state.items = if (more) state.items + items else items
                    state.nextPage = pages.firstOrNull()
                    if (!more && result.category.isNotBlank()) state.title = result.category
                    if (!more && items.isEmpty() && pages.isEmpty()) {
                        state.error = "Aucun contenu disponible ici pour le moment."
                    }
                }
                "resolved" -> {
                    val stream = result.stream
                    val top = stack.lastOrNull()
                    if (stream != null) {
                        if (top is Route.Browse && top.state === state) popRoute()
                        stack.add(Route.Player(PlayerRequest.Direct(stream, state.title)))
                    }
                }
                "empty" -> if (!more) {
                    val top = stack.lastOrNull()
                    val message = result.notifications.lastOrNull()?.message?.takeIf { it.isNotBlank() }
                    if (message == null && top is Route.Browse && top.state === state && state.items.isEmpty()) {
                        // e.g. a search cancelled from the keyboard: just go back
                        popRoute()
                    } else {
                        state.error = message ?: "Aucun contenu disponible ici pour le moment."
                    }
                }
                "error" -> if (more) toast("Erreur", result.error ?: "") else state.error = result.error
                else -> {
                    val handled = handleBuiltins(result.builtins, state)
                    if (!handled && !more) {
                        val top = stack.lastOrNull()
                        if (top is Route.Browse && top.state === state) popRoute()
                    }
                }
            }
            if (result.kind == "directory") handleBuiltins(result.builtins, state)
        }
    }

    /** Runs a "script" entry (RunPlugin…) and applies what it asks Kodi to do. */
    fun runAction(url: String, selected: String = "", current: BrowseState? = null) {
        viewModelScope.launch {
            _busy.value = "Un instant…"
            val result = engine.invoke(url, selected)
            _busy.value = null
            showNotifications(result.notifications)
            when (result.kind) {
                "resolved" -> result.stream?.let { stack.add(Route.Player(PlayerRequest.Direct(it, it.label))) }
                "directory" -> if (result.items.isNotEmpty()) {
                    val st = BrowseState(url, result.category.ifBlank { "Résultats" })
                    st.items = result.items.filter { it.kind != "next_page" }
                    st.nextPage = result.items.firstOrNull { it.kind == "next_page" }
                    st.loaded = true
                    stack.add(Route.Browse(st))
                }
                "error" -> toast("Erreur", result.error ?: "")
            }
            handleBuiltins(result.builtins, current)
        }
    }

    fun runContextAction(action: String, current: BrowseState?) {
        val (name, arg) = parseBuiltin(action)
        when (name.lowercase()) {
            "runplugin", "runaddon" -> runAction(arg.substringBefore(",").trim(), "", current)
            "container.update", "activatewindow" -> {
                val url = arg.split(",").firstOrNull { it.trim().startsWith("plugin://") }?.trim()
                if (url != null) openUrl(url, "")
            }
            "playmedia" -> stack.add(Route.Player(PlayerRequest.Vod(arg.substringBefore(","), "", "", "", "")))
            "container.refresh" -> current?.let { load(it) }
        }
    }

    /** Interprets xbmc.executebuiltin() commands recorded during a call. */
    private fun handleBuiltins(builtins: List<String>, current: BrowseState?): Boolean {
        var handled = false
        for (b in builtins) {
            val (name, arg) = parseBuiltin(b)
            when (name.lowercase()) {
                "container.refresh" -> { current?.let { load(it) }; handled = true }
                "container.update" -> {
                    val url = arg.substringBefore(",").trim()
                    if (url.startsWith("plugin://")) {
                        val replace = arg.contains("replace", true)
                        val top = stack.lastOrNull()
                        if (replace && top is Route.Browse && top.state === current) popRoute()
                        openUrl(url, current?.title ?: "")
                        handled = true
                    }
                }
                "action" -> if (arg.startsWith("back", true)) {
                    val top = stack.lastOrNull()
                    if (top is Route.Browse) popRoute()
                    handled = true
                }
                "playmedia" -> {
                    stack.add(Route.Player(PlayerRequest.Vod(arg.substringBefore(",").trim(), current?.title ?: "", "", "", "")))
                    handled = true
                }
                "runplugin" -> { runAction(arg.substringBefore(",").trim(), "", current); handled = true }
                "addon.opensettings" -> { tab = Tab.Settings; stack.clear(); handled = true }
            }
        }
        return handled
    }

    private fun parseBuiltin(s: String): Pair<String, String> {
        val open = s.indexOf('(')
        if (open < 0) return s.trim() to ""
        return s.substring(0, open).trim() to s.substring(open + 1, s.lastIndexOf(')').takeIf { it > open } ?: s.length)
    }

    fun showContextMenu(item: PluginItem, current: BrowseState?) {
        val entries = item.context
        val options = buildList {
            if (item.isPlayable) add("Lire")
            if (item.isFolder) add("Ouvrir")
            entries.forEach { add(it.label) }
        }
        if (options.size <= 1 && entries.isEmpty()) return
        _choice.value = Choice(item.title, options) { index ->
            val offset = if (item.isPlayable || item.isFolder) 1 else 0
            if (index < offset) open(item, current?.title ?: "")
            else entries.getOrNull(index - offset)?.let { runContextAction(it.action, current) }
        }
    }

    fun askRemoveResume(entry: ResumeEntry) {
        _choice.value = Choice(entry.label, listOf("Reprendre", "Recommencer depuis le début", "Retirer de la liste")) { i ->
            when (i) {
                0 -> resumeEntry(entry)
                1 -> {
                    store.removeResume(entry.url)
                    stack.add(Route.Player(PlayerRequest.Vod(entry.url, entry.label, entry.subtitle, entry.image, entry.selected)))
                }
                2 -> store.removeResume(entry.url)
            }
        }
    }

    fun ask(choice: Choice) {
        _choice.value = choice
    }

    fun dismissChoice() {
        _choice.value = null
    }

    private fun popRoute() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty()) restorePending = true
    }

    fun onBack(): Boolean {
        if (_choice.value != null) { _choice.value = null; return true }
        if (stack.isNotEmpty()) { popRoute(); return true }
        if (miniLive) { stopLive(); return true }
        if (tab != Tab.Home) { tab = Tab.Home; restorePending = true; return true }
        return false
    }

    // --------------------------------------------------------- add-on update
    var addonVersion by mutableStateOf("")
        private set
    var addonFromGitHub by mutableStateOf(false)
        private set

    fun refreshAddonInfo() {
        addonVersion = engine.installer.installedVersion()
        addonFromGitHub = engine.installer.source().startsWith("github:")
    }

    fun checkAddonUpdate() {
        viewModelScope.launch {
            _busy.value = "Recherche d'une mise à jour…"
            val remote = engine.remoteVersion()
            _busy.value = null
            val current = engine.installer.installedVersion()
            when {
                remote == null -> toast("Mise à jour", "GitHub est injoignable pour le moment.", "error")
                remote == current -> _choice.value = Choice(
                    "Catch-up TV & More $current est à jour", listOf("Réinstaller depuis GitHub", "Fermer")
                ) { if (it == 0) updateAddon() }
                else -> _choice.value = Choice(
                    "Version $remote disponible (installée : $current)", listOf("Mettre à jour maintenant", "Plus tard")
                ) { if (it == 0) updateAddon() }
            }
        }
    }

    fun updateAddon(revert: Boolean = false) {
        viewModelScope.launch {
            _busy.value = if (revert) "Restauration de la version intégrée…" else "Téléchargement de Catch-up TV & More…"
            val result = runCatching { if (revert) engine.revertAddon() else engine.updateAddon() }
            _busy.value = null
            result.onSuccess { v ->
                toast("Catch-up TV & More $v", if (revert) "Version intégrée restaurée." else "Mise à jour installée.")
                afterAddonChange()
            }.onFailure { toast("Échec de la mise à jour", it.message ?: "", "error") }
            refreshAddonInfo()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            engine.clearCache()
            toast("Cache vidé", "Le guide TV va être rechargé.")
            refreshEpg()
        }
    }

    private suspend fun afterAddonChange() {
        loadChannels()
        setupReplayRoot()
        exploreRoot = BrowseState("plugin://plugin.video.catchuptvandmore/", "Explorer")
        refreshEpg()
    }

    // ---------------------------------------------------------------- toasts
    private var toastId = 0L

    fun toast(title: String, message: String = "", kind: String = "info") {
        if (title.isBlank() && message.isBlank()) return
        val t = Toast(++toastId, title, message, kind)
        _toasts.value = (_toasts.value + t).takeLast(3)
        viewModelScope.launch {
            delay(if (kind == "error") 6000 else 4000)
            _toasts.value = _toasts.value.filterNot { it.id == t.id }
        }
    }

    fun showNotifications(list: List<Notification>) {
        list.forEach { toast(it.heading, it.message, it.kind) }
    }

    /** Resolves a playable plugin url; the player shows errors itself. */
    suspend fun resolve(url: String, selected: String): PluginResult = engine.invoke(url, selected)

    companion object {
        fun nowSec() = System.currentTimeMillis() / 1000
    }
}
