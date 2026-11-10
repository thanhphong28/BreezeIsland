package com.arnav.island.soundisland

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.arnav.island.soundisland.audio.SilentModeManager
import com.arnav.island.soundisland.audio.VolumeKeyObserver
import com.arnav.island.soundisland.audio.VolumeManager
import com.arnav.island.soundisland.render.ScreenEdge
import com.arnav.island.soundisland.work.WorkProfileConfig
import com.arnav.island.soundisland.work.WorkProfileManager
import com.arnav.island.storage.IslandSettings

/**
 * Main coordinator for Breeze Sound Island.
 * Connects audio and focus state changes with the Liquid Glass edge overlay.
 */
class SoundIslandController(
    private val context: Context,
    private val settingsFn: () -> IslandSettings,
) {

    private val handler = Handler(Looper.getMainLooper())
    val volumeManager = VolumeManager(context)
    val silentManager = SilentModeManager(context)
    val workManager = WorkProfileManager(context, volumeManager)

    private val view: SoundIslandView = SoundIslandView(
        context = context,
        initialEdge = settingsFn().soundIslandEdge,
        onVolumeDrag = { frac ->
            val info = volumeManager.getStreamInfo(AudioManager.STREAM_MUSIC)
            val newLevel = (frac * info.max).toInt()
            volumeObserver.recordVolume(AudioManager.STREAM_MUSIC, newLevel)
            volumeManager.setVolumeFraction(AudioManager.STREAM_MUSIC, frac)
            scheduleAutoDismiss()
        },
        onSilentTap = {
            val newMode = silentManager.toggleNext()
            updateVisualState(
                currentVisualState().copy(
                    mode = SoundIslandMode.SILENT,
                    silentMode = newMode,
                )
            )
            scheduleAutoDismiss()
        },
        onWorkTap = {
            val config = WorkProfileConfig(
                enableDnd = settingsFn().soundIslandWorkDnd,
                mediaVolumePercent = settingsFn().soundIslandWorkMediaVol,
                ringVolumePercent = 0,
                notificationVolumePercent = 0,
            )
            val active = workManager.toggle(config)
            updateVisualState(
                currentVisualState().copy(
                    mode = SoundIslandMode.WORK,
                    workActive = active,
                    dndActive = workManager.isDndActive(),
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

    private val volumeObserver = VolumeKeyObserver(context, volumeManager) { streamType, newVol, maxVol, fraction ->
        if (!settingsFn().soundIslandEnabled) return@VolumeKeyObserver
        handler.post {
            show(
                mode = SoundIslandMode.VOLUME,
                streamType = streamType,
                volume = newVol,
                maxVolume = maxVol,
                fraction = fraction,
            )
        }
    }

    private var dismissRunnable = Runnable { dismiss() }
    private var isShowing = false

    fun start() {
        if (settingsFn().soundIslandEnabled) {
            val s = settingsFn()
            overlay.updateEdge(s.soundIslandEdge)
            view.edge = s.soundIslandEdge
            volumeObserver.start()
            updateOverlayVerticalPosition()
        }
    }

    fun stop() {
        handler.removeCallbacks(dismissRunnable)
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
            updateOverlayVerticalPosition()
        } else {
            stop()
        }
    }

    fun show(
        mode: SoundIslandMode = SoundIslandMode.VOLUME,
        streamType: Int = AudioManager.STREAM_MUSIC,
        volume: Int? = null,
        maxVolume: Int? = null,
        fraction: Float? = null,
        force: Boolean = false,
    ) {
        val s = settingsFn()
        if (!force && !s.soundIslandEnabled) return

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
            silentMode = silentManager.currentMode(),
            workActive = workManager.isActive,
            dndActive = workManager.isDndActive(),
            slideProgress = 1f,
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

    fun showTestVolume() = show(SoundIslandMode.VOLUME, force = true)
    fun showTestSilent() = show(SoundIslandMode.SILENT, force = true)
    fun showTestWork() = show(SoundIslandMode.WORK, force = true)
    fun hide() = dismissNow()

    fun dismiss() {
        if (!isShowing) return
        // Instantly disable touch input so any taps or swipes immediately pass through
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
