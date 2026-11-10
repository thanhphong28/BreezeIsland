package com.arnav.island.soundisland.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.arnav.island.util.Diagnostics

/**
 * Listens for hardware volume button presses and audio level changes in the background
 * without using AccessibilityService or Root.
 *
 * Uses a dual-channel observation approach:
 *  1. Android's internal `android.media.VOLUME_CHANGED_ACTION` broadcast (instantaneous ~5ms).
 *  2. [ContentObserver] on `Settings.System` audio stream URIs as a fallback.
 */
class VolumeKeyObserver(
    private val context: Context,
    private val volumeManager: VolumeManager,
    private val onVolumeChanged: (streamType: Int, newVolume: Int, maxVolume: Int, fraction: Float) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_VOLUME_CHANGED) {
                val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                val newVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                val streamInfo = volumeManager.getStreamInfo(streamType)
                val resolvedVolume = if (newVolume >= 0) newVolume else streamInfo.current
                val fraction = if (streamInfo.max > streamInfo.min) {
                    ((resolvedVolume - streamInfo.min).toFloat() / (streamInfo.max - streamInfo.min)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                onVolumeChanged(streamType, resolvedVolume, streamInfo.max, fraction)
            }
        }
    }

    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val streamInfo = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC)
            onVolumeChanged(AudioManager.STREAM_MUSIC, streamInfo.current, streamInfo.max, streamInfo.fraction)
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

        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                contentObserver,
            )
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Failed to register content observer", e)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
        try {
            context.contentResolver.unregisterContentObserver(contentObserver)
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
