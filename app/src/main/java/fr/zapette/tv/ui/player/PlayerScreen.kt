@file:OptIn(UnstableApi::class)

package fr.zapette.tv.ui.player

import android.view.KeyEvent as AndroidKey
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import fr.zapette.tv.data.Channel
import fr.zapette.tv.data.ResumeEntry
import fr.zapette.tv.data.SelectedItem
import fr.zapette.tv.data.Stream
import fr.zapette.tv.data.ZapetteJson
import fr.zapette.tv.player.StreamPlayer
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.Choice
import fr.zapette.tv.ui.PlayerRequest
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.Clock
import fr.zapette.tv.ui.components.FocusSurface
import fr.zapette.tv.ui.components.Fmt
import fr.zapette.tv.ui.components.LiveBadge
import fr.zapette.tv.ui.components.PauseGlyph
import fr.zapette.tv.ui.components.ProgressLine
import fr.zapette.tv.ui.components.SingleLine
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.components.ZImage
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Everything the player overlay displays. */
private class PlayerUi {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var liveStream by mutableStateOf(false)

    // live
    var tunedId by mutableStateOf<String?>(null)
    var shownId by mutableStateOf<String?>(null)
    var banner by mutableStateOf(false)
    var zapList by mutableStateOf(false)
    var info by mutableStateOf(false)
    var number by mutableStateOf("")

    // vod
    var controls by mutableStateOf(true)
    var seekFlash by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var image by mutableStateOf("")
    var attempts by mutableIntStateOf(0)
}

/** Pending delayed actions of the player overlay. */
private class PlayerJobs {
    var tune: Job? = null
    var banner: Job? = null
    var number: Job? = null
    var controls: Job? = null
    var flash: Job? = null
}

