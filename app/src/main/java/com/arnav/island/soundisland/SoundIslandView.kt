package com.arnav.island.soundisland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.soundisland.render.ScreenEdge
import com.arnav.island.soundisland.render.SoundIslandRenderer
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class SoundIslandView(
    context: Context,
    initialEdge: ScreenEdge = ScreenEdge.RIGHT,
    val onVolumeDrag: (fraction: Float) -> Unit,
    val onSilentTap: () -> Unit,
    val onWorkTap: () -> Unit,
    val onModeSwitch: (SoundIslandMode) -> Unit,
    val onUserInteracted: () -> Unit,
    val onDismissRequested: () -> Unit,
    val onDismissFinished: () -> Unit,
) : View(context), Choreographer.FrameCallback {

    var edge: ScreenEdge = initialEdge
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val renderer = SoundIslandRenderer(context)
    private val choreographer = Choreographer.getInstance()

    // Motion physics springs: Liquid Glass, smooth damped motion
    val slideSpring = Spring(0f, SpringSpec(0.44f, 0.82f), restThreshold = 0.002f)
    val volumeSpring = Spring(0.5f, SpringSpec(0.24f, 0.90f), restThreshold = 0.001f)
    val expandSpring = Spring(0f, SpringSpec(0.32f, 0.84f), restThreshold = 0.002f)

    var visualState = SoundIslandVisualState()
        private set

    private var frameScheduled = false
    private var lastFrameNanos = 0L

    // Touch gesture tracking
    private enum class GestureDirection { NONE, VERTICAL_VOLUME, HORIZONTAL_SWIPE }
    private var currentGesture = GestureDirection.NONE
    private var trackingTouch = false
    private var downX = 0f
    private var downY = 0f
    private var lastReportedLevel = -1
    private var modeSwitchTriggered = false

    init {
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateState(state: SoundIslandVisualState, animateSlide: Boolean = true) {
        visualState = state
        volumeSpring.animateTo(state.volumeFraction)
        expandSpring.animateTo(state.expandProgress)
        if (animateSlide) {
            slideSpring.animateTo(state.slideProgress)
        } else {
            slideSpring.snapTo(state.slideProgress)
        }
        requestFrame()
    }

    fun setExpanded(expanded: Boolean) {
        expandSpring.animateTo(if (expanded) 1f else 0f)
        requestFrame()
    }

    fun requestFrame() {
        if (frameScheduled || !isAttachedToWindow) return
        frameScheduled = true
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        val dt = if (lastFrameNanos == 0L) 1f / 120f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos

        var moving = false
        if (slideSpring.step(dt)) moving = true
        if (volumeSpring.step(dt)) moving = true
        if (expandSpring.step(dt)) moving = true

        visualState = visualState.copy(
            slideProgress = slideSpring.value,
            volumeFraction = volumeSpring.value,
            expandProgress = expandSpring.value,
        )

        invalidate()

        if (moving) {
            requestFrame()
        } else {
            lastFrameNanos = 0L
            if (slideSpring.target == 0f && slideSpring.value <= 0.02f) {
                onDismissFinished()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        renderer.draw(
            canvas = canvas,
            state = visualState,
            edge = edge,
            screenWidth = w,
            centerY = centerY,
            alpha = 1f,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visualState.slideProgress < 0.25f || slideSpring.target == 0f) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingTouch = true
                currentGesture = GestureDirection.NONE
                modeSwitchTriggered = false
                onUserInteracted()

                if (visualState.mode == SoundIslandMode.VOLUME) {
                    // Instantly expand to full tactile capsule when user touches with finger
                    expandSpring.animateTo(1f)
                    requestFrame()
                    handleVolumeTouch(event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                onUserInteracted()

                val dx = event.x - downX
                val dy = event.y - downY

                // Classify gesture direction if not yet decided
                if (currentGesture == GestureDirection.NONE) {
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    if (absDy > absDx + renderer.dp(4f) && absDy > renderer.dp(6f)) {
                        currentGesture = GestureDirection.VERTICAL_VOLUME
                    } else if (absDx > absDy + renderer.dp(6f) && absDx > renderer.dp(8f)) {
                        currentGesture = GestureDirection.HORIZONTAL_SWIPE
                    }
                }

                when (currentGesture) {
                    GestureDirection.VERTICAL_VOLUME -> {
                        // Strict vertical volume drag: NEVER triggers mode change
                        if (visualState.mode == SoundIslandMode.VOLUME) {
                            handleVolumeTouch(event.y)
                        }
                    }
                    GestureDirection.HORIZONTAL_SWIPE -> {
                        // Horizontal swipe: used exclusively for mode switching or dismissal
                        val isSwipeTowardsCenter = if (edge == ScreenEdge.RIGHT) dx < -renderer.dp(24f) else dx > renderer.dp(24f)
                        val isSwipeTowardsBezel = if (edge == ScreenEdge.RIGHT) dx > renderer.dp(20f) else dx < -renderer.dp(20f)

                        if (isSwipeTowardsCenter && !modeSwitchTriggered) {
                            modeSwitchTriggered = true
                            downX = event.x // Reset origin to prevent multiple fires
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                            // Cycle modes: VOLUME -> SILENT -> WORK -> VOLUME
                            val nextMode = when (visualState.mode) {
                                SoundIslandMode.VOLUME -> SoundIslandMode.SILENT
                                SoundIslandMode.SILENT -> SoundIslandMode.WORK
                                SoundIslandMode.WORK -> SoundIslandMode.VOLUME
                            }
                            onModeSwitch(nextMode)
                        } else if (isSwipeTowardsBezel) {
                            onDismissRequested()
                            trackingTouch = false
                        }
                    }
                    GestureDirection.NONE -> {
                        // In between thresholds, keep updating volume if in volume mode
                        if (visualState.mode == SoundIslandMode.VOLUME) {
                            handleVolumeTouch(event.y)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return false
                trackingTouch = false

                val dx = event.x - downX
                val dy = event.y - downY

                // Tap detection: minimal movement
                if (abs(dx) < renderer.dp(12f) && abs(dy) < renderer.dp(12f)) {
                    when (visualState.mode) {
                        SoundIslandMode.VOLUME -> {
                            // Tap on volume: toggle expand
                            val targetExp = if (expandSpring.target > 0.5f) 0f else 1f
                            expandSpring.animateTo(targetExp)
                            requestFrame()
                        }
                        SoundIslandMode.SILENT -> {
                            onSilentTap()
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        SoundIslandMode.WORK -> {
                            onWorkTap()
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                }
                currentGesture = GestureDirection.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false
                currentGesture = GestureDirection.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleVolumeTouch(touchY: Float) {
        val sliderH = renderer.dp(132f)
        val centerY = height / 2f
        val sliderTop = centerY - sliderH / 2f
        val fraction = (1f - ((touchY - sliderTop) / sliderH)).coerceIn(0f, 1f)

        volumeSpring.animateTo(fraction)
        requestFrame()

        val discreteLevel = (fraction * visualState.maxVolume).toInt()
        if (discreteLevel != lastReportedLevel) {
            lastReportedLevel = discreteLevel
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onVolumeDrag(fraction)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(this)
        frameScheduled = false
        super.onDetachedFromWindow()
    }
}
