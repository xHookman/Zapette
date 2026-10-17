package fr.zapette.tv.ui.screens

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.Perf
import fr.zapette.tv.ui.components.Wordmark
import fr.zapette.tv.ui.isBack
import fr.zapette.tv.ui.isOk
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType

/** A key of the remote drawn on the right of the presentation. */
private enum class Key { Back, Menu, Up, Down, Left, Right, Ok, Play, Subtitles, ChUp, ChDown, Digits, Zero }

/** One line of a page: the key(s) to press, and what it does. */
private class Tip(val keys: String, val does: String)

private class Page(val kicker: String, val title: String, val tips: List<Tip>, val lit: Set<Key>, val note: String = "")

/** Five short pages: only what is needed to use the app, nothing else. */
private val Pages = listOf(
    Page(
        "Bienvenue", "La télé, simplement.",
        listOf(
            Tip("", "Les chaînes de la TNT, le guide TV et le replay, à la télécommande."),
            Tip("", "L'essentiel en 5 écrans. Moins d'une minute."),
        ),
        lit = setOf(Key.Ok), note = "Appuyez sur OK pour continuer",
    ),
    Page(
        "1 · Zapper", "Comme sur votre téléviseur.",
        listOf(
            Tip("CH+  CH−", "Chaîne suivante ou précédente"),
            Tip("1 … 9", "Tapez le numéro : 2 pour France 2, même depuis les menus"),
            Tip("0", "Revient à la chaîne d'avant"),
        ),
        lit = setOf(Key.ChUp, Key.ChDown, Key.Digits, Key.Zero),
        note = "Les flèches ▲ ▼ ne changent pas de chaîne : pas de zapping par erreur.",
    ),
    Page(
        "2 · Pendant une émission", "Tout est à portée de pouce.",
        listOf(
            Tip("OK", "Ce qui passe maintenant et après, puis la liste des chaînes"),
            Tip("◀", "Liste des chaînes"),
            Tip("▶", "Fiche du programme"),
            Tip("Sous-titres", "Active ou coupe les sous-titres (Menu : langue et audio)"),
        ),
        lit = setOf(Key.Ok, Key.Left, Key.Right, Key.Subtitles, Key.Menu),
    ),
    Page(
        "3 · Le mini-lecteur", "La chaîne continue pendant que vous cherchez.",
        listOf(
            Tip("Retour", "Quitte le plein écran : la chaîne reste dans un coin"),
            Tip("OK  OK", "Deux appuis rapides : retour en plein écran (Lecture aussi)"),
            Tip("Retour", "Depuis l'accueil : ferme le mini-lecteur"),
        ),
        lit = setOf(Key.Back, Key.Ok, Key.Play),
    ),
    Page(
        "4 · Replay", "Tout ce que vous avez raté.",
        listOf(
            Tip("Replay", "L'onglet du haut : chaque chaîne, chaque émission"),
            Tip("OK maintenu", "Options d'une vidéo (favoris, masquer…)"),
            Tip("◀  ▶", "En lecture : reculer ou avancer de 10 s (maintenir pour aller vite)"),
        ),
        lit = setOf(Key.Ok, Key.Left, Key.Right),
        note = "TF1+ et M6+ demandent un compte gratuit : Réglages → Comptes. Cette présentation : Réglages → À propos.",
    ),
)

