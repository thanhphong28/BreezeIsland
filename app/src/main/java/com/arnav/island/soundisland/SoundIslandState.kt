package com.arnav.island.soundisland

import com.arnav.island.soundisland.audio.SilentModeManager

enum class SoundIslandMode {
    VOLUME,
    SILENT,
    WORK,
}

data class SoundIslandVisualState(
    val mode: SoundIslandMode = SoundIslandMode.VOLUME,
    val volumeFraction: Float = 0.5f,
    val currentVolume: Int = 8,
    val maxVolume: Int = 15,
    val streamType: Int = 3, // STREAM_MUSIC
    val silentMode: SilentModeManager.Mode = SilentModeManager.Mode.SOUND,
    val workActive: Boolean = false,
    val dndActive: Boolean = false,
    val slideProgress: Float = 0f, // 0 = tucked into bezel, 1 = fully out
    val expandProgress: Float = 0f, // 0 = thin stick, 1 = full expanded capsule
)
