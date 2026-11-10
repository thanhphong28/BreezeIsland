package com.arnav.island.soundisland.work

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.arnav.island.soundisland.audio.VolumeManager
import com.arnav.island.util.Diagnostics

/**
 * Manages Work Focus mode: toggles DND and applies customized work audio profiles,
 * with snapshot save/restore mechanism.
 */
class WorkProfileManager(
    private val context: Context,
    private val volumeManager: VolumeManager,
) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)

    var isActive: Boolean = false
        private set

    private var previousSnapshot: AudioSnapshot? = null

    fun hasDndAccess(): Boolean =
        Build.VERSION.SDK_INT < 24 || nm.isNotificationPolicyAccessGranted

    fun isDndActive(): Boolean {
        if (!hasDndAccess()) return false
        val filter = nm.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /**
     * Toggles Work Mode on or off. Returns the new active state.
     */
    fun toggle(config: WorkProfileConfig): Boolean {
        return if (isActive) {
            deactivate()
            false
        } else {
            activate(config)
            true
        }
    }

    fun activate(config: WorkProfileConfig) {
        // 1. Capture snapshot before applying work profile
        val currentDnd = if (hasDndAccess()) nm.currentInterruptionFilter else NotificationManager.INTERRUPTION_FILTER_ALL
        val currentMedia = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC).current
        val currentRing = volumeManager.getStreamInfo(AudioManager.STREAM_RING).current
        val currentNotif = volumeManager.getStreamInfo(AudioManager.STREAM_NOTIFICATION).current
        val currentRingerMode = audio.ringerMode

        previousSnapshot = AudioSnapshot(
            mediaVolume = currentMedia,
            ringVolume = currentRing,
            notificationVolume = currentNotif,
            ringerMode = currentRingerMode,
            dndFilter = currentDnd,
        )

        // 2. Apply DND if permitted
        if (config.enableDnd && hasDndAccess()) {
            runCatching {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }.onFailure { Diagnostics.w(TAG, "Failed to enable DND", it) }
        }

        // 3. Apply volume levels
        volumeManager.setVolumeFraction(AudioManager.STREAM_MUSIC, config.mediaVolumePercent / 100f)
        volumeManager.setVolumeFraction(AudioManager.STREAM_RING, config.ringVolumePercent / 100f)
        volumeManager.setVolumeFraction(AudioManager.STREAM_NOTIFICATION, config.notificationVolumePercent / 100f)

        if (config.vibrateOnly) {
            runCatching { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
        }

        isActive = true
    }

    fun deactivate() {
        val snapshot = previousSnapshot
        if (snapshot != null) {
            // Restore DND
            if (hasDndAccess() && snapshot.dndFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                runCatching {
                    nm.setInterruptionFilter(snapshot.dndFilter)
                }.onFailure { Diagnostics.w(TAG, "Failed to restore DND", it) }
            }

            // Restore ringer mode
            runCatching { audio.ringerMode = snapshot.ringerMode }

            // Restore stream volumes
            volumeManager.setVolume(AudioManager.STREAM_MUSIC, snapshot.mediaVolume)
            volumeManager.setVolume(AudioManager.STREAM_RING, snapshot.ringVolume)
            volumeManager.setVolume(AudioManager.STREAM_NOTIFICATION, snapshot.notificationVolume)
        }
        previousSnapshot = null
        isActive = false
    }

    companion object {
        private const val TAG = "WorkProfileManager"
    }
}
