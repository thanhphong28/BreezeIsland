package com.arnav.island.island.render.presenters

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.arnav.island.animation.lerp
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.UnlockMethod
import com.arnav.island.events.UnlockPayload
import com.arnav.island.island.render.IslandHaptics
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.ColorExtractor
import kotlin.math.sin

/**
 * iPhone Dynamic Island Unlock Squircle:
 * - Face ID: 3D tilted neon green orbital scanning ring that smoothly morphs into the Apple Face ID smiley face.
 * - Fingerprint: Radiant electric blue biometric ridges with smooth acoustic pulse.
 * - Password: Apple-style warm gold padlock that smoothly springs open.
 *
 * Dimensions: Perfectly centered squircle matching iOS Dynamic Island Face ID.
 */
class UnlockPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: UnlockPayload? = null
    private var boundAt = 0L
    private var hapticPlayed = false

    private val ovalRect = RectF()
    private val innerOvalRect = RectF()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(rc.dp(5f), BlurMaskFilter.Blur.NORMAL)
    }

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: when (payload?.method) {
            UnlockMethod.FACE -> 0xFF30D158.toInt() // Apple Mint / Neon Green
            UnlockMethod.FINGERPRINT -> 0xFF0A84FF.toInt() // Apple Electric Blue
            UnlockMethod.PASSWORD, null -> 0xFFFFD60A.toInt() // Apple Warm Gold
        }

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            // Squircle matching the iPhone Face ID unlock Dynamic Island from user reference screenshot
            val w = rc.dp(82f)
            val h = rc.dp(80f)
            w to h
        } else {
            super.measure(event, mode)
        }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? UnlockPayload
        if (!isUpdate) {
            boundAt = nowElapsed
            hapticPlayed = false
        }
    }

    override fun step(dt: Float): Boolean {
        super.step(dt)
        val elapsed = nowElapsed - boundAt
        if (elapsed > 380L && !hapticPlayed) {
            hapticPlayed = true
            if (rc.settings.haptics) {
                rc.haptics?.play(IslandHaptics.Cue.CONFIRM, touch = false)
            }
        }
        // Continuous smooth animation runs throughout the display period
        return elapsed < event.durationMs
    }

    override fun nextFrameDelay(now: Long): Long {
        val elapsed = nowElapsed - boundAt
        return if (elapsed < event.durationMs) 0L else -1L
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) alpha else alpha * compactVisibility(w, h)
        if (a <= 0.004f) return

        val cx = w / 2f + rc.burnInX
        val cy = h / 2f + dp(7f) + rc.burnInY // Centered in the spacious lower area below top camera cutout

        val elapsed = (nowElapsed - boundAt).coerceAtLeast(0L)
        val method = payload?.method ?: UnlockMethod.FACE

        // Phase 1: Scanning / Verifying (0 -> 400ms)
        // Phase 2: Verified / Authenticated (400ms -> end)
        val verifyT = ((elapsed - 100L) / 350f).coerceIn(0f, 1f)
        val verified = smoothStep(verifyT)

        when (method) {
            UnlockMethod.FACE -> drawFaceId(canvas, cx, cy, verified, elapsed, a)
            UnlockMethod.FINGERPRINT -> drawFingerprint(canvas, cx, cy, verified, elapsed, a)
            UnlockMethod.PASSWORD -> drawPadlock(canvas, cx, cy, verified, elapsed, a)
        }
    }

    private fun drawFaceId(canvas: Canvas, cx: Float, cy: Float, verified: Float, elapsed: Long, alpha: Float) {
        val baseRadius = dp(21f)

        // 1. In scanning phase (verified < 1f): Draw 3D tilted neon green orbital scanning ring
        if (verified < 1f) {
            val scanAlpha = alpha * (1f - verified)
            val spinAngle = -26f + sin(elapsed * 0.0035f) * 12f // Subtle organic tilt matching screenshot

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(spinAngle)
            canvas.scale(1f, 0.62f) // Perspective foreshortening for orbital ellipse

            ovalRect.set(-baseRadius, -baseRadius, baseRadius, baseRadius)
            innerOvalRect.set(-baseRadius * 0.88f, -baseRadius * 0.88f, baseRadius * 0.88f, baseRadius * 0.88f)

            // Outer glowing neon green ring
            ringPaint.color = accent
            ringPaint.alpha = (scanAlpha * 240).toInt()
            ringPaint.strokeWidth = dp(3.2f)
            canvas.drawOval(ovalRect, ringPaint)

            // Neon soft aura
            glowPaint.color = accent
            glowPaint.alpha = (scanAlpha * 140).toInt()
            glowPaint.strokeWidth = dp(5.5f)
            canvas.drawOval(ovalRect, glowPaint)

            // Secondary dimmer offset orbit ring for 3D gyro feel (exact match to screenshot)
            canvas.save()
            canvas.rotate(14f)
            canvas.translate(dp(2.5f), dp(3.5f))
            ringPaint.alpha = (scanAlpha * 110).toInt()
            ringPaint.strokeWidth = dp(2.2f)
            canvas.drawOval(innerOvalRect, ringPaint)
            canvas.restore()

            canvas.restore()
        }

        // 2. Verified phase: Smoothly morph and fade in the Apple Face ID smiley face
        if (verified > 0f) {
            val faceAlpha = alpha * verified
            val faceScale = lerp(0.82f, 1.0f, verified)
            val faceSize = dp(34f) * faceScale

            // Glowing subtle halo behind the face
            if (verified > 0.4f) {
                val haloAlpha = faceAlpha * (verified - 0.4f) / 0.6f * 0.25f
                rc.circle(canvas, cx, cy, baseRadius * 1.15f, accent, haloAlpha)
            }

            rc.glyphs.draw(canvas, Glyph.FACE_ID, cx, cy, faceSize, accent, faceAlpha)
        }
    }

    private fun drawFingerprint(canvas: Canvas, cx: Float, cy: Float, verified: Float, elapsed: Long, alpha: Float) {
        val iconSize = dp(32f)

        // Concentric acoustic pulse wave expanding smoothly
        if (verified < 1f) {
            val pulseT = (elapsed / 450f).coerceIn(0f, 1f)
            val pulseRadius = dp(14f) + dp(18f) * pulseT
            val pulseAlpha = alpha * (1f - pulseT) * 0.6f
            ringPaint.color = accent
            ringPaint.strokeWidth = dp(2f)
            ringPaint.alpha = (pulseAlpha * 255).toInt()
            canvas.drawCircle(cx, cy, pulseRadius, ringPaint)
        }

        // Fingerprint symbol
        val fgColor = if (verified < 0.5f) {
            lerpColor(0xFF8E8E93.toInt(), accent, verified / 0.5f)
        } else {
            accent
        }
        val iconScale = lerp(0.92f, 1.04f, verified)
        rc.glyphs.draw(canvas, Glyph.FINGERPRINT, cx, cy, iconSize * iconScale, fgColor, alpha)

        // Soft aura on confirmation
        if (verified > 0.5f) {
            val auraAlpha = alpha * (verified - 0.5f) * 0.35f
            rc.circle(canvas, cx, cy, dp(22f), accent, auraAlpha)
        }
    }

    private fun drawPadlock(canvas: Canvas, cx: Float, cy: Float, verified: Float, elapsed: Long, alpha: Float) {
        val iconSize = dp(30f)
        val glyph = if (verified > 0.45f) Glyph.LOCK_OPEN else Glyph.LOCK
        val iconColor = if (verified < 0.3f) {
            lerpColor(0xFF8E8E93.toInt(), accent, verified / 0.3f)
        } else {
            accent
        }

        val popScale = lerp(0.9f, 1.0f, verified)
        rc.glyphs.draw(canvas, glyph, cx, cy, iconSize * popScale, iconColor, alpha)

        if (verified > 0.5f) {
            val auraAlpha = alpha * (verified - 0.5f) * 0.3f
            rc.circle(canvas, cx, cy, dp(20f), accent, auraAlpha)
        }
    }

    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerpColor(c1: Int, c2: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = lerp((c1 ushr 24).toFloat(), (c2 ushr 24).toFloat(), f).toInt()
        val r = lerp(((c1 ushr 16) and 0xFF).toFloat(), ((c2 ushr 16) and 0xFF).toFloat(), f).toInt()
        val g = lerp(((c1 ushr 8) and 0xFF).toFloat(), ((c2 ushr 8) and 0xFF).toFloat(), f).toInt()
        val b = lerp((c1 and 0xFF).toFloat(), (c2 and 0xFF).toFloat(), f).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    override val contentDescription: String
        get() = "${payload?.title ?: event.title}. ${payload?.subtitle ?: event.subtitle}"
}
