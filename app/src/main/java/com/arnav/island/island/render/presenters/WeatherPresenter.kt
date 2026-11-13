package com.arnav.island.island.render.presenters

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.WeatherCondition
import com.arnav.island.events.WeatherPayload
import com.arnav.island.island.render.IslandColors
import com.arnav.island.island.render.PresentMode
import com.arnav.island.island.render.Presenter
import com.arnav.island.island.render.RenderContext
import com.arnav.island.util.ColorExtractor
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Weather Island presenter inspired by iOS Weather Live Activities & RainViewer:
 *  - COMPACT: Animated weather icon on the left + current temperature on the right.
 *  - TOAST: Animated weather icon + headline/city + High/Low or Rain % badge.
 *  - EXPANDED: Full interactive weather card with animated icon, city, condition, large temperature,
 *    High/Low, 4 metric chips (Mưa %, Độ ẩm %, Gió km/h, UV) and a 4-slot hourly forecast strip.
 */
class WeatherPresenter(rc: RenderContext) : Presenter(rc) {

    private var payload: WeatherPayload? = null
    private var pad = 0f
    private var top = 0f
    private val boltPath = Path()

    override fun measure(event: IslandEvent, mode: PresentMode): Pair<Float, Float> = when (mode) {
        PresentMode.TOAST -> expandedWidth to (bandInset + dp(68f))
        PresentMode.EXPANDED -> expandedWidth to (bandInset + dp(156f))
        else -> super.measure(event, mode)
    }

    override fun onBind(isUpdate: Boolean) {
        payload = event.payload as? WeatherPayload
        pad = dp(18f)
        top = bandInset
        if (mode == PresentMode.EXPANDED || mode == PresentMode.TOAST) {
            val open = event.tapAction ?: event.actions.firstOrNull()
            if (open != null) {
                addTarget(ACTION_OPEN, open, 0f, 0f, layoutW, layoutH)
            }
        }
    }

    override fun nextFrameDelay(now: Long): Long = maxOf(24L, rc.settings.decorativeFrameMs)

    override fun draw(canvas: Canvas, w: Float, h: Float, alpha: Float, now: Long) {
        when (mode) {
            PresentMode.TOAST -> drawToast(canvas, alpha)
            PresentMode.EXPANDED -> drawExpanded(canvas, alpha)
            PresentMode.BUBBLE -> drawBubble(canvas, w, alpha)
            else -> drawCompact(canvas, w, h, alpha)
        }
    }

    private fun drawBubble(canvas: Canvas, d: Float, alpha: Float) {
        val p = payload ?: return
        val cx = d / 2f + rc.burnInX
        val cy = d / 2f + rc.burnInY
        drawAnimatedWeatherIcon(canvas, cx, cy, d * 0.36f, p.condition, alpha)
    }

    private fun drawCompact(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val a = alpha * compactVisibility(w, h)
        if (a <= 0.004f) return
        val p = payload ?: return
        val s = slot(h)
        val inset = (h - s) / 2f
        val bx = rc.burnInX
        val by = rc.burnInY
        val lx = h / 2f + bx
        val cy = h / 2f + by

        drawAnimatedWeatherIcon(canvas, lx, cy, s * 0.48f, p.condition, a)

        val right = w - inset - dp(2f) + bx
        rc.numberPaint.textSize = rc.sp(14.5f)
        val tempColor = accentColorFor(p.condition)
        rc.text(
            canvas,
            "${p.currentTempC}°",
            right,
            rc.baseline(rc.numberPaint, cy),
            rc.numberPaint,
            tempColor,
            a,
            Paint.Align.RIGHT
        )
    }

