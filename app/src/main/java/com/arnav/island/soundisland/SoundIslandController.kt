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

        // Debounce rapid duplicate callbacks arriving within 35ms
        if (elapsed < 35L) return
        lastVolumePressTime = now

        val wasClosedOrClosing = !isShowing || view.slideSpring.target == 0f
        if (wasClosedOrClosing) {
            // First press from hidden or closing state: ALWAYS open in minimal stick mode!
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

    private val volumeObserver = VolumeKeyObserver(
        context = context,
        volumeManager = volumeManager,
        onVolumeChanged = { newVol, maxVol, fraction ->
            handleVolumeTrigger(newVol, maxVol, fraction)
        },
        onSystemVolumeChanged = { newVol, maxVol, fraction ->
            // If user adjusts volume via Samsung Quick Settings slider or Bluetooth while island is open,
            // update island slider position smoothly in real time!
            if (isShowing && view.visualState.mode == SoundIslandMode.VOLUME) {
                handler.post {
                    updateVisualState(
                        currentVisualState().copy(
                            currentVolume = newVol,
                            maxVolume = maxVol,
                            volumeFraction = fraction,
                        )
                    )
                }
            }
        }
    )

    @Volatile private var isInternalAdjustment = false

    private val lastObservedStreamVolumes = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private fun connectVolumeSuppressor() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio?.let {
            lastObservedStreamVolumes[android.media.AudioManager.STREAM_RING] = it.getStreamVolume(android.media.AudioManager.STREAM_RING)
            lastObservedStreamVolumes[android.media.AudioManager.STREAM_NOTIFICATION] = it.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)
            lastObservedStreamVolumes[android.media.AudioManager.STREAM_SYSTEM] = it.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM)
        }

        VolumeSuppressor.onVolumeChangedCallback = { streamType, flags ->
            if (!isInternalAdjustment) {
                val hasShowUi = (flags and android.media.AudioManager.FLAG_SHOW_UI) != 0

                if (streamType == TARGET_STREAM) {
                    val streamInfo = volumeManager.getStreamInfo(TARGET_STREAM)
                    handleVolumeTrigger(streamInfo.current, streamInfo.max, streamInfo.fraction)
                } else if (hasShowUi && streamType == android.media.AudioManager.STREAM_RING) {
                    // Only redirect if this was a physical volume button press modifying STREAM_RING (when no music is playing).
                    // NEVER intercept or modify STREAM_NOTIFICATION, so Messenger/Zalo chimes are 100% untouched!
                    val currentVol = audio?.getStreamVolume(streamType) ?: -1
                    val prevVol = lastObservedStreamVolumes[streamType] ?: currentVol
                    lastObservedStreamVolumes[streamType] = currentVol

                    val direction = when {
                        currentVol > prevVol -> android.media.AudioManager.ADJUST_RAISE
                        currentVol < prevVol -> android.media.AudioManager.ADJUST_LOWER
                        else -> null
                    }

                    if (direction != null) {
                        try {
                            isInternalAdjustment = true
                            volumeManager.adjustVolume(TARGET_STREAM, direction)
                            if (prevVol != -1 && currentVol != prevVol && audio != null) {
                                audio.setStreamVolume(streamType, prevVol, 0)
                                lastObservedStreamVolumes[streamType] = prevVol
                            }
                        } finally {
                            handler.postDelayed({ isInternalAdjustment = false }, 80L)
                        }
                    }

                    val mediaInfo = volumeManager.getStreamInfo(TARGET_STREAM)
                    handleVolumeTrigger(mediaInfo.current, mediaInfo.max, mediaInfo.fraction)
                }
            }
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
            if (VolumeSuppressor.canUseShizuku()) {
                Thread {
                    VolumeSuppressor.setupMediaKeyRouting(context)
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
            if (VolumeSuppressor.canUseShizuku()) {
                Thread {
                    VolumeSuppressor.setupMediaKeyRouting(context)
                    if (s.soundIslandSuppressStock && !VolumeSuppressor.isSuppressed) {
                        VolumeSuppressor.suppress(context)
                    } else if (!s.soundIslandSuppressStock && VolumeSuppressor.isSuppressed) {
                        VolumeSuppressor.restore(context)
                    }
                }.start()
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
        view.expandSpring.snapTo(0f)
        overlay.setTouchable(false)
        view.updateState(currentVisualState().copy(slideProgress = 0f, expandProgress = 0f), animateSlide = true)
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
