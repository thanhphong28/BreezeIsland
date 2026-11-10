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

    private var lastStreamType = AudioManager.STREAM_MUSIC
    private var lastVolumePressTime = 0L
    private var consecutivePressCount = 0
    private var isShowing = false

    private val view: SoundIslandView = SoundIslandView(
        context = context,
        initialEdge = settingsFn().soundIslandEdge,
        onVolumeDrag = { frac ->
            val sType = lastStreamType
            val info = volumeManager.getStreamInfo(sType)
            val newLevel = (frac * info.max).toInt()
            volumeObserver.recordVolume(sType, newLevel)
            volumeManager.setVolumeFraction(sType, frac)
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
        },
    )

    private val overlay = SoundIslandOverlay(context, view, settingsFn().soundIslandEdge)

    private var dismissRunnable = Runnable { dismiss() }
    private var shrinkRunnable = Runnable { view.setExpanded(false) }

    private fun handleVolumeTrigger(streamType: Int, newVol: Int? = null, maxVol: Int? = null, frac: Float? = null) {
        if (!settingsFn().soundIslandEnabled) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastVolumePressTime
        lastVolumePressTime = now

        // Check if user is holding button (<240ms repeat) or clicking consecutively (<500ms)
        val isHoldingKey = elapsed in 1L..240L
        if (elapsed < 500L) {
            consecutivePressCount++
        } else {
            consecutivePressCount = 1
        }

        // Rule from user: Only expand to capsule when pressed > 3 times or held down
        val shouldExpand = consecutivePressCount >= 3 || isHoldingKey

        handler.post {
            handler.removeCallbacks(shrinkRunnable)
            if (shouldExpand) {
                view.setExpanded(true)
                handler.postDelayed(shrinkRunnable, 1200L)
            }
            val info = volumeManager.getStreamInfo(streamType)
            show(
                mode = SoundIslandMode.VOLUME,
                streamType = streamType,
                volume = newVol ?: info.current,
                maxVolume = maxVol ?: info.max,
                fraction = frac ?: info.fraction,
            )
        }
    }

    private val volumeObserver = VolumeKeyObserver(context, volumeManager) { streamType, newVol, maxVol, fraction ->
        handleVolumeTrigger(streamType, newVol, maxVol, fraction)
    }

    private fun connectVolumeSuppressor() {
        VolumeSuppressor.onVolumeChangedCallback = { streamType, _ ->
            val info = volumeManager.getStreamInfo(streamType)
            handleVolumeTrigger(streamType, info.current, info.max, info.fraction)
        }
    }

    fun start() {
        if (settingsFn().soundIslandEnabled) {
            val s = settingsFn()
            overlay.updateEdge(s.soundIslandEdge)
            view.edge = s.soundIslandEdge
            volumeObserver.start()
            connectVolumeSuppressor()
            updateOverlayVerticalPosition()
            if (s.soundIslandSuppressStock && VolumeSuppressor.canUseShizuku()) {
                Thread { VolumeSuppressor.suppress(context) }.start()
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
        streamType: Int = lastStreamType,
        volume: Int? = null,
        maxVolume: Int? = null,
        fraction: Float? = null,
        force: Boolean = false,
    ) {
        val s = settingsFn()
        if (!force && !s.soundIslandEnabled) return
        lastStreamType = streamType

        val info = volumeManager.getStreamInfo(streamType)
        val curVol = volume ?: info.current
        val maxVol = maxVolume ?: info.max
        val frac = fraction ?: info.fraction

        val state = SoundIslandVisualState(
            mode = mode,
            volumeFraction = frac,
            currentVolume = curVol,
            maxVolume = maxVol,
            streamType = streamType,
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
