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

/** A settings row, either native to Zapette or coming from the add-on's settings.xml. */
private sealed interface Row2 {
    data class Header(val label: String) : Row2
    data class Item(
        val label: String,
        val value: String = "",
        val toggle: Boolean? = null,
        val description: String = "",
        val onClick: () -> Unit,
    ) : Row2
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var schema by remember { mutableStateOf<List<SettingCategory>?>(null) }
    var category by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    val countries by vm.countries.collectAsState()

    LaunchedEffect(revision) {
        schema = runCatching { vm.engine.settingsSchema() }.getOrElse { emptyList() }
        vm.refreshAddonInfo()
    }
    LaunchedEffect(Unit) { vm.backdrop = null }

    fun accountName(prefix: String): String =
        schema.orEmpty().flatMap { accountsOf(it.entries) }.firstOrNull { it.login.id == "$prefix.login" }?.name ?: prefix

    fun saveSetting(id: String, value: String) {
        scope.launch {
            vm.engine.setSetting(id, value)
            if (id == "active_subtitle") vm.syncSubtitles(value)
            revision++
            // Login or password of a service changed: test them right away
            val prefix = id.substringBeforeLast('.', "")
            if (prefix.isNotEmpty() && (id.endsWith(".login") || id.endsWith(".password"))) {
                val login = vm.engine.getSetting("$prefix.login")
                val password = vm.engine.getSetting("$prefix.password")
                if (login.isNotBlank() && password.isNotBlank()) vm.checkAccount(prefix, accountName(prefix))
            }
        }
    }


