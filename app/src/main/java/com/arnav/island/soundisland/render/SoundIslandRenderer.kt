package com.arnav.island.soundisland.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import com.arnav.island.soundisland.SoundIslandMode
import com.arnav.island.soundisland.SoundIslandVisualState
import com.arnav.island.soundisland.audio.SilentModeManager
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance, allocation-free Canvas renderer for Breeze Sound Island.
 * Recreates the exact Liquid Glass Edge Notch visual design from the reference image.
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFFFFFFFF.toInt()
        textSize = sp(13.5f)
        letterSpacing = 0.05f
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Geometry caches
    private val notchPath = Path()
    private val trackPath = Path()
    private val fillClipPath = Path()
    private val tmpRect = RectF()

    // Precompiled vector paths for vector glyphs (24x24 standard viewBox)
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
        notchHeight: Float = dp(160f),
        tabWidth: Float = dp(76f),
        alpha: Float = 1f,
    ) {
        if (alpha <= 0.005f || state.slideProgress <= 0.005f) return

        val p = state.slideProgress.coerceIn(0f, 1f)
        val visibleWidth = tabWidth * p
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
            cornerRadius = dp(26f),
            filletRadius = dp(18f),
        )

        bgPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(notchPath, bgPaint)

        rimPaint.alpha = (0x1F * alpha * p).toInt().coerceIn(0, 255)
        canvas.drawPath(notchPath, rimPaint)

        // 2. Draw content based on current mode
        val contentAlpha = alpha * ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
        if (contentAlpha > 0.01f) {
            canvas.save()
            canvas.clipPath(notchPath)
            when (state.mode) {
                SoundIslandMode.VOLUME -> drawVolumeMode(canvas, state, edge, screenWidth, centerY, visibleWidth, notchHeight, contentAlpha)
                SoundIslandMode.SILENT -> drawSilentMode(canvas, state, edge, screenWidth, centerY, visibleWidth, notchHeight, contentAlpha)
                SoundIslandMode.WORK -> drawWorkMode(canvas, state, edge, screenWidth, centerY, visibleWidth, notchHeight, contentAlpha)
            }
            canvas.restore()
        }
    }

    /** Mode A: Vertical Volume Stadium Slider with inverted speaker icon. */
    private fun drawVolumeMode(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        visibleWidth: Float,
        notchHeight: Float,
        alpha: Float,
    ) {
        val sliderW = dp(38f)
        val sliderH = dp(132f)
        val sliderLeft = if (edge == ScreenEdge.RIGHT) screenWidth - visibleWidth + dp(14f) else visibleWidth - sliderW - dp(14f)
        val sliderTop = centerY - sliderH / 2f
        val sliderRight = sliderLeft + sliderW
        val sliderBottom = sliderTop + sliderH
        val radius = sliderW / 2f

        // 1. Draw slider dark track
        trackPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        trackPath.rewind()
        tmpRect.set(sliderLeft, sliderTop, sliderRight, sliderBottom)
        trackPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW)
        canvas.drawPath(trackPath, trackPaint)

        // 2. Draw white active volume level
        val frac = state.volumeFraction.coerceIn(0f, 1f)
        val fillHeight = sliderH * frac
        val fillTop = sliderBottom - fillHeight

        canvas.save()
        canvas.clipPath(trackPath)
        fillPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRect(sliderLeft, fillTop, sliderRight, sliderBottom, fillPaint)

        // 3. Draw speaker icon at the bottom of the slider
        val iconSize = dp(17f)
        val iconCx = sliderLeft + sliderW / 2f
        val iconCy = sliderBottom - dp(19f)
        val isMuted = state.currentVolume == 0 || frac <= 0.01f
        val icon = if (isMuted) speakerMutePath else speakerPath

        // If covered by the white active volume bar, invert icon to black; else white
        val iconCovered = fillTop <= (iconCy + iconSize / 2f)
        val iconColor = if (iconCovered) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        drawGlyph(canvas, icon, iconCx, iconCy, iconSize, iconColor, alpha)

        canvas.restore()
    }

    /** Mode B: Silent Mode pill with red crossed-out bell and bold SILENT text. */
    private fun drawSilentMode(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        visibleWidth: Float,
        notchHeight: Float,
        alpha: Float,
    ) {
        val isSilent = state.silentMode == SilentModeManager.Mode.SILENT
        val isVibrate = state.silentMode == SilentModeManager.Mode.VIBRATE

        val iconX = if (edge == ScreenEdge.RIGHT) screenWidth - visibleWidth + dp(22f) else dp(22f)
        val primaryIconY = centerY - dp(14f)
        val secondaryIconY = centerY + dp(14f)
        val iconSize = dp(20f)

        // 1. Primary Bell Icon (Red crossed bell if silent)
        val primaryColor = if (isSilent) 0xFFFF3B30.toInt() else 0xFFFFFFFF.toInt()
        val primaryPath = if (isSilent) bellOffPath else bellNormalPath
        drawGlyph(canvas, primaryPath, iconX, primaryIconY, iconSize, primaryColor, alpha)

        // 2. Secondary subtle bell icon below (indicator)
        val secondaryColor = if (isVibrate) 0xFFFF9500.toInt() else 0xFF555558.toInt()
        drawGlyph(canvas, bellNormalPath, iconX, secondaryIconY, dp(17f), secondaryColor, alpha * 0.75f)

        // 3. Bold text "SILENT" / "VIBRATE"
        val label = when (state.silentMode) {
            SilentModeManager.Mode.SILENT -> "SILENT"
            SilentModeManager.Mode.VIBRATE -> "VIBRATE"
            SilentModeManager.Mode.SOUND -> "RING"
        }
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        val textX = iconX + dp(16f)
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, textX, textY, textPaint)
    }

    /** Mode C: Work Focus Mode with purple moon / person / cyan badge and WORK text. */
    private fun drawWorkMode(
        canvas: Canvas,
        state: SoundIslandVisualState,
        edge: ScreenEdge,
        screenWidth: Float,
        centerY: Float,
        visibleWidth: Float,
        notchHeight: Float,
        alpha: Float,
    ) {
        val iconX = if (edge == ScreenEdge.RIGHT) screenWidth - visibleWidth + dp(22f) else dp(22f)
        val moonY = centerY - dp(24f)
        val personY = centerY
        val badgeY = centerY + dp(24f)
        val iconSize = dp(16f)

        // 1. Purple crescent moon (Focus / DND)
        drawGlyph(canvas, moonPath, iconX, moonY, iconSize, 0xFFAF52DE.toInt(), alpha)

        // 2. Purple person icon (Work profile)
        drawGlyph(canvas, personPath, iconX, personY, iconSize, 0xFFAF52DE.toInt(), alpha)

        // 3. Cyan ID badge icon (Work)
        drawGlyph(canvas, badgePath, iconX, badgeY, iconSize, 0xFF30B0C7.toInt(), alpha)

        // 4. Bold text "WORK"
        textPaint.color = 0xFFFFFFFF.toInt()
        textPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        val textX = iconX + dp(16f)
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("WORK", textX, textY, textPaint)
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
