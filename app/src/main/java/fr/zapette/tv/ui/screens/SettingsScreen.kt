package fr.zapette.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import fr.zapette.tv.BuildConfig
import fr.zapette.tv.Perf
import fr.zapette.tv.data.SettingCategory
import fr.zapette.tv.data.SettingEntry
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.Choice
import fr.zapette.tv.ui.TextAsk
import fr.zapette.tv.ui.components.FocusSurface
import fr.zapette.tv.ui.components.Spinner
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.launch

/** One line of the settings list: a section title or a setting. */
private sealed interface Line {
    data class Header(val label: String) : Line
    data class Item(
        val label: String,
        val value: String = "",
        val toggle: Boolean? = null,
        val description: String = "",
        val onClick: () -> Unit,
    ) : Line
}

/**
 * Settings: Zapette's own options first, then one page per category of the
 * add-on's settings.xml (accounts, quality…).
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var schema by remember { mutableStateOf<List<SettingCategory>?>(null) }
    var page by remember { mutableIntStateOf(0) }
    /** Bumped after an add-on setting changed: reloads the add-on's settings. */
    var addonRevision by remember { mutableIntStateOf(0) }
    /** Bumped after a Zapette option changed: redraws the list (options are not Compose state). */
    var localRevision by remember { mutableIntStateOf(0) }
    val countries by vm.live.countries.collectAsState()

    LaunchedEffect(addonRevision) {
        schema = runCatching { vm.engine.settingsSchema() }.getOrElse { emptyList() }
        vm.updates.refreshAddonInfo()
    }
    LaunchedEffect(Unit) { vm.backdrop = null }

    val accounts = remember(schema) { schema.orEmpty().flatMap { accountsOf(it.entries) } }

    fun saveSetting(id: String, value: String) = scope.launch {
        vm.engine.setSetting(id, value)
        if (id == "active_subtitle") vm.syncSubtitles(value)
        addonRevision++
        // Login or password of a service changed: test them right away
        val account = accounts.firstOrNull { id == it.login.id || id == it.password.id } ?: return@launch
        val login = vm.engine.getSetting(account.login.id)
        val password = vm.engine.getSetting(account.password.id)
        if (login.isNotBlank() && password.isNotBlank()) vm.updates.checkAccount(account.service, account.name)
    }

    /** Copies the login and password of one service to the services the user picks. */
    fun copyAccount(accounts: List<Account>) {
        val filled = accounts.filter { it.login.value.isNotBlank() }
        if (filled.isEmpty()) {
            vm.messages.toast("Aucun compte à copier", "Renseignez d'abord l'identifiant et le mot de passe d'une chaîne.")
            return
        }
        vm.messages.ask(Choice("Copier le compte de…", filled.map { "${it.name}  ·  ${it.login.value}" }) { i ->
            val source = filled[i]
            val targets = accounts.filter { it !== source }
            vm.messages.ask(Choice(
                "Copier « ${source.login.value} » vers…",
                targets.map { t -> t.name + if (t.login.value.isNotBlank()) "  ·  remplace ${t.login.value}" else "" },
                multi = true,
                onMulti = { picked ->
                    val chosen = picked.mapNotNull { targets.getOrNull(it) }
                    if (chosen.isNotEmpty()) scope.launch {
                        chosen.forEach { t ->
                            vm.engine.setSetting(t.login.id, source.login.value)
                            vm.engine.setSetting(t.password.id, source.password.value)
                        }
                        vm.messages.toast("Compte copié", "${source.name} → " + chosen.joinToString(", ") { it.name })
                        addonRevision++
                        chosen.forEach { t -> vm.updates.checkAccount(t.service, t.name) }
                    }
                },
            ))
        })
    }

    fun zapetteLines(): List<Line> {
        val store = vm.store
        fun option(label: String, on: Boolean, description: String, flip: () -> Unit) =
            Line.Item(label, toggle = on, description = description) { flip(); localRevision++ }

        val country = countries.firstOrNull { it.id == store.liveCountry }?.label ?: store.liveCountry
        val lines = mutableListOf<Line>(
            Line.Header("Télévision"),
            option("Démarrer sur le direct", store.startOnLive,
                "À l'ouverture, Zapette reprend la dernière chaîne regardée, comme un téléviseur.") {
                store.startOnLive = !store.startOnLive
            },
            option("Bandeau d'information au zapping", store.bannerOnZap,
                "Affiche la chaîne et le programme en cours à chaque changement de chaîne.") {
                store.bannerOnZap = !store.bannerOnZap
            },
            option("Effet de zapping rétro", store.zapEffect,
                "Neige façon vieux téléviseur pendant le changement de chaîne.") {
                store.zapEffect = !store.zapEffect
            },
            option("Souffle pendant la neige", store.zapSound,
                "Le souffle discret d'un téléviseur entre deux chaînes, pendant l'effet de zapping.") {
                store.zapSound = !store.zapSound
            },
            option("Mini-lecteur en quittant le direct", store.miniOnBack,
                "Retour depuis une chaîne la garde dans un coin de l'écran pendant que vous parcourez les menus. " +
                    "OK deux fois ou Lecture : retour en plein écran. Retour (depuis l'accueil) : la ferme.") {
                store.miniOnBack = !store.miniOnBack
            },
            Line.Item("Chaînes en direct", value = country,
                description = "Bouquet utilisé pour l'accueil, la liste des chaînes et le guide TV.") {
                if (countries.isNotEmpty()) vm.messages.ask(Choice("Chaînes en direct", countries.map { it.label },
                    countries.indexOfFirst { it.id == store.liveCountry }) { i -> vm.changeCountry(countries[i].id); localRevision++ })
            },
            Line.Item(
                "Mode léger (TV peu puissante)",
                value = when (store.perfMode) {
                    "on" -> "Toujours"
                    "off" -> "Jamais"
                    else -> "Automatique · " + if (Perf.detectedWeak) "activé" else "désactivé"
                },
                description = "Moins d'animations, pas d'images d'ambiance, images plus légères, vidéo limitée à 720p. " +
                    "Pris en compte au prochain démarrage de Zapette.",
            ) {
                val modes = listOf("auto", "on", "off")
                vm.messages.ask(Choice("Mode léger", listOf("Automatique (recommandé)", "Toujours", "Jamais"),
                    modes.indexOf(store.perfMode)) { i ->
                    store.perfMode = modes[i]
                    localRevision++
                    vm.messages.toast("Mode léger", "Redémarrez Zapette pour l'appliquer.")
                })
            },
            Line.Header("Lecture"),
            Line.Item("Sous-titres", toggle = vm.subtitlesOn,
                description = "Affiche les sous-titres français quand le programme en propose. Pendant la lecture : " +
                    "touche Sous-titres de la télécommande, ou ▶ puis OK (Audio et sous-titres).") {
                vm.setSubtitles(!vm.subtitlesOn)
            },
            Line.Header("Catch-up TV & More"),
            Line.Item(
                "Version de l'extension",
                value = vm.updates.addonVersion + if (vm.updates.addonFromGitHub) " · GitHub" else " · intégrée",
                description = "L'extension est mise à jour indépendamment de l'application, directement depuis GitHub.",
            ) { vm.updates.checkAddon() },
            Line.Item("Rechercher une mise à jour", description = "Télécharge la dernière version publiée sur GitHub.") {
                vm.updates.checkAddon()
            },
        )
        if (vm.updates.addonFromGitHub) lines += Line.Item(
            "Revenir à la version intégrée", description = "En cas de problème après une mise à jour.",
        ) { vm.updates.updateAddon(revert = true) }
        lines += listOf(
            Line.Item("Vider le cache", description = "Supprime le cache des pages et le guide TV téléchargé.") { vm.updates.clearCache() },
            Line.Header("Application"),
            Line.Item(
                "Mettre à jour Zapette",
                value = vm.updates.appRelease?.takeIf { it.isNewer }?.let { "${it.versionName} disponible" }
                    ?: "Version ${BuildConfig.VERSION_NAME}",
                description = "Télécharge et installe la dernière version publiée sur GitHub. " +
                    "La première fois, Android demande d'autoriser Zapette à installer des applications.",
            ) { vm.updates.checkApp(manual = true) },
            Line.Header("À propos"),
            Line.Item("Revoir la présentation", description = "Les touches de la télécommande en 5 écrans.") { vm.showIntro() },
            Line.Item("Zapette ${BuildConfig.VERSION_NAME}",
                description = "Interface TV pour Catch-up TV & More. Les contenus sont fournis par les chaînes ; " +
                    "certains nécessitent un compte (TF1+, M6+…) à renseigner dans « Comptes ».") {},
        )
        return lines
    }

    fun addonLines(category: SettingCategory?): List<Line> {
        val entries = category?.entries.orEmpty()
        val pageAccounts = accountsOf(entries)
        val shortcuts = if (pageAccounts.size >= 2) listOf(
            Line.Header("Raccourci"),
            Line.Item("Copier un compte vers d'autres chaînes",
                description = "Reprend l'identifiant et le mot de passe d'une chaîne pour les chaînes que vous choisissez.") {
                copyAccount(pageAccounts)
            },
            Line.Item("Vérifier les comptes renseignés",
                description = "Teste la connexion de chaque compte et indique si l'identifiant ou le mot de passe est refusé.") {
                val filled = pageAccounts.filter { it.login.value.isNotBlank() && it.password.value.isNotBlank() }
                if (filled.isEmpty()) vm.messages.toast("Aucun compte renseigné")
                else scope.launch { filled.forEach { vm.updates.checkAccount(it.service, it.name) } }
            },
        ) else emptyList()
        return shortcuts + entries.mapNotNull { e -> addonLine(vm, e, ::saveSetting, onAction = { url -> vm.runAction(url) }) }
    }

    val pages = listOf("Zapette") + schema.orEmpty().map { it.label }
    // Rebuilt only when something shown in the list changed
    val lines = remember(
        page, schema, localRevision, countries,
        vm.subtitlesOn, vm.updates.addonVersion, vm.updates.addonFromGitHub, vm.updates.appRelease,
    ) {
        if (page == 0) zapetteLines() else addonLines(schema?.getOrNull(page - 1))
    }

    Row(Modifier.fillMaxSize().padding(start = Z.Gutter, end = Z.Gutter, top = 12.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            Text("Réglages", style = ZType.Headline, color = Z.Text)
            Spacer(Modifier.height(18.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(pages) { index, label ->
                    PageTab(label, selected = page == index) { page = index }
                }
                if (schema == null) item { Spinner(Modifier.padding(16.dp), size = 22.dp) }
            }
        }
        Spacer(Modifier.width(28.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 56.dp, bottom = 48.dp),
        ) {
            items(lines) { line ->
                when (line) {
                    is Line.Header -> Text(
                        line.label.uppercase(), style = ZType.Small.copy(letterSpacing = 1.4.sp), color = Z.Live,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                    )
                    is Line.Item -> SettingLine(line)
                }
            }
        }
    }
}