    /** Copies the login and password of one service to the services the user picks. */
    fun copyAccount(accounts: List<Account>) {
        val filled = accounts.filter { it.login.value.isNotBlank() }
        if (filled.isEmpty()) {
            vm.toast("Aucun compte à copier", "Renseignez d'abord l'identifiant et le mot de passe d'une chaîne.")
            return
        }
        vm.ask(Choice("Copier le compte de…", filled.map { "${it.name}  ·  ${it.login.value}" }) { i ->
            val source = filled[i]
            val targets = accounts.filter { it !== source }
            vm.ask(Choice(
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
                        vm.toast("Compte copié", "${source.name} → " + chosen.joinToString(", ") { it.name })
                        revision++
                        chosen.forEach { t -> vm.checkAccount(t.login.id.removeSuffix(".login"), t.name) }
                    }
                },
            ))
        })
    }

    val categories = listOf("Zapette") + schema.orEmpty().map { it.label }

    val rows: List<Row2> = if (category == 0) {
        val country = countries.firstOrNull { it.id == vm.store.liveCountry }?.label ?: vm.store.liveCountry
        listOf(
            Row2.Header("Télévision"),
            Row2.Item("Démarrer sur le direct", toggle = vm.store.startOnLive,
                description = "À l'ouverture, Zapette reprend la dernière chaîne regardée, comme un téléviseur.") {
                vm.store.startOnLive = !vm.store.startOnLive; revision++
            },
            Row2.Item("Bandeau d'information au zapping", toggle = vm.store.bannerOnZap,
                description = "Affiche la chaîne et le programme en cours à chaque changement de chaîne.") {
                vm.store.bannerOnZap = !vm.store.bannerOnZap; revision++
            },
            Row2.Item("Effet de zapping rétro", toggle = vm.store.zapEffect,
                description = "Neige et numéro de chaîne vert façon vieux téléviseur pendant le changement de chaîne.") {
                vm.store.zapEffect = !vm.store.zapEffect; revision++
            },
            Row2.Item("Mini-lecteur en quittant le direct", toggle = vm.store.miniOnBack,
                description = "Retour depuis une chaîne la garde à l'écran, en haut à droite, pendant que vous parcourez les menus. " +
                    "Choisir une chaîne la remplace en plein écran ; Lecture revient en plein écran ; Retour (à l'accueil) la ferme.") {
                vm.store.miniOnBack = !vm.store.miniOnBack; revision++
            },
            Row2.Item("Chaînes en direct", value = country,
                description = "Bouquet utilisé pour l'accueil, la liste des chaînes et le guide TV.") {
                val list = countries
                if (list.isNotEmpty()) vm.ask(Choice("Chaînes en direct", list.map { it.label },
                    list.indexOfFirst { it.id == vm.store.liveCountry }) { i -> vm.changeCountry(list[i].id); revision++ })
            },
            Row2.Item(
                "Mode léger (TV peu puissante)",
                value = when (vm.store.perfMode) {
                    "on" -> "Toujours"
                    "off" -> "Jamais"
                    else -> "Automatique · " + if (Perf.detectedWeak) "activé" else "désactivé"
                },
                description = "Moins d'animations, pas d'images d'ambiance, images plus légères, vidéo limitée à 720p. " +
                    "Pris en compte au prochain démarrage de Zapette.",
            ) {
                val modes = listOf("auto", "on", "off")
                vm.ask(Choice("Mode léger", listOf("Automatique (recommandé)", "Toujours", "Jamais"),
                    modes.indexOf(vm.store.perfMode)) { i ->
                    vm.store.perfMode = modes[i]
                    revision++
                    vm.toast("Mode léger", "Redémarrez Zapette pour l'appliquer.")
                })
            },
            Row2.Header("Lecture"),
            Row2.Item("Sous-titres", toggle = vm.subtitlesOn,
                description = "Affiche les sous-titres français quand le programme en propose. Pendant la lecture : " +
                    "touche Sous-titres de la télécommande, ou ▶ puis OK (Audio et sous-titres).") {
                vm.setSubtitles(!vm.subtitlesOn); revision++
            },
            Row2.Header("Catch-up TV & More"),
            Row2.Item(
                "Version de l'extension", value = vm.addonVersion + if (vm.addonFromGitHub) " · GitHub" else " · intégrée",
                description = "L'extension est mise à jour indépendamment de l'application, directement depuis GitHub.",
            ) { vm.checkAddonUpdate() },
            Row2.Item("Rechercher une mise à jour", description = "Télécharge la dernière version publiée sur GitHub.") {
                vm.checkAddonUpdate()
            },
        ) + (if (vm.addonFromGitHub) listOf(
            Row2.Item("Revenir à la version intégrée", description = "En cas de problème après une mise à jour.") {
                vm.updateAddon(revert = true)
            }
        ) else emptyList()) + listOf(
            Row2.Item("Vider le cache", description = "Supprime le cache des pages et le guide TV téléchargé.") { vm.clearCache() },
            Row2.Header("Application"),
            Row2.Item(
                "Mettre à jour Zapette",
                value = vm.appRelease?.takeIf { it.isNewer }?.let { "${it.versionName} disponible" }
                    ?: "Version ${BuildConfig.VERSION_NAME}",
                description = "Télécharge et installe la dernière version compilée sur GitHub. " +
                    "La première fois, Android demande d'autoriser Zapette à installer des applications.",
            ) { vm.checkAppUpdate(manual = true) },
            Row2.Header("À propos"),
            Row2.Item("Zapette ${BuildConfig.VERSION_NAME}",
                description = "Interface TV pour Catch-up TV & More. Les contenus sont fournis par les chaînes ; " +
                    "certains nécessitent un compte (TF1+, M6+…) à renseigner dans « Comptes ».") {},
        )
    } else {
        val cat = schema?.getOrNull(category - 1)
        val entries = cat?.entries.orEmpty()
        val accounts = accountsOf(entries)
        val copyRow = if (accounts.size >= 2) listOf(
            Row2.Header("Raccourci"),
            Row2.Item("Copier un compte vers d'autres chaînes",
                description = "Reprend l'identifiant et le mot de passe d'une chaîne pour les chaînes que vous choisissez.") {
                copyAccount(accounts)
            },
            Row2.Item("Vérifier les comptes renseignés",
                description = "Teste la connexion de chaque compte et indique si l'identifiant ou le mot de passe est refusé.") {
                val filled = accounts.filter { it.login.value.isNotBlank() && it.password.value.isNotBlank() }
                if (filled.isEmpty()) vm.toast("Aucun compte renseigné")
                else scope.launch { filled.forEach { vm.checkAccount(it.login.id.removeSuffix(".login"), it.name) } }
            },
        ) else emptyList()
        copyRow + entries.mapNotNull { e -> addonRow(vm, e, ::saveSetting, onAction = { url -> vm.runAction(url) }) }
    }

    Row(Modifier.fillMaxSize().padding(start = Z.Gutter, end = Z.Gutter, top = 12.dp)) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            Text("Réglages", style = ZType.Headline, color = Z.Text)
            Spacer(Modifier.height(18.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(categories) { index, label ->
                    FocusSurface(
                        onClick = { category = index }, onFocus = { category = index },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), focusedScale = 1.03f,
                        container = if (category == index) Z.Surface2 else Color.Transparent,
                        focusedContainer = Color.White, ring = false,
                    ) { focused ->
                        Text(
                            label, style = ZType.Label.copy(fontSize = 16.sp),
                            color = if (focused) Color.Black else if (category == index) Z.Text else Z.Muted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        )
                    }
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
            items(rows) { row ->
                when (row) {
                    is Row2.Header -> Text(
                        row.label.uppercase(), style = ZType.Small.copy(letterSpacing = 1.4.sp), color = Z.Live,
                        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                    )
                    is Row2.Item -> SettingRow(row)
                }
            }
        }
    }
}

/** A service of the add-on that needs a login (TF1+, 6play, RMC+…). */
private data class Account(val name: String, val login: SettingEntry, val password: SettingEntry)

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

private fun addonRow(
    vm: AppViewModel,
    e: SettingEntry,
    set: (String, String) -> Unit,
    onAction: (String) -> Unit,
): Row2? = when (e.type) {
    "section" -> Row2.Header(e.label)
    "bool" -> Row2.Item(e.label, toggle = e.value.equals("true", true)) {
        set(e.id, if (e.value.equals("true", true)) "false" else "true")
    }
    "enum", "labelenum", "select" -> {
        val current = e.options.firstOrNull { it.value == e.value }?.label ?: e.value
        Row2.Item(e.label, value = current) {
            vm.ask(Choice(e.label, e.options.map { it.label }, e.options.indexOfFirst { it.value == e.value }) { i ->
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
        Row2.Item(e.label, value = shown) {
            vm.askText(TextAsk(e.label, if (secret) "" else e.value, secret) { set(e.id, it) })
        }
    }
    "action" -> Row2.Item(e.label) { onAction(e.action) }
    else -> null
}

@Composable
private fun SettingRow(row: Row2.Item) {
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
