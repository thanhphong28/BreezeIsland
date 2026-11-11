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
    val onRingerTap: () -> Unit,
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
    val slideLaunchSpec = SpringSpec(0.26f, 0.94f)
    val slideRetractSpec = SpringSpec(0.20f, 0.96f)
    val slideSpring = Spring(0f, slideLaunchSpec, restThreshold = 0.002f)
    val volumeSpring = Spring(0.5f, SpringSpec(0.18f, 0.94f), restThreshold = 0.001f)
    val expandSpring = Spring(0f, SpringSpec(0.22f, 0.94f), restThreshold = 0.002f)
    val ringerPopSpring = Spring(1f, SpringSpec(0.18f, 0.94f), restThreshold = 0.002f)

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
    private var downTime = 0L
    private var isLongPressOrDrag = false
    private var lastReportedLevel = -1
    private var modeSwitchTriggered = false

    private val longPressRunnable = Runnable {
        if (!trackingTouch) return@Runnable
        isLongPressOrDrag = true
        if (visualState.mode == SoundIslandMode.VOLUME) {
            setExpanded(true)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    init {
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateState(state: SoundIslandVisualState, animateSlide: Boolean = true) {
        val wasClosed = slideSpring.target == 0f && slideSpring.value <= 0.04f

        // If not attached yet or slide spring was at rest 0, snap volume immediately so it doesn't jump
        if (!isAttachedToWindow || wasClosed) {
            volumeSpring.snapTo(state.volumeFraction)
        } else {
            volumeSpring.animateTo(state.volumeFraction)
        }

        expandSpring.animateTo(state.expandProgress)
        if (animateSlide) {
            if (state.slideProgress > 0f) {
                if (wasClosed) {
                    slideSpring.snapTo(0f)
                    slideSpring.animateTo(1f, slideLaunchSpec)
                    slideSpring.impulse(2.4f)
                } else {
                    slideSpring.animateTo(state.slideProgress, slideLaunchSpec)
                    slideSpring.impulse(0.35f)
                }
            } else {
                slideSpring.animateTo(0f, slideRetractSpec)
            }
        } else {
            slideSpring.snapTo(state.slideProgress)
        }

        visualState = state.copy(
            volumeFraction = volumeSpring.value,
            slideProgress = slideSpring.value,
            expandProgress = expandSpring.value,
            ringerPopScale = ringerPopSpring.value,
        )
        requestFrame()
    }

    fun setExpanded(expanded: Boolean) {
        expandSpring.animateTo(if (expanded) 1f else 0f)
        requestFrame()
    }

    fun triggerRingerPop() {
        ringerPopSpring.snapTo(0.72f)
        ringerPopSpring.animateTo(1f)
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
        if (ringerPopSpring.step(dt)) moving = true

        visualState = visualState.copy(
            slideProgress = slideSpring.value,
            volumeFraction = volumeSpring.value,
            expandProgress = expandSpring.value,
            ringerPopScale = ringerPopSpring.value,
        )

        invalidate()

        if (moving) {
            requestFrame()
        } else {
            lastFrameNanos = 0L
            if (slideSpring.target == 0f && slideSpring.value <= 0.04f) {
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
                downTime = System.currentTimeMillis()
                isLongPressOrDrag = false
                currentGesture = GestureDirection.NONE
                modeSwitchTriggered = false

                // If user tapped outside the active notch boundary, dismiss immediately
                val notchW = renderer.dp(if (visualState.mode == SoundIslandMode.VOLUME) 32f + 44f * expandSpring.value else 56f)
                val isInsideNotch = if (edge == ScreenEdge.RIGHT) {
                    event.x >= (width - notchW - renderer.dp(16f))
                } else {
                    event.x <= (notchW + renderer.dp(16f))
                }
                if (!isInsideNotch) {
                    onDismissRequested()
                    return false
                }

                trackingTouch = true
                onUserInteracted()

                // Schedule long press check (160ms): holding triggers expansion to full capsule
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, 160L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                onUserInteracted()

                val dx = event.x - downX
                val dy = event.y - downY
                val absDx = abs(dx)
                val absDy = abs(dy)

                // Classify gesture direction if not yet decided
                if (currentGesture == GestureDirection.NONE) {
                    if (absDy > absDx + renderer.dp(4f) && absDy > renderer.dp(6f)) {
                        currentGesture = GestureDirection.VERTICAL_VOLUME
                        isLongPressOrDrag = true
                        removeCallbacks(longPressRunnable)
                        if (visualState.mode == SoundIslandMode.VOLUME) {
                            setExpanded(true)
                        }
                    } else if (absDx > absDy + renderer.dp(6f) && absDx > renderer.dp(8f)) {
                        currentGesture = GestureDirection.HORIZONTAL_SWIPE
                        removeCallbacks(longPressRunnable)
                    }
                }

                when (currentGesture) {
                    GestureDirection.VERTICAL_VOLUME -> {
                        // Strict vertical volume drag: only active when user holds and drags
                        if (visualState.mode == SoundIslandMode.VOLUME) {
                            handleVolumeTouch(event.y)
                        }
                    }
                    GestureDirection.HORIZONTAL_SWIPE -> {
                        // Horizontal swipe: switch between VOLUME <-> RINGER or dismiss
                        val isSwipeTowardsCenter = if (edge == ScreenEdge.RIGHT) dx < -renderer.dp(24f) else dx > renderer.dp(24f)
                        val isSwipeTowardsBezel = if (edge == ScreenEdge.RIGHT) dx > renderer.dp(20f) else dx < -renderer.dp(20f)

                        if (isSwipeTowardsCenter && !modeSwitchTriggered) {
                            modeSwitchTriggered = true
                            downX = event.x
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                            val nextMode = if (visualState.mode == SoundIslandMode.VOLUME) {
                                SoundIslandMode.RINGER
                            } else {
                                SoundIslandMode.VOLUME
                            }
                            onModeSwitch(nextMode)
                        } else if (isSwipeTowardsBezel) {
                            onDismissRequested()
                            trackingTouch = false
                        }
                    }
                    GestureDirection.NONE -> {
                        // Still holding in place
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (!trackingTouch) return false
                trackingTouch = false

                val elapsed = System.currentTimeMillis() - downTime
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val isSingleTap = !isLongPressOrDrag && currentGesture == GestureDirection.NONE && dx < renderer.dp(12f) && dy < renderer.dp(12f) && elapsed < 350L

                if (isSingleTap) {
                    // Tap rule from user:
                    // 1. Tapping on volume does NOT change volume; it opens Ringer mode!
                    // 2. Tapping on ringer switches the ringer state with pop animation!
                    if (visualState.mode == SoundIslandMode.VOLUME) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onModeSwitch(SoundIslandMode.RINGER)
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        triggerRingerPop()
                        onRingerTap()
                    }
                }

                currentGesture = GestureDirection.NONE
                onUserInteracted()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
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
        removeCallbacks(longPressRunnable)
        choreographer.removeFrameCallback(this)
        frameScheduled = false
        super.onDetachedFromWindow()
    }
}