/** Entry of the left column; resting on it shows its page. */
@Composable
private fun PageTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    FocusSurface(
        onClick = onSelect, onFocus = onSelect,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusedScale = 1.03f,
        container = if (selected) Z.Surface2 else Color.Transparent,
        focusedContainer = Color.White, ring = false,
    ) { focused ->
        Text(
            label, style = ZType.Label.copy(fontSize = 16.sp),
            color = if (focused) Color.Black else if (selected) Z.Text else Z.Muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

/** A service of the add-on that needs a login (TF1+, 6play, RMC+…). */
private data class Account(val name: String, val login: SettingEntry, val password: SettingEntry) {
    /** Service id used by the add-on for its settings ("tf1plus", "6play"…). */
    val service: String get() = login.id.removeSuffix(".login")
}

private fun accountsOf(entries: List<SettingEntry>): List<Account> = entries
    .filter { it.type == "text" && it.id.endsWith(".login") }
    .mapNotNull { login ->
        val prefix = login.id.removeSuffix(".login")
        val password = entries.firstOrNull { it.id == "$prefix.password" } ?: return@mapNotNull null
        val name = login.label
            .replace(Regex("^(Identifiant|Login|Nom d'utilisateur|E-?mail)\\s*(de |d')?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(login|username|e-?mail)$", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { prefix }
        Account(name, login, password)
    }

private fun addonLine(
    vm: AppViewModel,
    e: SettingEntry,
    set: (String, String) -> Unit,
    onAction: (String) -> Unit,
): Line? = when (e.type) {
    "section" -> Line.Header(e.label)
    "bool" -> Line.Item(e.label, toggle = e.value.equals("true", true)) {
        set(e.id, if (e.value.equals("true", true)) "false" else "true")
    }
    "enum", "labelenum", "select" -> {
        val current = e.options.firstOrNull { it.value == e.value }?.label ?: e.value
        Line.Item(e.label, value = current) {
            vm.messages.ask(Choice(e.label, e.options.map { it.label }, e.options.indexOfFirst { it.value == e.value }) { i ->
                set(e.id, e.options[i].value)
            })
        }
    }
    "text", "number", "slider" -> {
        val secret = e.hidden || e.id.contains("password", true) || e.id.contains("pass", true)
        val shown = when {
            e.value.isBlank() -> "Non renseigné"
            secret -> "••••••••"
            else -> e.value
        }
        Line.Item(e.label, value = shown) {
            vm.messages.askText(TextAsk(e.label, if (secret) "" else e.value, secret) { set(e.id, it) })
        }
    }
    "action" -> Line.Item(e.label) { onAction(e.action) }
    else -> null
}

@Composable
private fun SettingLine(row: Line.Item) {
    FocusSurface(
        onClick = row.onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        focusedScale = 1.015f, container = Color.Transparent, focusedContainer = Z.Surface2,
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.label, style = ZType.Label.copy(fontSize = 16.sp), color = Z.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.description.isNotBlank() && focused) {
                    Spacer(Modifier.height(4.dp))
                    Text(row.description, style = ZType.Body.copy(fontSize = 13.sp), color = Z.Muted)
                }
            }
            Spacer(Modifier.width(16.dp))
            when {
                row.toggle != null -> Toggle(row.toggle)
                row.value.isNotBlank() -> Text(
                    row.value, style = ZType.Label, color = Z.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(260.dp),
                )
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (on) Z.Live else Z.Surface3),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(Color.White))
    }
}
