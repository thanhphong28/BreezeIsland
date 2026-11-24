package com.arnav.island.dex

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
) {
    val label: String get() = "$name (${width}×$height)"
}

/**
 * Discovers real physical/wireless external monitors (USB-C/HDMI, TV, Projector, Smart View /
 * Miracast) while filtering out the phone's main screen, Galaxy Z Flip's built-in Cover Screen
 * (Flex Window), and BreezeIsland's own virtual scrcpy display.
 */
object ExternalDisplayFinder {
    private val EXCLUDED_NAME_TOKENS = listOf(
        "scrcpy",
        "built-in",
        "cover",
        "subdisplay",
        "sub_display",
        "flex",
        "overlay #",
    )

    internal fun isExternalDisplayCandidate(
        displayId: Int,
        name: String?,
        flags: Int,
        state: Int,
        ignoreVirtualDisplayId: Int,
    ): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return false
        if (ignoreVirtualDisplayId >= 0 && displayId == ignoreVirtualDisplayId) return false
        if (state == Display.STATE_OFF) return false
        if ((flags and Display.FLAG_PRIVATE) != 0) return false
        val lower = name.orEmpty().lowercase()
        if (EXCLUDED_NAME_TOKENS.any { lower.contains(it) }) return false
        return true
    }

    fun findExternalDisplays(context: Context, ignoreVirtualDisplayId: Int = -1): List<Display> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val seen = LinkedHashSet<Int>()
        val result = ArrayList<Display>()

        val presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        for (d in presentation) {
            if (isExternalDisplayCandidate(d.displayId, d.name, d.flags, d.state, ignoreVirtualDisplayId)) {
                if (seen.add(d.displayId)) result.add(d)
            }
        }
        // Fallback: some Miracast / USB-C hubs expose a public non-presentation secondary display.
        if (result.isEmpty()) {
            for (d in dm.displays) {
                val isPresentation = (d.flags and Display.FLAG_PRESENTATION) != 0
                if (isPresentation &&
                    isExternalDisplayCandidate(d.displayId, d.name, d.flags, d.state, ignoreVirtualDisplayId)
                ) {
                    if (seen.add(d.displayId)) result.add(d)
                }
            }
        }
        return result
    }

    fun toInfo(display: Display): ExternalDisplayInfo {
        val mode = display.mode
        return ExternalDisplayInfo(
            displayId = display.displayId,
            name = display.name.orEmpty().ifBlank { "External Display #${display.displayId}" },
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            refreshRate = mode.refreshRate,
        )
    }
}

/**
 * Renders the live Samsung DeX H.264 stream FULLSCREEN directly onto a connected external monitor,
 * TV, projector, or Smart View / Miracast display using a display-scoped `TYPE_APPLICATION_OVERLAY`
 * window owned by [DexService].
 *
 * Because the window lives on the external display inside [DexService], Samsung DeX remains
 * fullscreen on the external screen even while you press Home and use your phone normally!
 */