    private fun drawToast(canvas: Canvas, alpha: Float) {
        val p = payload ?: return
        val w = layoutW
        val cy = top + dp(28f)
        val iconCx = pad + dp(20f)

        drawAnimatedWeatherIcon(canvas, iconCx, cy, dp(19f), p.condition, alpha)

        // Right pill badge: Rain probability or High/Low
        val isRainy = p.rainProbPercent >= 45 ||
            p.condition == WeatherCondition.RAIN ||
            p.condition == WeatherCondition.HEAVY_RAIN ||
            p.condition == WeatherCondition.THUNDERSTORM
        val badgeText = if (isRainy) "Mưa ${p.rainProbPercent}%" else "C:${p.highTempC}° T:${p.lowTempC}°"
        val badgeColor = if (isRainy) 0xFF38BDF8.toInt() else accentColorFor(p.condition)

        rc.captionPaint.textSize = rc.sp(12f)
        val badgeW = rc.captionPaint.measureText(badgeText) + dp(18f)
        val badgeH = dp(26f)
        val badgeR = w - pad
        val badgeL = badgeR - badgeW
        rc.roundRect(
            canvas,
            badgeL,
            cy - badgeH / 2f,
            badgeR,
            cy + badgeH / 2f,
            badgeH / 2f,
            ColorExtractor.withAlpha(badgeColor, 0.18f),
            alpha
        )
        rc.text(
            canvas,
            badgeText,
            (badgeL + badgeR) / 2f,
            rc.baseline(rc.captionPaint, cy),
            rc.captionPaint,
            badgeColor,
            alpha,
            Paint.Align.CENTER
        )

        val textX = iconCx + dp(28f)
        val maxTextW = (badgeL - textX - dp(10f)).coerceAtLeast(dp(40f))

        rc.titlePaint.textSize = rc.sp(15.5f)
        val headline = if (!p.alertHeadline.isNullOrBlank()) {
            "${p.currentTempC}°C · ${p.alertHeadline}"
        } else {
            "${p.currentTempC}°C · ${p.cityName}"
        }
        rc.text(
            canvas,
            rc.ellipsize(headline, rc.titlePaint, maxTextW),
            textX,
            cy - dp(3f),
            rc.titlePaint,
            IslandColors.TEXT,
            alpha
        )

        rc.captionPaint.textSize = rc.sp(12.5f)
        val sub = if (!p.alertHeadline.isNullOrBlank()) {
            "${p.cityName} · ${p.conditionLabel} · C:${p.highTempC}° T:${p.lowTempC}°"
        } else {
            "${p.conditionLabel} · Cảm giác ${p.feelsLikeC}°C"
        }
        rc.text(
            canvas,
            rc.ellipsize(sub, rc.captionPaint, maxTextW),
            textX,
            cy + dp(14f),
            rc.captionPaint,
            IslandColors.TEXT_SECONDARY,
            alpha
        )
    }

