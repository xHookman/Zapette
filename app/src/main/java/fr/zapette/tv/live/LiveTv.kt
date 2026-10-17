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
    suspend fun loadChannels(): Boolean {
        val list = runCatching { engine.liveChannels(store.liveCountry) }.getOrDefault(emptyList())
        byId = list.associateBy { it.id }
        _channels.value = list
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
        _guide.value = emptyMap()
        loadChannels()
        startGuide()
    }

    fun channel(id: String?): Channel? = id?.let { byId[it] }

    fun channelNumber(number: Int): Channel? = _channels.value.firstOrNull { it.number == number }

    /** Remembers the channel for "start on the last channel" and for "previous channel" (0). */
    fun rememberWatched(channel: Channel) {
        if (store.lastChannelId == channel.id) return
        store.previousChannelId = store.lastChannelId
        store.lastChannelId = channel.id
    }

    // ------------------------------------------------------------------- guide
    /**
     * Loads today's guide, then tomorrow's, and again when the date changes.
     * Parsing runs in Python, so the first load can wait for the first channel to start.
     */
    fun startGuide(waitFirstMs: Long = 0) {
        guideJob?.cancel()
        guideJob = scope.launch {
            delay(waitFirstMs)
            var loadedDay = -1
            while (true) {
                val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                if (day != loadedDay) {
                    val country = store.liveCountry
                    val today = engine.guide(country, 0)
                    if (today.isNotEmpty()) {
                        _guide.value = today
                        loadedDay = day
                        val tomorrow = engine.guide(country, 1)
                        if (tomorrow.isNotEmpty()) _guide.value = merge(today, tomorrow)
                    }
                }
                delay(10 * 60_000L)
            }
        }
    }

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

    companion object {
        fun nowSeconds() = System.currentTimeMillis() / 1000
    }
}
