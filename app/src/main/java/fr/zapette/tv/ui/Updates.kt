package fr.zapette.tv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.zapette.tv.BuildConfig
import fr.zapette.tv.data.Store
import fr.zapette.tv.engine.AddonEngine
import fr.zapette.tv.engine.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Maintenance actions of the settings screen, with their messages:
 * Zapette and add-on updates, cache, account checks.
 */
class Updates(
    context: Context,
    private val store: Store,
    private val engine: AddonEngine,
    private val messages: Messages,
    private val scope: CoroutineScope,
    /** Reload what depends on the add-on (channels, menus) after it changed. */
    private val onAddonChanged: suspend () -> Unit,
    private val onCacheCleared: () -> Unit,
) {
    private val updater = AppUpdater(context) { store.developerMode }

    // ------------------------------------------------------------------- Zapette
    /** Latest Zapette release found on GitHub (null until checked). */
    var appRelease by mutableStateOf<AppUpdater.Release?>(null)
        private set

    fun checkApp(manual: Boolean) = scope.launch {
        val release = if (manual) messages.whileBusy("Recherche d'une mise à jour de Zapette…") { updater.latest() }
        else updater.latest()
        if (release != null) appRelease = release
        when {
            release == null -> if (manual) messages.toast("Mise à jour", "GitHub est injoignable pour le moment.", error = true)
            !release.isNewer -> if (manual) messages.toast("Zapette est à jour", "Version ${BuildConfig.VERSION_NAME}")
            !manual -> messages.toast("Zapette ${release.versionName} disponible", "Réglages → Mettre à jour Zapette")
            else -> messages.ask(
                Choice(
                    "Zapette ${release.versionName} disponible (installée : ${BuildConfig.VERSION_NAME})",
                    listOf("Installer maintenant", "Plus tard"),
                ) { if (it == 0) installApp(release) }
            )
        }
    }

    /** Waiting for the user to allow Zapette to install apps, in the system settings. */
    private var waitingPermission: AppUpdater.Release? = null

    /** Set while the system installer is on screen (Zapette goes to the background). */
    private var installerShown = false

    private fun installApp(release: AppUpdater.Release) = scope.launch {
        if (!updater.canInstall()) {
            // The install carries on by itself when the user comes back from the settings
            waitingPermission = release
            messages.toast("Autorisation nécessaire", "Autorisez Zapette à installer des applications : l'installation reprendra ensuite.")
            if (!updater.openInstallPermission()) {
                waitingPermission = null
                messages.toast("Autorisation nécessaire", "Paramètres → Applications → Zapette → Installer des applications inconnues.", error = true)
            }
            return@launch
        }
        val title = "Téléchargement de Zapette ${release.versionName}…"
        messages.busy(title)
        val apk = runCatching { updater.download(release) { percent -> messages.busy("$title $percent %") } }
        messages.busy(null)
        apk.onFailure { messages.toast("Téléchargement impossible", it.message ?: "", error = true) }
        apk.onSuccess { file ->
            val problem = updater.verify(file, release)
            if (problem != null) {
                file.delete()
                messages.toast("Mise à jour refusée", problem, error = true)
            } else {
                startInstaller(file)
            }
        }
    }

    /**
     * Hands the APK to the system installer. Some TVs drop the first request (the
     * installer never shows): if Zapette is still on screen a moment later, ask again once.
     */
    private suspend fun startInstaller(file: java.io.File) {
        installerShown = false
        repeat(2) {
            runCatching { updater.install(file) }.onFailure {
                messages.toast("Installation impossible", it.message ?: "", error = true)
                return
            }
            delay(4_000)
            if (installerShown) return
        }
    }

    /** Zapette goes to the background (the installer or the settings took the screen). */
    fun onAppStop() {
        installerShown = true
    }

    /** Back in Zapette: carries on with an install that was waiting for the permission. */
    fun onAppStart() {
        val release = waitingPermission ?: return
        if (updater.canInstall()) {
            waitingPermission = null
            installApp(release)
        }
    }

    // ------------------------------------------------------------------- add-on
    var addonVersion by mutableStateOf("")
        private set
    var addonFromGitHub by mutableStateOf(false)
        private set

    fun refreshAddonInfo() {
        addonVersion = engine.installer.installedVersion()
        addonFromGitHub = engine.installer.source().startsWith("github:")
    }

    fun checkAddon() = scope.launch {
        val remote = messages.whileBusy("Recherche d'une mise à jour…") { engine.remoteVersion() }
        val current = engine.installer.installedVersion()
        when (remote) {
            null -> messages.toast("Mise à jour", "GitHub est injoignable pour le moment.", error = true)
            current -> messages.ask(
                Choice("Catch-up TV & More $current est à jour", listOf("Réinstaller depuis GitHub", "Fermer")) {
                    if (it == 0) updateAddon()
                }
            )
            else -> messages.ask(
                Choice("Version $remote disponible (installée : $current)", listOf("Mettre à jour maintenant", "Plus tard")) {
                    if (it == 0) updateAddon()
                }
            )
        }
    }

    fun updateAddon(revert: Boolean = false) = scope.launch {
        val busy = if (revert) "Restauration de la version intégrée…" else "Téléchargement de Catch-up TV & More…"
        val result = messages.whileBusy(busy) { runCatching { if (revert) engine.revertAddon() else engine.updateAddon() } }
        result.onSuccess { version ->
            messages.toast("Catch-up TV & More $version", if (revert) "Version intégrée restaurée." else "Mise à jour installée.")
            onAddonChanged()
        }
        result.onFailure { messages.toast("Échec de la mise à jour", it.message ?: "", error = true) }
        refreshAddonInfo()
    }

    fun clearCache() = scope.launch {
        engine.clearCache()
        messages.toast("Cache vidé", "Le guide TV va être rechargé.")
        onCacheCleared()
    }

    // ----------------------------------------------------------------- accounts
    /** Logs in with the saved credentials and says whether they are accepted. */
    suspend fun checkAccount(service: String, name: String) {
        val result = messages.whileBusy("Vérification du compte $name…") { engine.checkAccount(service) }
        when (result.status) {
            "ok" -> messages.toast("Compte $name valide", "Identifiant et mot de passe acceptés.")
            "invalid" -> messages.toast("Compte $name refusé", "Identifiant ou mot de passe incorrect.", error = true)
            "network" -> messages.toast(
                "Vérification impossible ($name)",
                "Le service ne répond pas : connexion internet, ou accès réservé à la France.", error = true,
            )
            else -> messages.toast("Compte $name enregistré", "La vérification n'est pas disponible pour ce service.")
        }
    }
}
