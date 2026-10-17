package fr.zapette.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.ui.isBack
import fr.zapette.tv.ui.isOk
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/** Keys that can't be guessed, in the order they are used. */
private val Tips = listOf(
    "CH+  CH−" to "Zapper",
    "1 … 9" to "Aller à une chaîne  ·  0 : chaîne précédente",
    "OK maintenu" to "Options d'une vidéo (favoris…)",
)

/**
 * One screen shown at the first start (and from the settings): the few keys
 * that can't be guessed, readable at a glance. OK or Retour closes it.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Z.Bg)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val e = event.nativeKeyEvent
                if (event.type == KeyEventType.KeyDown && e.repeatCount == 0 && (e.isOk() || e.isBack())) onDone()
                true   // nothing reaches the screens behind
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Les touches à connaître", style = ZType.Headline, color = Z.Text)
            Spacer(Modifier.height(28.dp))

            // The mini-player: the one thing nobody guesses
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Z.LiveSoft)
                    .border(2.dp, Z.Live, RoundedCornerShape(20.dp)).padding(horizontal = 32.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TipRow("Retour", "En direct : la chaîne continue dans un coin", big = true)
                TipRow("OK  OK", "Deux appuis rapides : retour en plein écran", big = true)
            }
            Spacer(Modifier.height(22.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Tips.forEach { (keys, does) -> TipRow(keys, does) }
            }
            Spacer(Modifier.height(34.dp))
            Text("OK pour commencer", style = ZType.Label, color = Z.Muted)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun TipRow(keys: String, does: String, big: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(150.dp)) {
            Text(
                keys, style = ZType.Label.copy(fontSize = if (big) 18.sp else 16.sp), color = Color.White, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (big) Z.Live else Z.Surface3)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Text(does, style = ZType.Body.copy(fontSize = if (big) 20.sp else 17.sp), color = if (big) Z.Text else Z.Muted)
    }
}
