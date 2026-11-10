package com.arnav.island.soundisland

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.arnav.island.soundisland.audio.SilentModeManager
import com.arnav.island.soundisland.audio.VolumeKeyObserver
import com.arnav.island.soundisland.audio.VolumeManager
import com.arnav.island.soundisland.audio.VolumeSuppressor
import com.arnav.island.soundisland.render.ScreenEdge
import com.arnav.island.storage.IslandSettings

/**
 * Main coordinator for Breeze Sound Island.
 * Connects hardware volume buttons and ringer state with the Liquid Glass edge overlay.
 *
 * Dedicated strictly to 2 clean modes:
 *  1. VOLUME: Vertical sleek stick, morphing to tactile capsule on >3 presses or hold/drag.
 *  2. RINGER: Pure icon-only ringer switcher (Ring ↔ Vibrate ↔ Mute).
 */
class SoundIslandController(
    private val context: Context,
    private val settingsFn: () -> IslandSettings,
) {

    private val handler = Handler(Looper.getMainLooper())
    val volumeManager = VolumeManager(context)
    val silentManager = SilentModeManager(context)

    companion object {
        const val TARGET_STREAM = AudioManager.STREAM_MUSIC
    }

    private var lastVolumePressTime = 0L
    private var consecutivePressCount = 0
    private var isShowing = false

    private val view: SoundIslandView = SoundIslandView(
        context = context,
        initialEdge = settingsFn().soundIslandEdge,
        onVolumeDrag = { frac ->
            volumeManager.setVolumeFraction(TARGET_STREAM, frac)
            scheduleAutoDismiss()
        },
        onRingerTap = {
            val newMode = silentManager.toggleNext()
            updateVisualState(
                currentVisualState().copy(
                    mode = SoundIslandMode.RINGER,
                    ringerMode = newMode,
                )
            )
            scheduleAutoDismiss()
        },
        onModeSwitch = { newMode ->
            updateVisualState(currentVisualState().copy(mode = newMode))
            scheduleAutoDismiss()
        },
        onUserInteracted = { scheduleAutoDismiss() },
        onDismissRequested = { dismissNow() },
        onDismissFinished = {
            overlay.setTouchable(false)
            overlay.detach()
            isShowing = false
            consecutivePressCount = 0
            view.expandSpring.snapTo(0f)
            view.setExpanded(false)
        },
    )

    private val overlay = SoundIslandOverlay(context, view, settingsFn().soundIslandEdge)

    private var dismissRunnable = Runnable { dismiss() }
    private var shrinkRunnable = Runnable {
        view.setExpanded(false)
        consecutivePressCount = 1
    }

    private fun handleVolumeTrigger(newVol: Int, maxVol: Int, frac: Float) {
        if (!settingsFn().soundIslandEnabled) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastVolumePressTime
        lastVolumePressTime = now

        if (!isShowing) {
            // First press from hidden state: ALWAYS open in minimal stick mode!
            consecutivePressCount = 1
            handler.removeCallbacks(shrinkRunnable)
            view.expandSpring.snapTo(0f)
            view.setExpanded(false)
        } else {
            // Holding button (repeat rate ~150-200ms) or rapid consecutive clicks (<600ms)
            val isHoldingKey = elapsed in 60L..250L
            if (elapsed in 60L..600L) {
                consecutivePressCount++
            } else if (elapsed > 600L) {
                consecutivePressCount = 1
            }

            // Expand to capsule only on >3 consecutive presses or holding key
            val shouldExpand = consecutivePressCount >= 3 || isHoldingKey
            handler.removeCallbacks(shrinkRunnable)
            if (shouldExpand) {
                view.setExpanded(true)
                // Auto-shrink back to stick after 1.5s of no button presses (iOS behavior)
                handler.postDelayed(shrinkRunnable, 1500L)
            }
        }

        handler.post {
            show(
                mode = SoundIslandMode.VOLUME,
                streamType = TARGET_STREAM,
                volume = newVol,
                maxVolume = maxVol,
                fraction = frac,
            )
        }
    }

    private val volumeObserver = VolumeKeyObserver(context, volumeManager) { newVol, maxVol, fraction ->
        handleVolumeTrigger(newVol, maxVol, fraction)
    }

    private fun connectVolumeSuppressor() {
        // Intentionally keep onVolumeChangedCallback null: BroadcastReceiver is our
        // single, atomic source of truth with verified volume indexes.
        VolumeSuppressor.onVolumeChangedCallback = null
    }

    fun start() {
        if (settingsFn().soundIslandEnabled) {
            val s = settingsFn()
            overlay.updateEdge(s.soundIslandEdge)
            view.edge = s.soundIslandEdge
            volumeObserver.start()
            connectVolumeSuppressor()
            updateOverlayVerticalPosition()
            if (VolumeSuppressor.canUseShizuku()) {
                Thread {
                    // Tell Android & Samsung One UI to always route volume keys to Media
                    VolumeSuppressor.runShizukuShell("settings put secure volume_keys_control_media 1")
                    if (s.soundIslandSuppressStock) {
                        VolumeSuppressor.suppress(context)
                    }
                }.start()
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(dismissRunnable)
        handler.removeCallbacks(shrinkRunnable)
        VolumeSuppressor.onVolumeChangedCallback = null
        volumeObserver.stop()
        overlay.setTouchable(false)
        overlay.detach()
        isShowing = false
    }

    fun updateSettings(s: IslandSettings) {
        overlay.updateEdge(s.soundIslandEdge)
        view.edge = s.soundIslandEdge
        if (s.soundIslandEnabled) {
            volumeObserver.start()
            connectVolumeSuppressor()
            updateOverlayVerticalPosition()
            if (s.soundIslandSuppressStock && !VolumeSuppressor.isSuppressed && VolumeSuppressor.canUseShizuku()) {
                Thread { VolumeSuppressor.suppress(context) }.start()
            } else if (!s.soundIslandSuppressStock && VolumeSuppressor.isSuppressed) {
                Thread { VolumeSuppressor.restore(context) }.start()
            }
        } else {
            stop()
            if (VolumeSuppressor.isSuppressed) {
                Thread { VolumeSuppressor.restore(context) }.start()
            }
        }
    }

    fun show(
        mode: SoundIslandMode = SoundIslandMode.VOLUME,
        streamType: Int = TARGET_STREAM,
        volume: Int? = null,
        maxVolume: Int? = null,
        fraction: Float? = null,
        force: Boolean = false,
    ) {
        val s = settingsFn()
        if (!force && !s.soundIslandEnabled) return

        val info = volumeManager.getStreamInfo(TARGET_STREAM)
        val curVol = volume ?: info.current
        val maxVol = maxVolume ?: info.max
        val frac = fraction ?: info.fraction

        val state = SoundIslandVisualState(
            mode = mode,
            volumeFraction = frac,
            currentVolume = curVol,
            maxVolume = maxVol,
            streamType = TARGET_STREAM,
            ringerMode = silentManager.currentMode(),
            slideProgress = 1f,
            expandProgress = if (mode != SoundIslandMode.VOLUME) 1f else view.expandSpring.target,
        )

        overlay.updateEdge(s.soundIslandEdge)
        view.edge = s.soundIslandEdge
        updateOverlayVerticalPosition()

        overlay.attach()
        overlay.setTouchable(true)
        view.updateState(state, animateSlide = true)
        isShowing = true

        scheduleAutoDismiss()
    }

    fun showTestVolume() {
        show(SoundIslandMode.VOLUME, force = true)
    }

    fun showTestRinger() = show(SoundIslandMode.RINGER, force = true)

    fun hide() = dismissNow()

    fun dismiss() {
        if (!isShowing) return
        handler.removeCallbacks(shrinkRunnable)
        consecutivePressCount = 0
        view.setExpanded(false)
        overlay.setTouchable(false)
        view.updateState(currentVisualState().copy(slideProgress = 0f), animateSlide = true)
    }

    fun dismissNow() {
        handler.removeCallbacks(dismissRunnable)
        dismiss()
    }

    private fun scheduleAutoDismiss() {
        handler.removeCallbacks(dismissRunnable)
        val timeout = settingsFn().soundIslandAutoDismissMs
        handler.postDelayed(dismissRunnable, timeout)
    }

    private fun currentVisualState() = view.visualState

    private fun updateVisualState(state: SoundIslandVisualState) {
        view.updateState(state, animateSlide = true)
    }

    private fun updateOverlayVerticalPosition() {
        val displayMetrics = context.resources.displayMetrics
        val screenH = displayMetrics.heightPixels
        val yPercent = settingsFn().soundIslandYPercent.coerceIn(0.1f, 0.8f)
        val yPx = (screenH * yPercent).toInt()
        overlay.setVerticalPosition(yPx)
    }
}
