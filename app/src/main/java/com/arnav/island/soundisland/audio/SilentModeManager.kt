package com.arnav.island.soundisland.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.arnav.island.util.Diagnostics

/**
 * Reads and toggles device ringer states: Normal (Sound), Vibrate, and Silent.
 */
class SilentModeManager(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)

    enum class Mode(val ringerMode: Int, val label: String) {
        SOUND(AudioManager.RINGER_MODE_NORMAL, "Sound"),
        VIBRATE(AudioManager.RINGER_MODE_VIBRATE, "Vibrate"),
        SILENT(AudioManager.RINGER_MODE_SILENT, "Silent"),
    }

    fun currentMode(): Mode = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> Mode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> Mode.VIBRATE
        else -> Mode.SOUND
    }

    fun hasDndAccess(): Boolean =
        Build.VERSION.SDK_INT < 24 || nm.isNotificationPolicyAccessGranted

    /**
     * Cycles between SOUND -> VIBRATE -> SILENT -> SOUND.
     * If DND access is not granted, gracefully alternates between SOUND <-> VIBRATE.
     */
    fun toggleNext(): Mode {
        val current = currentMode()
        val next = when (current) {
            Mode.SOUND -> Mode.VIBRATE
            Mode.VIBRATE -> if (hasDndAccess()) Mode.SILENT else Mode.SOUND
            Mode.SILENT -> Mode.SOUND
        }
        setMode(next)
        return currentMode()
    }

    fun setMode(mode: Mode): Boolean {
        return try {
            audio.ringerMode = mode.ringerMode
            true
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "Setting ringer mode to ${mode.name} requires DND permission", e)
            // Fallback to Vibrate if Silent was rejected
            if (mode == Mode.SILENT) {
                runCatching { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            }
            false
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Failed to set ringer mode", e)
            false
        }
    }

    companion object {
        private const val TAG = "SilentModeManager"
    }
}
