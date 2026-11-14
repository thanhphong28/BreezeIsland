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
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the **Breeze Mascot** inside the Dynamic Island at 120Hz:
 * - Lush, continuously flowing Aurora bottom gradient (Cyan -> Electric Blue -> Deep Violet)
 *   that melts into pure OLED black around the camera lens.
 * - Glowing white pill-shaped robot eyes with soft cyan/white bloom and lively procedural motion
 *   (glancing, double-blinking, bouncing, head tilting, happy `^ ^` arcs, cheeky `; O` winks,
 *   floating hearts, star sparkles, sleepy `Zzz`, and musical groove notes `♪ ♫` when combined
 *   with active music).
 */
class MascotPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload = MascotPayload()
    private var activeMood = MascotMood.HAPPY
    private var startTimeMs = 0L
    private var lastInteractionCount = -1

    // Interactive bounce & eye-opening springs when summoned or tapped
    private val bounceSpring = Spring(0f, SpringSpec(0.34f, 0.58f), restThreshold = 0.002f)
    private val eyeOpenSpring = Spring(0f, SpringSpec(0.26f, 0.78f), restThreshold = 0.002f)

    // Pre-allocated BlurMaskFilters (never allocated per frame, guaranteed radius >= 1f)
    private val outerEyeBlur = BlurMaskFilter(max(1f, rc.dp(6.5f)), BlurMaskFilter.Blur.NORMAL)
    private val innerEyeBlur = BlurMaskFilter(max(1f, rc.dp(2.5f)), BlurMaskFilter.Blur.NORMAL)
    private val arcEyeBlur = BlurMaskFilter(max(1f, rc.dp(5.5f)), BlurMaskFilter.Blur.NORMAL)
    private val cheekBlur = BlurMaskFilter(max(1f, rc.dp(4.2f)), BlurMaskFilter.Blur.NORMAL)

    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val eyeOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = outerEyeBlur
    }
    private val eyeInnerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = innerEyeBlur
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
        maskFilter = arcEyeBlur
    }
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = cheekBlur
    }
    private val decoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val noteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val shaderMatrix = Matrix()
    private val eyeRect = RectF()
    private val leftPath = Path()
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
        intArrayOf(0xE000F0FF.toInt(), 0x6600B0FF, 0x00000000),
        floatArrayOf(0f, 0.56f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val spotBlue = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xE02979FF.toInt(), 0x663D5AFE, 0x00000000),
        floatArrayOf(0f, 0.58f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val spotViolet = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xE09C27B0.toInt(), 0x66651FFF, 0x00000000),
        floatArrayOf(0f, 0.56f, 1f),
        Shader.TileMode.CLAMP,
    )

    override val glowColor: Int
        get() = 0xFF2979FF.toInt()

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> {
        val p = event.payload as? MascotPayload
        val extraW = when {
            p?.isAssistantActive == true -> dp(28f)
            p?.isMusicPlaying == true -> dp(24f)
            else -> 0f
        }
        return when (mode) {
            PresentMode.EXPANDED -> {
                val w = dp(220f) + extraW
                val h = max(bandInset + dp(48f), dp(78f))
                w to h
            }
            PresentMode.TOAST -> {
                val w = dp(176f) + extraW
                val h = max(bandInset + dp(34f), dp(62f))
                w to h
            }
            else -> {
                val w = max(rc.geometry.compactWidth, dp(160f)) + extraW
                val h = max(rc.geometry.compactHeight + dp(18f), dp(54f))
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
            eyeOpenSpring.snapTo(0.15f)
            eyeOpenSpring.animateTo(1f)
            bounceSpring.snapTo(0f)
            bounceSpring.impulse(14f)
        } else if (p.interactiveCount != lastInteractionCount) {
            lastInteractionCount = p.interactiveCount
            startTimeMs = nowElapsed
            bounceSpring.impulse(18f)
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
        val elapsed = (nowElapsed - startTimeMs).coerceAtLeast(0L)
        // Always keep 120Hz Choreographer loop alive while Mascot is on screen
        return moving || elapsed < event.durationMs + 1_500L
    }

    override fun nextFrameDelay(now: Long): Long = 0L

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        if (alpha <= 0.004f || w <= 2f || h <= 2f) return
        // Use nowElapsed (SystemClock.elapsedRealtime) to match startTimeMs and preserve 100% Float precision!
        val elapsedMs = (nowElapsed - startTimeMs).coerceAtLeast(0L)
        val elapsedS = (elapsedMs % 120_000L) / 1000f
        val scaleFactor = if (mode == PresentMode.EXPANDED) 1.22f else 1.0f

        // 1. Draw the lush, living Aurora Bottom Glow inside the island pill
        drawAuroraBackdrop(canvas, w, h, alpha, elapsedS)

        // 2. Safely calculate eye center positions below the camera cutout (crash-proof for any h)
        val rawCamBottom = rc.geometry.cameraY - rc.geometry.baseTop + rc.geometry.holeRadius + dp(3.5f)
        val maxSafeTop = max(dp(8f), h * 0.46f)
        val minSafeTop = min(dp(16f), maxSafeTop)
        val camSafeTop = rawCamBottom.coerceIn(minSafeTop, maxSafeTop)
        val centerAreaY = camSafeTop + (h - camSafeTop) * 0.48f
        val cx = w / 2f

        // 3. If Gemini / "OK Google" is active or Music is playing alongside Mascot, draw companion effects!
        if (payload.isAssistantActive) {
            drawAssistantCompanion(canvas, w, h, cx, centerAreaY, elapsedS, scaleFactor, alpha)
        } else if (payload.isMusicPlaying) {
            drawMusicCompanion(canvas, w, h, cx, centerAreaY, elapsedS, scaleFactor, alpha)
        }

        // 4. Compute procedural animation state for current mood
        val pose = computeEyePose(activeMood, elapsedS, scaleFactor, payload.isMusicPlaying)
        val springOffsetY = -bounceSpring.value * dp(5.5f)

        val eyeSpacing = dp(26f) * scaleFactor * pose.spacingScale
        val baseEyeW = dp(9.6f) * scaleFactor
        val baseEyeH = dp(15.4f) * scaleFactor
        val openFactor = eyeOpenSpring.value.coerceIn(0.12f, 1.18f)

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

        // Dynamic flowing motion of the 3 Aurora light orbs along the bottom edge
        val drift1 = sin(t * 2.8f) * dp(16f)
        val drift2 = cos(t * 2.2f) * dp(14f)
        val drift3 = sin(t * 3.1f + 1.2f) * dp(15f)
        val breathe1 = 0.85f + 0.18f * sin(t * 3.6f)
        val breathe2 = 0.86f + 0.16f * cos(t * 3.0f)

        // Orb 1: Left Cyan Glow (#00F0FF)
        val c1x = w * 0.20f + drift1
        val c1y = h * (0.93f + 0.05f * sin(t * 2.5f))
        val r1x = max(1f, w * 0.58f)
        val r1y = max(1f, h * 0.72f * breathe1)
        shaderMatrix.setScale(r1x, r1y)
        shaderMatrix.postTranslate(c1x, c1y)
        spotCyan.setLocalMatrix(shaderMatrix)
        auroraPaint.shader = spotCyan
        auroraPaint.alpha = (245 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, 0f, right, bottom, auroraPaint)

        // Orb 2: Center Electric Blue Glow (#2979FF)
        val c2x = w * 0.50f + drift2
        val c2y = h * (0.97f + 0.04f * cos(t * 2.9f))
        val r2x = max(1f, w * 0.60f)
        val r2y = max(1f, h * 0.75f * breathe2)
        shaderMatrix.setScale(r2x, r2y)
        shaderMatrix.postTranslate(c2x, c2y)
        spotBlue.setLocalMatrix(shaderMatrix)
        auroraPaint.shader = spotBlue
        auroraPaint.alpha = (250 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, 0f, right, bottom, auroraPaint)

        // Orb 3: Right Violet/Purple Glow (#9C27B0)
        val c3x = w * 0.80f + drift3
        val c3y = h * (0.93f + 0.05f * cos(t * 2.4f))
        val r3x = max(1f, w * 0.58f)
        val r3y = max(1f, h * 0.72f * breathe1)
        shaderMatrix.setScale(r3x, r3y)
        shaderMatrix.postTranslate(c3x, c3y)
        spotViolet.setLocalMatrix(shaderMatrix)
        auroraPaint.shader = spotViolet
        auroraPaint.alpha = (242 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(left, 0f, right, bottom, auroraPaint)

        // Subtle luminous rim along the bottom edge of the island
        shaderMatrix.setScale(max(1f, w), 1f)
        shaderMatrix.postTranslate(sin(t * 2.0f) * dp(12f), 0f)
        horizontalAurora.setLocalMatrix(shaderMatrix)
        rimPaint.shader = horizontalAurora
        rimPaint.strokeWidth = dp(2.2f)
        rimPaint.alpha = (185 * alpha).toInt().coerceIn(0, 255)
        val inset = dp(0.8f)
        val cornerR = min(h / 2f, rc.geometry.expandedRadius)
        eyeRect.set(inset, -dp(20f), max(inset + 1f, w - inset), max(1f, h - inset))
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
        if (arcAmount > 0.32f) {
            // Happy curved arc eye (^ shape)
            val strokeW = dp(3.8f) * scaleFactor
            val halfW = ew * 0.68f
            val archH = ew * 0.56f * arcAmount

            leftPath.rewind()
            leftPath.moveTo(ex - halfW, ey + archH * 0.36f)
            leftPath.quadTo(ex, ey - archH, ex + halfW, ey + archH * 0.36f)

            // Outer cyan/white halo
            arcEyeGlowPaint.strokeWidth = strokeW * 2.3f
            arcEyeGlowPaint.color = 0xAA70E8FF.toInt()
            arcEyeGlowPaint.alpha = (195 * alpha).toInt().coerceIn(0, 255)
            canvas.drawPath(leftPath, arcEyeGlowPaint)

            // Crisp white core
            arcEyePaint.strokeWidth = strokeW
            arcEyePaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            canvas.drawPath(leftPath, arcEyePaint)
            return
        }

        val halfW = max(dp(1.8f), ew / 2f)
        val halfH = max(dp(1.3f), eh / 2f)
        val rx = min(halfW, halfH)

        eyeRect.set(ex - halfW, ey - halfH, ex + halfW, ey + halfH)

        // Outer soft cyan-white glow halo
        eyeOuterGlowPaint.color = 0xCC80ECFF.toInt()
        eyeOuterGlowPaint.alpha = (215 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(eyeRect, rx, rx, eyeOuterGlowPaint)

        // Inner bright white bloom
        eyeInnerGlowPaint.color = 0xFFFFFFFF.toInt()
        eyeInnerGlowPaint.alpha = (170 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(eyeRect, rx, rx, eyeInnerGlowPaint)

        // Pure white eye core
        eyeCorePaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(eyeRect, rx, rx, eyeCorePaint)
    }

    /**
     * Draws Gemini AI 4-point sparkles (`✦`) and voice-listening aura bars when Mascot appears
     * together with "OK Google" / Gemini Assistant!
     */
    private fun drawAssistantCompanion(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        t: Float,
        scaleFactor: Float,
        alpha: Float,
    ) {
        // 1. Voice-listening equalizer bars on both sides in Gemini gradient colors
        val barCount = 4
        val barW = dp(2.5f) * scaleFactor
        val barGap = dp(4.2f) * scaleFactor
        val maxBarH = dp(15f) * scaleFactor
        val edgeInset = dp(16f) * scaleFactor
        val geminiPalette = intArrayOf(
            0xFF4CC9F0.toInt(),
            0xFF4895EF.toInt(),
            0xFF7209B7.toInt(),
            0xFFF72585.toInt(),
        )

        for (side in -1..1 step 2) {
            val baseX = if (side < 0) edgeInset else w - edgeInset
            for (i in 0 until barCount) {
                val x = baseX - side * (i * barGap)
                val wave = 0.25f + 0.75f * abs(sin(t * (6.4f + i * 1.4f) + i * 0.85f + if (side > 0) 0.9f else 0f))
                val bh = max(barW, maxBarH * wave)
                decoPaint.color = geminiPalette[i % geminiPalette.size]
                decoPaint.alpha = (215 * alpha).toInt().coerceIn(0, 255)
                eyeRect.set(x - barW / 2f, cy - bh / 2f, x + barW / 2f, cy + bh / 2f)
                canvas.drawRoundRect(eyeRect, barW / 2f, barW / 2f, decoPaint)
            }
        }

        // 2. Signature Gemini 4-point AI stars (✦) twinkling around Mascot
        for (i in 0..1) {
            val dir = if (i == 0) -1f else 1f
            val pulse = 0.68f + 0.32f * sin(t * 5.2f + i * 1.7f)
            val sx = cx + dir * dp(35f) * scaleFactor
            val sy = cy - dp(4f) * scaleFactor + cos(t * 3.8f + i) * dp(2.5f)
            val sr = dp(5.6f) * scaleFactor * pulse
            val starColor = if (i == 0) 0xFF70F8FF.toInt() else 0xFFD08BFF.toInt()
            drawSparkle(canvas, sx, sy, sr, starColor, alpha * (0.75f + 0.25f * pulse))
        }
    }

    /**
     * Draws dancing equalizer bars and floating musical notes (`♪`, `♫`) when Mascot appears while
     * music is playing on the Island.
     */
    private fun drawMusicCompanion(
        canvas: Canvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        t: Float,
        scaleFactor: Float,
        alpha: Float,
    ) {
        // 1. Mini rhythmic equalizer bars on left & right edges
        val barCount = 3
        val barW = dp(2.6f) * scaleFactor
        val barGap = dp(4.5f) * scaleFactor
        val maxBarH = dp(14f) * scaleFactor
        val edgeInset = dp(18f) * scaleFactor

        for (side in -1..1 step 2) {
            val baseX = if (side < 0) edgeInset else w - edgeInset
            for (i in 0 until barCount) {
                val x = baseX - side * (i * barGap)
                val wave = 0.28f + 0.72f * abs(sin(t * (5.2f + i * 1.3f) + i * 0.9f + if (side > 0) 1.1f else 0f))
                val bh = max(barW, maxBarH * wave)
                decoPaint.color = if (i == 0) 0xFF00F0FF.toInt() else 0xFFB388FF.toInt()
                decoPaint.alpha = (195 * alpha).toInt().coerceIn(0, 255)
                eyeRect.set(x - barW / 2f, cy - bh / 2f, x + barW / 2f, cy + bh / 2f)
                canvas.drawRoundRect(eyeRect, barW / 2f, barW / 2f, decoPaint)
            }
        }

        // 2. Floating musical notes rising gently beside the Mascot
        for (i in 0..1) {
            val cycle = (t * 0.75f + i * 0.5f) % 1f
            val noteAlpha = sin(cycle * PI.toFloat()).coerceIn(0f, 1f) * alpha
            val dir = if (i == 0) -1f else 1f
            val nx = cx + dir * (dp(36f) * scaleFactor + sin(t * 3.5f + i) * dp(3f))
            val ny = cy + dp(6f) - cycle * dp(15f) * scaleFactor
            val noteColor = if (i == 0) 0xFF70F8FF.toInt() else 0xFFE088FF.toInt()
            drawMusicNote(canvas, nx, ny, dp(4.5f) * scaleFactor, noteColor, noteAlpha)
        }
    }

    private fun drawMusicNote(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, alpha: Float) {
        if (alpha <= 0.02f) return
        val a = (240 * alpha).toInt().coerceIn(0, 255)
        decoPaint.color = color
        decoPaint.alpha = a
        noteStrokePaint.color = color
        noteStrokePaint.alpha = a
        noteStrokePaint.strokeWidth = size * 0.34f

        // Note head
        val headR = size * 0.45f
        canvas.drawCircle(x - headR * 0.3f, y + size * 0.4f, headR, decoPaint)
        // Stem
        val stemX = x + headR * 0.5f
        val topY = y - size * 0.75f
        canvas.drawLine(stemX, y + size * 0.4f, stemX, topY, noteStrokePaint)
        // Flag
        canvas.drawLine(stemX, topY, stemX + size * 0.65f, topY + size * 0.35f, noteStrokePaint)
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
                // Soft rosy-pink cheek glow + twinkling sparkles when smiling
                val smilePhase = smileWindow(elapsedS % 3.4f, 1.0f, 2.55f)
                if (smilePhase > 0.03f) {
                    cheekPaint.color = 0xFFFF7EB3.toInt()
                    cheekPaint.alpha = (155 * alpha * smilePhase).toInt().coerceIn(0, 255)
                    val cheekY = cy + dp(6.5f) * scaleFactor
                    val cheekOffset = eyeSpacing * 0.78f
                    canvas.drawCircle(cx - cheekOffset, cheekY, dp(4.5f) * scaleFactor, cheekPaint)
                    canvas.drawCircle(cx + cheekOffset, cheekY, dp(4.5f) * scaleFactor, cheekPaint)

                    val spAlpha = sin(smilePhase * PI.toFloat()).coerceIn(0f, 1f) * alpha
                    drawSparkle(
                        canvas,
                        rightEyeX + dp(14f) * scaleFactor,
                        cy - dp(6f) * scaleFactor,
                        dp(4.2f) * scaleFactor * smilePhase,
                        0xFF80F8FF.toInt(),
                        spAlpha,
                    )
                }
            }
            MascotMood.WINK -> {
                // Cute 4-point sparkle stars next to the winking eye
                val winkPhase = smileWindow(elapsedS % 2.8f, 0.35f, 2.15f)
                if (winkPhase > 0.03f) {
                    val sx = rightEyeX + dp(14f) * scaleFactor
                    val sy = cy - dp(5f) * scaleFactor - sin(elapsedS * 4f) * dp(2.2f)
                    val sr = dp(5.2f) * scaleFactor * (0.65f + 0.35f * sin(elapsedS * 6f)) * winkPhase
                    drawSparkle(canvas, sx, sy, sr, 0xFF70F8FF.toInt(), alpha * winkPhase)
                }
            }
            MascotMood.LOVE -> {
                // Soft glowing cheeks + floating mini hearts continuously
                cheekPaint.color = 0xFFFF6B9D.toInt()
                cheekPaint.alpha = (165 * alpha).toInt().coerceIn(0, 255)
                val cheekY = cy + dp(6.2f) * scaleFactor
                val cheekOffset = eyeSpacing * 0.78f
                canvas.drawCircle(cx - cheekOffset, cheekY, dp(4.6f) * scaleFactor, cheekPaint)
                canvas.drawCircle(cx + cheekOffset, cheekY, dp(4.6f) * scaleFactor, cheekPaint)

                // Floating hearts on left and right
                for (i in 0..2) {
                    val cycle = (elapsedS * 0.85f + i * 0.33f) % 1f
                    val heartAlpha = sin(cycle * PI.toFloat()).coerceIn(0f, 1f) * alpha
                    val isLeft = i % 2 == 0
                    val baseX = if (isLeft) leftEyeX - dp(15f) * scaleFactor else rightEyeX + dp(15f) * scaleFactor
                    val hx = baseX + sin(cycle * PI.toFloat() * 2f + i) * dp(3f)
                    val hy = cy + dp(4f) - cycle * dp(14f) * scaleFactor
                    val hs = dp(4.5f) * scaleFactor * (0.75f + 0.35f * sin(cycle * PI.toFloat()))
                    drawHeart(canvas, hx, hy, hs, 0xFFFF6B9D.toInt(), heartAlpha)
                }
            }
            MascotMood.EXCITED -> {
                // Twinkling sparkles bursting on both sides
                for (i in 0..2) {
                    val phase = (elapsedS * 1.8f + i * 0.33f) % 1f
                    val spAlpha = sin(phase * PI.toFloat()).coerceIn(0f, 1f) * alpha
                    val sx = when (i) {
                        0 -> leftEyeX - dp(15f) * scaleFactor
                        1 -> rightEyeX + dp(15f) * scaleFactor
                        else -> cx + sin(elapsedS * 4f) * dp(18f) * scaleFactor
                    }
                    val sy = if (i == 2) cy - dp(10f) * scaleFactor else cy - dp(3f) * scaleFactor + cos(elapsedS * 6f + i) * dp(3f)
                    val sr = dp(4.6f) * scaleFactor * (0.6f + 0.45f * sin(phase * PI.toFloat()))
                    val color = if (i % 2 == 0) 0xFF80F8FF.toInt() else 0xFFFFD54A.toInt()
                    drawSparkle(canvas, sx, sy, sr, color, spAlpha)
                }
            }
            MascotMood.SLEEPY -> {
                // Floating sleepy "Z" and dreamy bubbles rising slowly on the right
                for (i in 0..1) {
                    val cycle = (elapsedS * 0.65f + i * 0.5f) % 1f
                    val bAlpha = sin(cycle * PI.toFloat()).coerceIn(0f, 1f) * alpha * 0.9f
                    val bx = rightEyeX + dp(12f + i * 5f) * scaleFactor + cycle * dp(5f)
                    val by = cy + dp(2f) - cycle * dp(13f) * scaleFactor
                    val zs = dp(3.2f + i * 0.8f) * scaleFactor
                    drawSleepyZ(canvas, bx, by, zs, 0xFF90E8FF.toInt(), bAlpha)
                }
            }
            MascotMood.AUTO -> Unit
        }
    }

    private fun drawSleepyZ(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        if (alpha <= 0.02f) return
        noteStrokePaint.color = color
        noteStrokePaint.alpha = (235 * alpha).toInt().coerceIn(0, 255)
        noteStrokePaint.strokeWidth = max(1f, s * 0.36f)
        decoPath.rewind()
        decoPath.moveTo(cx - s, cy - s)
        decoPath.lineTo(cx + s, cy - s)
        decoPath.lineTo(cx - s, cy + s)
        decoPath.lineTo(cx + s, cy + s)
        canvas.drawPath(decoPath, noteStrokePaint)
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

    private fun computeEyePose(
        mood: MascotMood,
        t: Float,
        scaleFactor: Float,
        withMusic: Boolean,
    ): EyePose {
        val blink = naturalBlink(t)
        val musicBobY = if (withMusic) -abs(sin(t * 7.2f)) * dp(2.0f) * scaleFactor else 0f
        val musicTilt = if (withMusic) sin(t * 3.6f) * 3.2f else 0f

        return when (mood) {
            MascotMood.HAPPY -> {
                // Repeating 3.4s choreography: curious left glance -> right glance -> beaming ^ ^ bounce!
                val c = t % 3.4f
                val lookX = when {
                    c < 0.50f -> sin(c / 0.50f * PI.toFloat()) * (-dp(5.5f) * scaleFactor)
                    c < 1.05f -> sin((c - 0.50f) / 0.55f * PI.toFloat()) * (dp(5.8f) * scaleFactor)
                    else -> sin(t * 2.6f) * dp(2.2f) * scaleFactor
                }
                val smile = smileWindow(c, 1.00f, 2.55f)
                val bounceY = if (smile > 0f) {
                    -abs(sin((c - 1.00f) * 8.5f)) * dp(3.0f) * smile
                } else {
                    sin(t * 4.2f) * dp(1.4f)
                }
                val tilt = sin(t * 2.8f) * 4.2f + musicTilt
                EyePose(
                    lookX = lookX,
                    lookY = bounceY + musicBobY,
                    leftScaleX = 1f + 0.10f * smile,
                    leftScaleY = (1f - 0.88f * blink).coerceAtLeast(0.12f),
                    rightScaleX = 1f + 0.10f * smile,
                    rightScaleY = (1f - 0.88f * blink).coerceAtLeast(0.12f),
                    leftArc = smile,
                    rightArc = smile,
                    headTiltDeg = tilt,
                )
            }
            MascotMood.WINK -> {
                // Playful head tilt + left eye stays big & bright while right eye winks!
                val c = t % 2.8f
                val wink = smileWindow(c, 0.35f, 2.15f)
                val lookX = sin(t * 2.5f) * dp(3.5f) * scaleFactor
                val lookY = -abs(sin(t * 5.5f)) * dp(2.0f) * wink + sin(t * 3.5f) * dp(1.2f) + musicBobY
                EyePose(
                    lookX = lookX,
                    lookY = lookY,
                    leftScaleX = 1f + 0.14f * wink,
                    leftScaleY = ((1f + 0.14f * wink) * (1f - 0.88f * blink)).coerceAtLeast(0.12f),
                    rightScaleX = 1f + 0.14f * wink,
                    rightScaleY = (1f - 0.88f * blink).coerceAtLeast(0.12f),
                    rightArc = wink,
                    headTiltDeg = -7.0f * wink + sin(t * 2.8f) * 2.5f + musicTilt,
                )
            }
            MascotMood.LOVE -> {
                // Warm happy arc eyes with gentle rhythmic heartbeat pulse & sway
                val c = t % 3.2f
                val pulse = 0.5f + 0.5f * sin(t * 6.5f)
                val smile = smileWindow(c, 0.35f, 2.75f)
                val lookY = sin(t * 4.0f) * dp(2.0f) * scaleFactor + musicBobY
                EyePose(
                    lookX = sin(t * 2.2f) * dp(3.2f) * scaleFactor,
                    lookY = lookY,
                    leftScaleX = 1f + 0.12f * pulse,
                    leftScaleY = ((1f + 0.10f * pulse) * (1f - 0.85f * blink)).coerceAtLeast(0.12f),
                    rightScaleX = 1f + 0.12f * pulse,
                    rightScaleY = ((1f + 0.10f * pulse) * (1f - 0.85f * blink)).coerceAtLeast(0.12f),
                    leftArc = smile,
                    rightArc = smile,
                    headTiltDeg = sin(t * 2.8f) * 5.2f + musicTilt,
                )
            }
            MascotMood.EXCITED -> {
                // Energetic left-right hop & wide sparkling eyes, alternating eye bounce!
                val c = t % 3.0f
                val hop = abs(sin(t * 9.0f))
                val side = sin(t * 4.5f)
                val smile = smileWindow(c, 1.35f, 2.55f)
                EyePose(
                    lookX = side * dp(5.2f) * scaleFactor,
                    lookY = -hop * dp(3.4f) * scaleFactor + musicBobY,
                    leftScaleX = 1.10f,
                    leftScaleY = (1.14f - 0.85f * blink).coerceAtLeast(0.14f),
                    rightScaleX = 1.10f,
                    rightScaleY = (1.14f - 0.85f * blink).coerceAtLeast(0.14f),
                    leftOffsetY = sin(t * 9.0f) * dp(1.8f),
                    rightOffsetY = -sin(t * 9.0f) * dp(1.8f),
                    leftArc = smile,
                    rightArc = smile,
                    headTiltDeg = side * 6.5f + musicTilt,
                    spacingScale = 1.05f,
                )
            }
            MascotMood.SLEEPY -> {
                // Cozy half-closed eyes breathing slowly and nodding
                val breathe = 0.5f + 0.5f * sin(t * 2.2f)
                val openRatio = 0.22f + 0.18f * breathe
                EyePose(
                    lookX = sin(t * 1.4f) * dp(2.4f) * scaleFactor,
                    lookY = dp(1.5f) + cos(t * 2.2f) * dp(1.8f) + musicBobY * 0.5f,
                    leftScaleX = 1.10f,
                    leftScaleY = openRatio,
                    rightScaleX = 1.10f,
                    rightScaleY = openRatio,
                    headTiltDeg = -3.0f + sin(t * 1.5f) * 3.0f,
                )
            }
            MascotMood.AUTO -> EyePose()
        }
    }

    /** Natural double-blink & periodic blink curve (0 = open, 1 = closed). */
    private fun naturalBlink(t: Float): Float {
        val mod = t % 2.8f
        return when {
            mod in 0.22f..0.36f -> sin((mod - 0.22f) / 0.14f * PI.toFloat())
            mod in 0.42f..0.54f -> sin((mod - 0.42f) / 0.12f * PI.toFloat())
            mod in 1.85f..2.01f -> sin((mod - 1.85f) / 0.16f * PI.toFloat())
            else -> 0f
        }.coerceIn(0f, 1f)
    }

    /** Smoothly ramps 0 -> 1 -> 0 between [startS] and [endS]. */
    private fun smileWindow(cycle: Float, startS: Float, endS: Float): Float {
        if (cycle < startS || cycle > endS) return 0f
        val ramp = 0.18f
        return when {
            cycle < startS + ramp -> (cycle - startS) / ramp
            cycle > endS - ramp -> (endS - cycle) / ramp
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