@Composable
fun PlayerScreen(vm: AppViewModel, request: PlayerRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exo: ExoPlayer = remember { StreamPlayer.create(context) }
    val ui = remember { PlayerUi() }
    val rootFocus = remember { FocusRequester() }
    val channels by vm.channels.collectAsState()
    val now by vm.now.collectAsState()
    val isLive = request is PlayerRequest.Live

    var subtitlesOn by remember { mutableStateOf(false) }
    val jobs = remember { PlayerJobs() }

    // ------------------------------------------------------------ helpers
    fun showBanner(seconds: Long = 5) {
        ui.banner = true
        jobs.banner?.cancel()
        jobs.banner = scope.launch { delay(seconds * 1000); if (!ui.zapList && !ui.info) { ui.banner = false } }
    }

    fun showControls() {
        ui.controls = true
        jobs.controls?.cancel()
        jobs.controls = scope.launch { delay(4500); if (exo.isPlaying) { ui.controls = false } }
    }

    fun selectedJson(c: Channel) = ZapetteJson.encodeToString(
        SelectedItem.serializer(),
        SelectedItem(c.label, c.url, mapOf("thumb" to c.logo, "fanart" to c.fanart), mapOf("title" to c.label, "plot" to "")),
    )

    fun play(stream: Stream, startMs: Long = 0) {
        ui.error = null
        ui.loading = true
        StreamPlayer.load(context, exo, stream, startMs, subtitlesOn)
    }

    fun tune(channel: Channel, immediate: Boolean) {
        ui.shownId = channel.id
        ui.error = null
        if (vm.store.bannerOnZap || !immediate) showBanner()
        jobs.tune?.cancel()
        jobs.tune = scope.launch {
            if (!immediate) delay(450)          // let fast zapping settle on a channel
            ui.loading = true
            ui.tunedId = channel.id
            vm.rememberChannel(channel)
            exo.stop()
            val result = vm.resolve(channel.url, selectedJson(channel))
            if (!isActive) return@launch
            val stream = result.stream
            if (result.kind == "resolved" && stream != null) play(stream)
            else {
                ui.loading = false
                ui.error = result.error ?: result.notifications.lastOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "${channel.label} est indisponible pour le moment."
            }
        }
    }

    fun ordered() = channels.sortedBy { it.number }

    fun zap(step: Int) {
        val list = ordered()
        if (list.isEmpty()) return
        val from = list.indexOfFirst { it.id == (ui.shownId ?: ui.tunedId) }.coerceAtLeast(0)
        val next = list[(from + step + list.size) % list.size]
        tune(next, immediate = false)
    }

    fun commitNumber() {
        val typed = ui.number
        ui.number = ""
        if (typed.isEmpty()) return
        if (typed == "0") {
            vm.channel(vm.store.previousChannelId)?.let { tune(it, true) }
            return
        }
        val n = typed.toIntOrNull() ?: return
        val c = channels.firstOrNull { it.number == n }
        if (c != null) tune(c, true) else vm.toast("Aucune chaîne n°$n")
    }

    fun typeDigit(d: Int) {
        ui.number = (ui.number + d).takeLast(3)
        jobs.number?.cancel()
        if (ui.number.length >= 3) commitNumber()
        else jobs.number = scope.launch { delay(1600); commitNumber() }
    }

    fun openTracks() {
        val groups = exo.currentTracks.groups
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val audioDescription = f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0
                val name = f.label ?: languageName(f.language) + if (audioDescription) " (audiodescription)" else ""
                entries += ("Audio · $name" + if (g.isTrackSelected(i)) "  ✓" else "") to {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        val texts = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (texts.isNotEmpty()) {
            val anyOn = texts.any { it.isSelected }
            entries += ("Sous-titres · désactivés" + if (!anyOn) "  ✓" else "") to {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            texts.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val name = f.label ?: languageName(f.language)
                    entries += ("Sous-titres · $name" + if (g.isTrackSelected(i)) "  ✓" else "") to {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
        }
        if (entries.size <= 1) vm.toast("Pas d'autre piste audio ou de sous-titres")
        else vm.ask(Choice("Audio et sous-titres", entries.map { it.first }) { entries[it].second() })
    }

    fun seekBy(ms: Long) {
        if (ui.liveStream) return
        val target = (exo.currentPosition + ms).coerceIn(0, (exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        exo.seekTo(target)
        ui.position = target
        ui.seekFlash = (if (ms > 0) "+" else "−") + "${kotlin.math.abs(ms) / 1000} s"
        jobs.flash?.cancel()
        jobs.flash = scope.launch { delay(900); ui.seekFlash = null }
        showControls()
    }

    fun saveResume() {
        val r = request as? PlayerRequest.Vod ?: return
        if (ui.liveStream || ui.duration <= 0) return
        vm.store.saveResume(
            ResumeEntry(
                url = r.url, label = r.title.ifBlank { ui.title }, subtitle = r.subtitle, image = r.image.ifBlank { ui.image },
                position = exo.currentPosition, duration = ui.duration, updatedAt = System.currentTimeMillis(),
                selected = r.selected,
            )
        )
    }

    fun retry() {
        ui.attempts++
        when (request) {
            is PlayerRequest.Live -> vm.channel(ui.shownId ?: ui.tunedId)?.let { tune(it, true) }
            else -> { ui.error = null; ui.loading = true; exo.prepare(); exo.play() }
        }
    }

    // ------------------------------------------------------------- start
    LaunchedEffect(Unit) {
        subtitlesOn = runCatching { vm.engine.getSetting("active_subtitle") == "true" }.getOrDefault(false)
        runCatching { rootFocus.requestFocus() }
        when (request) {
            is PlayerRequest.Live -> {
                val c = vm.channel(request.channelId) ?: ordered().firstOrNull()
                if (c == null) { ui.loading = false; ui.error = "Aucune chaîne disponible." } else tune(c, true)
            }
            is PlayerRequest.Vod -> {
                ui.title = request.title; ui.subtitle = request.subtitle; ui.image = request.image
                val result = vm.resolve(request.url, request.selected)
                val stream = result.stream
                if (result.kind == "resolved" && stream != null) {
                    if (ui.title.isBlank()) ui.title = stream.label
                    if (ui.image.isBlank()) ui.image = stream.art["fanart"] ?: stream.art["thumb"] ?: ""
                    val start = vm.store.resumePosition(request.url)
                    play(stream, start)
                    if (start > 0) vm.toast("Reprise à ${Fmt.clock(start)}", "Maintenez OK sur la carte pour recommencer.")
                } else {
                    ui.loading = false
                    ui.error = result.error ?: result.notifications.lastOrNull()?.message ?: "Lecture impossible."
                }
                showControls()
            }
            is PlayerRequest.Direct -> {
                ui.title = request.title.ifBlank { request.stream.label }
                play(request.stream)
                showControls()
            }
        }
    }

    // Position polling + periodic resume save
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            ui.position = exo.currentPosition
            ui.duration = exo.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
            ui.liveStream = isLive || exo.isCurrentMediaItemLive
            if (++ticks % 30 == 0 && !isLive) saveResume()
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> { ui.loading = false; ui.attempts = 0 }
                    Player.STATE_BUFFERING -> ui.loading = true
                    Player.STATE_ENDED -> if (!isLive) {
                        (request as? PlayerRequest.Vod)?.let { vm.store.removeResume(it.url) }
                        onClose()
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.playing = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition(); exo.prepare(); return
                }
                // A live stream may drop: retry once silently
                if (isLive && ui.attempts == 0 && error.errorCode in NETWORK_ERRORS) { retry(); return }
                ui.loading = false
                ui.error = friendlyError(error)
            }
        }
        exo.addListener(listener)
        onDispose {
            saveResume()
            exo.removeListener(listener)
            exo.release()
        }
    }

    // Pause when the TV goes to standby / another app takes over
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { saveResume(); exo.pause() }
                Lifecycle.Event.ON_START -> if (isLive) { exo.seekToDefaultPosition(); exo.play() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // ------------------------------------------------------------ keys
    fun onKey(code: Int, repeat: Int): Boolean {
        val digit = when (code) {
            in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> code - AndroidKey.KEYCODE_0
            in AndroidKey.KEYCODE_NUMPAD_0..AndroidKey.KEYCODE_NUMPAD_9 -> code - AndroidKey.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (code == AndroidKey.KEYCODE_BACK || code == AndroidKey.KEYCODE_ESCAPE) {
            when {
                ui.number.isNotEmpty() -> { ui.number = ""; jobs.number?.cancel() }
                ui.zapList -> { ui.zapList = false; runCatching { rootFocus.requestFocus() } }
                ui.info -> ui.info = false
                isLive && ui.banner -> ui.banner = false
                else -> onClose()
            }
            return true
        }
        if (isLive) {
            if (digit >= 0) { typeDigit(digit); return true }
            if (ui.zapList) return false   // the list handles navigation itself
            when (code) {
                AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP, AndroidKey.KEYCODE_PAGE_UP -> { ui.info = false; zap(+1) }
                AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_CHANNEL_DOWN, AndroidKey.KEYCODE_PAGE_DOWN -> { ui.info = false; zap(-1) }
                AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER -> when {
                    ui.error != null -> retry()
                    ui.info -> openTracks()
                    ui.banner -> { ui.zapList = true }
                    else -> showBanner()
                }
                AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_GUIDE -> { ui.info = false; ui.zapList = true }
                AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_INFO -> {
                    ui.info = !ui.info
                    if (ui.info) { ui.banner = true; jobs.banner?.cancel() } else showBanner()
                }
                AndroidKey.KEYCODE_LAST_CHANNEL -> vm.channel(vm.store.previousChannelId)?.let { tune(it, true) }
                AndroidKey.KEYCODE_MENU, AndroidKey.KEYCODE_CAPTIONS -> openTracks()
                else -> return false
            }
            return true
        }
        // ---- VOD
        when (code) {
            AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER -> when {
                ui.error != null -> retry()
                !ui.controls -> showControls()
                else -> { if (exo.isPlaying) exo.pause() else exo.play(); showControls() }
            }
            AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE -> { if (exo.isPlaying) exo.pause() else exo.play(); showControls() }
            AndroidKey.KEYCODE_MEDIA_PLAY -> { exo.play(); showControls() }
            AndroidKey.KEYCODE_MEDIA_PAUSE -> { exo.pause(); showControls() }
            AndroidKey.KEYCODE_DPAD_LEFT -> seekBy(-(if (repeat > 6) 60_000L else if (repeat > 0) 30_000L else 10_000L))
            AndroidKey.KEYCODE_DPAD_RIGHT -> seekBy(if (repeat > 6) 60_000L else if (repeat > 0) 30_000L else 10_000L)
            AndroidKey.KEYCODE_MEDIA_REWIND -> seekBy(-30_000)
            AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
            AndroidKey.KEYCODE_DPAD_UP -> showControls()
            AndroidKey.KEYCODE_DPAD_DOWN -> if (ui.controls) openTracks() else showControls()
            AndroidKey.KEYCODE_MENU, AndroidKey.KEYCODE_CAPTIONS -> openTracks()
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------- layout
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                if (e.type == KeyEventType.KeyDown) onKey(code, e.nativeKeyEvent.repeatCount)
                else code == AndroidKey.KEYCODE_BACK || code == AndroidKey.KEYCODE_ESCAPE ||
                    (!ui.zapList && code != AndroidKey.KEYCODE_UNKNOWN)
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = exo
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val shown = vm.channel(ui.shownId)

        // Loading
        if (ui.loading && ui.error == null) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isLive && shown != null) {
                        ChannelLogo(shown.logo, size = 96.dp, background = Color(0x33FFFFFF))
                        Spacer(Modifier.height(20.dp))
                    }
                    Spinner(size = 40.dp)
                }
            }
        }

        // Error
        ui.error?.let { message ->
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(Z.Surface).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isLive && shown != null) ChannelLogo(shown.logo, size = 72.dp)
                    else Icon(Icons.Filled.Warning, null, tint = Z.Live, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Lecture impossible", style = ZType.Title, color = Z.Text)
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = ZType.Body, color = Z.Muted, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (isLive) "OK pour réessayer  ·  ▲ ▼ pour changer de chaîne" else "OK pour réessayer  ·  Retour pour quitter",
                        style = ZType.Small, color = Z.Faint,
                    )
                }
            }
        }

        if (isLive) {
            LiveBanner(vm, ui, shown, now, Modifier.align(Alignment.BottomCenter))
            AnimatedVisibility(
                ui.info && shown != null, modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
            ) { shown?.let { InfoPanel(vm, it, now) } }
            AnimatedVisibility(
                ui.zapList, modifier = Modifier.align(Alignment.CenterStart),
                enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
            ) {
                ZapList(vm, ordered(), ui.tunedId, now) { c ->
                    ui.zapList = false
                    runCatching { rootFocus.requestFocus() }
                    if (c.id != ui.tunedId) tune(c, true) else showBanner()
                }
            }
            if (ui.number.isNotEmpty()) NumberBox(ui.number, channels, Modifier.align(Alignment.TopEnd))
        } else {
            VodControls(ui, Modifier.align(Alignment.BottomCenter))
            ui.seekFlash?.let {
                Text(
                    it, style = ZType.Headline, color = Color.White,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99000000)).padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private val NETWORK_ERRORS = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
)

