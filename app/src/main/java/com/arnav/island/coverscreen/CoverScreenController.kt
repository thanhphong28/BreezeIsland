package com.arnav.island.coverscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.arnav.island.core.AppGraph
import com.arnav.island.coverscreen.render.CoverScreenView
import com.arnav.island.storage.IslandSettings
import kotlin.math.sqrt

/**
 * Manages the **Z Flip 5 Cover Screen** overlay (`Display #1` / Samsung `BUILTIN` secondary display):
 * - Automatically detects when the phone is folded and the Flex Window turns on (`Display.STATE_ON`).
 * - Attaches a full-screen interactive [CoverScreenView] onto the secondary display using
 *   `createDisplayContext(coverDisplay).createWindowContext(TYPE_APPLICATION_OVERLAY)`.
 * - Monitors the accelerometer (`Sensor.TYPE_ACCELEROMETER`) while the cover screen is active so
 *   shaking the phone triggers playful 3D puppy reactions.
 * - Supports minimizing to a compact `[🐾]` edge tab so the user can view/use underlying apps and
 *   expand back at any time.
 */
class CoverScreenController(
    private val context: Context,
    private val graph: AppGraph,
    private val settingsFn: () -> IslandSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)

    private var started = false
    private var attachedDisplayId: Int = Display.INVALID_DISPLAY
    private var coverWm: WindowManager? = null
    private var coverView: CoverScreenView? = null
    private var isMinimized = false

    private var shakeRegistered = false
    private var lastShakeElapsed = 0L
    private var lastAccelMag = SensorManager.GRAVITY_EARTH

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { handler.post { syncCoverOverlay() } }
        override fun onDisplayRemoved(displayId: Int) { handler.post { syncCoverOverlay() } }
        override fun onDisplayChanged(displayId: Int) { handler.post { syncCoverOverlay() } }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handler.post {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    isMinimized = false
                }
                syncCoverOverlay()
            }
        }
    }

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = sqrt(x * x + y * y + z * z)
            val delta = mag - lastAccelMag
            lastAccelMag = mag
            val linearExcess = kotlin.math.abs(mag - SensorManager.GRAVITY_EARTH)
            if (linearExcess > 11.5f || kotlin.math.abs(delta) > 9.5f) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastShakeElapsed > 1_800L) {
                    lastShakeElapsed = now
                    coverView?.triggerShake()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        runCatching { displayManager.registerDisplayListener(displayListener, handler) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        syncCoverOverlay()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { context.unregisterReceiver(screenReceiver) }
        detachCoverOverlay()
    }

    fun updateSettings(s: IslandSettings) {
        coverView?.settings = s
        syncCoverOverlay()
    }

    fun isCoverDisplayPresent(): Boolean = findCoverDisplay(displayManager) != null

    fun coverDisplaySummary(): String {
        val d = findCoverDisplay(displayManager) ?: return "Không phát hiện màn hình phụ"
        val stateName = when (d.state) {
            Display.STATE_ON -> "Đang bật"
            Display.STATE_OFF -> "Đang tắt"
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "AOD"
            else -> "Trạng thái ${d.state}"
        }
        return "Display #${d.displayId} (${d.name}) · $stateName"
    }

    private fun syncCoverOverlay() {
        val s = settingsFn()
        if (!started || !s.enabled || !s.coverScreenEnabled || !Settings.canDrawOverlays(context)) {
            detachCoverOverlay()
            return
        }

        val cover = findCoverDisplay(displayManager)
        if (cover == null || cover.state != Display.STATE_ON) {
            detachCoverOverlay()
            return
        }

        if (coverView == null || attachedDisplayId != cover.displayId) {
            attachToDisplay(cover, s)
        } else {
            coverView?.settings = s
            updateShakeSensor(shouldListen = !isMinimized && s.coverShakeEnabled)
        }
    }

    private fun attachToDisplay(cover: Display, s: IslandSettings) {
        detachCoverOverlay()
        try {
            val displayCtx = context.createDisplayContext(cover)
            val winCtx = displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = winCtx.getSystemService(WindowManager::class.java)
            val view = CoverScreenView(
                context = winCtx,
                graph = graph,
                isOverlayMode = true,
                targetDisplayId = cover.displayId,
                onMinimizeRequested = { setMinimized(true) },
                onExpandRequested = { setMinimized(false) },
            ).apply {
                settings = s
                isMinimizedHandle = this@CoverScreenController.isMinimized
            }

            wm.addView(view, buildLayoutParams(winCtx, minimized = isMinimized))
            coverWm = wm
            coverView = view
            attachedDisplayId = cover.displayId
            updateShakeSensor(shouldListen = !isMinimized && s.coverShakeEnabled)
        } catch (_: Throwable) {
            detachCoverOverlay()
        }
    }

    private fun setMinimized(minimized: Boolean) {
        if (isMinimized == minimized) return
        isMinimized = minimized
        val view = coverView ?: return
        val wm = coverWm ?: return
        view.isMinimizedHandle = minimized
        runCatching {
            wm.updateViewLayout(view, buildLayoutParams(view.context, minimized))
        }
        updateShakeSensor(shouldListen = !minimized && settingsFn().coverShakeEnabled)
    }

    private fun buildLayoutParams(ctx: Context, minimized: Boolean): WindowManager.LayoutParams {
        val density = ctx.resources.displayMetrics.density
        val sizePx = (48f * density).toInt()
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        return if (minimized) {
            WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = (6f * density).toInt()
            }
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= 30) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
    }

    private fun detachCoverOverlay() {
        updateShakeSensor(false)
        val view = coverView
        val wm = coverWm
        coverView = null
        coverWm = null
        attachedDisplayId = Display.INVALID_DISPLAY
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
    }

    private fun updateShakeSensor(shouldListen: Boolean) {
        if (shouldListen && !shakeRegistered) {
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                runCatching {
                    sensorManager.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_UI, handler)
                    shakeRegistered = true
                }
            }
        } else if (!shouldListen && shakeRegistered) {
            runCatching { sensorManager?.unregisterListener(shakeListener) }
            shakeRegistered = false
        }
    }

    companion object {
        private const val SAMSUNG_BUILTIN_DISPLAY_CATEGORY = "com.samsung.android.hardware.display.category.BUILTIN"

        fun findCoverDisplay(dm: DisplayManager): Display? {
            // 1. Samsung Galaxy Z Flip Flex Window built-in category
            runCatching {
                dm.getDisplays(SAMSUNG_BUILTIN_DISPLAY_CATEGORY)
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            }.getOrNull()?.let { return it }

            // 2. All displays / presentation displays
            dm.getDisplays().firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }

            // 3. Direct display ID 1 lookup fallback
            return dm.getDisplay(1)?.takeIf { it.displayId != Display.DEFAULT_DISPLAY }
        }
    }
}