class DexExternalDisplayHost(
    private val serviceContext: Context,
) {
    companion object {
        private const val TAG = "DexExternalHost"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = serviceContext.getSystemService(DisplayManager::class.java)

    private val _attachedDisplay = MutableStateFlow<ExternalDisplayInfo?>(null)
    val attachedDisplay: StateFlow<ExternalDisplayInfo?> = _attachedDisplay.asStateFlow()

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var attachedDisplayId: Int = -1
    private var currentSession: DexSession? = null
    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0
    private var listenerRegistered = false
    private var autoAttachEnabled = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (autoAttachEnabled && attachedDisplayId == -1) {
                tryAttachBestExternalDisplay()
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == attachedDisplayId) {
                detach()
                if (autoAttachEnabled) tryAttachBestExternalDisplay()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == attachedDisplayId) {
                applyAspectRatio(currentVideoWidth, currentVideoHeight)
            }
        }
    }

    fun startWatching(session: DexSession, autoAttach: Boolean) {
        mainHandler.post {
            currentSession = session
            autoAttachEnabled = autoAttach
            if (!listenerRegistered) {
                displayManager?.registerDisplayListener(displayListener, mainHandler)
                listenerRegistered = true
            }
            if (autoAttach) {
                tryAttachBestExternalDisplay()
            }
        }
    }

    fun updateVideoSize(width: Int, height: Int) {
        currentVideoWidth = width
        currentVideoHeight = height
        mainHandler.post {
            applyAspectRatio(width, height)
            // If we just got video size and haven't attached yet, retry now.
            if (autoAttachEnabled && attachedDisplayId == -1) {
                tryAttachBestExternalDisplay()
            }
        }
    }

    fun tryAttachBestExternalDisplay(): Boolean {
        val session = currentSession ?: return false
        if (!Settings.canDrawOverlays(serviceContext)) {
            Log.w(TAG, "Overlay permission required to draw DeX on external display")
            return false
        }
        val external = ExternalDisplayFinder.findExternalDisplays(serviceContext, session.displayId).firstOrNull()
            ?: return false
        if (attachedDisplayId == external.displayId && rootView != null) return true
        return attachToDisplay(external, session)
    }

    private fun attachToDisplay(display: Display, session: DexSession): Boolean {
        detachInternal()
        return try {
            val displayCtx = serviceContext.createDisplayContext(display)
            val winCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                displayCtx
            }
            val wm = winCtx.getSystemService(WindowManager::class.java)
                ?: return false

            val root = FrameLayout(winCtx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val sv = SurfaceView(winCtx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                )
            }
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    session.videoDecoder?.setSurface(holder.surface)
                    applyAspectRatio(session.videoWidth, session.videoHeight)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    session.videoDecoder?.clearSurface(holder.surface)
                }
            })
            root.addView(sv)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                title = "BreezeIsland-DeX-External"
            }

            wm.addView(root, lp)
            windowManager = wm
            rootView = root
            surfaceView = sv
            attachedDisplayId = display.displayId
            _attachedDisplay.value = ExternalDisplayFinder.toInfo(display)

            root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) {
                    applyAspectRatio(currentVideoWidth, currentVideoHeight)
                }
            }
            Log.i(TAG, "Attached Samsung DeX fullscreen onto external display ${display.displayId} (${display.name})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach DeX window to external display ${display.displayId}", t)
            detachInternal()
            false
        }
    }

    private fun applyAspectRatio(videoWidth: Int, videoHeight: Int) {
        val root = rootView ?: return
        val sv = surfaceView ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        root.post {
            val cw = root.width
            val ch = root.height
            if (cw <= 0 || ch <= 0) return@post
            val scale = minOf(cw.toFloat() / videoWidth, ch.toFloat() / videoHeight)
            val params = sv.layoutParams as FrameLayout.LayoutParams
            params.width = (videoWidth * scale).toInt()
            params.height = (videoHeight * scale).toInt()
            params.gravity = Gravity.CENTER
            sv.layoutParams = params
        }
    }

    fun detach() {
        mainHandler.post { detachInternal() }
    }

    fun stopWatching() {
        mainHandler.post {
            autoAttachEnabled = false
            currentSession = null
            if (listenerRegistered) {
                runCatching { displayManager?.unregisterDisplayListener(displayListener) }
                listenerRegistered = false
            }
            detachInternal()
        }
    }

    private fun detachInternal() {
        val sv = surfaceView
        if (sv != null) {
            runCatching { currentSession?.videoDecoder?.clearSurface(sv.holder.surface) }
        }
        val root = rootView
        val wm = windowManager
        if (root != null && wm != null) {
            runCatching { wm.removeViewImmediate(root) }
        }
        windowManager = null
        rootView = null
        surfaceView = null
        attachedDisplayId = -1
        _attachedDisplay.value = null
    }
}
