package com.arnav.island.island.render.presenters

import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.arnav.island.animation.lerp
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.UnlockPayload
import com.arnav.island.island.render.IslandHaptics
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
 * True-3D Volumetric Biometric Unlock Squircle (v2.1.3):
 * - Dimensions: 104dp x 100dp centered squircle enclosing the top camera punch hole.
 * - Seamless 3-Stage 120Hz 3D Choreography:
 *   1. (0ms -> 460ms): Holographic 3D Biometric Fingerprint with sweeping neon laser scanline,
 *      which then folds in 3D perspective directly into the orbital ring.
 *   2. (320ms -> 1240ms): True 3D Projected Gyroscopic iPhone Rings — analytical 3D perspective
 *      projection (x, y, z) with 3 volumetric layers (deep emerald echo ring, secondary stereo
 *      trail ring, and primary luminous neon green ring with front-hemisphere specular highlight),
 *      matching iOS Face ID's 3D glass torus 1:1.
 *   3. (1020ms -> 2050ms): 3D Perspective Unfold into a Custom Sculpted 3D Padlock (with solid
 *      extruded 3D thickness, metallic gradient, keyhole, and a physical shackle that lifts up
 *      and swings open with a radial light bloom).
 */
class UnlockPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: UnlockPayload? = null
    private var boundAt = 0L
    private var hapticPlayed = false

    // 3D Perspective Camera for Fingerprint & Sculpted Padlock
    private val camera3D = Camera().apply {
        setLocation(0f, 0f, -12f)
    }
    private val matrix3D = Matrix()
    private val shaderMatrix = Matrix()

    // Pre-allocated BlurMaskFilters (safe radius >= 1f, zero per-frame allocations)
    private val outerRingBlur = BlurMaskFilter(max(1f, rc.dp(7.5f)), BlurMaskFilter.Blur.NORMAL)
    private val midRingBlur = BlurMaskFilter(max(1f, rc.dp(3.2f)), BlurMaskFilter.Blur.NORMAL)
    private val echoRingBlur = BlurMaskFilter(max(1f, rc.dp(2.4f)), BlurMaskFilter.Blur.NORMAL)
    private val scanLineBlur = BlurMaskFilter(max(1f, rc.dp(4.5f)), BlurMaskFilter.Blur.NORMAL)
    private val bloomBlur = BlurMaskFilter(max(1f, rc.dp(14f)), BlurMaskFilter.Blur.NORMAL)
    private val lockGlowBlur = BlurMaskFilter(max(1f, rc.dp(6f)), BlurMaskFilter.Blur.NORMAL)

    // Paints for 3D Projected Gyroscopic Rings
    private val ringOuterAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = outerRingBlur
    }
    private val ringMidGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = midRingBlur
    }
    private val ringCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringEchoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = echoRingBlur
    }

    // Laser scanline & ambient glow paints
    private val scanGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = scanLineBlur
    }
    private val scanCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = bloomBlur
    }

    // Sculpted 3D Padlock paints
    private val lockExtrudePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lockBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lockRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shackleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = lockGlowBlur
    }
    private val shacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val keyholePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF06150B.toInt()
    }

    private val lockBodyGradient = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(
            0xFF7BFF9E.toInt(),
            0xFF34D860.toInt(),
            0xFF20A844.toInt(),
        ),
        floatArrayOf(0f, 0.52f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val ambientHaloGradient = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x5530D158, 0x2200E5FF, 0x00000000),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val fullRingPath = Path()
    private val frontArcPath = Path()
    private val echoRingPath1 = Path()
    private val echoRingPath2 = Path()
    private val shacklePath = Path()
    private val bodyRect = RectF()

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            val w = rc.dp(104f)
            val h = rc.dp(100f)
            w to h
        } else {
            super.measure(event, mode)
        }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? UnlockPayload
        if (!isUpdate || boundAt == 0L) {
            boundAt = nowElapsed
            hapticPlayed = false
        }
    }

    override fun step(dt: Float): Boolean {
        super.step(dt)
        val elapsed = (nowElapsed - boundAt).coerceAtLeast(0L)
        if (elapsed > 1180L && !hapticPlayed) {
            hapticPlayed = true
            if (rc.settings.haptics) {
                rc.haptics?.play(IslandHaptics.Cue.CONFIRM, touch = false)
            }
        }
        return elapsed < event.durationMs + 400L
    }

    override fun nextFrameDelay(now: Long): Long = 0L

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        if (alpha <= 0.004f || w <= 4f || h <= 4f) return
        val a = if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) alpha else alpha * compactVisibility(w, h)
        if (a <= 0.004f) return

        // Scale smoothly with the squircle as it opens/closes from the camera
        val sizeScale = min(w / dp(104f), h / dp(100f)).coerceIn(0.35f, 1.15f)
        val cx = w / 2f + rc.burnInX
        val cy = h * 0.56f + rc.burnInY
        val elapsed = (nowElapsed - boundAt).coerceAtLeast(0L)

        // Subtle ambient 3D depth halo behind the center
        val haloR = max(1f, dp(36f) * sizeScale)
        shaderMatrix.setScale(haloR, haloR)
        shaderMatrix.postTranslate(cx, cy)
        ambientHaloGradient.setLocalMatrix(shaderMatrix)
        bloomPaint.shader = ambientHaloGradient
        bloomPaint.alpha = (180 * a).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, haloR, bloomPaint)
        bloomPaint.shader = null

        // =========================================================================================
        // STAGE 1 (0ms .. 480ms): 3D Biometric Fingerprint with Laser Scan -> 3D Fold into Ring
        // =========================================================================================
        if (elapsed < 480L) {
            val stageT = (elapsed / 480f).coerceIn(0f, 1f)
            val foldT = ((elapsed - 280f) / 200f).coerceIn(0f, 1f)
            val smoothFold = easeInCubic(foldT)
            val fpAlpha = a * (1f - smoothFold)

            if (fpAlpha > 0.01f) {
                val rotX = smoothFold * 64f
                val rotY = smoothFold * 78f
                val fpScale = lerp(0.88f, 1.04f, min(1f, elapsed / 220f)) * lerp(1f, 0.78f, smoothFold)
                val iconSize = dp(35f) * sizeScale * fpScale

                canvas.save()
                camera3D.save()
                camera3D.rotateX(rotX)
                camera3D.rotateY(rotY)
                camera3D.getMatrix(matrix3D)
                camera3D.restore()
                matrix3D.preTranslate(-cx, -cy)
                matrix3D.postTranslate(cx, cy)
                canvas.concat(matrix3D)

                // Fingerprint color transitions from Electric Cyan (#00F0FF) to Apple Neon Green (#30D158)
                val scanProgress = (elapsed / 320f).coerceIn(0f, 1f)
                val fpColor = blendArgb(0xFF00F0FF.toInt(), 0xFF34E868.toInt(), scanProgress)
                rc.glyphs.draw(canvas, Glyph.FINGERPRINT, cx, cy, iconSize, fpColor, fpAlpha)

                // Sweeping horizontal biometric laser scanline
                if (elapsed < 360L) {
                    val scanY = cy - iconSize * 0.48f + iconSize * 0.96f * easeInOutCubic(scanProgress)
                    val scanHalfW = iconSize * 0.54f * sin(scanProgress * PI.toFloat()).coerceIn(0.25f, 1f)
                    val lineAlpha = sin(scanProgress * PI.toFloat()).coerceIn(0f, 1f) * fpAlpha

                    scanGlowPaint.color = 0xFF30D158.toInt()
                    scanGlowPaint.strokeWidth = dp(4.5f) * sizeScale
                    scanGlowPaint.alpha = (210 * lineAlpha).toInt().coerceIn(0, 255)
                    canvas.drawLine(cx - scanHalfW, scanY, cx + scanHalfW, scanY, scanGlowPaint)

                    scanCorePaint.color = 0xFFD8FFE4.toInt()
                    scanCorePaint.strokeWidth = dp(1.6f) * sizeScale
                    scanCorePaint.alpha = (255 * lineAlpha).toInt().coerceIn(0, 255)
                    canvas.drawLine(cx - scanHalfW, scanY, cx + scanHalfW, scanY, scanCorePaint)
                }

                canvas.restore()
            }
        }

        // =========================================================================================
        // STAGE 2 (300ms .. 1240ms): True-3D Projected Gyroscopic iPhone Rings (iOS Face ID Torus)
        // =========================================================================================
        if (elapsed in 300L..1240L) {
            val ringT = ((elapsed - 300f) / 940f).coerceIn(0f, 1f)
            val fadeIn = (ringT / 0.16f).coerceIn(0f, 1f)
            val fadeOut = ((1f - ringT) / 0.18f).coerceIn(0f, 1f)
            val ringAlpha = a * min(fadeIn, fadeOut)

            if (ringAlpha > 0.01f) {
                // Smooth 3D orbital angles matching the iPhone Face ID 3D green ring
                val orbit = easeInOutCubic(ringT)
                val rotY = -38f + orbit * 398f // Completes a full 3D gyroscopic revolution
                val rotX = 52f * cos(orbit * PI.toFloat() * 1.35f)
                val rotZ = -24f + orbit * 145f

                // Radius expands smoothly, holds full 3D presence, then contracts into the padlock
                val baseRadius = when {
                    ringT < 0.18f -> lerp(dp(18f), dp(29.5f), easeOutQuad(ringT / 0.18f))
                    ringT > 0.80f -> lerp(dp(29.5f), dp(15f), easeInQuad((ringT - 0.80f) / 0.20f))
                    else -> dp(29.5f)
                } * sizeScale

                val focal = dp(125f) * sizeScale

                // 1. Deep Emerald 3D Echo Ring (Inner offset 3D glass shadow ring, just like iPhone photo)
                project3DRing(
                    outFull = echoRingPath2,
                    outFront = null,
                    cx = cx,
                    cy = cy,
                    radius = baseRadius * 0.88f,
                    rotXDeg = rotX + 16f,
                    rotYDeg = rotY - 34f,
                    rotZDeg = rotZ - 18f,
                    focal = focal,
                )
                ringEchoPaint.color = 0xFF1E9E40.toInt()
                ringEchoPaint.strokeWidth = dp(2.2f) * sizeScale
                ringEchoPaint.alpha = (110 * ringAlpha).toInt().coerceIn(0, 255)
                canvas.drawPath(echoRingPath2, ringEchoPaint)

                // 2. Secondary Stereoscopic Trailing 3D Ring
                project3DRing(
                    outFull = echoRingPath1,
                    outFront = null,
                    cx = cx,
                    cy = cy,
                    radius = baseRadius * 0.95f,
                    rotXDeg = rotX + 8f,
                    rotYDeg = rotY - 18f,
                    rotZDeg = rotZ - 9f,
                    focal = focal,
                )
                ringEchoPaint.color = 0xFF30D158.toInt()
                ringEchoPaint.strokeWidth = dp(2.6f) * sizeScale
                ringEchoPaint.alpha = (155 * ringAlpha).toInt().coerceIn(0, 255)
                canvas.drawPath(echoRingPath1, ringEchoPaint)

                // 3. Primary Luminous 3D Neon Ring + Front-Hemisphere Specular Highlight
                project3DRing(
                    outFull = fullRingPath,
                    outFront = frontArcPath,
                    cx = cx,
                    cy = cy,
                    radius = baseRadius,
                    rotXDeg = rotX,
                    rotYDeg = rotY,
                    rotZDeg = rotZ,
                    focal = focal,
                )

                // Layer 3A: Wide Outer Neon Green Aura
                ringOuterAuraPaint.color = 0xFF28C850.toInt()
                ringOuterAuraPaint.strokeWidth = dp(7.0f) * sizeScale
                ringOuterAuraPaint.alpha = (175 * ringAlpha).toInt().coerceIn(0, 255)
                canvas.drawPath(fullRingPath, ringOuterAuraPaint)

                // Layer 3B: Medium Vivid Green Bloom
                ringMidGlowPaint.color = 0xFF42E86B.toInt()
                ringMidGlowPaint.strokeWidth = dp(4.2f) * sizeScale
                ringMidGlowPaint.alpha = (225 * ringAlpha).toInt().coerceIn(0, 255)
                canvas.drawPath(fullRingPath, ringMidGlowPaint)

                // Layer 3C: Crisp Full Ring Core
                ringCorePaint.color = 0xFF6BFF92.toInt()
                ringCorePaint.strokeWidth = dp(2.7f) * sizeScale
                ringCorePaint.alpha = (250 * ringAlpha).toInt().coerceIn(0, 255)
                canvas.drawPath(fullRingPath, ringCorePaint)

                // Layer 3D: Front-Hemisphere (z < 0, closest to viewer) Bright Mint-White Specular Core
                ringCorePaint.color = 0xFFD4FFE0.toInt()
                ringCorePaint.strokeWidth = dp(3.1f) * sizeScale
                ringCorePaint.alpha = (255 * ringAlpha).toInt().coerceIn(0, 255)
                canvas.drawPath(frontArcPath, ringCorePaint)
            }
        }

        // =========================================================================================
        // STAGE 3 (1040ms .. end): 3D Perspective Unfold into Sculpted 3D Padlock + Shackle Pop
        // =========================================================================================
        if (elapsed >= 1040L) {
            val lockElapsed = elapsed - 1040L
            val unfoldT = (lockElapsed / 320f).coerceIn(0f, 1f)
            val lockAlpha = a * (lockElapsed / 140f).coerceIn(0f, 1f)

            // Radiant confirmation bloom wave when the shackle pops open
            if (elapsed in 1160L..1720L) {
                val bloomT = ((elapsed - 1160f) / 560f).coerceIn(0f, 1f)
                val bloomRadius = (dp(14f) + dp(30f) * easeOutQuad(bloomT)) * sizeScale
                val bloomAlpha = a * (1f - bloomT) * 0.52f
                bloomPaint.color = 0xFF34E868.toInt()
                bloomPaint.alpha = (bloomAlpha * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, bloomRadius, bloomPaint)
            }

            // Smooth 3D rotation with subtle spring settle: -82 deg -> +7 deg -> 0 deg
            val angleY = dampedSpringAngle(unfoldT)
            val angleX = (1f - easeOutCubic(unfoldT)) * 18f

            // Shackle pop-open progress (starts right as the lock faces the user at ~1180ms)
            val shackleT = ((elapsed - 1170f) / 280f).coerceIn(0f, 1f)
            val shackleOpen = easeOutBack(shackleT)

            drawSculpted3DPadlock(
                canvas = canvas,
                cx = cx,
                cy = cy + dp(2f) * sizeScale,
                scale = sizeScale * lerp(0.86f, 1.0f, easeOutBack(unfoldT).coerceIn(0f, 1.08f)),
                angleX = angleX,
                angleY = angleY,
                shackleOpen = shackleOpen,
                alpha = lockAlpha,
            )
        }
    }

    /**
     * Analytically projects a 3D ring of radius [radius] rotated by Euler angles
     * ([rotXDeg], [rotYDeg], [rotZDeg]) into 2D screen space with perspective [focal].
     * Also populates [outFront] with the front-facing hemisphere (z < 0) for true 3D depth shading.
     */
    private fun project3DRing(
        outFull: Path,
        outFront: Path?,
        cx: Float,
        cy: Float,
        radius: Float,
        rotXDeg: Float,
        rotYDeg: Float,
        rotZDeg: Float,
        focal: Float,
    ) {
        outFull.rewind()
        outFront?.rewind()

        val rx = rotXDeg * (PI.toFloat() / 180f)
        val ry = rotYDeg * (PI.toFloat() / 180f)
        val rz = rotZDeg * (PI.toFloat() / 180f)

        val cosX = cos(rx); val sinX = sin(rx)
        val cosY = cos(ry); val sinY = sin(ry)
        val cosZ = cos(rz); val sinZ = sin(rz)

        val steps = 60
        var frontStarted = false

        for (i in 0..steps) {
            val theta = (i.toFloat() / steps) * (2f * PI.toFloat())
            val x0 = radius * cos(theta)
            val y0 = radius * sin(theta)

            // 1. Rotate around Z
            val x1 = x0 * cosZ - y0 * sinZ
            val y1 = x0 * sinZ + y0 * cosZ

            // 2. Rotate around X
            val y2 = y1 * cosX
            val z2 = y1 * sinX

            // 3. Rotate around Y
            val x3 = x1 * cosY + z2 * sinY
            val z3 = -x1 * sinY + z2 * cosY

            // Perspective projection (z3 < 0 is closer to camera)
            val persp = focal / max(dp(24f), focal + z3)
            val sx = cx + x3 * persp
            val sy = cy + y2 * persp

            if (i == 0) {
                outFull.moveTo(sx, sy)
            } else {
                outFull.lineTo(sx, sy)
            }

            if (outFront != null) {
                if (z3 <= 0f) {
                    if (!frontStarted) {
                        outFront.moveTo(sx, sy)
                        frontStarted = true
                    } else {
                        outFront.lineTo(sx, sy)
                    }
                } else {
                    frontStarted = false
                }
            }
        }
        outFull.close()
    }

    /**
     * Draws a solid, 3D-extruded iOS-style Padlock with an animated shackle that lifts and unlatches.
     */
    private fun drawSculpted3DPadlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        angleX: Float,
        angleY: Float,
        shackleOpen: Float,
        alpha: Float,
    ) {
        val bodyW = dp(24.5f) * scale
        val bodyH = dp(19.5f) * scale
        val bodyCorner = dp(6.2f) * scale
        val bodyTop = cy - bodyH * 0.28f
        val bodyBottom = bodyTop + bodyH

        canvas.save()
        camera3D.save()
        camera3D.rotateX(angleX)
        camera3D.rotateY(angleY)
        camera3D.getMatrix(matrix3D)
        camera3D.restore()
        matrix3D.preTranslate(-cx, -cy)
        matrix3D.postTranslate(cx, cy)
        canvas.concat(matrix3D)

        // 1. 3D Side Extrusion (gives the padlock real 3D block thickness while rotating)
        val extrudeOffset = -sin(angleY * (PI.toFloat() / 180f)) * dp(4.2f) * scale
        if (abs(extrudeOffset) > 0.3f) {
            lockExtrudePaint.color = 0xFF146B2C.toInt()
            lockExtrudePaint.alpha = (240 * alpha).toInt().coerceIn(0, 255)
            bodyRect.set(
                cx - bodyW / 2f + extrudeOffset,
                bodyTop + dp(0.6f),
                cx + bodyW / 2f + extrudeOffset,
                bodyBottom + dp(0.6f),
            )
            canvas.drawRoundRect(bodyRect, bodyCorner, bodyCorner, lockExtrudePaint)
        }

        // 2. Animated 3D Shackle (U-shaped top bar that lifts up and swings open)
        val shackleHalfW = bodyW * 0.29f
        val shackleLift = shackleOpen * dp(4.8f) * scale
        val shackleArcTop = bodyTop - dp(10.8f) * scale - shackleLift
        val shackleStroke = dp(3.1f) * scale

        shacklePath.rewind()
        // Left leg stays anchored inside the left side of the lock body
        val leftX = cx - shackleHalfW
        val rightX = cx + shackleHalfW
        val archCenterY = shackleArcTop + shackleHalfW

        shacklePath.moveTo(leftX, bodyTop + dp(2.5f) * scale)
        shacklePath.lineTo(leftX, archCenterY)
        bodyRect.set(leftX, shackleArcTop, rightX, shackleArcTop + shackleHalfW * 2f)
        shacklePath.arcTo(bodyRect, 180f, 180f, false)
        // Right leg lifts out of the lock body when shackleOpen > 0!
        val rightLegBottom = (bodyTop + dp(2.5f) * scale) - shackleOpen * dp(6.2f) * scale
        shacklePath.lineTo(rightX, max(archCenterY, rightLegBottom))

        // Shackle soft glow
        shackleGlowPaint.color = 0xFF42E86B.toInt()
        shackleGlowPaint.strokeWidth = shackleStroke * 1.9f
        shackleGlowPaint.alpha = (160 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(shacklePath, shackleGlowPaint)

        // Shackle bright metallic-mint core
        shacklePaint.color = 0xFFB8FFCC.toInt()
        shacklePaint.strokeWidth = shackleStroke
        shacklePaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(shacklePath, shacklePaint)

        // 3. Solid 3D Lock Body with vertical luminous gradient
        bodyRect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyBottom)
        shaderMatrix.setScale(1f, max(1f, bodyH))
        shaderMatrix.postTranslate(0f, bodyTop)
        lockBodyGradient.setLocalMatrix(shaderMatrix)
        lockBodyPaint.shader = lockBodyGradient
        lockBodyPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(bodyRect, bodyCorner, bodyCorner, lockBodyPaint)

        // Specular rim around the lock body
        lockRimPaint.color = 0xAAFFFFFF.toInt()
        lockRimPaint.strokeWidth = dp(1.1f) * scale
        lockRimPaint.alpha = (150 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(bodyRect, bodyCorner, bodyCorner, lockRimPaint)

        // 4. Crisp Keyhole in the center of the lock body
        val khY = bodyTop + bodyH * 0.45f
        val khR = dp(2.1f) * scale
        keyholePaint.alpha = (235 * alpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, khY, khR, keyholePaint)
        bodyRect.set(cx - khR * 0.52f, khY, cx + khR * 0.52f, khY + dp(4.5f) * scale)
        canvas.drawRoundRect(bodyRect, khR * 0.5f, khR * 0.5f, keyholePaint)

        canvas.restore()
    }

    /** Smoothly animates from -82 deg -> +6 deg overshoot -> 0 deg. */
    private fun dampedSpringAngle(t: Float): Float {
        if (t <= 0f) return -82f
        if (t >= 1f) return 0f
        val decay = (1f - t) * (1f - t)
        return -82f * decay * cos(t * PI.toFloat() * 1.35f)
    }

    private fun blendArgb(c1: Int, c2: Int, ratio: Float): Int {
        val f = ratio.coerceIn(0f, 1f)
        val a = ((c1 ushr 24) + (((c2 ushr 24) - (c1 ushr 24)) * f)).toInt()
        val r = (((c1 ushr 16) and 0xFF) + ((((c2 ushr 16) and 0xFF) - ((c1 ushr 16) and 0xFF)) * f)).toInt()
        val g = (((c1 ushr 8) and 0xFF) + ((((c2 ushr 8) and 0xFF) - ((c1 ushr 8) and 0xFF)) * f)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * f)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f) / 2f

    private fun easeOutCubic(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

    private fun easeInCubic(x: Float): Float = x * x * x

    private fun easeOutQuad(x: Float): Float = 1f - (1f - x) * (1f - x)

    private fun easeInQuad(x: Float): Float = x * x

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = x - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }

    override val contentDescription: String
        get() = "${payload?.title ?: event.title}. ${payload?.subtitle ?: event.subtitle}"
}