    private fun drawExpanded(canvas: Canvas, alpha: Float) {
        val p = payload ?: return
        val w = layoutW
        val accent = accentColorFor(p.condition)

        // Row 1: Animated Weather Icon + City & Condition + Big Temperature on the right
        val headerCy = top + dp(22f)
        val iconCx = pad + dp(20f)
        drawAnimatedWeatherIcon(canvas, iconCx, headerCy, dp(20f), p.condition, alpha)

        // Big temperature on top right
        rc.bigNumberPaint.textSize = rc.sp(30f)
        val tempStr = "${p.currentTempC}°"
        val tempW = rc.bigNumberPaint.measureText(tempStr)
        rc.text(
            canvas,
            tempStr,
            w - pad,
            headerCy + dp(6f),
            rc.bigNumberPaint,
            IslandColors.TEXT,
            alpha,
            Paint.Align.RIGHT
        )

        rc.captionPaint.textSize = rc.sp(11f)
        rc.text(
            canvas,
            "C:${p.highTempC}° T:${p.lowTempC}°",
            w - pad,
            headerCy + dp(21f),
            rc.captionPaint,
            accent,
            alpha,
            Paint.Align.RIGHT
        )

        val textX = iconCx + dp(28f)
        val maxTitleW = (w - pad - tempW - dp(14f) - textX).coerceAtLeast(dp(50f))
        rc.titlePaint.textSize = rc.sp(16f)
        rc.text(
            canvas,
            rc.ellipsize(p.cityName, rc.titlePaint, maxTitleW),
            textX,
            headerCy - dp(2f),
            rc.titlePaint,
            IslandColors.TEXT,
            alpha
        )

        rc.captionPaint.textSize = rc.sp(12.5f)
        val condLine = if (!p.alertHeadline.isNullOrBlank()) {
            "${p.alertHeadline} · ${p.conditionLabel}"
        } else {
            "${p.conditionLabel} · Cảm giác ${p.feelsLikeC}°"
        }
        rc.text(
            canvas,
            rc.ellipsize(condLine, rc.captionPaint, maxTitleW),
            textX,
            headerCy + dp(15f),
            rc.captionPaint,
            IslandColors.TEXT_SECONDARY,
            alpha
        )

        // Row 2: 4 metric chips (Mưa %, Độ ẩm %, Gió km/h, Chỉ số UV)
        val chipTop = top + dp(52f)
        val chipH = dp(34f)
        val gap = dp(6f)
        val totalW = w - pad * 2f
        val chipW = (totalW - gap * 3f) / 4f
        val metrics = listOf(
            "Khả năng mưa" to "${p.rainProbPercent}%",
            "Độ ẩm" to "${p.humidityPercent}%",
            "Sức gió" to "${p.windKmh} km/h",
            "Chỉ số UV" to "${p.uvIndex.roundToInt()}"
        )
        metrics.forEachIndexed { idx, (label, value) ->
            val l = pad + idx * (chipW + gap)
            val r = l + chipW
            val cx = (l + r) / 2f
            rc.roundRect(canvas, l, chipTop, r, chipTop + chipH, dp(10f), IslandColors.CONTROL, alpha)

            rc.captionPaint.textSize = rc.sp(9.5f)
            rc.text(
                canvas,
                label,
                cx,
                chipTop + dp(12.5f),
                rc.captionPaint,
                IslandColors.TEXT_SECONDARY,
                alpha,
                Paint.Align.CENTER
            )

            rc.numberPaint.textSize = rc.sp(11.5f)
            val valColor = if (idx == 0 && p.rainProbPercent >= 50) 0xFF38BDF8.toInt() else IslandColors.TEXT
            rc.text(
                canvas,
                value,
                cx,
                chipTop + chipH - dp(5.5f),
                rc.numberPaint,
                valColor,
                alpha,
                Paint.Align.CENTER
            )
        }

        // Row 3: 4-slot Hourly forecast strip
        val hourlyTop = chipTop + chipH + dp(8f)
        val hourlyH = dp(52f)
        val items = p.hourly.take(4)
        if (items.isNotEmpty()) {
            val slotW = totalW / items.size
            items.forEachIndexed { i, item ->
                val sx = pad + i * slotW + slotW / 2f
                rc.captionPaint.textSize = rc.sp(10.5f)
                rc.text(
                    canvas,
                    item.hourLabel,
                    sx,
                    hourlyTop + dp(12f),
                    rc.captionPaint,
                    IslandColors.TEXT_SECONDARY,
                    alpha,
                    Paint.Align.CENTER
                )

                val g = glyphForCondition(item.condition)
                val gColor = accentColorFor(item.condition)
                rc.glyphs.draw(canvas, g, sx, hourlyTop + dp(26f), dp(14f), gColor, alpha)

                rc.numberPaint.textSize = rc.sp(11f)
                val tempRain = if (item.rainProbPercent >= 40) {
                    "${item.tempC}° · ${item.rainProbPercent}%"
                } else {
                    "${item.tempC}°"
                }
                rc.text(
                    canvas,
                    tempRain,
                    sx,
                    hourlyTop + hourlyH - dp(4f),
                    rc.numberPaint,
                    IslandColors.TEXT,
                    alpha,
                    Paint.Align.CENTER
                )
            }
        }
    }

