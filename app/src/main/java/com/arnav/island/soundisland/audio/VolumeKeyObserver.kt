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
    private val onVolumeChanged: (newVolume: Int, maxVolume: Int, fraction: Float) -> Unit,
) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_VOLUME_CHANGED) return

            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (streamType == -1) return

            val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            val prevVolume = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)

            if (streamType == AudioManager.STREAM_MUSIC) {
                val streamInfo = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC)
                val resolvedVolume = if (newVolume >= 0) newVolume else streamInfo.current
                onVolumeChanged(resolvedVolume, streamInfo.max, streamInfo.fraction)
            } else {
                // When system routes keys to Ringtone/Notification instead of Media,
                // redirect hardware button intent to STREAM_MUSIC as requested by user.
                if (prevVolume != -1 && newVolume != -1) {
                    val direction = when {
                        newVolume > prevVolume -> AudioManager.ADJUST_RAISE
                        newVolume < prevVolume -> AudioManager.ADJUST_LOWER
                        else -> 0
                    }
                    if (direction != 0) {
                        volumeManager.adjustVolume(AudioManager.STREAM_MUSIC, direction)
                        // Preserve original ringer volume so it remains unaffected
                        try {
                            volumeManager.setVolume(streamType, prevVolume)
                        } catch (_: Exception) {}
                    }
                }
                val mediaInfo = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC)
                onVolumeChanged(mediaInfo.current, mediaInfo.max, mediaInfo.fraction)
            }
        }
    }

    fun start() {
        if (registered) return

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

    companion object {
        private const val TAG = "VolumeKeyObserver"
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
