package fr.zapette.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.ChannelTile
import fr.zapette.tv.ui.components.EmptyState
import fr.zapette.tv.ui.components.restoreFocus
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/** All live channels, numbered like on a TV set. */
@Composable
fun LiveScreen(vm: AppViewModel) {
    val channels by vm.channels.collectAsState()
    val epg by vm.epg.collectAsState()
    val now by vm.now.collectAsState()

    if (channels.isEmpty()) {
        EmptyState("Aucune chaîne", "Choisissez un pays dans les réglages.")
        return
    }
    val target = vm.liveFocusId ?: vm.store.lastChannelId ?: channels.first().id

    LaunchedEffect(Unit) { vm.backdrop = null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Z.Gutter, end = Z.Gutter, top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
            Text("Chaînes en direct", style = ZType.Headline, color = Z.Text)
            Spacer(Modifier.width(14.dp))
            Text("${channels.size} chaînes · tapez un numéro pour zapper", style = ZType.Label, color = Z.Muted)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = vm.liveGridState,
            contentPadding = PaddingValues(start = Z.Gutter, end = Z.Gutter, bottom = 48.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(channels, key = { it.id }, contentType = { "channel" }) { c ->
                ChannelTile(
                    channel = c,
                    programme = epg[c.xmltvId]?.firstOrNull { it.isOnAt(now) },
                    now = now,
                    onClick = { vm.watch(c) },
                    // No ambient artwork behind this dense grid: it made scrolling heavy
                    onFocus = { vm.liveFocusId = c.id },
                    modifier = Modifier.restoreFocus(vm.restorePending && c.id == target) { vm.restorePending = false },
                )
            }
        }
    }
}
