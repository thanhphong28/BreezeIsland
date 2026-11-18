package com.arnav.island.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.arnav.island.events.MediaPayload
import kotlin.math.max

data class MusicWidgetRenderState(
    val media: MediaPayload? = null,
    val artwork: Bitmap? = null,
    val appIcon: Bitmap? = null,
    val linkedPackage: String = "",
    val linkedAppLabel: String = "",
    val linkedAppIcon: Bitmap? = null,
    val showLyrics: Boolean = true,
    val fullLyrics: Boolean = false,
    val transparentBg: Boolean = false,
    val lyrics: LyricWindow = LyricWindow(),
    val positionMs: Long = 0L,
)

/**
 * Renders the 4x4 Cover Screen & Home Music Widget in two seamless full-bleed modes:
 *  - Controls + Live Lyric Mode ([MusicWidgetRenderState.fullLyrics] == false): Full-bleed HD
 *    artwork, compact top header with circular mode/close buttons, floating pure-white center
 *    transport controls, centered live synced lyric + chevron button, and minimal pill timeline.
 *  - Poster Editorial Lyric Mode ([MusicWidgetRenderState.fullLyrics] == true): Unobstructed
 *    full-bleed HD artwork with a subtle top-left mini art badge and bold left-aligned editorial
 *    synced lyrics at the bottom.
 */
object BreezeMusicWidgetRenderer {

    const val SIZE_PX = 800
    private const val REMOTE_SIZE_PX = 480
    private const val CARD_RADIUS = 42f

