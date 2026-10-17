@file:OptIn(ExperimentalComposeUiApi::class)

package fr.zapette.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.Tab
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/* Top bar: logo, tabs and clock. */

// ------------------------------------------------------------------ top bar

@Composable
fun TopBar(vm: AppViewModel) {
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Z.Gutter, end = Z.Gutter, top = Z.GutterTop, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        Spacer(Modifier.width(36.dp))
        Row(
            Modifier
                .focusProperties { enter = { requesters.getValue(vm.tab) } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.filter { it != Tab.Settings }.forEach { tab ->
                TabPill(tab.label, selected = vm.tab == tab, requester = requesters.getValue(tab),
                    onFocus = { vm.previewTab(tab) }) { vm.selectTab(tab) }
            }
            Spacer(Modifier.width(18.dp))
            TabPill(null, selected = vm.tab == Tab.Settings, requester = requesters.getValue(Tab.Settings),
                onFocus = { vm.previewTab(Tab.Settings) }) {
                vm.selectTab(Tab.Settings)
            }
        }
        Spacer(Modifier.weight(1f))
        Clock()
    }
}

@Composable
private fun TabPill(
    label: String?,
    selected: Boolean,
    requester: FocusRequester,
    onFocus: () -> Unit,
    onSelect: () -> Unit,
) {
    FocusSurface(
        onClick = onSelect,
        onFocus = onFocus,  // like Google TV: resting on a tab shows it
        modifier = Modifier.height(40.dp).focusRequester(requester),
        shape = RoundedCornerShape(20.dp),
        focusedScale = 1.04f,
        container = if (selected) Color(0x24FFFFFF) else Color.Transparent,
        focusedContainer = Color.White,
        ring = false,
    ) { focused ->
        val color = when {
            focused -> Color.Black
            selected -> Z.Text
            else -> Z.Muted
        }
        Box(Modifier.padding(horizontal = if (label == null) 10.dp else 18.dp).align(Alignment.Center)) {
            if (label == null) {
                Icon(Icons.Filled.Settings, contentDescription = "Réglages", tint = color, modifier = Modifier.size(22.dp))
            } else {
                Text(label, style = ZType.Label.copy(fontSize = 16.sp), color = color)
            }
        }
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(6.dp)).background(Z.Live),
            contentAlignment = Alignment.Center,
        ) {
            Text("⚡", fontSize = 12.sp, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Text("zapette", style = ZType.Title.copy(fontSize = 22.sp, letterSpacing = (-0.3).sp), color = Z.Text)
    }
}
