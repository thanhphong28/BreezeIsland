package com.arnav.island.soundisland.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import com.arnav.island.soundisland.SoundIslandMode
import com.arnav.island.soundisland.SoundIslandVisualState
import com.arnav.island.soundisland.audio.SilentModeManager

/**
 * High-performance, allocation-free Canvas renderer for Breeze Sound Island.
 * Recreates the exact Liquid Glass Edge Notch visual design:
 *  - Volume Stick mode: Ultra-slim stick hugging screen bezel on single volume tap.
 *  - Volume Capsule mode: Expands into full tactile slider on long-press or touch drag.
 *  - Silent Mode: Minimalist icon-only pill (pure visual glyph, zero text).
 *  - Work Mode: Minimalist triple-icon pill (pure visual glyphs, zero text).
 */
class SoundIslandRenderer(private val context: Context) {

    val density: Float = context.resources.displayMetrics.density
    fun dp(v: Float): Float = v * density
    fun sp(v: Float): Float = v * density

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF000000.toInt() // OLED Black
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = 0x1FFFFFFF // Hairline Liquid Glass rim
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2A2A2E.toInt() // Dark grey track
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt() // Bright clean white
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Geometry caches
    private val notchPath = Path()
    private val trackPath = Path()
    private val tmpRect = RectF()

    // Precompiled vector paths (24x24 standard viewBox)
    private val speakerPath = PathParser.createPathFromPathData("M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z")
    private val speakerMutePath = PathParser.createPathFromPathData("M7 9v6h4l5 5V4L11 9H7zm11.59 3l2.71-2.71-1.41-1.41L17.18 10.59 14.47 7.88l-1.41 1.41L15.77 12l-2.71 2.71 1.41 1.41 2.71-2.71 2.71 2.71 1.41-1.41z")
    private val bellOffPath = PathParser.createPathFromPathData("M20 18.69L7.84 6.14 5.27 3.49 4 4.76l2.8 2.8v.01c-.52.99-.8 2.16-.8 3.43v5l-2 2v1h15.73l2 2 1.27-1.27L20 18.69zM12 22c1.11 0 2-.89 2-2h-4c0 1.11.89 2 2 2zm6-7.32V11c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68c-.78.18-1.49.52-2.09.98L18 14.68z")
    private val bellNormalPath = PathParser.createPathFromPathData("M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z")
    private val moonPath = PathParser.createPathFromPathData("M12.3 2a10 10 0 0 0-.19 14 10 10 0 0 0 11.89 4A10 10 0 1 1 12.3 2z")
    private val personPath = PathParser.createPathFromPathData("M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z")
    private val badgePath = PathParser.createPathFromPathData("M20 7h-4V5c0-1.1-.9-2-2-2h-4c-1.1 0-2 .9-2 2v2H4c-1.1 0-2 .9-2 2v11c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V9c0-1.1-.9-2-2-2zM10 5h4v2h-4V5zm10 15H4V9h16v11z")

    /**
     * Draws the complete Breeze Sound Island notch and its mode content.
     */
    fun draw(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        alpha: Float = 1f,
    ) {
        if (alpha <= 0.005f || state.slideProgress <= 0.005f) return

        val pSlide = state.slideProgress.coerceIn(0f, 1f)
        val pExp = state.expandProgress.coerceIn(0f, 1f)

        // Calculate dynamic dimensions based on mode and expansion progress
        val tabWidth: Float
        val notchHeight: Float
        val cornerRadius: Float

        when (state.mode) {
            SoundIslandMode.VOLUME -> {
                val stickW = dp(32f)
                val fullW = dp(76f)
                tabWidth = stickW + (fullW - stickW) * pExp

                val stickH = dp(136f)
                val fullH = dp(160f)
                notchHeight = stickH + (fullH - stickH) * pExp
                cornerRadius = dp(16f) + dp(10f) * pExp
            }
            SoundIslandMode.SILENT -> {
                tabWidth = dp(56f)
                notchHeight = dp(112f)
                cornerRadius = dp(24f)
            }
            SoundIslandMode.WORK -> {
                tabWidth = dp(56f)
                notchHeight = dp(126f)
                cornerRadius = dp(24f)
            }
        }

        val visibleWidth = tabWidth * pSlide
        val top = centerY - notchHeight / 2f
        val bottom = centerY + notchHeight / 2f

        // 1. Build and draw Edge Notch background
        EdgeBezelShape.build(
            path = notchPath,
            edge = edge,
            screenWidth = screenWidth,
            top = top,
            bottom = bottom,
            tabWidth = visibleWidth,
            cornerRadius = cornerRadius,
            filletRadius = dp(16f),
        )

        bgPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(notchPath, bgPaint)

        rimPaint.alpha = (0x26 * alpha * pSlide).toInt().coerceIn(0, 255)
        canvas.drawPath(notchPath, rimPaint)

        // 2. Draw content based on current mode
        val contentAlpha = alpha * ((pSlide - 0.15f) / 0.85f).coerceIn(0f, 1f)
        if (contentAlpha > 0.01f) {
            canvas.save()
            canvas.clipPath(notchPath)
            when (state.mode) {
                SoundIslandMode.VOLUME -> drawVolumeMode(canvas, state, edge, screenWidth, centerY, visibleWidth, notchHeight, pExp, contentAlpha)
                SoundIslandMode.SILENT -> drawSilentMode(canvas, state, edge, screenWidth, centerY, visibleWidth, contentAlpha)
                SoundIslandMode.WORK -> drawWorkMode(canvas, state, edge, screenWidth, centerY, visibleWidth, contentAlpha)
            }
            canvas.restore()
        }
    }

