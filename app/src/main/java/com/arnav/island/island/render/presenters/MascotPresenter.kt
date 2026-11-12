package com.arnav.island.island.render.presenters

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MascotMood
import com.arnav.island.events.MascotPayload
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.RenderContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the **Breeze Mascot** inside the Dynamic Island:
 * - Lush Aurora bottom gradient (Cyan -> Electric Blue -> Deep Violet) that melts into pure OLED
 *   black around the camera lens.
 * - Glowing white pill-shaped robot eyes with soft cyan/white bloom.
 * - Rich procedural expressions (Happy, Wink, Love, Excited, Sleepy) at 120Hz.
 */
class MascotPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload = MascotPayload()
    private var activeMood = MascotMood.HAPPY
    private var startTimeMs = 0L
    private var lastInteractionCount = 0

    // Interactive bounce spring when summoned or tapped
    private val bounceSpring = Spring(0f, SpringSpec(0.32f, 0.65f), restThreshold = 0.002f)
    private val eyeOpenSpring = Spring(0f, SpringSpec(0.28f, 0.82f), restThreshold = 0.002f)

    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val auroraMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val arcEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val arcEyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val decoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val shaderMatrix = Matrix()
    private val eyeRect = RectF()
    private val leftPath = Path()
    private val rightPath = Path()
    private val decoPath = Path()

    // Base horizontal gradient: Cyan (#00F0FF) -> Blue (#2979FF) -> Purple/Violet (#8B5CF6)
    private val horizontalAurora = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(
            0xFF00E5FF.toInt(),
            0xFF00B0FF.toInt(),
            0xFF2979FF.toInt(),
            0xFF651FFF.toInt(),
            0xFF9C27B0.toInt(),
        ),
        floatArrayOf(0f, 0.25f, 0.52f, 0.78f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val spotCyan = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xD800F0FF.toInt(), 0x5500B0FF, 0x00000000),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val spotBlue = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xD82979FF.toInt(), 0x553D5AFE, 0x00000000),
        floatArrayOf(0f, 0.58f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val spotViolet = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xD88E24AA.toInt(), 0x55651FFF, 0x00000000),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )

    override val glowColor: Int
        get() = 0xFF2979FF.toInt()

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        return when (mode) {
            PresentMode.EXPANDED -> {
                val w = dp(216f)
                val h = max(bandInset + dp(46f), dp(76f))
                w to h
            }
            PresentMode.TOAST -> {
                val w = dp(172f)
                val h = max(bandInset + dp(32f), dp(60f))
                w to h
            }
            else -> {
                val w = max(rc.geometry.compactWidth, dp(156f))
                val h = max(rc.geometry.compactHeight + dp(18f), dp(52f))
                w to h
            }
        }
    }

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? MascotPayload ?: MascotPayload()
        payload = p
        activeMood = resolveMood(p.mood, p.seed)
        if (!isUpdate || startTimeMs == 0L) {
            startTimeMs = nowElapsed
            lastInteractionCount = p.interactiveCount
            eyeOpenSpring.snapTo(0.1f)
            eyeOpenSpring.animateTo(1f)
            bounceSpring.snapTo(0f)
            bounceSpring.impulse(12f)
        } else if (p.interactiveCount != lastInteractionCount) {
            lastInteractionCount = p.interactiveCount
            startTimeMs = nowElapsed
            bounceSpring.impulse(16f)
        }
    }

    private fun resolveMood(mood: MascotMood, seed: Int): MascotMood {
        if (mood != MascotMood.AUTO) return mood
        val pool = listOf(
            MascotMood.HAPPY,
            MascotMood.WINK,
            MascotMood.LOVE,
            MascotMood.EXCITED,
            MascotMood.SLEEPY,
        )
        val idx = abs(seed) % pool.size
        return pool[idx]
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (bounceSpring.step(dt)) moving = true
        if (eyeOpenSpring.step(dt)) moving = true
        return moving
    }

    override fun nextFrameDelay(now: Long): Long = 0L

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        if (alpha <= 0.004f) return
        val elapsedS = ((now - startTimeMs).coerceAtLeast(0L)) / 1000f
        val scaleFactor = if (mode == PresentMode.EXPANDED) 1.2f else 1.0f

        // 1. Draw the lush Aurora Bottom Glow inside the island pill
        drawAuroraBackdrop(canvas, w, h, alpha, elapsedS)

        // 2. Calculate eye center positions below the camera cutout
        val camSafeTop = (rc.geometry.cameraY - rc.geometry.baseTop + rc.geometry.holeRadius + dp(4f))
            .coerceIn(dp(18f), h * 0.48f)
        val centerAreaY = camSafeTop + (h - camSafeTop) * 0.48f
        val cx = w / 2f

        // Compute procedural animation state for current mood
        val pose = computeEyePose(activeMood, elapsedS, scaleFactor)
        val springOffsetY = -bounceSpring.value * dp(5f)

        val eyeSpacing = dp(26f) * scaleFactor * pose.spacingScale
        val baseEyeW = dp(9.5f) * scaleFactor
        val baseEyeH = dp(15.2f) * scaleFactor
        val openFactor = eyeOpenSpring.value.coerceIn(0.1f, 1.15f)

        val leftX = cx - eyeSpacing / 2f + pose.lookX
        val rightX = cx + eyeSpacing / 2f + pose.lookX
        val baseY = centerAreaY + pose.lookY + springOffsetY

        canvas.save()
        canvas.rotate(pose.headTiltDeg, cx, baseY)

        // Draw Left Eye
        drawSingleEye(
            canvas = canvas,
            ex = leftX,
            ey = baseY + pose.leftOffsetY,
            ew = baseEyeW * pose.leftScaleX,
            eh = baseEyeH * pose.leftScaleY * openFactor,
            arcAmount = pose.leftArc,
            alpha = alpha,
            scaleFactor = scaleFactor,
        )

        // Draw Right Eye
        drawSingleEye(
            canvas = canvas,
            ex = rightX,
            ey = baseY + pose.rightOffsetY,
            ew = baseEyeW * pose.rightScaleX,
            eh = baseEyeH * pose.rightScaleY * openFactor,
            arcAmount = pose.rightArc,
            alpha = alpha,
            scaleFactor = scaleFactor,
        )

        // Draw mood-specific cute decorations (hearts, sparkles, sleepy Zzz, blush)
        drawMoodDecorations(
            canvas = canvas,
            mood = activeMood,
            cx = cx,
            cy = baseY,
            leftEyeX = leftX,
            rightEyeX = rightX,
            eyeSpacing = eyeSpacing,
            elapsedS = elapsedS,
            scaleFactor = scaleFactor,
            alpha = alpha,
        )

        canvas.restore()
    }

    private fun drawAuroraBackdrop(canvas: Canvas, w: Float, h: Float, alpha: Float, t: Float) {
        val pad = dp(28f)
        val left = -pad
        val right = w + pad
        val bottom = h + dp(14f)

        // Animated gentle drift of the 3 Aurora light orbs along the bottom edge
        val drift = sin(t * 2.2f) * dp(10f)
        val breathe = 0.88f + 0.12f * sin(t * 3.0f)

        // Orb 1: Left Cyan Glow (#00F0FF)
        val c1x = w * 0.20f + drift * 0.7f
        val c1y = h * 0.94f
        val r1x = w * 0.56f
        val r1y = h * 0.68f * breathe
        shaderMatrix.setScale(r1x, r1y)
        shaderMatrix.postTranslate(c1x, c1y)
        spotCyan.setLocalMatrix(shaderMatrix)
        auroraPaint.shader = spotCyan
        auroraPaint.alpha = (245 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, 0f, right, bottom, auroraPaint)

        // Orb 2: Center Electric Blue Glow (#2979FF)
        val c2x = w * 0.52f - drift * 0.5f
        val c2y = h * 0.98f
        val r2x = w * 0.58f
        val r2y = h * 0.72f * breathe
        shaderMatrix.setScale(r2x, r2y)
        shaderMatrix.postTranslate(c2x, c2y)
        spotBlue.setLocalMatrix(shaderMatrix)
        auroraPaint.shader = spotBlue
        auroraPaint.alpha = (250 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, 0f, right, bottom, auroraPaint)

        // Orb 3: Right Violet/Purple Glow (#8E24AA)
        val c3x = w * 0.82f + drift * 0.6f
        val c3y = h * 0.94f
        val r3x = w * 0.56f
        val r3y = h * 0.68f * breathe
        shaderMatrix.setScale(r3x, r3y)
        shaderMatrix.postTranslate(c3x, c3y)
        spotViolet.setLocalMatrix(shaderMatrix)
        auroraPaint.shader = spotViolet
        auroraPaint.alpha = (240 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, 0f, right, bottom, auroraPaint)

        // Subtle luminous rim along the bottom edge of the island
        shaderMatrix.setScale(w, 1f)
        shaderMatrix.postTranslate(0f, 0f)
        horizontalAurora.setLocalMatrix(shaderMatrix)
        rimPaint.shader = horizontalAurora
        rimPaint.strokeWidth = dp(2.2f)
        rimPaint.alpha = (180 * alpha).toInt().coerceIn(0, 255)
        val inset = dp(0.8f)
        val cornerR = min(h / 2f, rc.geometry.expandedRadius)
        eyeRect.set(inset, -dp(20f), w - inset, h - inset)
        canvas.drawRoundRect(eyeRect, cornerR, cornerR, rimPaint)
    }

    private fun drawSingleEye(
        canvas: Canvas,
        ex: Float,
        ey: Float,
        ew: Float,
        eh: Float,
        arcAmount: Float,
        alpha: Float,
        scaleFactor: Float,
    ) {
        if (arcAmount > 0.35f) {
            // Happy curved arc eye (^ shape)
            val strokeW = dp(3.8f) * scaleFactor
            val halfW = ew * 0.65f
            val archH = ew * 0.52f * arcAmount

            leftPath.rewind()
            leftPath.moveTo(ex - halfW, ey + archH * 0.35f)
            leftPath.quadTo(ex, ey - archH, ex + halfW, ey + archH * 0.35f)

            // Outer cyan/white halo
            arcEyeGlowPaint.strokeWidth = strokeW * 2.2f
            arcEyeGlowPaint.color = 0xAA70E8FF.toInt()
            arcEyeGlowPaint.alpha = (185 * alpha).toInt().coerceIn(0, 255)
            arcEyeGlowPaint.maskFilter = BlurMaskFilter(dp(5.5f) * scaleFactor, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(leftPath, arcEyeGlowPaint)

            // Crisp white core
            arcEyePaint.strokeWidth = strokeW
            arcEyePaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            canvas.drawPath(leftPath, arcEyePaint)
            return
        }

        val halfW = max(dp(1.8f), ew / 2f)
        val halfH = max(dp(1.4f), eh / 2f)
        val rx = min(halfW, halfH)

        eyeRect.set(ex - halfW, ey - halfH, ex + halfW, ey + halfH)

        // Outer soft cyan-white glow halo (matches the glowing eyes in reference image)
        eyeGlowPaint.color = 0xCC80ECFF.toInt()
        eyeGlowPaint.alpha = (210 * alpha).toInt().coerceIn(0, 255)
        eyeGlowPaint.maskFilter = BlurMaskFilter(dp(6.5f) * scaleFactor, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(eyeRect, rx, rx, eyeGlowPaint)

        // Inner bright white bloom
        eyeGlowPaint.color = 0xFFFFFFFF.toInt()
        eyeGlowPaint.alpha = (160 * alpha).toInt().coerceIn(0, 255)
        eyeGlowPaint.maskFilter = BlurMaskFilter(dp(2.5f) * scaleFactor, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(eyeRect, rx, rx, eyeGlowPaint)

        // Pure white eye core
        eyeCorePaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(eyeRect, rx, rx, eyeCorePaint)
    }

    private fun drawMoodDecorations(
        canvas: Canvas,
        mood: MascotMood,
        cx: Float,
        cy: Float,
        leftEyeX: Float,
        rightEyeX: Float,
        eyeSpacing: Float,
        elapsedS: Float,
        scaleFactor: Float,
        alpha: Float,
    ) {
        when (mood) {
            MascotMood.HAPPY -> {
                // Soft rosy-pink/cyan cheek glow when smiling
                val smilePhase = smileWindow(elapsedS, 1.55f, 3.15f)
                if (smilePhase > 0.05f) {
                    decoPaint.maskFilter = BlurMaskFilter(dp(4f) * scaleFactor, BlurMaskFilter.Blur.NORMAL)
                    decoPaint.color = 0xFFFF7EB3.toInt()
                    decoPaint.alpha = (135 * alpha * smilePhase).toInt().coerceIn(0, 255)
                    val cheekY = cy + dp(6.5f) * scaleFactor
                    val cheekOffset = eyeSpacing * 0.78f
                    canvas.drawCircle(cx - cheekOffset, cheekY, dp(4.2f) * scaleFactor, decoPaint)
                    canvas.drawCircle(cx + cheekOffset, cheekY, dp(4.2f) * scaleFactor, decoPaint)
                    decoPaint.maskFilter = null
                }
            }
            MascotMood.WINK -> {
                // Cute 4-point sparkle star next to the winking eye
                val winkPhase = smileWindow(elapsedS, 0.75f, 2.35f)
                if (winkPhase > 0.05f) {
                    val sx = rightEyeX + dp(14f) * scaleFactor
                    val sy = cy - dp(5f) * scaleFactor - winkPhase * dp(2.5f)
                    val sr = dp(4.8f) * scaleFactor * winkPhase
                    drawSparkle(canvas, sx, sy, sr, 0xFF70F8FF.toInt(), alpha * winkPhase)
                }
            }
            MascotMood.LOVE -> {
                // Soft glowing cheeks + floating mini hearts
                decoPaint.maskFilter = BlurMaskFilter(dp(4.2f) * scaleFactor, BlurMaskFilter.Blur.NORMAL)
                decoPaint.color = 0xFFFF6B9D.toInt()
                decoPaint.alpha = (155 * alpha).toInt().coerceIn(0, 255)
                val cheekY = cy + dp(6.2f) * scaleFactor
                val cheekOffset = eyeSpacing * 0.78f
                canvas.drawCircle(cx - cheekOffset, cheekY, dp(4.5f) * scaleFactor, decoPaint)
                canvas.drawCircle(cx + cheekOffset, cheekY, dp(4.5f) * scaleFactor, decoPaint)
                decoPaint.maskFilter = null

                // Floating hearts on left and right
                for (i in 0..1) {
                    val cycle = (elapsedS * 0.85f + i * 0.5f) % 1f
                    val heartAlpha = sin(cycle * PI.toFloat()).coerceIn(0f, 1f) * alpha
                    val hx = if (i == 0) leftEyeX - dp(15f) * scaleFactor else rightEyeX + dp(15f) * scaleFactor
                    val hy = cy + dp(3f) - cycle * dp(12f) * scaleFactor
                    val hs = dp(4.2f) * scaleFactor * (0.75f + 0.35f * sin(cycle * PI.toFloat()))
                    drawHeart(canvas, hx, hy, hs, 0xFFFF6B9D.toInt(), heartAlpha)
                }
            }
            MascotMood.EXCITED -> {
                // Twinkling sparkles on both sides
                for (i in 0..1) {
                    val phase = (elapsedS * 1.6f + i * 0.5f) % 1f
                    val spAlpha = sin(phase * PI.toFloat()).coerceIn(0f, 1f) * alpha
                    val sx = if (i == 0) leftEyeX - dp(15f) * scaleFactor else rightEyeX + dp(15f) * scaleFactor
                    val sy = cy - dp(3f) * scaleFactor + cos(elapsedS * 5f + i) * dp(2f)
                    val sr = dp(4.4f) * scaleFactor * (0.6f + 0.4f * sin(phase * PI.toFloat()))
                    drawSparkle(canvas, sx, sy, sr, 0xFF80F8FF.toInt(), spAlpha)
                }
            }
            MascotMood.SLEEPY -> {
                // Floating sleepy bubbles / dots rising slowly on the right
                for (i in 0..1) {
                    val cycle = (elapsedS * 0.55f + i * 0.5f) % 1f
                    val bAlpha = sin(cycle * PI.toFloat()).coerceIn(0f, 1f) * alpha * 0.85f
                    val bx = rightEyeX + dp(11f + i * 5f) * scaleFactor + cycle * dp(4f)
                    val by = cy - cycle * dp(11f) * scaleFactor
                    val br = dp(1.8f + i * 0.9f) * scaleFactor
                    decoPaint.color = 0xCC90E0FF.toInt()
                    decoPaint.alpha = (220 * bAlpha).toInt().coerceIn(0, 255)
                    canvas.drawCircle(bx, by, br, decoPaint)
                }
            }
            MascotMood.AUTO -> Unit
        }
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f || r <= 0.5f) return
        val inner = r * 0.26f
        decoPath.rewind()
        decoPath.moveTo(cx, cy - r)
        decoPath.quadTo(cx + inner, cy - inner, cx + r, cy)
        decoPath.quadTo(cx + inner, cy + inner, cx, cy + r)
        decoPath.quadTo(cx - inner, cy + inner, cx - r, cy)
        decoPath.quadTo(cx - inner, cy - inner, cx, cy - r)
        decoPath.close()

        decoPaint.color = color
        decoPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(decoPath, decoPaint)
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f || s <= 0.5f) return
        decoPath.rewind()
        decoPath.moveTo(cx, cy + s * 0.65f)
        decoPath.cubicTo(cx - s * 1.2f, cy - s * 0.1f, cx - s * 0.65f, cy - s * 0.95f, cx, cy - s * 0.3f)
        decoPath.cubicTo(cx + s * 0.65f, cy - s * 0.95f, cx + s * 1.2f, cy - s * 0.1f, cx, cy + s * 0.65f)
        decoPath.close()

        decoPaint.color = color
        decoPaint.alpha = (240 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(decoPath, decoPaint)
    }

    private data class EyePose(
        val lookX: Float = 0f,
        val lookY: Float = 0f,
        val leftScaleX: Float = 1f,
        val leftScaleY: Float = 1f,
        val rightScaleX: Float = 1f,
        val rightScaleY: Float = 1f,
        val leftOffsetY: Float = 0f,
        val rightOffsetY: Float = 0f,
        val leftArc: Float = 0f,
        val rightArc: Float = 0f,
        val headTiltDeg: Float = 0f,
        val spacingScale: Float = 1f,
    )

    private fun computeEyePose(mood: MascotMood, t: Float, scaleFactor: Float): EyePose {
        val blink = naturalBlink(t)

        return when (mood) {
            MascotMood.HAPPY -> {
                // Curiously glances left, then right, then beams with happy ^ ^ eyes!
                val lookX = when {
                    t < 0.7f -> sin(t / 0.7f * PI.toFloat()) * (-dp(4.5f) * scaleFactor)
                    t < 1.45f -> sin((t - 0.7f) / 0.75f * PI.toFloat()) * (dp(4.8f) * scaleFactor)
                    else -> sin(t * 1.8f) * dp(1.5f) * scaleFactor
                }
                val smile = smileWindow(t, 1.55f, 3.15f)
                val bounceY = if (smile > 0f) -abs(sin((t - 1.55f) * 6.5f)) * dp(2.2f) * smile else sin(t * 2.5f) * dp(0.8f)
                val tilt = sin(t * 2.2f) * 2.8f
                EyePose(
                    lookX = lookX,
                    lookY = bounceY,
                    leftScaleX = 1f + 0.08f * smile,
                    leftScaleY = (1f - 0.88f * blink).coerceAtLeast(0.12f),
                    rightScaleX = 1f + 0.08f * smile,
                    rightScaleY = (1f - 0.88f * blink).coerceAtLeast(0.12f),
                    leftArc = smile,
                    rightArc = smile,
                    headTiltDeg = tilt,
                )
            }
            MascotMood.WINK -> {
                // Playful head tilt + left eye stays big & bright while right eye winks!
                val wink = smileWindow(t, 0.75f, 2.35f)
                val lookX = sin(t * 2.0f) * dp(2.5f) * scaleFactor
                val lookY = -wink * dp(1.5f) + sin(t * 3.0f) * dp(0.8f)
                EyePose(
                    lookX = lookX,
                    lookY = lookY,
                    leftScaleX = 1f + 0.10f * wink,
                    leftScaleY = ((1f + 0.10f * wink) * (1f - 0.88f * blink)).coerceAtLeast(0.12f),
                    rightScaleX = 1f + 0.12f * wink,
                    rightScaleY = (1f - 0.88f * blink).coerceAtLeast(0.12f),
                    rightArc = wink,
                    headTiltDeg = -5.5f * wink + sin(t * 2f) * 1.5f,
                )
            }
            MascotMood.LOVE -> {
                // Warm happy arc eyes with gentle rhythmic heartbeat pulse
                val pulse = 0.5f + 0.5f * sin(t * 5.2f)
                val smile = smileWindow(t, 0.9f, 3.4f)
                val lookY = sin(t * 3.2f) * dp(1.4f) * scaleFactor
                EyePose(
                    lookX = sin(t * 1.6f) * dp(2.0f) * scaleFactor,
                    lookY = lookY,
                    leftScaleX = 1f + 0.10f * pulse,
                    leftScaleY = (1f + 0.08f * pulse) * (1f - 0.85f * blink),
                    rightScaleX = 1f + 0.10f * pulse,
                    rightScaleY = (1f + 0.08f * pulse) * (1f - 0.85f * blink),
                    leftArc = smile,
                    rightArc = smile,
                    headTiltDeg = sin(t * 2.4f) * 4.0f,
                )
            }
            MascotMood.EXCITED -> {
                // Energetic left-right hop & wide sparkling eyes, alternating eye bounce!
                val hop = abs(sin(t * 7.5f))
                val side = sin(t * 3.75f)
                val smile = smileWindow(t, 2.1f, 3.3f)
                EyePose(
                    lookX = side * dp(4.2f) * scaleFactor,
                    lookY = -hop * dp(2.8f) * scaleFactor,
                    leftScaleX = 1.08f,
                    leftScaleY = (1.12f - 0.85f * blink).coerceAtLeast(0.14f),
                    rightScaleX = 1.08f,
                    rightScaleY = (1.12f - 0.85f * blink).coerceAtLeast(0.14f),
                    leftOffsetY = sin(t * 7.5f) * dp(1.2f),
                    rightOffsetY = -sin(t * 7.5f) * dp(1.2f),
                    leftArc = smile,
                    rightArc = smile,
                    headTiltDeg = side * 5.0f,
                    spacingScale = 1.04f,
                )
            }
            MascotMood.SLEEPY -> {
                // Cozy half-closed eyes breathing slowly
                val breathe = 0.5f + 0.5f * sin(t * 1.8f)
                val openRatio = 0.24f + 0.14f * breathe
                EyePose(
                    lookX = sin(t * 0.9f) * dp(1.5f) * scaleFactor,
                    lookY = dp(1.5f) + cos(t * 1.8f) * dp(1.2f),
                    leftScaleX = 1.08f,
                    leftScaleY = openRatio,
                    rightScaleX = 1.08f,
                    rightScaleY = openRatio,
                    headTiltDeg = -2.5f + sin(t * 1.1f) * 2.0f,
                )
            }
            MascotMood.AUTO -> EyePose()
        }
    }

    /** Natural double-blink & periodic blink curve (0 = open, 1 = closed). */
    private fun naturalBlink(t: Float): Float {
        val mod = t % 3.6f
        return when {
            mod in 0.42f..0.58f -> sin((mod - 0.42f) / 0.16f * PI.toFloat())
            mod in 2.75f..2.91f -> sin((mod - 2.75f) / 0.16f * PI.toFloat())
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    /** Smoothly ramps 0 -> 1 -> 0 between [startS] and [endS]. */
    private fun smileWindow(t: Float, startS: Float, endS: Float): Float {
        val cycle = t % 4.2f
        if (cycle < startS || cycle > endS) return 0f
        val ramp = 0.22f
        return when {
            cycle < startS + ramp -> (cycle - startS) / ramp
            cycle > endS - ramp -> (endS - cycle) / ramp
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
