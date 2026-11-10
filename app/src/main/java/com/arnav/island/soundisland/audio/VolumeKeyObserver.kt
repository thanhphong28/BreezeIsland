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
 * Listens for hardware volume button presses and audio stream updates strictly,
 * without using AccessibilityService or Root.
 *
 * Ensures seamless iOS-like Media volume handling even when no music is playing.
 */
class VolumeKeyObserver(
    private val context: Context,
    private val volumeManager: VolumeManager,
    private val onVolumeChanged: (newVolume: Int, maxVolume: Int, fraction: Float) -> Unit,
    private val onSystemVolumeChanged: ((newVolume: Int, maxVolume: Int, fraction: Float) -> Unit)? = null,
) {

    private var registered = false
    private var lastRedirectTime = 0L

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
                val frac = if (streamInfo.max > streamInfo.min) {
                    ((resolvedVolume - streamInfo.min).toFloat() / (streamInfo.max - streamInfo.min)).coerceIn(0f, 1f)
                } else 0f
                onVolumeChanged(resolvedVolume, streamInfo.max, frac)
            } else if (streamType == AudioManager.STREAM_RING) {
                // When no music is playing, Samsung's default hardware key routing may fall back to
                // STREAM_RING. We redirect this hardware key step to STREAM_MUSIC (Media)
                // just like iOS. STREAM_NOTIFICATION is intentionally ignored so notification chimes are untouched!
                val now = System.currentTimeMillis()
                if (now - lastRedirectTime > 90L) {
                    lastRedirectTime = now
                    val direction = when {
                        prevVolume != -1 && newVolume > prevVolume -> AudioManager.ADJUST_RAISE
                        prevVolume != -1 && newVolume < prevVolume -> AudioManager.ADJUST_LOWER
                        else -> null
                    }
                    if (direction != null) {
                        volumeManager.adjustVolume(AudioManager.STREAM_MUSIC, direction)
                    }
                    // Immediately fetch updated STREAM_MUSIC info and notify onVolumeChanged!
                    val mediaInfo = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC)
                    onVolumeChanged(mediaInfo.current, mediaInfo.max, mediaInfo.fraction)
                }
            }
        }
    }

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        private var lastObservedVolume = -1
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val info = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC)
            if (info.current != lastObservedVolume) {
                lastObservedVolume = info.current
                onSystemVolumeChanged?.invoke(info.current, info.max, info.fraction)
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

        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                contentObserver
            )
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to register content observer", t)
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