    /**
     * Mode A: Vertical Volume Slider.
     * Transitions seamlessly from ultra-slim stick to full capsule with icon.
     */
    private fun drawVolumeMode(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        visibleWidth: Float,
        notchHeight: Float,
        pExp: Float,
        alpha: Float,
    ) {
        val sliderW = dp(10f) + (dp(38f) - dp(10f)) * pExp
        val sliderH = dp(116f) + (dp(132f) - dp(116f)) * pExp
        val marginFromEdge = dp(11f) + (dp(14f) - dp(11f)) * pExp

        val sliderLeft = if (edge == ScreenEdge.RIGHT) {
            screenWidth - visibleWidth + marginFromEdge
        } else {
            visibleWidth - marginFromEdge - sliderW
        }
        val sliderTop = centerY - sliderH / 2f
        val sliderRight = sliderLeft + sliderW
        val sliderBottom = sliderTop + sliderH
        val radius = sliderW / 2f

        // 1. Draw slider track
        trackPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        trackPath.rewind()
        tmpRect.set(sliderLeft, sliderTop, sliderRight, sliderBottom)
        trackPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW)
        canvas.drawPath(trackPath, trackPaint)

        // 2. Draw active volume fill
        val frac = state.volumeFraction.coerceIn(0f, 1f)
        val fillHeight = sliderH * frac
        val fillTop = sliderBottom - fillHeight

        canvas.save()
        canvas.clipPath(trackPath)
        fillPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(sliderLeft, fillTop, sliderRight, sliderBottom, fillPaint)

        // 3. Draw speaker icon (fades in as the stick morphs into full capsule)
        val iconAlpha = ((pExp - 0.25f) / 0.75f).coerceIn(0f, 1f) * alpha
        if (iconAlpha > 0.02f) {
            val iconSize = dp(17f)
            val iconCx = sliderLeft + sliderW / 2f
            val iconCy = sliderBottom - dp(19f)
            val isMuted = state.currentVolume == 0 || frac <= 0.01f
            val icon = if (isMuted) speakerMutePath else speakerPath

            val iconCovered = fillTop <= (iconCy + iconSize / 2f)
            val iconColor = if (iconCovered) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            drawGlyph(canvas, icon, iconCx, iconCy, iconSize, iconColor, iconAlpha)
        }

        canvas.restore()
    }

    /**
     * Mode B: Silent Mode - PURE ICON ONLY (Zero text, clean & minimal).
     */
    private fun drawSilentMode(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        visibleWidth: Float,
        alpha: Float,
    ) {
        val isSilent = state.silentMode == SilentModeManager.Mode.SILENT
        val isVibrate = state.silentMode == SilentModeManager.Mode.VIBRATE

        val iconX = if (edge == ScreenEdge.RIGHT) screenWidth - visibleWidth / 2f + dp(3f) else visibleWidth / 2f - dp(3f)
        val iconSize = dp(27f)

        when {
            isSilent -> {
                // Striking Red crossed-out bell
                drawGlyph(canvas, bellOffPath, iconX, centerY, iconSize, 0xFFFF3B30.toInt(), alpha)
            }
            isVibrate -> {
                // Vibrant Amber vibrating bell
                drawGlyph(canvas, bellNormalPath, iconX, centerY, iconSize, 0xFFFF9500.toInt(), alpha)
            }
            else -> {
                // Pristine White normal bell
                drawGlyph(canvas, bellNormalPath, iconX, centerY, iconSize, 0xFFFFFFFF.toInt(), alpha)
            }
        }
    }

    /**
     * Mode C: Work Mode - PURE ICONS ONLY (Zero text, clean & minimal).
     */
    private fun drawWorkMode(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        visibleWidth: Float,
        alpha: Float,
    ) {
        val iconX = if (edge == ScreenEdge.RIGHT) screenWidth - visibleWidth / 2f + dp(3f) else visibleWidth / 2f - dp(3f)
        val moonY = centerY - dp(26f)
        val personY = centerY
        val badgeY = centerY + dp(26f)
        val iconSize = dp(18f)

        // 1. Purple crescent moon (Focus / DND)
        drawGlyph(canvas, moonPath, iconX, moonY, iconSize, 0xFFAF52DE.toInt(), alpha)

        // 2. Purple person icon (Work profile)
        drawGlyph(canvas, personPath, iconX, personY, iconSize, 0xFFAF52DE.toInt(), alpha)

        // 3. Cyan ID badge icon (Work)
        drawGlyph(canvas, badgePath, iconX, badgeY, iconSize, 0xFF30B0C7.toInt(), alpha)
    }

    private fun drawGlyph(canvas: Canvas, path: Path, cx: Float, cy: Float, size: Float, color: Int, alpha: Float) {
        val scale = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(scale, scale)
        iconPaint.color = color
        iconPaint.alpha = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(path, iconPaint)
        canvas.restore()
    }
}
