package com.arnav.island.overlay

import android.content.Context
import com.arnav.island.BuildConfig
import com.arnav.island.soundisland.audio.VolumeSuppressor

/**
 * One-shot, read-only diagnostic for the Samsung Now Bar chip that overlaps the island.
 *
 * Runs once per app version while Shizuku is available and writes
 * `Download/BreezeIsland_nowbar_diag.txt` containing the system settings and status bar slots
 * related to chips / Now Bar / live notifications. It changes nothing on the device.
 */
object NowBarDiagnostics {
    private const val PREFS = "breeze_diag"
    private const val OUT = "/sdcard/Download/BreezeIsland_nowbar_diag.txt"

    fun runOnce(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "nowbar_diag_v${BuildConfig.VERSION_CODE}"
        if (prefs.getBoolean(key, false) || !VolumeSuppressor.canUseShizuku()) return
        val pattern = "chip|ongoing|now_?bar|live_?notif|media|cover|sub_?display|status_?bar|icon_blacklist"
        val cmd = "{ " +
            "echo '== secure =='; settings list secure | grep -i -E '$pattern'; " +
            "echo '== system =='; settings list system | grep -i -E '$pattern'; " +
            "echo '== global =='; settings list global | grep -i -E '$pattern'; " +
            "echo '== dumpsys statusbar =='; dumpsys statusbar | grep -i -E '$pattern' | head -n 80; " +
            "echo '== active notifications (media/live) =='; dumpsys notification --noredact | grep -i -E 'live|ongoing_activity|promoted|chip' | head -n 60; " +
            "} > $OUT 2>&1"
        if (VolumeSuppressor.runShizukuShell(cmd)) prefs.edit().putBoolean(key, true).apply()
    }
}
