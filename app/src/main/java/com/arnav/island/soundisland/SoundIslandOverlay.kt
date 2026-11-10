package com.arnav.island.soundisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import com.arnav.island.soundisland.render.ScreenEdge
import com.arnav.island.util.Diagnostics

/**
 * Manages the WindowManager overlay specifically for Breeze Sound Island.
 * Sits flush along the screen bezel with dynamic left/right placement.
 */
class SoundIslandOverlay(
    private val context: Context,
    private val view: SoundIslandView,
    initialEdge: ScreenEdge = ScreenEdge.RIGHT,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    fun dp(v: Float): Float = v * density

    var attached = false
        private set

    var edge: ScreenEdge = initialEdge
        private set

    private val windowWidth = dp(96f).toInt()
    private val windowHeight = dp(210f).toInt()

    private val params = WindowManager.LayoutParams(
        windowWidth,
        windowHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or (if (edge == ScreenEdge.RIGHT) Gravity.END else Gravity.START)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        x = 0
        y = dp(140f).toInt()
        title = "BreezeSoundIsland"
    }

    fun attach() {
        if (attached) return
        try {
            wm.addView(view, params)
            attached = true
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Could not add SoundIsland overlay window", e)
        }
    }

    fun detach() {
        if (!attached) return
        try {
            wm.removeViewImmediate(view)
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Could not remove SoundIsland overlay window", e)
        }
        attached = false
    }

    fun updateEdge(newEdge: ScreenEdge) {
        if (edge == newEdge) return
        edge = newEdge
        view.edge = newEdge
        params.gravity = Gravity.TOP or (if (edge == ScreenEdge.RIGHT) Gravity.END else Gravity.START)
        if (attached) {
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    fun setVerticalPosition(yPx: Int) {
        if (params.y == yPx) return
        params.y = yPx
        if (attached) {
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    /**
     * Controls whether touch events are forwarded to the island or passed straight through
     * to the underlying app/launcher.
     */
    fun setTouchable(touchable: Boolean) {
        val oldFlags = params.flags
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (attached && oldFlags != params.flags) {
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "SoundIslandOverlay"
    }
}
