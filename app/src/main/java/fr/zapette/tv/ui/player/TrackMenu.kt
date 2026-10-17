package fr.zapette.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import fr.zapette.tv.player.StreamPlayer
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.Choice
import java.util.Locale

/** Subtitles on/off at once (Subtitles key, first line of the audio menu). */
fun toggleSubtitles(vm: AppViewModel, player: Player) {
    val on = !vm.subtitlesOn
    vm.setSubtitles(on)
    StreamPlayer.applySubtitles(player, on)
    val available = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
    vm.messages.toast(
        if (on) "Sous-titres activés" else "Sous-titres désactivés",
        if (on && !available) "Ce programme n'en propose pas pour le moment." else "",
    )
}

/** "Audio et sous-titres" menu: subtitles switch, then every audio and subtitle track. */
fun openTrackMenu(vm: AppViewModel, player: Player) {
    val entries = mutableListOf<Pair<String, () -> Unit>>()
    entries += (if (vm.subtitlesOn) "Sous-titres : activés  ·  désactiver" else "Sous-titres : désactivés  ·  activer") to
        { toggleSubtitles(vm, player) }

    for (group in player.currentTracks.groups) {
        val isAudio = group.type == C.TRACK_TYPE_AUDIO
        if (!isAudio && group.type != C.TRACK_TYPE_TEXT) continue
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val described = format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0
            val name = format.label ?: (languageName(format.language) + if (described) " (audiodescription)" else "")
            val check = if (group.isTrackSelected(i)) "  ✓" else ""
            entries += "${if (isAudio) "Audio" else "Sous-titres"} · $name$check" to {
                if (!isAudio && !vm.subtitlesOn) vm.setSubtitles(true)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .apply { if (!isAudio) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) }
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    vm.messages.ask(Choice("Audio et sous-titres", entries.map { it.first }) { entries[it].second() })
}

private fun languageName(code: String?): String {
    if (code.isNullOrBlank() || code == "und") return "Par défaut"
    if (code == "qad" || code == "qaa") return "Audiodescription"
    val name = Locale(code).getDisplayLanguage(Locale.FRENCH)
    return if (name.isBlank() || name == code) code.uppercase() else name.replaceFirstChar { it.uppercase() }
}