private fun friendlyError(e: PlaybackException): String {
    val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    return when {
        http == 403 || http == 401 -> "Accès refusé par la chaîne ($http). Le flux est peut-être géo-restreint ou réservé aux abonnés."
        http == 404 || http == 410 -> "Ce flux n'est plus disponible ($http)."
        http != null -> "Le serveur de la chaîne a répondu une erreur $http."
        e.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
            "Ce programme est protégé (DRM) et la licence n'a pas pu être obtenue sur cet appareil."
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Connexion au flux impossible. Vérifiez votre accès internet."
        e.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Ce format vidéo n'est pas pris en charge par votre appareil."
        else -> "Erreur de lecture (${e.errorCodeName})."
    }
}

private fun languageName(code: String?): String {
    if (code.isNullOrBlank() || code == "und") return "Par défaut"
    if (code == "qad" || code == "qaa") return "Audiodescription"
    val name = Locale(code).getDisplayLanguage(Locale.FRENCH)
    return if (name.isBlank() || name == code) code.uppercase() else name.replaceFirstChar { it.uppercase() }
}

// =========================================================== live overlays

@Composable
private fun LiveBanner(vm: AppViewModel, ui: PlayerUi, channel: Channel?, now: Long, modifier: Modifier) {
    AnimatedVisibility(
        visible = ui.banner && channel != null, modifier = modifier,
        enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
    ) {
        val c = channel ?: return@AnimatedVisibility
        val current = vm.nowPlaying(c, now)
        val next = vm.nextUp(c, now)
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0xB3000000), 1f to Color(0xF2000000))
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = Z.Gutter, end = Z.Gutter, top = 72.dp, bottom = 36.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(Z.Surface3),
                    contentAlignment = Alignment.Center,
                ) { Text("${c.number}", style = ZType.Headline.copy(fontSize = 30.sp), color = Color.White) }
                Spacer(Modifier.width(14.dp))
                ChannelLogo(c.logo, size = 72.dp, background = Color(0x26FFFFFF))
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, style = ZType.Label, color = Z.Muted)
                        Spacer(Modifier.width(10.dp))
                        LiveBadge()
                        if (ui.tunedId != c.id || ui.loading) {
                            Spacer(Modifier.width(10.dp))
                            Spinner(size = 16.dp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        current?.title ?: c.label, style = ZType.Headline, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (current != null) {
                        val meta = listOf(current.subtitle, current.prettyCategory, Fmt.range(current.start, current.stop))
                            .filter { it.isNotBlank() }.joinToString("  ·  ")
                        Text(meta, style = ZType.Label, color = Z.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(current.progress(now), Modifier.width(420.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(Fmt.remaining(current.stop, now), style = ZType.Small, color = Z.Muted)
                        }
                    }
                    if (next != null) {
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Text("À suivre  ", style = ZType.Small, color = Z.Live)
                            SingleLine("${Fmt.time(next.start)}  ${next.title}", ZType.Small, Z.Text)
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Clock()
                    Spacer(Modifier.height(16.dp))
                    Text("▲▼ zapper   OK chaînes   ▶ infos", style = ZType.Small, color = Z.Faint)
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(vm: AppViewModel, c: Channel, now: Long) {
    val current = vm.nowPlaying(c, now)
    val upcoming = vm.programmes(c).filter { it.start >= (current?.stop ?: now) }.take(5)
    Column(
        Modifier.width(470.dp).fillMaxHeight().background(Color(0xF0101217)).padding(28.dp),
    ) {
        if (current != null) {
            ZImage(current.icon.ifBlank { c.fanart }, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.height(16.dp))
            Text(current.title, style = ZType.Title, color = Z.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOf(current.prettyCategory, current.episode, current.rating, Fmt.range(current.start, current.stop))
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, style = ZType.Small, color = Z.Muted)
            Spacer(Modifier.height(10.dp))
            Text(
                current.desc.ifBlank { "Pas de résumé." }, style = ZType.Body.copy(fontSize = 14.sp), color = Z.Muted,
                maxLines = 7, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(c.label, style = ZType.Title, color = Z.Text)
            Text("Programme non communiqué", style = ZType.Body, color = Z.Muted)
        }
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("ENSUITE", style = ZType.Small.copy(letterSpacing = 1.4.sp), color = Z.Live)
            Spacer(Modifier.height(6.dp))
            upcoming.forEach { p ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(Fmt.time(p.start), style = ZType.Number.copy(fontSize = 14.sp), color = Z.Text, modifier = Modifier.width(56.dp))
                    SingleLine(p.title, ZType.Body.copy(fontSize = 14.sp), Z.Muted)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("OK : audio et sous-titres", style = ZType.Small, color = Z.Faint)
    }
}

@Composable
private fun ZapList(vm: AppViewModel, channels: List<Channel>, tunedId: String?, now: Long, onPick: (Channel) -> Unit) {
    val start = channels.indexOfFirst { it.id == tunedId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    Column(Modifier.width(470.dp).fillMaxHeight().background(Color(0xF00B0C10))) {
        Row(Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chaînes", style = ZType.Title, color = Z.Text)
            Spacer(Modifier.weight(1f))
            Clock()
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.id }) { c ->
                val p = vm.nowPlaying(c, now)
                FocusSurface(
                    onClick = { onPick(c) },
                    modifier = Modifier.fillMaxWidth().then(if (c.id == channels.getOrNull(start)?.id) Modifier.focusRequester(focus) else Modifier),
                    shape = RoundedCornerShape(12.dp), focusedScale = 1.02f,
                    container = if (c.id == tunedId) Z.LiveSoft else Color.Transparent, focusedContainer = Z.Surface3,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.number}", style = ZType.Number, color = if (c.id == tunedId) Z.Live else Z.Faint, modifier = Modifier.width(34.dp))
                        ChannelLogo(c.logo, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            SingleLine(p?.title ?: c.label, ZType.Label, Z.Text)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SingleLine(c.label, ZType.Small, Z.Muted, Modifier.weight(1f, fill = false))
                                if (p != null) {
                                    Spacer(Modifier.width(8.dp))
                                    ProgressLine(p.progress(now), Modifier.width(70.dp), height = 3.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun NumberBox(typed: String, channels: List<Channel>, modifier: Modifier) {
    val match = typed.toIntOrNull()?.let { n -> channels.firstOrNull { it.number == n } }
    Row(
        modifier.padding(top = 32.dp, end = Z.Gutter).clip(RoundedCornerShape(18.dp)).background(Color(0xE6101217))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(typed, style = ZType.Display.copy(fontSize = 44.sp, fontFeatureSettings = "tnum"), color = Color.White)
        if (match != null) {
            Spacer(Modifier.width(16.dp))
            ChannelLogo(match.logo, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Text(match.label, style = ZType.Label, color = Z.Muted)
        } else if (typed == "0") {
            Spacer(Modifier.width(16.dp))
            Text("Chaîne précédente", style = ZType.Label, color = Z.Muted)
        }
    }
}

// ================================================================ VOD

@Composable
private fun VodControls(ui: PlayerUi, modifier: Modifier) {
    AnimatedVisibility(
        visible = ui.controls || !ui.playing && !ui.loading && ui.error == null, modifier = modifier,
        enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut(),
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color(0xA6000000), 1f to Color(0xF2000000))
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(start = Z.Gutter, end = Z.Gutter, top = 90.dp, bottom = 34.dp)) {
                if (ui.subtitle.isNotBlank()) Text(ui.subtitle.uppercase(), style = ZType.Small.copy(letterSpacing = 1.2.sp), color = Z.Live)
                Text(ui.title, style = ZType.Headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        if (ui.playing) PauseGlyph(Modifier.height(22.dp))
                        else Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    if (ui.liveStream) {
                        LiveBadge()
                        Spacer(Modifier.weight(1f))
                    } else {
                        Text(Fmt.clock(ui.position), style = ZType.Number, color = Color.White)
                        Spacer(Modifier.width(14.dp))
                        ProgressLine(
                            if (ui.duration > 0) ui.position.toFloat() / ui.duration else 0f,
                            Modifier.weight(1f), height = 6.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text("−" + Fmt.clock(ui.duration - ui.position), style = ZType.Number, color = Z.Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    Text(
                        if (ui.liveStream) "OK lecture/pause   ▼ audio et sous-titres"
                        else "OK lecture/pause   ◀ ▶ ±10 s (maintenir pour accélérer)   ▼ audio et sous-titres",
                        style = ZType.Small, color = Z.Faint,
                    )
                    Spacer(Modifier.weight(1f))
                    Clock()
                }
            }
        }
    }
}
