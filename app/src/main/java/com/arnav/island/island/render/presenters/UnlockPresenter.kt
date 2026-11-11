package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.UnlockMethod
import com.arnav.island.events.UnlockPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.IslandHaptics
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.ColorExtractor
import kotlin.math.max

/**
 * iOS-style Dynamic Island unlock animation adapted for Android:
 * - Face ID: corner brackets with smiley face morphing into confirmation
 * - Fingerprint: glowing ridge scan pulses into authenticated state
 * - Password / PIN / Pattern: locked padlock springs open with tactile pop
 */
class UnlockPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: UnlockPayload? = null
    private var boundAt = 0L

    // Progress spring: 0f -> 1f (representing the unlock verification and confirmation)
    private val verified = Spring(0f, SpringSpec(0.28f, 0.96f), restThreshold = 0.002f)
    private val iconPop = Spring(1f, SpringSpec(0.24f, 0.92f), restThreshold = 0.002f)

    private var label = ""

    private val accent: Int
        get() = event.colors.accent.takeIf { it != 0 }?.let(ColorExtractor::legibleOnBlack) ?: when (payload?.method) {
            UnlockMethod.FACE -> 0xFF30D158.toInt() // Apple Mint Green
            UnlockMethod.FINGERPRINT -> 0xFF0A84FF.toInt() // Apple Cyan/Electric Blue
            UnlockMethod.PASSWORD, null -> 0xFFFFD60A.toInt() // Apple Warm Gold
        }

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> =
        if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) {
            (rc.geometry.compactWidth + dp(58f)) to rc.geometry.compactHeight
        } else {
            super.measure(event, mode)
        }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? UnlockPayload
        if (!isUpdate) {
            boundAt = nowElapsed
            verified.snapTo(0f)
            iconPop.snapTo(0.6f)
            iconPop.animateTo(1f)
        }
        rc.labelPaint.textSize = rc.sp(13.5f)
        val room = max(0f, layoutW / 2f - rc.geometry.cameraExclusionHalfWidth - dp(32f))
        label = rc.ellipsize(payload?.title ?: event.title, rc.labelPaint, room)
    }

    private val isVerifying: Boolean get() = nowElapsed - boundAt < 220L

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (!isVerifying && verified.target < 1f) {
            verified.animateTo(1f)
            iconPop.animateTo(1.12f)
            if (rc.settings.haptics) {
                rc.haptics?.play(IslandHaptics.Cue.CONFIRM, touch = false)
            }
        } else if (verified.value > 0.85f && iconPop.target > 1f) {
            iconPop.animateTo(1f)
        }

        if (verified.step(dt)) moving = true
        if (iconPop.step(dt)) moving = true
        return moving || isVerifying
    }

    override fun nextFrameDelay(now: Long): Long =
        if (isVerifying || !verified.isAtRest || !iconPop.isAtRest) 0 else -1

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = if (mode == PresentMode.TOAST || mode == PresentMode.EXPANDED) alpha else alpha * compactVisibility(w, h)
        if (a <= 0.004f) return

        val s = slot(h)
        val inset = (h - s) / 2f
        val cy = h / 2f + rc.burnInY
        val v = verified.value.coerceIn(0f, 1f)
        val pop = iconPop.value.coerceIn(0.5f, 1.4f)

        // 1. Draw Unlock Glyphs on Left Side
        val method = payload?.method ?: UnlockMethod.FACE
        val iconX = inset + s / 2f + rc.burnInX
        val iconSize = s * 0.94f * pop

        val iconColor = if (v < 0.35f) {
            lerpColor(IslandColors.TEXT_SECONDARY, accent, v / 0.35f)
        } else {
            accent
        }

        val glyph = when (method) {
            UnlockMethod.FACE -> Glyph.FACE_ID
            UnlockMethod.FINGERPRINT -> Glyph.FINGERPRINT
            UnlockMethod.PASSWORD -> if (v > 0.4f) Glyph.LOCK_OPEN else Glyph.LOCK
        }

        rc.glyphs.draw(canvas, glyph, iconX, cy, iconSize, iconColor, a)

        // 2. Draw Method Label Text in Center/Left of Text area
        rc.labelPaint.textSize = rc.sp(13.5f)
        rc.labelPaint.textAlign = Paint.Align.LEFT
        val textX = inset + s + dp(10f) + rc.burnInX
        rc.text(canvas, label, textX, rc.baseline(rc.labelPaint, cy), rc.labelPaint, IslandColors.TEXT, a)

        // 3. Draw Success Checkmark or Glow Indicator on Right Side
        val rightX = w - h / 2f + rc.burnInX
        if (v > 0.1f) {
            val checkRadius = lerp(0f, s * 0.44f, v)
            val checkAlpha = a * v
            rc.circle(canvas, rightX, cy, checkRadius, accent, checkAlpha)
            rc.glyphs.draw(canvas, Glyph.CHECK, rightX, cy, checkRadius * 1.35f, 0xFF000000.toInt(), checkAlpha)
        }
    }

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
