package com.arnav.island.soundisland.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.arnav.island.util.Diagnostics

/**
 * Listens for hardware volume button presses strictly when volume changes,
 * without using AccessibilityService or Root.
 *
 * Filters out spurious broadcasts (such as app switching, audio focus shifts,
 * and duplicate intent deliveries) to prevent unwanted pop-ups.
 */
class VolumeKeyObserver(
    private val context: Context,
    private val volumeManager: VolumeManager,
    private val onVolumeChanged: (streamType: Int, newVolume: Int, maxVolume: Int, fraction: Float) -> Unit,
) {

    private var registered = false
    private val lastVolumes = mutableMapOf<Int, Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_VOLUME_CHANGED) return

            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (streamType == -1) return

            val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            val prevVolume = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)

            // If prevVolume is known and equals newVolume, no actual user volume change happened
            if (prevVolume != -1 && newVolume == prevVolume) return

            val streamInfo = volumeManager.getStreamInfo(streamType)
            val resolvedVolume = if (newVolume >= 0) newVolume else streamInfo.current

            // Check if volume level actually changed from our last recorded level
            val previousRecorded = lastVolumes[streamType]
            if (previousRecorded != null && previousRecorded == resolvedVolume) {
                // Spurious broadcast (e.g. app transition, audio routing change)
                return
            }

            lastVolumes[streamType] = resolvedVolume

            val fraction = if (streamInfo.max > streamInfo.min) {
                ((resolvedVolume - streamInfo.min).toFloat() / (streamInfo.max - streamInfo.min)).coerceIn(0f, 1f)
            } else {
                0f
            }

            onVolumeChanged(streamType, resolvedVolume, streamInfo.max, fraction)
        }
    }

    fun start() {
        if (registered) return

        // Pre-populate last known volumes so starting up never fires a false trigger
        val streams = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_VOICE_CALL,
        )
        for (s in streams) {
            try {
                lastVolumes[s] = volumeManager.getStreamInfo(s).current
            } catch (_: Exception) {}
        }

        try {
            val filter = IntentFilter(ACTION_VOLUME_CHANGED)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            try {
                val filter = IntentFilter(ACTION_VOLUME_CHANGED)
                ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "Failed to register volume broadcast receiver", t)
            }
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        registered = false
    }

    /** Keep internal state updated when the user manually changes volume on Breeze Sound Island. */
    fun recordVolume(streamType: Int, level: Int) {
        lastVolumes[streamType] = level
    }

    companion object {
        private const val TAG = "VolumeKeyObserver"
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
