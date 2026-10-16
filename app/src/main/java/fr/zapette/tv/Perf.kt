package fr.zapette.tv

import android.app.ActivityManager
import android.content.Context

/**
 * Light mode for weak TV sets: fewer animations, no ambient artwork, smaller
 * bitmaps, video capped to 720p. Decided once at start-up.
 */
object Perf {
    /** True when the light mode is active for this run of the app. */
    var lite = false
        private set

    /** What the automatic detection concluded (shown in the settings). */
    var detectedWeak = false
        private set

    fun init(context: Context, mode: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = mem.totalMem / (1024.0 * 1024 * 1024)
        detectedWeak = am.isLowRamDevice || totalGb < 2.2 || Runtime.getRuntime().availableProcessors() <= 2
        lite = when (mode) {
            "on" -> true
            "off" -> false
            else -> detectedWeak
        }
    }
}
