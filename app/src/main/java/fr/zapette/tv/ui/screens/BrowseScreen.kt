package fr.zapette.tv.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.BrowseState
import fr.zapette.tv.ui.components.EmptyState
import fr.zapette.tv.ui.components.MediaCard
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.components.ZButton
import fr.zapette.tv.ui.components.restoreFocus
import fr.zapette.tv.ui.components.subtitleOf
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/**
 * Any directory of the add-on (replay channels, shows, episodes, websites…),
 * rendered as a grid of cards with the focused item described on top.
 */
@Composable
fun BrowseScreen(vm: AppViewModel, state: BrowseState, embedded: Boolean) {
    val resume by vm.store.resume.collectAsState()

    LaunchedEffect(state) {
        if (!state.loaded && !state.loading) vm.load(state)
    }

    val items = state.items
    val logoMode = items.isNotEmpty() && items.count { it.hasLogoArt } * 2 >= items.size
    val columns = if (logoMode) 5 else 4
    var restored by remember(state) { mutableStateOf(false) }
    val canRestore = if (embedded) vm.restoreFocus else !restored

    Column(Modifier.fillMaxSize()) {
        // Reads the focused item itself: a focus move redraws the header, not the grid
        BrowseHeader(state, embedded)

        // -------------------------------------------------------- body
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && items.isEmpty() -> Row(
                    Modifier.padding(horizontal = Z.Gutter, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(size = 28.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Chargement…", style = ZType.Body, color = Z.Muted)
                }
                state.error != null && items.isEmpty() -> {
                    val focus = remember { FocusRequester() }
                    EmptyState("Oups", state.error ?: "") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ZButton("Réessayer", onClick = { vm.load(state) }, icon = Icons.Filled.Refresh, primary = true,
                                modifier = Modifier.focusRequester(focus))
                            if (!embedded) ZButton("Retour", onClick = { vm.onBack() })
                        }
                    }
                    LaunchedEffect(state.error) { if (!embedded || vm.restoreFocus) runCatching { focus.requestFocus() } }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = state.grid,
                    contentPadding = PaddingValues(start = Z.Gutter, end = Z.Gutter, top = 8.dp, bottom = 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { i, it -> "$i|${it.url}" }, contentType = { _, _ -> "card" }) { index, item ->
                        MediaCard(
                            item = item,
                            onClick = { vm.open(item, state.title) },
                            onLongClick = { vm.showContextMenu(item, state) },
                            onFocus = {
                                state.focusedIndex = index
                                state.focused = item
                                // Infinite scroll: fetch the next page when the focus gets close to the end
                                if (index >= state.items.size - columns * 2 && state.nextPage != null && !state.loadingMore) {
                                    vm.load(state, more = true)
                                }
                                vm.backdrop = if (item.hasLogoArt) null else item.backdrop
                            },
                            resumeProgress = resume.firstOrNull { it.url == item.url }?.progress ?: 0f,
                            modifier = Modifier.restoreFocus(canRestore && index == state.focusedIndex.coerceAtMost(items.lastIndex)) {
                                restored = true
                                if (embedded) vm.restoreFocus = false
                            },
                        )
                    }
                    if (state.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spinner(size = 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Plus de contenus…", style = ZType.Label, color = Z.Muted)
                        }
                    }
                }
            }
        }
    }
}

/** Title of the folder, then the title, subtitle and summary of the focused item. */
@Composable
private fun BrowseHeader(state: BrowseState, embedded: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (embedded) 150.dp else 186.dp)
            .padding(start = Z.Gutter, end = Z.Gutter, top = if (embedded) 8.dp else Z.GutterTop)
    ) {
        Text(
            state.title.ifBlank { "Catch-up TV & More" }.uppercase(),
            style = ZType.Small.copy(letterSpacing = 1.5.sp), color = Z.Live, maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        val f = state.focused
        Text(
            f?.title ?: state.title, style = ZType.Headline, color = Z.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp),
        )
        if (f != null) {
            val sub = subtitleOf(f)
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(sub, style = ZType.Label, color = Z.Muted, maxLines = 1)
            }
            val plot = f.info.plot ?: f.info.plotoutline
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    plot, style = ZType.Body, color = Z.Muted, maxLines = if (embedded) 2 else 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 700.dp),
                )
            }
        }
    }
}
