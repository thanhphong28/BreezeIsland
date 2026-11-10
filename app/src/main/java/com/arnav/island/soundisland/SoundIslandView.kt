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
import com.arnav.island.soundisland.render.EdgeBezelShape
import com.arnav.island.soundisland.render.ScreenEdge
import com.arnav.island.soundisland.render.SoundIslandRenderer
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class SoundIslandView(
    context: Context,
    val edge: ScreenEdge = ScreenEdge.RIGHT,
    val onVolumeDrag: (fraction: Float) -> Unit,
    val onSilentTap: () -> Unit,
    val onWorkTap: () -> Unit,
    val onModeSwitch: (SoundIslandMode) -> Unit,
    val onUserInteracted: () -> Unit,
    val onDismissRequested: () -> Unit,
) : View(context), Choreographer.FrameCallback {

    private val renderer = SoundIslandRenderer(context)
    private val choreographer = Choreographer.getInstance()

    // Motion physics springs
    val slideSpring = Spring(0f, SpringSpec(0.36f, 0.74f), restThreshold = 0.003f)
    val volumeSpring = Spring(0.5f, SpringSpec(0.28f, 0.82f), restThreshold = 0.002f)

    var visualState = SoundIslandVisualState()
        private set

    private var frameScheduled = false
    private var lastFrameNanos = 0L

    // Touch interaction state
    private var trackingTouch = false
    private var downX = 0f
    private var downY = 0f
    private var lastReportedLevel = -1

    init {
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateState(state: SoundIslandVisualState, animateSlide: Boolean = true) {
        val oldMode = visualState.mode
        visualState = state
        volumeSpring.animateTo(state.volumeFraction)
        if (animateSlide) {
            slideSpring.animateTo(state.slideProgress)
        } else {
            slideSpring.snapTo(state.slideProgress)
        }
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

        visualState = visualState.copy(
            slideProgress = slideSpring.value,
            volumeFraction = volumeSpring.value,
        )

        invalidate()

        if (moving) {
            requestFrame()
        } else {
            lastFrameNanos = 0L
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
            notchHeight = renderer.dp(160f),
            tabWidth = renderer.dp(78f),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                trackingTouch = true
                onUserInteracted()

                if (visualState.mode == SoundIslandMode.VOLUME) {
                    handleVolumeTouch(event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                onUserInteracted()
                val dx = event.x - downX
                val dy = event.y - downY

                // Fling/swipe right towards bezel dismisses
                if (edge == ScreenEdge.RIGHT && dx > renderer.dp(24f)) {
                    onDismissRequested()
                    trackingTouch = false
                    return true
                }

                if (visualState.mode == SoundIslandMode.VOLUME) {
                    handleVolumeTouch(event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return false
                trackingTouch = false
                val dx = event.x - downX
                val dy = event.y - downY

                // Tap detection (minimal movement)
                if (abs(dx) < renderer.dp(12f) && abs(dy) < renderer.dp(12f)) {
                    when (visualState.mode) {
                        SoundIslandMode.VOLUME -> {
                            // Cycle to SILENT on tap outside slider or double tap
                            onModeSwitch(SoundIslandMode.SILENT)
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleVolumeTouch(touchY: Float) {
        val sliderH = renderer.dp(132f)
        val centerY = height / 2f
        val sliderTop = centerY - sliderH / 2f
        val sliderBottom = centerY + sliderH / 2f

        val fraction = (1f - ((touchY - sliderTop) / sliderH)).coerceIn(0f, 1f)
        volumeSpring.snapTo(fraction)
        visualState = visualState.copy(volumeFraction = fraction)
        invalidate()

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
