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
import kotlin.math.sin

/**
 * iPhone Dynamic Island 3D Biometric Unlock Squircle (v2.0.0):
 * - Dimensions: 94dp x 92dp centered squircle enclosing the top camera punch hole.
 * - Sequence:
 *   1. (0 -> 320ms): Glowing holographic 3D biometric fingerprint with expanding cyan sonar wave.
 *   2. (320ms -> 820ms): 3D Y-axis coin flip in real perspective space, seamlessly morphing
 *      at edge-on 90 degrees into the open padlock.
 *   3. (820ms -> end): Golden open padlock shines with warm radiant bloom, confirming unlock.
 */
class UnlockPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: UnlockPayload? = null
    private var boundAt = 0L
    private var hapticPlayed = false

    // 3D Perspective Projection
    private val camera3D = Camera().apply {
        setLocation(0f, 0f, -12f)
    }
    private val matrix3D = Matrix()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(rc.dp(6f), BlurMaskFilter.Blur.NORMAL)
    }

    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(rc.dp(12f), BlurMaskFilter.Blur.NORMAL)
    }

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: 0xFF00F0FF.toInt()

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            // Enhanced, commanding squircle matching iPhone Face ID / biometric island
            val w = rc.dp(94f)
            val h = rc.dp(92f)
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
        if (elapsed > 820L && !hapticPlayed) {
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

        // ---------------------------------------------------------------------
        // 3D Timeline:
        // 0..320ms: Biometric Fingerprint scan & pulse
        // 320..820ms: 3D Y-axis flip (0 deg -> 180 deg)
        // 820..end: Unlocked golden padlock with radiant bloom
        // ---------------------------------------------------------------------
        val flipT = ((elapsed - 320f) / 500f).coerceIn(0f, 1f)
        val smoothFlip = easeInOutCubic(flipT)
        val angleY = smoothFlip * 180f

        // Subtle organic 3D X-tilt during the flip for authentic tactile depth
        val angleX = -9f * sin(smoothFlip * Math.PI.toFloat())

        val iconSize = dp(34f)

        // Background Pulse Wave (during initial scan)
        if (flipT < 0.2f) {
            val pulseT = (elapsed / 320f).coerceIn(0f, 1f)
            val pulseRadius = dp(16f) + dp(22f) * pulseT
            val pulseAlpha = a * (1f - pulseT) * 0.7f
            ringPaint.color = 0xFF00F0FF.toInt()
            ringPaint.strokeWidth = dp(2.4f)
            ringPaint.alpha = (pulseAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, pulseRadius, ringPaint)
        }

        // Golden Bloom Halo (upon unlock confirmation)
        if (flipT > 0.85f) {
            val bloomT = ((flipT - 0.85f) / 0.15f).coerceIn(0f, 1f)
            val bloomAlpha = a * bloomT * 0.35f
            bloomPaint.color = 0xFFFFD60A.toInt() // Apple Warm Gold
            bloomPaint.alpha = (bloomAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, dp(26f), bloomPaint)
        }

        // Apply 3D Perspective Matrix
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
            // Front Face: 3D Holographic Electric Blue Fingerprint
            val fpColor = 0xFF00F0FF.toInt() // Neon Cyan / Electric Blue
            glowPaint.color = fpColor
            glowPaint.strokeWidth = dp(4f)
            glowPaint.alpha = (a * 150).toInt().coerceIn(0, 255)

            // Neon ridge glow
            rc.glyphs.draw(canvas, Glyph.FINGERPRINT, cx, cy, iconSize, fpColor, a)
        } else {
            // Back Face: Luxurious Warm Gold Open Padlock (Glyph.LOCK_OPEN)
            // Flip local X-axis so padlock is facing the user correctly after 180-deg flip
            canvas.scale(-1f, 1f, cx, cy)

            val lockColor = 0xFFFFD60A.toInt() // Apple Warm Gold
            val settlePop = lerp(0.92f, 1.0f, smoothFlip)

            rc.glyphs.draw(canvas, Glyph.LOCK_OPEN, cx, cy, iconSize * settlePop, lockColor, a)
        }

        canvas.restore()
    }

    private fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f) / 2f

    override val contentDescription: String
        get() = "${payload?.title ?: event.title}. ${payload?.subtitle ?: event.subtitle}"
}
