package fr.zapette.tv.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.data.Channel
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.Hero
import fr.zapette.tv.ui.components.ChannelLogo
import fr.zapette.tv.ui.components.EmptyState
import fr.zapette.tv.ui.components.Fmt
import fr.zapette.tv.ui.components.LiveBadge
import fr.zapette.tv.ui.components.LiveCard
import fr.zapette.tv.ui.components.LogoTile
import fr.zapette.tv.ui.components.Pill
import fr.zapette.tv.ui.components.ProgressLine
import fr.zapette.tv.ui.components.ResumeCard
import fr.zapette.tv.ui.components.SectionTitle
import fr.zapette.tv.ui.components.TonightCard
import fr.zapette.tv.ui.components.restoreFocus
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

private val CardWidth = 252.dp
private val RowPadding = PaddingValues(horizontal = 48.dp)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val channels by vm.channels.collectAsState()
    val epg by vm.epg.collectAsState()
    val now by vm.now.collectAsState()
    val resume by vm.resume.collectAsState()
    val replay = vm.replayRoot

    LaunchedEffect(replay) {
        if (replay != null && !replay.loaded && !replay.loading) vm.load(replay)
    }

    if (channels.isEmpty()) {
        EmptyState(
            "Aucune chaîne en direct",
            "Choisissez un pays dans les réglages ou vérifiez la connexion internet.",
        )
        return
    }

    if (vm.homeFocusKey == null) vm.homeFocusKey = "live:${channels.first().id}"

    fun liveHero(c: Channel) {
        val p = vm.nowPlaying(c, now)
        vm.hero = Hero(
            kicker = c.label, live = true, title = p?.title ?: c.label,
            meta = listOfNotNull(
                p?.prettyCategory?.takeIf { it.isNotBlank() },
                p?.let { Fmt.range(it.start, it.stop) },
                p?.let { Fmt.remaining(it.stop, now) },
            ).joinToString("  ·  "),
            description = p?.desc ?: "", logo = c.logo, progress = p?.progress(now),
        )
        vm.backdrop = p?.icon?.ifBlank { null } ?: c.fanart
    }

    // Prime time of the main channels: computed once per guide update, not at each frame
    val tonight = remember(channels, epg) {
        channels.filter { it.number in 1..27 }.mapNotNull { c -> vm.primeTime(c)?.let { c to it } }
    }

    Column(Modifier.fillMaxSize()) {
        HeroPanel(vm.hero)
        LazyColumn(
            state = vm.homeListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "live") {
                Column {
                    SectionTitle("En ce moment", trailing = Fmt.day(now))
                    LazyRow(
                        state = vm.rowState("live"),
                        contentPadding = RowPadding,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(channels, key = { it.id }) { c ->
                            val key = "live:${c.id}"
                            LiveCard(
                                channel = c, programme = epg[c.xmltvId]?.firstOrNull { it.isOnAt(now) }, now = now,
                                width = CardWidth,
                                onClick = { vm.watch(c) },
                                onFocus = { vm.homeFocusKey = key; liveHero(c) },
                                modifier = Modifier.restoreFocus(vm.restorePending && vm.homeFocusKey == key) { vm.restorePending = false },
                            )
                        }
                    }
                }
            }

            if (resume.isNotEmpty()) item(key = "resume") {
                Column {
                    SectionTitle("Reprendre la lecture")
                    LazyRow(state = vm.rowState("resume"), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(resume, key = { it.url }) { e ->
                            val key = "resume:${e.url}"
                            ResumeCard(
                                e, CardWidth,
                                onClick = { vm.resumeEntry(e) },
                                onLongClick = { vm.askRemoveResume(e) },
                                onFocus = {
                                    vm.homeFocusKey = key
                                    vm.hero = Hero(kicker = "Reprendre", title = e.label, meta = e.subtitle,
                                        description = "${Fmt.clock(e.position)} / ${Fmt.clock(e.duration)}", progress = e.progress)
                                    vm.backdrop = e.image.ifBlank { null }
                                },
                                modifier = Modifier.restoreFocus(vm.restorePending && vm.homeFocusKey == key) { vm.restorePending = false },
                            )
                        }
                    }
                }
            }

            if (tonight.isNotEmpty()) item(key = "tonight") {
                Column {
                    SectionTitle("Ce soir à la télé")
                    LazyRow(state = vm.rowState("tonight"), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(tonight, key = { it.first.id }) { (c, p) ->
                            val key = "tonight:${c.id}"
                            TonightCard(
                                c, p, CardWidth,
                                onClick = {
                                    if (p.isOnAt(AppViewModel.nowSec())) vm.watch(c)
                                    else vm.toast("${p.title}", "À ${Fmt.time(p.start)} sur ${c.label}")
                                },
                                onFocus = {
                                    vm.homeFocusKey = key
                                    vm.hero = Hero(kicker = "Ce soir · ${c.label}", title = p.title,
                                        meta = listOf(p.prettyCategory, Fmt.range(p.start, p.stop)).filter { it.isNotBlank() }.joinToString("  ·  "),
                                        description = p.desc, logo = c.logo)
                                    vm.backdrop = p.icon.ifBlank { null } ?: c.fanart
                                },
                                modifier = Modifier.restoreFocus(vm.restorePending && vm.homeFocusKey == key) { vm.restorePending = false },
                            )
                        }
                    }
                }
            }

            if (replay != null && replay.items.isNotEmpty()) item(key = "replay") {
                Column {
                    SectionTitle("Replay", trailing = "toutes les chaînes")
                    LazyRow(state = vm.rowState("replay"), contentPadding = RowPadding, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(replay.items, key = { it.url }) { item ->
                            val key = "replay:${item.url}"
                            LogoTile(
                                item, 196.dp,
                                onClick = { vm.open(item, "Replay") },
                                onFocus = {
                                    vm.homeFocusKey = key
                                    vm.hero = Hero(kicker = "Replay", title = item.title,
                                        description = "Émissions, séries et documentaires disponibles en rattrapage.", logo = item.thumb ?: "")
                                    vm.backdrop = item.art["fanart"]
                                },
                                modifier = Modifier.restoreFocus(vm.restorePending && vm.homeFocusKey == key) { vm.restorePending = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Large text block describing the focused card (Google TV style). */
@Composable
fun HeroPanel(hero: Hero?) {
    Box(Modifier.fillMaxWidth().height(196.dp).padding(start = Z.Gutter, end = Z.Gutter, top = 12.dp)) {
        AnimatedContent(targetState = hero, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hero") { h ->
            if (h != null) {
                Column(Modifier.widthIn(max = 620.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (h.logo.isNotBlank()) {
                            ChannelLogo(h.logo, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        if (h.live) {
                            LiveBadge()
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(h.kicker, style = ZType.Label, color = Z.Muted, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        h.title, style = ZType.Display.copy(fontSize = 34.sp, lineHeight = 40.sp), color = Z.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (h.meta.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(h.meta, style = ZType.Label, color = Z.Muted, maxLines = 1)
                    }
                    if (h.progress != null) {
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(h.progress, Modifier.width(260.dp))
                    }
                    if (h.description.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            h.description, style = ZType.Body, color = Z.Muted.copy(alpha = 0.9f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Pill("Bienvenue", color = Z.Surface2)
            }
        }
    }
}
