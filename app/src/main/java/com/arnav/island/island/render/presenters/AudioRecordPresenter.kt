package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.animation.lerp
import com.arnav.island.animation.organic
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.AudioRecordPayload
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.island.render.RollingText
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Formatters
import kotlin.math.sin

/**
 * iOS Voice Memos / Samsung Voice Recorder live activity presenter:
 *  - Compact pill: pulsing red mic + animated red voice waveform + rolling timer.
 *  - Expanded card: App title & status, wide multi-bar voice waveform, big timer & controls.
 */
class AudioRecordPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: AudioRecordPayload? = null
    private val amp = Spring(1f, SpringSpec(0.32f, 1f), restThreshold = 0.002f)
    private val compactTimer = RollingText()
    private val bigTimer = RollingText()

    private var pad = 0f
    private var top = 0f
    private var waveY = 0f
    private var bottomY = 0f

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.EXPANDED, PresentMode.TOAST -> expandedWidth to (bandInset + dp(122f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        val p = event.payload as? AudioRecordPayload
        payload = p
        val active = p?.isPaused != true
        if (isUpdate) amp.animateTo(if (active) 1f else 0.15f) else amp.snapTo(if (active) 1f else 0.15f)
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) layoutExpanded()
    }

    private fun layoutExpanded() {
        val w = layoutW
        pad = dp(20f)
        top = bandInset
        waveY = top + dp(52f)
        bottomY = top + dp(94f)

        val toggle = event.actions.firstOrNull { it.id == ACTION_TOGGLE }
        val stop = event.actions.firstOrNull { it.id == ACTION_STOP }
        val open = event.actions.firstOrNull { it.id == ACTION_OPEN } ?: event.tapAction

        var btnX = pad + dp(20f)
        if (toggle != null) {
            addCircleTarget(ACTION_TOGGLE, toggle, btnX, bottomY, dp(22f))
            btnX += dp(50f)
        }
        if (stop != null) {
            addCircleTarget(ACTION_STOP, stop, btnX, bottomY, dp(22f))
        } else if (open != null) {
            addCircleTarget(ACTION_OPEN, open, btnX, bottomY, dp(22f))
        }
    }

    override fun step(dt: Float): Boolean {
        var moving = super.step(dt)
        if (amp.step(dt)) moving = true
        if (compactTimer.step(dt)) moving = true
        if (bigTimer.step(dt)) moving = true
        return moving
    }

    override fun nextFrameDelay(now: Long): Long {
        val p = payload ?: return -1
        return if (p.isPaused) -1 else maxOf(16L, rc.settings.decorativeFrameMs)
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.EXPANDED, PresentMode.TOAST -> drawExpanded(canvas, alpha, now)
            PresentMode.BUBBLE -> drawBubble(canvas, w, alpha)
            else -> drawCompact(canvas, w, h, alpha, now)
        }
    }

    private fun drawBubble(canvas: Canvas, d: Float, alpha: Float) {
        val cx = d / 2f + rc.burnInX
        val cy = d / 2f + rc.burnInY
        val pulse = 0.85f + 0.15f * sin(rc.animTime * 5f)
        rc.circle(canvas, cx, cy, d * 0.34f * pulse, ColorExtractor.withAlpha(IslandColors.RED, 0.24f), alpha)
        rc.glyphs.draw(canvas, Glyph.MIC, cx, cy, d * 0.48f, IslandColors.RED, alpha)
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val p = payload ?: return
        val s = slot(h)
        val inset = (h - s) / 2f
        val bx = rc.burnInX
        val by = rc.burnInY
        val lx = h / 2f + bx
        val cy = h / 2f + by

        // Left: Pulsing red microphone badge + mini voice bars
        val pulse = if (p.isPaused) 1f else 0.86f + 0.14f * sin(rc.animTime * 5.5f)
        rc.circle(canvas, lx, cy, s * 0.44f * pulse, ColorExtractor.withAlpha(IslandColors.RED, 0.24f), a)
        rc.glyphs.draw(canvas, Glyph.MIC, lx, cy, s * 0.64f, IslandColors.RED, a)

        // Right: Elapsed recording timer + mini red dot
        val right = w - inset - dp(2f) + bx
        rc.numberPaint.textSize = rc.sp(14.5f)
        val timeStr = Formatters.elapsed(p.elapsedAt(now))
        val color = if (p.isPaused) IslandColors.TEXT_SECONDARY else IslandColors.RED
        compactTimer.set(timeStr, direction = 1, animate = true)
        compactTimer.draw(canvas, rc, right, rc.baseline(rc.numberPaint, cy), rc.numberPaint, color, a, Paint.Align.RIGHT)
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float, now: Long) {
        val p = payload ?: return
        val w = layoutW

        // Header row: Red pulsing mic + title & app label
        val iconCx = pad + dp(16f)
        val headerCy = top + dp(18f)
        val pulse = if (p.isPaused) 1f else 0.85f + 0.15f * sin(rc.animTime * 5.5f)
        rc.circle(canvas, iconCx, headerCy, dp(16f) * pulse, ColorExtractor.withAlpha(IslandColors.RED, 0.22f), alpha)
        rc.glyphs.draw(canvas, Glyph.MIC, iconCx, headerCy, dp(18f), IslandColors.RED, alpha)

        val textX = pad + dp(40f)
        rc.titlePaint.textSize = rc.sp(15.5f)
        rc.captionPaint.textSize = rc.sp(12.5f)
        val title = p.title.ifBlank { p.appLabel }
        rc.text(canvas, rc.ellipsize(title, rc.titlePaint, w - textX - pad), textX, headerCy - dp(2f), rc.titlePaint, IslandColors.TEXT, alpha)
        val statusText = if (p.isPaused) "${p.appLabel} · Tạm dừng" else "${p.appLabel} · Đang ghi âm"
        rc.text(canvas, rc.ellipsize(statusText, rc.captionPaint, w - textX - pad), textX, headerCy + dp(14f), rc.captionPaint, IslandColors.RED, alpha)

        // Middle row: iOS Voice Memos style multi-bar animated red waveform
        val waveLeft = pad
        val waveRight = w - pad
        val waveWidth = waveRight - waveLeft
        val bars = 28
        val stepX = waveWidth / bars
        val barW = dp(3.2f)
        val maxBarH = dp(26f)
        val t = rc.animTime
        for (i in 0 until bars) {
            val centerWeight = 1f - kotlin.math.abs((i - bars / 2f) / (bars / 2f)) * 0.45f
            val v = organic(t * 1.35f, i * 0.73f) * centerWeight
            val barH = lerp(dp(3.5f), maxBarH, (0.12f + 0.88f * v) * amp.value).coerceAtLeast(barW)
            val cx = waveLeft + i * stepX + stepX / 2f
            val barColor = if (i % 3 == 0) IslandColors.RED else ColorExtractor.withAlpha(IslandColors.RED, 0.78f)
            rc.roundRect(canvas, cx - barW / 2f, waveY - barH / 2f, cx + barW / 2f, waveY + barH / 2f, barW / 2f, barColor, alpha)
        }

        // Bottom row: Control buttons on the left, Big rolling timer on the right
        val toggle = event.actions.firstOrNull { it.id == ACTION_TOGGLE }
        val stop = event.actions.firstOrNull { it.id == ACTION_STOP }
        val open = event.actions.firstOrNull { it.id == ACTION_OPEN } ?: event.tapAction

        var btnX = pad + dp(20f)
        if (toggle != null) {
            drawCircleButton(
                canvas,
                ACTION_TOGGLE,
                if (p.isPaused) Glyph.PLAY else Glyph.PAUSE,
                btnX,
                bottomY,
                dp(20f),
                ActionStyle.DEFAULT,
                alpha,
                glyphScale = 0.44f,
            )
            btnX += dp(50f)
        }
        if (stop != null) {
            drawCircleButton(
                canvas,
                ACTION_STOP,
                Glyph.STOP,
                btnX,
                bottomY,
                dp(20f),
                ActionStyle.DESTRUCTIVE,
                alpha,
                glyphScale = 0.44f,
            )
            btnX += dp(40f)
        } else if (open != null) {
            drawCircleButton(
                canvas,
                ACTION_OPEN,
                Glyph.OPEN,
                btnX,
                bottomY,
                dp(20f),
                ActionStyle.DEFAULT,
                alpha,
                glyphScale = 0.44f,
            )
            btnX += dp(40f)
        }

        val paint = rc.bigNumberPaint
        paint.textSize = rc.sp(34f)
        val timeText = Formatters.stopwatch(p.elapsedAt(now))
        val avail = (w - pad) - btnX - dp(8f)
        val tw = paint.measureText(timeText)
        if (tw > avail && avail > 0f) paint.textSize *= avail / tw
        bigTimer.set(timeText, direction = 1, animate = false)
        bigTimer.draw(
            canvas,
            rc,
            w - pad,
            rc.baseline(paint, bottomY),
            paint,
            if (p.isPaused) IslandColors.TEXT_SECONDARY else IslandColors.TEXT,
            alpha,
            Paint.Align.RIGHT,
        )
    }

    override val contentDescription: String
        get() = payload?.title ?: "Voice recording"

    companion object {
        const val ACTION_TOGGLE = "audiorec.toggle"
        const val ACTION_STOP = "audiorec.stop"
        const val ACTION_OPEN = "audiorec.open"
    }
}