    private fun drawAnimatedWeatherIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        condition: WeatherCondition,
        alpha: Float
    ) {
        val t = rc.animTime
        val accent = accentColorFor(condition)
        rc.circle(canvas, cx, cy, radius * 1.05f, ColorExtractor.withAlpha(accent, 0.18f), alpha)

        when (condition) {
            WeatherCondition.CLEAR_DAY -> {
                val pulse = 1f + 0.06f * sin(t * 3.2f)
                rc.circle(canvas, cx, cy, radius * 0.42f * pulse, 0xFFFBBF24.toInt(), alpha)
                rc.stroke.shader = null
                rc.stroke.strokeWidth = dp(1.7f)
                rc.stroke.strokeCap = Paint.Cap.ROUND
                rc.stroke.color = 0xFFF59E0B.toInt()
                rc.stroke.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                val rot = t * 0.55f
                for (i in 0 until 8) {
                    val a = rot + i * (Math.PI / 4.0).toFloat()
                    val r1 = radius * 0.58f
                    val r2 = radius * 0.82f
                    canvas.drawLine(
                        cx + cos(a) * r1,
                        cy + sin(a) * r1,
                        cx + cos(a) * r2,
                        cy + sin(a) * r2,
                        rc.stroke
                    )
                }
            }

            WeatherCondition.CLEAR_NIGHT -> {
                rc.glyphs.draw(canvas, Glyph.MOON, cx, cy, radius * 1.25f, 0xFFA5B4FC.toInt(), alpha)
            }

            WeatherCondition.PARTLY_CLOUDY -> {
                rc.circle(canvas, cx - radius * 0.22f, cy - radius * 0.20f, radius * 0.34f, 0xFFFBBF24.toInt(), alpha)
                val drift = sin(t * 2.0f) * dp(1.5f)
                rc.glyphs.draw(canvas, Glyph.CLOUD, cx + drift + radius * 0.06f, cy + radius * 0.10f, radius * 1.25f, 0xFFE2E8F0.toInt(), alpha)
            }

            WeatherCondition.CLOUDY -> {
                val drift = sin(t * 1.8f) * dp(1.6f)
                rc.glyphs.draw(canvas, Glyph.CLOUD, cx + drift, cy, radius * 1.30f, 0xFFCBD5E1.toInt(), alpha)
            }

            WeatherCondition.RAIN, WeatherCondition.HEAVY_RAIN -> {
                rc.glyphs.draw(canvas, Glyph.CLOUD, cx, cy - radius * 0.16f, radius * 1.22f, 0xFF94A3B8.toInt(), alpha)
                rc.stroke.shader = null
                rc.stroke.strokeWidth = dp(1.6f)
                rc.stroke.strokeCap = Paint.Cap.ROUND
                rc.stroke.color = 0xFF38BDF8.toInt()
                rc.stroke.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                for (i in -1..1) {
                    val phase = (t * 2.6f + (i + 1) * 0.33f) % 1f
                    val dx = cx + i * radius * 0.34f - phase * radius * 0.12f
                    val dy = cy + radius * 0.18f + phase * radius * 0.48f
                    canvas.drawLine(dx, dy, dx - radius * 0.10f, dy + radius * 0.20f, rc.stroke)
                }
            }

            WeatherCondition.THUNDERSTORM -> {
                rc.glyphs.draw(canvas, Glyph.CLOUD, cx, cy - radius * 0.18f, radius * 1.22f, 0xFF64748B.toInt(), alpha)
                val flash = if (sin(t * 7.5f) > 0.15f) 1f else 0.55f
                rc.fill.shader = null
                rc.fill.color = 0xFFFACC15.toInt()
                rc.fill.alpha = (255 * alpha * flash).toInt().coerceIn(0, 255)
                boltPath.reset()
                boltPath.moveTo(cx + radius * 0.05f, cy + radius * 0.02f)
                boltPath.lineTo(cx - radius * 0.20f, cy + radius * 0.42f)
                boltPath.lineTo(cx + radius * 0.02f, cy + radius * 0.42f)
                boltPath.lineTo(cx - radius * 0.10f, cy + radius * 0.82f)
                boltPath.lineTo(cx + radius * 0.24f, cy + radius * 0.34f)
                boltPath.lineTo(cx + radius * 0.04f, cy + radius * 0.34f)
                boltPath.close()
                canvas.drawPath(boltPath, rc.fill)
            }

            WeatherCondition.SNOW -> {
                rc.glyphs.draw(canvas, Glyph.SNOW, cx, cy, radius * 1.28f, 0xFFBAE6FD.toInt(), alpha)
            }
        }
    }

    override val contentDescription: String
        get() = payload?.let { "${it.cityName}, ${it.currentTempC} độ C, ${it.conditionLabel}" } ?: "Thời tiết"

    companion object {
        const val ACTION_OPEN = "weather.open"

        fun accentColorFor(condition: WeatherCondition): Int = when (condition) {
            WeatherCondition.CLEAR_DAY -> 0xFFFBBF24.toInt()
            WeatherCondition.CLEAR_NIGHT -> 0xFFA5B4FC.toInt()
            WeatherCondition.PARTLY_CLOUDY -> 0xFFFCD34D.toInt()
            WeatherCondition.CLOUDY -> 0xFFCBD5E1.toInt()
            WeatherCondition.RAIN, WeatherCondition.HEAVY_RAIN -> 0xFF38BDF8.toInt()
            WeatherCondition.THUNDERSTORM -> 0xFFFACC15.toInt()
            WeatherCondition.SNOW -> 0xFFBAE6FD.toInt()
        }

        fun glyphForCondition(condition: WeatherCondition): Glyph = when (condition) {
            WeatherCondition.CLEAR_DAY -> Glyph.SUN
            WeatherCondition.CLEAR_NIGHT -> Glyph.MOON
            WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLOUDY -> Glyph.CLOUD
            WeatherCondition.RAIN, WeatherCondition.HEAVY_RAIN -> Glyph.RAIN
            WeatherCondition.THUNDERSTORM -> Glyph.STORM
            WeatherCondition.SNOW -> Glyph.SNOW
        }
    }
}
