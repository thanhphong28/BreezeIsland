package com.arnav.island.soundisland.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.arnav.island.util.Diagnostics

/**
 * Manages audio stream volume levels using Android's [AudioManager].
 *
 * When Breeze Sound Island adjusts the volume programmatically, it passes flags without
 * [AudioManager.FLAG_SHOW_UI] so Samsung One UI's native volume dialog is not triggered.
 */
class VolumeManager(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    data class StreamInfo(
        val streamType: Int,
        val current: Int,
        val max: Int,
        val min: Int,
    ) {
        val fraction: Float
            get() = if (max > min) ((current - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
    }

    fun getStreamInfo(streamType: Int = AudioManager.STREAM_MUSIC): StreamInfo {
        val max = runCatching { audio.getStreamMaxVolume(streamType) }.getOrDefault(15).coerceAtLeast(1)
        val min = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { audio.getStreamMinVolume(streamType) }.getOrDefault(0)
        } else {
            0
        }
        val current = runCatching { audio.getStreamVolume(streamType) }.getOrDefault(0).coerceIn(min, max)
        return StreamInfo(streamType, current, max, min)
    }

    /**
     * Sets volume for [streamType].
     *
     * Crucially passes `flags = 0` (or [AudioManager.FLAG_PLAY_SOUND] optionally) so Android does
     * NOT raise the native Samsung Volume UI overlay.
     */
    fun setVolume(streamType: Int, volumeIndex: Int, playSound: Boolean = false): Boolean {
        return try {
            val max = audio.getStreamMaxVolume(streamType)
            val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(streamType) else 0
            val clamped = volumeIndex.coerceIn(min, max)
            val flags = if (playSound) AudioManager.FLAG_PLAY_SOUND else 0
            audio.setStreamVolume(streamType, clamped, flags)
            true
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Failed to set volume for stream $streamType", e)
            false
        }
    }

    /** Sets volume by fraction (0.0 .. 1.0). */
    fun setVolumeFraction(streamType: Int, fraction: Float, playSound: Boolean = false): Boolean {
        val info = getStreamInfo(streamType)
        val target = info.min + Math.round((info.max - info.min) * fraction.coerceIn(0f, 1f))
        return setVolume(streamType, target, playSound)
    }

    /** Adjusts volume up or down by step (+1 / -1) without system UI. */
    fun adjustVolume(streamType: Int = AudioManager.STREAM_MUSIC, direction: Int, playSound: Boolean = false): Boolean {
        return try {
            val flags = if (playSound) AudioManager.FLAG_PLAY_SOUND else 0
            audio.adjustStreamVolume(streamType, direction, flags)
            true
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Failed to adjust volume for stream $streamType", e)
            false
        }
    }

    companion object {
        private const val TAG = "SoundIslandVolume"
    }
}