    private val boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun render(state: MusicWidgetRenderState): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        renderOntoCanvas(Canvas(bmp), SIZE_PX.toFloat(), state)
        return bmp
    }

    /**
     * 480x480 ARGB_8888 bitmap (~900 KB) — safely under the 1 MB Android Binder limit while
     * preserving true alpha transparency and smooth anti-aliased corners with zero border.
     */
    fun renderForRemoteViews(state: MusicWidgetRenderState): Bitmap {
        val bmp = Bitmap.createBitmap(REMOTE_SIZE_PX, REMOTE_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scale = REMOTE_SIZE_PX.toFloat() / SIZE_PX.toFloat()
        canvas.scale(scale, scale)
        renderOntoCanvas(canvas, SIZE_PX.toFloat(), state)
        return bmp
    }

    private fun renderOntoCanvas(
        canvas: Canvas,
        canvasPx: Float,
        state: MusicWidgetRenderState,
    ) {
        val W = canvasPx
        val H = canvasPx
        val save = canvas.save()

        if (!state.transparentBg) {
            val cardClip = Path().apply {
                addRoundRect(RectF(0f, 0f, W, H), CARD_RADIUS, CARD_RADIUS, Path.Direction.CW)
            }
            canvas.clipPath(cardClip)
        }

        val media = state.media
        val posterMode = media != null && state.fullLyrics

        // 1. Full-bleed HD Album Art + Cinematic Vignette (or Seamless Transparent Feather)
        drawFullBleedArtwork(canvas, W, H, state, posterMode)

        // 2. Content stage
        if (media == null) {
            drawIdleMinimalStage(canvas, W, H, state)
        } else if (posterMode) {
            drawPosterLyricStage(canvas, W, H, media, state)
        } else {
            drawOverlayControlsStage(canvas, W, H, media, state)
        }

        canvas.restoreToCount(save)
    }

    private fun drawFullBleedArtwork(
        canvas: Canvas,
        W: Float,
        H: Float,
        state: MusicWidgetRenderState,
        posterMode: Boolean,
    ) {
        val artwork = state.artwork
        if (state.transparentBg) {
            if (artwork != null) {
                val layer = canvas.saveLayer(0f, 0f, W, H, null)
                drawCenterCropBitmap(canvas, artwork, RectF(0f, 0f, W, H), alpha = 235)
                // Feather edges smoothly into the Cover Screen wallpaper so there is no box frame
                val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    shader = RadialGradient(
                        W * 0.5f, H * 0.48f, W * 0.54f,
                        intArrayOf(
                            0xFFFFFFFF.toInt(),
                            0xEEFFFFFF.toInt(),
                            0x88FFFFFF.toInt(),
                            0x00FFFFFF,
                        ),
                        floatArrayOf(0f, 0.58f, 0.84f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, W, H, maskPaint)
                canvas.restoreToCount(layer)

                // Soft central legibility scrim
                val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        W * 0.5f, H * 0.58f, W * 0.52f,
                        intArrayOf(0x66000000, 0x33000000, Color.TRANSPARENT),
                        floatArrayOf(0f, 0.65f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, W, H, scrim)
            }
            return
        }

        // Pure AMOLED Black base (blends invisibly into Z Flip 5 bezel)
        val blackPaint = Paint().apply { color = Color.BLACK }
        canvas.drawRect(0f, 0f, W, H, blackPaint)

        if (artwork != null) {
            drawCenterCropBitmap(canvas, artwork, RectF(0f, 0f, W, H), alpha = 255)

            if (posterMode) {
                // Image 2 look: upper 55% of artwork is crystal-clear; bottom fades smoothly to black for bold lyrics
                val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, H,
                        intArrayOf(
                            0x38000000,
                            0x00000000,
                            0x00000000,
                            0x99000000.toInt(),
                            0xEB000000.toInt(),
                        ),
                        floatArrayOf(0f, 0.16f, 0.48f, 0.74f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, W, H, grad)
            } else {
                // Image 3 look: full-bleed sharp art with top/bottom dark vignette & light center scrim
                val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, H,
                        intArrayOf(
                            0x88000000.toInt(),
                            0x28000000,
                            0x38000000,
                            0xB8000000.toInt(),
                            0xEA000000.toInt(),
                        ),
                        floatArrayOf(0f, 0.22f, 0.55f, 0.78f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRect(0f, 0f, W, H, grad)
            }
        } else {
            // Subtle dark ambient gradient when player doesn't supply artwork
            val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    W * 0.5f, H * 0.38f, W * 0.65f,
                    intArrayOf(0xFF1E2230.toInt(), 0xFF07080C.toInt(), 0xFF000000.toInt()),
                    floatArrayOf(0f, 0.65f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, W, H, fallback)
        }
    }

    /**
     * Mode Hình 2: Full-bleed poster with a tiny top-left album badge and large, bold,
     * left-aligned white editorial lyrics at the bottom.
     */
    private fun drawPosterLyricStage(
        canvas: Canvas,
        W: Float,
        H: Float,
        media: MediaPayload,
        state: MusicWidgetRenderState,
    ) {
        // Top-left small floating mini artwork badge (like Image 2)
        val badgeRect = RectF(54f, 54f, 100f, 100f)
        val badgeIcon = state.artwork ?: state.appIcon ?: state.linkedAppIcon
        if (badgeIcon != null) {
            drawRoundedBitmap(canvas, badgeIcon, badgeRect, 10f)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
            canvas.drawRoundRect(badgeRect, 10f, 10f, p)
        }

        val leftX = 56f
        val maxWidth = (W - 112f).toInt()
        val rawCurrent = state.lyrics.currentLine?.takeIf { it.isNotBlank() && it != "♪ …" }

        if (rawCurrent != null) {
            val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = 48f
                color = Color.WHITE
                setShadowLayer(18f, 0f, 4f, 0xCC000000.toInt())
            }
            val curLayout = StaticLayout.Builder
                .obtain(rawCurrent, 0, rawCurrent.length, lyricPaint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.04f)
                .build()

            val nextLine = state.lyrics.nextLine?.takeIf { it.isNotBlank() && curLayout.lineCount <= 2 }
            val nextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = 36f
                color = 0x78FFFFFF
                setShadowLayer(12f, 0f, 3f, 0xAA000000.toInt())
            }
            val nextLayout = nextLine?.let {
                StaticLayout.Builder
                    .obtain(it, 0, it.length, nextPaint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0f, 1.04f)
                    .build()
            }

            val totalH = curLayout.height + (nextLayout?.let { it.height + 18 } ?: 0)
            val startY = (H - 76f - totalH).coerceAtLeast(410f)

            canvas.save()
            canvas.translate(leftX, startY)
            curLayout.draw(canvas)
            canvas.restore()

            if (nextLayout != null) {
                canvas.save()
                canvas.translate(leftX, startY + curLayout.height + 18f)
                nextLayout.draw(canvas)
                canvas.restore()
            }
        } else {
            // Editorial title + artist when track has no synced lyrics
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = 46f
                color = Color.WHITE
                setShadowLayer(18f, 0f, 4f, 0xCC000000.toInt())
            }
            val titleLayout = StaticLayout.Builder
                .obtain(media.title, 0, media.title.length, titlePaint, maxWidth)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.04f)
                .build()

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mediumTypeface
                textSize = 28f
                color = 0xB8FFFFFF.toInt()
                setShadowLayer(12f, 0f, 3f, 0xAA000000.toInt())
            }
            val startY = H - 120f - titleLayout.height
            canvas.save()
            canvas.translate(leftX, startY)
            titleLayout.draw(canvas)
            canvas.restore()

            if (media.artist.isNotBlank()) {
                val clippedArtist = TextUtils.ellipsize(media.artist, artistPaint, maxWidth.toFloat(), TextUtils.TruncateAt.END).toString()
                canvas.drawText(clippedArtist, leftX, startY + titleLayout.height + 38f, artistPaint)
            }
        }
    }

    /**
     * Mode Hình 3: Full-bleed artwork with top header + circular buttons, floating white center
     * transport icons, left vertical volume bar, centered live lyric + chevron button, and
     * minimal bottom timeline.
     */
    private fun drawOverlayControlsStage(
        canvas: Canvas,
        W: Float,
        H: Float,
        media: MediaPayload,
        state: MusicWidgetRenderState,
    ) {
        // 1. Top Row (y = 50..108)
        val thumbRect = RectF(56f, 52f, 110f, 106f)
        val thumbBmp = state.artwork ?: state.appIcon ?: state.linkedAppIcon
        if (thumbBmp != null) {
            drawRoundedBitmap(canvas, thumbBmp, thumbRect, 12f)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
            canvas.drawRoundRect(thumbRect, 12f, 12f, p)
            drawMusicNoteGlyph(canvas, thumbRect.centerX(), thumbRect.centerY(), 14f, Color.WHITE)
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 28f
            color = Color.WHITE
            setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumTypeface
            textSize = 21f
            color = 0xCCFFFFFF.toInt()
            setShadowLayer(8f, 0f, 2f, 0xAA000000.toInt())
        }
        val maxTitleW = 528f - 128f
        val clippedTitle = TextUtils.ellipsize(media.title, titlePaint, maxTitleW, TextUtils.TruncateAt.END).toString()
        val subtitleText = media.artist.ifBlank { media.appLabel }
        val clippedArtist = TextUtils.ellipsize(subtitleText, artistPaint, maxTitleW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedTitle, 128f, 76f, titlePaint)
        canvas.drawText(clippedArtist, 128f, 102f, artistPaint)

        // Top-right circular buttons matching Image 3 (Expand to Poster Mode + Close/App button)
        val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x68282A34 }
        val expandCx = 584f
        val closeCx = 656f
        val topBtnCy = 79f
        canvas.drawCircle(expandCx, topBtnCy, 25f, circleFill)
        canvas.drawCircle(closeCx, topBtnCy, 25f, circleFill)
        drawExpandArrowsGlyph(canvas, expandCx, topBtnCy, 10f, Color.WHITE)
        drawCloseGlyph(canvas, closeCx, topBtnCy, 8.5f, Color.WHITE)

        // 2. Left Vertical Volume Bar (x = 46, y = 290..450) matching Image 3
        val volTrackLeft = 44f
        val volTrackRight = 51f
        val volTop = 292f
        val volBottom = 452f
        val volRailP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x45FFFFFF }
        canvas.drawRoundRect(RectF(volTrackLeft, volTop, volTrackRight, volBottom), 3.5f, 3.5f, volRailP)

        val volFrac = (media.volume ?: 0.55f).coerceIn(0.15f, 1f)
        val fillTop = volBottom - (volBottom - volTop) * volFrac
        val volFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(RectF(volTrackLeft, fillTop, volTrackRight, volBottom), 3.5f, 3.5f, volFillP)

        // 3. Center Floating White Transport Icons (cy = 370) — NO background pills!
        val controlsCy = 370f
        val prevColor = if (media.canSkipPrevious) Color.WHITE else 0x88FFFFFF.toInt()
        val nextColor = if (media.canSkipNext) Color.WHITE else 0x88FFFFFF.toInt()

        drawPrevGlyph(canvas, 236f, controlsCy, 26f, prevColor)
        if (media.isPlaying) {
            drawPauseGlyph(canvas, 400f, controlsCy, 30f, Color.WHITE)
        } else {
            drawPlayGlyph(canvas, 404f, controlsCy, 30f, Color.WHITE)
        }
        drawNextGlyph(canvas, 564f, controlsCy, 26f, nextColor)

        // 4. Lower-Middle Centered Synced Lyric + Right Chevron Circle (y = 550..605)
        val lyricCy = 576f
        val activeLyric = state.lyrics.currentLine?.takeIf { it.isNotBlank() && it != "♪ …" }
            ?: state.lyrics.nextLine?.takeIf { it.isNotBlank() }
            ?: media.album.takeIf { it.isNotBlank() && it != media.title }

        if (!activeLyric.isNullOrBlank()) {
            drawTwoToneLyricLine(canvas, activeLyric, centerX = 376f, baselineY = lyricCy + 9f, maxWidth = 500f)
        }

        // Right circular chevron button (`^`) at (668, 576) matching Image 3
        canvas.drawCircle(668f, lyricCy, 23f, circleFill)
        drawChevronUpGlyph(canvas, 668f, lyricCy, 8.5f, Color.WHITE)

        // 5. Bottom Timestamps + Minimal White Pill Progress Bar (y = 650..705)
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 23f
            color = Color.WHITE
            fontFeatureSettings = "tnum"
            setShadowLayer(8f, 0f, 2f, 0xAA000000.toInt())
        }
        val duration = media.durationMs.coerceAtLeast(0L)
        val pos = state.positionMs.coerceAtLeast(0L)
        val leftEdge = 82f
        val rightEdge = 692f

        canvas.drawText(formatDuration(pos), leftEdge, 664f, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (duration > 0L) formatDuration(duration) else "--:--", rightEdge, 664f, timePaint)

        // Sleek minimal progress bar (matches Image 3)
        val barTop = 684f
        val barBottom = 694f
        val barRight = 420f
        val railP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x42FFFFFF }
        canvas.drawRoundRect(RectF(leftEdge, barTop, barRight, barBottom), 5f, 5f, railP)

        if (duration > 0L) {
            val frac = (pos.toFloat() / duration.toFloat()).coerceIn(0.03f, 1f)
            val fillRight = leftEdge + (barRight - leftEdge) * frac
            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(leftEdge, barTop, fillRight, barBottom), 5f, 5f, fillP)
        }
    }

    /**
     * Draws a centered lyric phrase in Image 3's two-tone style (first half bold bright white,
     * trailing words soft translucent white).
     */
    private fun drawTwoToneLyricLine(
        canvas: Canvas,
        line: String,
        centerX: Float,
        baselineY: Float,
        maxWidth: Float,
    ) {
        val clean = line.trim()
        val primaryP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 26f
            color = Color.WHITE
            setShadowLayer(12f, 0f, 2f, 0xCC000000.toInt())
        }
        val secondaryP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumTypeface
            textSize = 26f
            color = 0x99FFFFFF.toInt()
            setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
        }

        val clipped = TextUtils.ellipsize(clean, primaryP, maxWidth, TextUtils.TruncateAt.END).toString()
        val words = clipped.split(" ")
        if (words.size <= 1) {
            primaryP.textAlign = Paint.Align.CENTER
            canvas.drawText(clipped, centerX, baselineY, primaryP)
            return
        }

        val splitIdx = (words.size / 2).coerceAtLeast(1)
        val part1 = words.take(splitIdx).joinToString(" ") + " "
        val part2 = words.drop(splitIdx).joinToString(" ")
        val w1 = primaryP.measureText(part1)
        val w2 = secondaryP.measureText(part2)
        val startX = centerX - (w1 + w2) / 2f

        canvas.drawText(part1, startX, baselineY, primaryP)
        canvas.drawText(part2, startX + w1, baselineY, secondaryP)
    }

    private fun drawIdleMinimalStage(
        canvas: Canvas,
        W: Float,
        H: Float,
        state: MusicWidgetRenderState,
    ) {
        val cx = W / 2f
        val iconRect = RectF(cx - 56f, 176f, cx + 56f, 288f)
        if (state.linkedAppIcon != null) {
            drawRoundedBitmap(canvas, state.linkedAppIcon, iconRect, 28f)
        } else {
            val circleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
            canvas.drawRoundRect(iconRect, 28f, 28f, circleP)
            drawMusicNoteGlyph(canvas, cx, iconRect.centerY(), 28f, Color.WHITE)
        }

        // Floating transport icons ready to tap
        val controlsCy = 410f
        drawPrevGlyph(canvas, 236f, controlsCy, 24f, 0x88FFFFFF.toInt())
        drawPlayGlyph(canvas, 404f, controlsCy, 30f, Color.WHITE)
        drawNextGlyph(canvas, 564f, controlsCy, 24f, 0x88FFFFFF.toInt())

        val title = if (state.linkedAppLabel.isNotBlank()) state.linkedAppLabel else "BreezeMusic"
        val sub = "Chạm ▶ để phát nhạc"

        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 32f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            setShadowLayer(12f, 0f, 3f, 0xCC000000.toInt())
        }
        val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumTypeface
            textSize = 23f
            color = 0x99FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt())
        }
        canvas.drawText(title, cx, 575f, titleP)
        canvas.drawText(sub, cx, 614f, subP)
    }

    private fun drawCenterCropBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, alpha: Int) {
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = max(dst.width() / bw, dst.height() / bh)
        val drawW = bw * scale
        val drawH = bh * scale
        val left = dst.centerX() - drawW / 2f
        val top = dst.centerY() - drawH / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.alpha = alpha.coerceIn(0, 255)
        }
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
    }

    private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, radius: Float) {
        val layer = canvas.saveLayer(dst, null)
        val maskP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRoundRect(dst, radius, radius, maskP)

        val imgP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = max(dst.width() / bw, dst.height() / bh)
        val drawW = bw * scale
        val drawH = bh * scale
        val left = dst.centerX() - drawW / 2f
        val top = dst.centerY() - drawH / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), imgP)
        canvas.restoreToCount(layer)
    }

    private fun shadowGlyphPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        setShadowLayer(16f, 0f, 4f, 0x99000000.toInt())
    }

    private fun drawPlayGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx - r * 0.55f, cy - r * 0.76f)
            lineTo(cx + r * 0.82f, cy)
            lineTo(cx - r * 0.55f, cy + r * 0.76f)
            close()
        }
        canvas.drawPath(path, shadowGlyphPaint(color))
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = shadowGlyphPaint(color)
        val barW = r * 0.36f
        val gap = r * 0.22f
        val top = cy - r * 0.72f
        val bot = cy + r * 0.72f
        canvas.drawRoundRect(RectF(cx - gap - barW, top, cx - gap, bot), 6f, 6f, p)
        canvas.drawRoundRect(RectF(cx + gap, top, cx + gap + barW, bot), 6f, 6f, p)
    }

    private fun drawPrevGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = shadowGlyphPaint(color)
        val tri1 = Path().apply {
            moveTo(cx - r * 0.85f, cy)
            lineTo(cx - r * 0.05f, cy - r * 0.64f)
            lineTo(cx - r * 0.05f, cy + r * 0.64f)
            close()
        }
        val tri2 = Path().apply {
            moveTo(cx - r * 0.12f, cy)
            lineTo(cx + r * 0.72f, cy - r * 0.64f)
            lineTo(cx + r * 0.72f, cy + r * 0.64f)
            close()
        }
        canvas.drawPath(tri1, p)
        canvas.drawPath(tri2, p)
    }

    private fun drawNextGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = shadowGlyphPaint(color)
        val tri1 = Path().apply {
            moveTo(cx - r * 0.72f, cy - r * 0.64f)
            lineTo(cx + r * 0.12f, cy)
            lineTo(cx - r * 0.72f, cy + r * 0.64f)
            close()
        }
        val tri2 = Path().apply {
            moveTo(cx + r * 0.05f, cy - r * 0.64f)
            lineTo(cx + r * 0.85f, cy)
            lineTo(cx + r * 0.05f, cy + r * 0.64f)
            close()
        }
        canvas.drawPath(tri1, p)
        canvas.drawPath(tri2, p)
    }

    private fun drawExpandArrowsGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Top-right & bottom-left diagonal expand arrows
        canvas.drawLine(cx - r, cy, cx + r, cy, p)
        canvas.drawLine(cx, cy - r, cx, cy + r, p)
        val s = r * 0.55f
        canvas.drawRect(RectF(cx - s, cy - s, cx + s, cy + s), p)
    }

    private fun drawCloseGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, p)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, p)
    }

    private fun drawChevronUpGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(cx - r, cy + r * 0.35f)
            lineTo(cx, cy - r * 0.55f)
            lineTo(cx + r, cy + r * 0.35f)
        }
        canvas.drawPath(path, p)
    }

    private fun drawMusicNoteGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(cx - r * 0.28f, cy + r * 0.42f, r * 0.28f, p)
        canvas.drawCircle(cx + r * 0.48f, cy + r * 0.28f, r * 0.26f, p)
        canvas.drawRect(cx - r * 0.08f, cy - r * 0.62f, cx + r * 0.04f, cy + r * 0.42f, p)
        canvas.drawRect(cx + r * 0.62f, cy - r * 0.74f, cx + r * 0.74f, cy + r * 0.28f, p)
        val beam = Path().apply {
            moveTo(cx - r * 0.08f, cy - r * 0.62f)
            lineTo(cx + r * 0.74f, cy - r * 0.78f)
            lineTo(cx + r * 0.74f, cy - r * 0.56f)
            lineTo(cx - r * 0.08f, cy - r * 0.40f)
            close()
        }
        canvas.drawPath(beam, p)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return "$mins:${secs.toString().padStart(2, '0')}"
    }
}
