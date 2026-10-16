package fr.zapette.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.components.Wordmark
import fr.zapette.tv.ui.components.ZButton
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

@Composable
fun SplashScreen(boot: AppViewModel.Boot, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Wordmark()
            Spacer(Modifier.height(40.dp))
            when (boot) {
                is AppViewModel.Boot.Failed -> {
                    Text("Impossible de démarrer", style = ZType.Title, color = Z.Text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        boot.message, style = ZType.Body, color = Z.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    val focus = remember { FocusRequester() }
                    ZButton("Réessayer", onClick = onRetry, icon = Icons.Filled.Refresh, primary = true,
                        modifier = Modifier.focusRequester(focus))
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
                is AppViewModel.Boot.Loading -> {
                    Spinner(size = 34.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(boot.message, style = ZType.Label.copy(fontSize = 15.sp), color = Z.Muted)
                }
                AppViewModel.Boot.Ready -> Unit
            }
        }
    }
}
