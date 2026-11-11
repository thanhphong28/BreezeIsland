package com.arnav.island.island.render.presenters

import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.arnav.island.animation.lerp
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.UnlockPayload
import com.arnav.island.island.render.IslandHaptics
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.ColorExtractor
import kotlin.math.PI
import kotlin.math.sin

/**
 * iPhone Dynamic Island 3D Biometric Unlock Squircle (v2.0.3):
 * - Dimensions: 98dp x 94dp centered squircle enclosing the top camera punch hole.
 * - Sequence:
 *   1. (0 -> 350ms): Glowing holographic 3D biometric fingerprint with subtle sonar radar pulse.
 *   2. (300ms -> 1000ms): 3D Gyroscopic iPhone Orbiting Ring (Perspective 3D space with dual-layer
 *      luminous Apple neon green glow and spatial depth trail, matching iOS Face ID).
 *   3. (950ms -> 1220ms): Seamless 3D flip transitioning into the opened golden padlock.
 *   4. (950ms -> end): Radiant warm gold bloom burst with haptic confirmation.
 */
class UnlockPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: UnlockPayload? = null
    private var boundAt = 0L
    private var hapticPlayed = false

    // 3D Perspective Projection
    private val camera3D = Camera().apply {
        setLocation(0f, 0f, -10f)
    }
    private val matrix3D = Matrix()

    // Paints for 3D Gyroscopic Rings
    private val ringCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(rc.dp(5.5f), BlurMaskFilter.Blur.NORMAL)
    }

    private val ringTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(rc.dp(3.5f), BlurMaskFilter.Blur.NORMAL)
    }

    // Sonar radar pulse
    private val sonarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Radiant confirmation bloom
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(rc.dp(16f), BlurMaskFilter.Blur.NORMAL)
    }

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            val w = rc.dp(98f)
            val h = rc.dp(94f)
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
        if (elapsed > 950L && !hapticPlayed) {
            hapticPlayed = true
            if (rc.settings.haptics) {
                rc.haptics?.play(IslandHaptics.Cue.CONFIRM, touch = false)
            }
        }
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

        val iconSize = dp(34f)

        // ---------------------------------------------------------------------
        // Stage 1: Sonar Radar Pulse (0..350ms)
        // ---------------------------------------------------------------------
        if (elapsed < 380L) {
            val sonarT = (elapsed / 380f).coerceIn(0f, 1f)
            val sonarRadius = dp(14f) + dp(22f) * easeOutQuad(sonarT)
            val sonarAlpha = a * (1f - sonarT) * 0.7f
            sonarPaint.color = 0xFF00F0FF.toInt() // Cyan glow
            sonarPaint.strokeWidth = dp(2.2f)
            sonarPaint.alpha = (sonarAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, sonarRadius, sonarPaint)
        }

        // ---------------------------------------------------------------------
        // Stage 2: 3D Gyroscopic iPhone Orbiting Ring (300ms .. 1050ms)
        // Perspective 3D rotation with co-axial depth trail as in iPhone Face ID
        // ---------------------------------------------------------------------
        if (elapsed in 280L..1080L) {
            val ringT = ((elapsed - 300f) / 700f).coerceIn(0f, 1f)
            val ringFade = when {
                ringT < 0.15f -> ringT / 0.15f
                ringT > 0.82f -> (1f - ringT) / 0.18f
                else -> 1f
            }
            val spin = easeInOutCubic(ringT) * 360f

            // Dynamic radius: emerges smoothly, peaks at 30dp, snaps into center
            val ringRadius = when {
                ringT < 0.22f -> lerp(dp(18f), dp(30f), easeOutQuad(ringT / 0.22f))
                ringT > 0.78f -> lerp(dp(30f), dp(13f), easeInQuad((ringT - 0.78f) / 0.22f))
                else -> dp(30f)
            }

            val tiltBase = 56f + 7f * sin(ringT * PI.toFloat())

            // 1. Secondary Translucent Trailing Ring (creates 3D stereoscopic glass volume)
            canvas.save()
            camera3D.save()
            camera3D.rotateX(tiltBase + 5f)
            camera3D.rotateY(spin - 22f)
            camera3D.rotateZ((spin - 22f) * 0.45f)
            camera3D.getMatrix(matrix3D)
            camera3D.restore()

            matrix3D.preTranslate(-cx, -cy)
            matrix3D.postTranslate(cx, cy)
            canvas.concat(matrix3D)

            ringTrailPaint.color = 0xFF30D158.toInt() // Apple Neon Green
            ringTrailPaint.strokeWidth = dp(2.6f)
            ringTrailPaint.alpha = (a * ringFade * 115).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringRadius * 0.95f, ringTrailPaint)
            canvas.restore()

            // 2. Primary 3D Neon Glowing Ring (Vivid Face ID Apple Neon Green)
            canvas.save()
            camera3D.save()
            camera3D.rotateX(tiltBase)
            camera3D.rotateY(spin)
            camera3D.rotateZ(spin * 0.45f)
            camera3D.getMatrix(matrix3D)
            camera3D.restore()

            matrix3D.preTranslate(-cx, -cy)
            matrix3D.postTranslate(cx, cy)
            canvas.concat(matrix3D)

            // Radiant Outer Glow
            ringGlowPaint.color = 0xFF30D158.toInt()
            ringGlowPaint.strokeWidth = dp(5.5f)
            ringGlowPaint.alpha = (a * ringFade * 165).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringRadius, ringGlowPaint)

            // Sharp Inner Luminous Core
            ringCorePaint.color = 0xFF34C759.toInt()
            ringCorePaint.strokeWidth = dp(2.8f)
            ringCorePaint.alpha = (a * ringFade * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, ringRadius, ringCorePaint)

            canvas.restore()
        }

        // ---------------------------------------------------------------------
        // Stage 3: Radiant Golden Bloom Burst upon unlock confirmation (950ms .. end)
        // ---------------------------------------------------------------------
        if (elapsed >= 950L) {
            val bloomT = ((elapsed - 950f) / 450f).coerceIn(0f, 1f)
            val bloomRadius = dp(16f) + dp(28f) * easeOutQuad(bloomT)
            val bloomAlpha = a * (1f - bloomT) * 0.42f
            bloomPaint.color = 0xFFFFD60A.toInt() // Apple Warm Gold
            bloomPaint.alpha = (bloomAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, bloomRadius, bloomPaint)
        }

        // ---------------------------------------------------------------------
        // Center Glyph: Holographic Fingerprint -> 3D Flip -> Golden Padlock Open
        // ---------------------------------------------------------------------
        val flipT = ((elapsed - 950f) / 260f).coerceIn(0f, 1f)
        val smoothFlip = easeInOutCubic(flipT)
        val angleY = smoothFlip * 180f
        val angleX = -8f * sin(smoothFlip * PI.toFloat())

        canvas.save()
        camera3D.save()
        camera3D.rotateX(angleX)
        camera3D.rotateY(angleY)
        camera3D.getMatrix(matrix3D)
        camera3D.restore()

        matrix3D.preTranslate(-cx, -cy)
        matrix3D.postTranslate(cx, cy)
        canvas.concat(matrix3D)

        if (angleY < 90f) {
            // Front Face: Glowing Cyan Biometric Fingerprint
            val fpColor = 0xFF00F0FF.toInt() // Electric Cyan
            val breathe = 1f + 0.04f * sin((elapsed / 220f) * PI.toFloat()).coerceIn(-0.04f, 0.04f)
            rc.glyphs.draw(canvas, Glyph.FINGERPRINT, cx, cy, iconSize * breathe, fpColor, a)
        } else {
            // Back Face: Warm Gold Opened Padlock (Glyph.LOCK_OPEN)
            // Flip local X-axis so padlock faces user upright
            canvas.scale(-1f, 1f, cx, cy)

            val lockColor = 0xFFFFD60A.toInt() // Apple Warm Gold
            val settlePop = lerp(0.92f, 1.0f, smoothFlip)

            rc.glyphs.draw(canvas, Glyph.LOCK_OPEN, cx, cy, iconSize * settlePop, lockColor, a)
        }

        canvas.restore()
    }

    private fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f) / 2f

    private fun easeOutQuad(x: Float): Float = 1f - (1f - x) * (1f - x)

    private fun easeInQuad(x: Float): Float = x * x

    override val contentDescription: String
        get() = "${payload?.title ?: event.title}. ${payload?.subtitle ?: event.subtitle}"
}