/**
 * Presentation shown once, at the first start (and again from the settings).
 * OK / ▶ next page · ◀ previous page · Retour skips it.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val last = Pages.lastIndex

    fun onKey(e: KeyEvent): Boolean {
        when {
            e.isBack() -> onDone()
            e.isOk() || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (index == last) onDone() else index++
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) index--
            else -> return false
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1A0F0D), Z.Bg), radius = 1600f))
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val e = event.nativeKeyEvent
                // Every key stays here: nothing reaches the screens behind
                if (event.type == KeyEventType.KeyDown && e.repeatCount == 0) onKey(e)
                true
            }
            .focusable()
    ) {
        Wordmark(Modifier.align(Alignment.TopStart).padding(start = Z.Gutter, top = Z.GutterTop))

        AnimatedContent(
            targetState = index,
            transitionSpec = {
                if (Perf.lite) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    val way = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(260)) { it / 8 * way } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 8 * way } + fadeOut(tween(200)))
                }
            },
            label = "intro",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            PageView(Pages[shown])
        }

        Footer(index, last, Modifier.align(Alignment.BottomCenter))
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun PageView(page: Page) {
    Row(
        Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 56.dp, bottom = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(page.kicker.uppercase(), style = ZType.Small.copy(letterSpacing = 1.6.sp), color = Z.Live)
            Spacer(Modifier.height(10.dp))
            Text(page.title, style = ZType.Display, color = Z.Text)
            Spacer(Modifier.height(30.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                page.tips.forEach { TipLine(it) }
            }
            if (page.note.isNotBlank()) {
                Spacer(Modifier.height(28.dp))
                Text(page.note, style = ZType.Body, color = Z.Faint, modifier = Modifier.widthIn(max = 560.dp))
            }
        }
        Spacer(Modifier.width(48.dp))
        Remote(page.lit)
    }
}

@Composable
private fun TipLine(tip: Tip) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (tip.keys.isNotBlank()) {
            Box(Modifier.width(132.dp)) {
                Text(
                    tip.keys, style = ZType.Label.copy(fontSize = 16.sp), color = Color.White, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Z.Surface3)
                        .border(1.dp, Z.Line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(tip.does, style = ZType.Body.copy(fontSize = 19.sp, lineHeight = 26.sp), color = Z.Text,
            modifier = Modifier.widthIn(max = 540.dp))
    }
}

@Composable
private fun Footer(index: Int, last: Int, modifier: Modifier) {
    Row(
        modifier.padding(bottom = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..last) {
            Box(
                Modifier.padding(horizontal = 4.dp).size(width = if (i == index) 26.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape).background(if (i == index) Z.Live else Z.Surface3)
            )
        }
        Spacer(Modifier.width(24.dp))
        Text(
            if (index == last) "OK : c'est parti   ·   ◀ revenir" else "OK suivant   ·   ◀ revenir   ·   Retour : passer",
            style = ZType.Label, color = Z.Muted,
        )
    }
}

// ----------------------------------------------------------------- the remote

/** A simple TV remote; the keys of the current page glow. */
@Composable
private fun Remote(lit: Set<Key>) {
    // One soft pulse shared by all lit keys (still image in light mode)
    val glow = if (Perf.lite) 1f else {
        val pulse = rememberInfiniteTransition(label = "pulse")
        pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glow").value
    }

    @Composable
    fun Btn(key: Key, label: String, w: Dp = 44.dp, h: Dp = 24.dp, shape: Shape = RoundedCornerShape(12.dp)) {
        val on = key in lit
        Box(
            Modifier.size(w, h).clip(shape).background(if (on) Z.Live.copy(alpha = glow) else Color(0xFF2B2F38)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = ZType.Small.copy(fontSize = 11.sp), color = if (on) Color.White else Z.Muted, maxLines = 1)
        }
    }

    Column(
        Modifier.width(200.dp).clip(RoundedCornerShape(96.dp)).background(Color(0xFF16181E))
            .border(1.dp, Z.Line, RoundedCornerShape(96.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Btn(Key.Back, "Retour", w = 64.dp)
            Btn(Key.Menu, "Menu", w = 64.dp)
        }
        Spacer(Modifier.height(12.dp))

        // D-pad
        Box(Modifier.size(120.dp).clip(CircleShape).background(Color(0xFF22252D)), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) { Btn(Key.Up, "▲", 34.dp, 28.dp, CircleShape) }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) { Btn(Key.Down, "▼", 34.dp, 28.dp, CircleShape) }
            Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) { Btn(Key.Left, "◀", 28.dp, 34.dp, CircleShape) }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { Btn(Key.Right, "▶", 28.dp, 34.dp, CircleShape) }
            Btn(Key.Ok, "OK", 48.dp, 48.dp, CircleShape)
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn(Key.Play, "Lecture", w = 64.dp)
            Btn(Key.Subtitles, "CC", w = 52.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Btn(Key.ChUp, "CH+", w = 52.dp)
            Btn(Key.ChDown, "CH−", w = 52.dp)
        }
        Spacer(Modifier.height(12.dp))

        // Number pad
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (col in 1..3) Btn(Key.Digits, "${row * 3 + col}", 36.dp, 22.dp, RoundedCornerShape(11.dp))
                }
            }
            Btn(Key.Zero, "0", 36.dp, 22.dp, RoundedCornerShape(11.dp))
        }
    }
}
