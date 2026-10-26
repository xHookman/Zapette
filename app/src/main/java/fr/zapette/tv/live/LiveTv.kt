package fr.zapette.tv.live

import android.content.Context
import coil.imageLoader
import fr.zapette.tv.Images
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.Country
import fr.zapette.tv.data.Programme
import fr.zapette.tv.data.Store
import fr.zapette.tv.engine.AddonEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/** Guide of each channel, by XMLTV id. */
typealias Guide = Map<String, List<Programme>>

/**
 * Live TV data: the channel list (TV numbering), the TV guide and the clock.
 * The playback itself is in [LivePlayback].
 */
class LiveTv(
    private val context: Context,
    private val engine: AddonEngine,
    private val store: Store,
    private val scope: CoroutineScope,
) {
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _guide = MutableStateFlow<Guide>(emptyMap())
    val guide: StateFlow<Guide> = _guide.asStateFlow()

    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries.asStateFlow()

    private val _now = MutableStateFlow(nowSeconds())
    /** Current time in seconds, updated on every minute (progress bars, "reste x min"). */
    val now: StateFlow<Long> = _now.asStateFlow()

    private var byId: Map<String, Channel> = emptyMap()
    private var guideJob: Job? = null

    init {
        scope.launch {
            while (true) {
                _now.value = nowSeconds()
                delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
            }
        }
    }

    // ---------------------------------------------------------------- channels
    /** Channels in TV numbering order, as the add-on gives them. */
    private var tvOrder: List<Channel> = emptyList()

    suspend fun loadChannels(): Boolean {
        val list = runCatching { engine.liveChannels(store.liveCountry) }.getOrDefault(emptyList())
        byId = list.associateBy { it.id }
        tvOrder = list
        _channels.value = inUserOrder(list)
        // Logos are fetched once, in the background, so that screens never wait for them
        list.forEach { if (it.logo.isNotBlank()) context.imageLoader.enqueue(Images.logo(context, it.logo)) }
        return list.isNotEmpty()
    }

    suspend fun loadCountries() {
        _countries.value = runCatching { engine.liveCountries() }.getOrDefault(emptyList())
    }

    suspend fun changeCountry(countryId: String) {
        if (countryId == store.liveCountry) return
        store.liveCountry = countryId
        store.lastChannelId = null
        store.previousChannelId = null
        loadChannels()
        reloadGuide()
    }

    fun channel(id: String?): Channel? = id?.let { byId[it] }

    // ---------------------------------------------------------- channel order
    /**
     * The user can move channels in the guide. The numbers do not change (2 stays
     * France 2); only the order of the list and of CH+ / CH− does.
     */
    val hasUserOrder: Boolean get() = store.channelOrder.isNotEmpty()

    private fun inUserOrder(list: List<Channel>): List<Channel> {
        val order = store.channelOrder
        if (order.isEmpty()) return list
        val rank = order.withIndex().associate { (i, id) -> id to i }
        // Channels not in the saved order (new ones) keep their place after the others
        return list.sortedBy { rank[it.id] ?: (order.size + list.indexOf(it)) }
    }

    /** Moves a channel up (−1) or down (+1) in the list. */
    fun moveChannel(id: String, step: Int) {
        val list = _channels.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + step
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _channels.value = list
        store.channelOrder = list.map { it.id }
    }

    fun resetChannelOrder() {
        store.channelOrder = emptyList()
        _channels.value = tvOrder
    }

    fun channelNumber(number: Int): Channel? = _channels.value.firstOrNull { it.number == number }

    /** Remembers the channel for "start on the last channel" and for "previous channel" (0). */
    fun rememberWatched(channel: Channel) {
        if (store.lastChannelId == channel.id) return
        store.previousChannelId = store.lastChannelId
        store.lastChannelId = channel.id
    }

    // ------------------------------------------------------------------- guide
    private var todayLoaded = -1        // day of year of the guide in memory
    private var tomorrowLoaded = -1

    /**
     * Today's guide, now. Called at start-up so that the home screen opens complete;
     * from the disk cache it takes a moment, the first time of the day it is parsed.
     */
    suspend fun loadToday(): Boolean {
        val today = engine.guide(store.liveCountry, 0)
        if (today.isEmpty()) return false
        _guide.value = today
        todayLoaded = dayOfYear()
        return true
    }

    /**
     * Keeps the guide up to date: today's (if not loaded yet), then tomorrow's, and
     * again when the date changes. Parsing runs in Python, so the first load can
     * wait for the first channel to start.
     */
    fun startGuide(waitFirstMs: Long = 0) {
        guideJob?.cancel()
        guideJob = scope.launch {
            delay(waitFirstMs)
            while (true) {
                val day = dayOfYear()
                if (todayLoaded != day) loadToday()
                if (todayLoaded == day && tomorrowLoaded != day) {
                    val tomorrow = engine.guide(store.liveCountry, 1)
                    if (tomorrow.isNotEmpty()) {
                        _guide.value = merge(_guide.value, tomorrow)
                        tomorrowLoaded = day
                    }
                }
                delay(10 * 60_000L)
            }
        }
    }

    /** Forgets the guide and loads it again (country changed, cache cleared, add-on updated). */
    fun reloadGuide() {
        todayLoaded = -1
        tomorrowLoaded = -1
        _guide.value = emptyMap()
        startGuide()
    }

    private fun dayOfYear() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    private fun merge(a: Guide, b: Guide): Guide = (a.keys + b.keys).associateWith { id ->
        (a[id].orEmpty() + b[id].orEmpty()).distinctBy { it.start }.sortedBy { it.start }
    }

    fun programmes(channel: Channel): List<Programme> = _guide.value[channel.xmltvId].orEmpty()

    fun nowPlaying(channel: Channel, at: Long = _now.value): Programme? = programmes(channel).firstOrNull { it.isOnAt(at) }

    fun nextUp(channel: Channel, at: Long = _now.value): Programme? = programmes(channel).firstOrNull { it.start >= at }

    /** "Ce soir": the programme on air at 21:10 today. */
    fun primeTime(channel: Channel): Programme? {
        val evening = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 10); set(Calendar.SECOND, 0)
        }.timeInMillis / 1000
        return programmes(channel).firstOrNull { it.isOnAt(evening) }
    }

    /** "Ce soir à la télé": prime time of the main channels (TNT 1 to 27). */
    fun tonight(channels: List<Channel>): List<Pair<Channel, Programme>> =
        channels.filter { it.number in 1..27 }.mapNotNull { c -> primeTime(c)?.let { c to it } }

    companion object {
        fun nowSeconds() = System.currentTimeMillis() / 1000
    }
}
