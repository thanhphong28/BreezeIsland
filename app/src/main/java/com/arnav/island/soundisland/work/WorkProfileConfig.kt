package com.arnav.island.soundisland.work

data class WorkProfileConfig(
    val enableDnd: Boolean = true,
    val mediaVolumePercent: Int = 30,
    val ringVolumePercent: Int = 0,
    val notificationVolumePercent: Int = 0,
    val vibrateOnly: Boolean = true,
)

data class AudioSnapshot(
    val mediaVolume: Int,
    val ringVolume: Int,
    val notificationVolume: Int,
    val ringerMode: Int,
    val dndFilter: Int,
    val timestamp: Long = System.currentTimeMillis(),
)
