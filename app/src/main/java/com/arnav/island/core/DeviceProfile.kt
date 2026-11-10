package com.arnav.island.core

import android.os.Build

/**
 * Fallback display and camera parameters per model. Always superseded by Android's own
 * DisplayCutout API on API 29+, and by user calibration when set; kept as a safety net for early
 * frames before the first window insets arrive.
 */
data class DeviceProfile(
    val id: String,
    val displayName: String,
    val models: Set<String>,
    val referenceWidthPx: Int,
    val referenceHeightPx: Int,
    val cameraCenterXFraction: Float,
    val cameraCenterYDp: Float,
    val cameraRadiusDp: Float,
    val cornerRadiusDp: Float,
    val maxRefreshRateHz: Int,
) {
    val fallbackCameraYDp: Float get() = cameraCenterYDp
    val fallbackCameraRadiusDp: Float get() = cameraRadiusDp
}

object DeviceProfiles {

    val GalaxyZFlip5 = DeviceProfile(
        id = "zflip5",
        displayName = "Galaxy Z Flip5",
        models = setOf("SM-F731B", "SM-F731U", "SM-F731U1", "SM-F731W", "SM-F731N", "SM-F7310", "SM-F731Q"),
        referenceWidthPx = 1080,
        referenceHeightPx = 2640,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 16.5f,
        cameraRadiusDp = 9.0f,
        cornerRadiusDp = 38f,
        maxRefreshRateHz = 120,
    )

    val GalaxyZFlip6 = DeviceProfile(
        id = "zflip6",
        displayName = "Galaxy Z Flip6",
        models = setOf("SM-F741B", "SM-F741U", "SM-F741U1", "SM-F741W", "SM-F741N", "SM-F7410", "SM-F741Q"),
        referenceWidthPx = 1080,
        referenceHeightPx = 2640,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 16.5f,
        cameraRadiusDp = 9.0f,
        cornerRadiusDp = 38f,
        maxRefreshRateHz = 120,
    )

    val GalaxyZFlip4 = DeviceProfile(
        id = "zflip4",
        displayName = "Galaxy Z Flip4",
        models = setOf("SM-F721B", "SM-F721U", "SM-F721U1", "SM-F721W", "SM-F721N", "SM-F7210"),
        referenceWidthPx = 1080,
        referenceHeightPx = 2640,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 16.5f,
        cameraRadiusDp = 9.2f,
        cornerRadiusDp = 38f,
        maxRefreshRateHz = 120,
    )

    val GalaxyS23Plus = DeviceProfile(
        id = "s23_plus",
        displayName = "Galaxy S23+",
        models = setOf("SM-S916B", "SM-S916U", "SM-S916U1", "SM-S916W", "SM-S916N", "SM-S9160", "SM-S916E", "SM-S916Q"),
        referenceWidthPx = 1080,
        referenceHeightPx = 2340,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 17.5f,
        cameraRadiusDp = 9.2f,
        cornerRadiusDp = 38f,
        maxRefreshRateHz = 120,
    )

    val GalaxyS23 = DeviceProfile(
        id = "s23",
        displayName = "Galaxy S23",
        models = setOf("SM-S911B", "SM-S911U", "SM-S911U1", "SM-S911W", "SM-S911N", "SM-S9110", "SM-S911E"),
        referenceWidthPx = 1080,
        referenceHeightPx = 2340,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 17.5f,
        cameraRadiusDp = 9.2f,
        cornerRadiusDp = 38f,
        maxRefreshRateHz = 120,
    )

    val GalaxyS23Ultra = DeviceProfile(
        id = "s23_ultra",
        displayName = "Galaxy S23 Ultra",
        models = setOf("SM-S918B", "SM-S918U", "SM-S918U1", "SM-S918W", "SM-S918N", "SM-S9180", "SM-S918E"),
        referenceWidthPx = 1440,
        referenceHeightPx = 3088,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 17f,
        cameraRadiusDp = 8.8f,
        cornerRadiusDp = 14f,
        maxRefreshRateHz = 120,
    )

    val GenericPunchHole = DeviceProfile(
        id = "generic_center_hole",
        displayName = "Centered punch-hole",
        models = emptySet(),
        referenceWidthPx = 1080,
        referenceHeightPx = 2400,
        cameraCenterXFraction = 0.5f,
        cameraCenterYDp = 17f,
        cameraRadiusDp = 9.0f,
        cornerRadiusDp = 34f,
        maxRefreshRateHz = 120,
    )

    private val all = listOf(GalaxyZFlip5, GalaxyZFlip6, GalaxyZFlip4, GalaxyS23Plus, GalaxyS23, GalaxyS23Ultra)

    fun detect(model: String = Build.MODEL.orEmpty().trim()): DeviceProfile = current(model)

    fun current(model: String = Build.MODEL.orEmpty().trim()): DeviceProfile {
        val u = model.uppercase()
        all.firstOrNull { u in it.models }?.let { return it }
        return when {
            u.startsWith("SM-F731") -> GalaxyZFlip5
            u.startsWith("SM-F741") -> GalaxyZFlip6
            u.startsWith("SM-F721") -> GalaxyZFlip4
            u.startsWith("SM-S916") -> GalaxyS23Plus
            u.startsWith("SM-S911") -> GalaxyS23
            u.startsWith("SM-S918") -> GalaxyS23Ultra
            else -> GenericPunchHole
        }
    }
}
