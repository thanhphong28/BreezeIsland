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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.OutputKind
import kotlin.math.max
import kotlin.math.min

data class MusicWidgetRenderState(
    val media: MediaPayload? = null,
    val artwork: Bitmap? = null,
    val appIcon: Bitmap? = null,
    val linkedPackage: String = "",
    val linkedAppLabel: String = "",
    val linkedAppIcon: Bitmap? = null,
    val showLyrics: Boolean = true,
    val fullLyrics: Boolean = false,
    val lyrics: LyricWindow = LyricWindow(),
    val positionMs: Long = 0L,
)

/**
 * Renders the 800x800 ultra-modern glassmorphic 4x4 Music & Live Lyrics card shared by both the
 * Z Flip 5 Cover Screen / Home AppWidget and the Settings live preview.
 */
object BreezeMusicWidgetRenderer {

    const val SIZE_PX = 800
    private const val CARD_RADIUS = 54f
    private const val DEFAULT_ACCENT = 0xFF38BDF8.toInt()
    private const val DEFAULT_ACCENT_ALT = 0xFF818CF8.toInt()

    private val boldTypeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun render(state: MusicWidgetRenderState): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        renderOntoCanvas(Canvas(bmp), SIZE_PX.toFloat(), state, clipRoundedCorners = true)
        return bmp
    }

    /**
     * Compact 640x640 RGB_565 bitmap (~800 KB) safe for Android Binder / Samsung Flex Window
     * RemoteViews IPC while relying on widget_root's clipToOutline="true" for outer corners.
     */
    fun renderForRemoteViews(state: MusicWidgetRenderState): Bitmap {
        val outSize = 640
        val bmp = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        val scale = outSize.toFloat() / SIZE_PX.toFloat()
        canvas.scale(scale, scale)
        renderOntoCanvas(canvas, SIZE_PX.toFloat(), state, clipRoundedCorners = false)
        return bmp
    }

    private fun renderOntoCanvas(
        canvas: Canvas,
        canvasPx: Float,
        state: MusicWidgetRenderState,
        clipRoundedCorners: Boolean,
    ) {
        val W = canvasPx
        val H = canvasPx

        val save = canvas.save()
        if (clipRoundedCorners) {
            val cardClip = Path().apply {
                addRoundRect(RectF(0f, 0f, W, H), CARD_RADIUS, CARD_RADIUS, Path.Direction.CW)
            }
            canvas.clipPath(cardClip)
        }

        val media = state.media
        val accent = boostAccent(media?.accent ?: 0)
        val accentAlt = shiftColor(accent, 0.25f, DEFAULT_ACCENT_ALT)

        // 1. Deep Glassmorphic Backdrop + Blurred/Tinted Artwork Aura
        drawBackdrop(canvas, W, H, state.artwork, accent, accentAlt, isPlaying = media?.isPlaying == true)

        // 2. Band 1 (0..112px -> 14% weight): Linked App Pill + Lyric Mode Toggle
        drawHeaderBar(canvas, state, accent)

        // 3. Band 2 (112..560px -> 56% weight): Album Art + Metadata + Live Synced Lyrics
        if (media == null) {
            drawIdleHero(canvas, state, accent)
        } else if (state.showLyrics && state.fullLyrics) {
            drawFullLyricsStage(canvas, media, state.artwork, state.lyrics, accent)
        } else {
            drawStandardStage(canvas, media, state.artwork, state.lyrics, state.showLyrics, accent)
        }

        // 4. Band 3 (560..648px -> 11% weight): Timestamps + Glowing Progress Bar
        drawTimeline(canvas, media, state.positionMs, accent, accentAlt)

        // 5. Band 4 (648..800px -> 19% weight): Tactile Frosted Transport Controls
        drawTransportControls(canvas, media, state.linkedAppLabel.isNotEmpty(), accent)

        // Subtle outer frosted border & top rim highlight
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            shader = LinearGradient(
                0f, 0f, W, H,
                intArrayOf(0x55FFFFFF, 0x1EFFFFFF, withAlpha(accent, 90)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(1.2f, 1.2f, W - 1.2f, H - 1.2f), CARD_RADIUS, CARD_RADIUS, borderPaint)

        canvas.restoreToCount(save)
    }

    private fun drawBackdrop(
        canvas: Canvas,
        W: Float,
        H: Float,
        artwork: Bitmap?,
        accent: Int,
        accentAlt: Int,
        isPlaying: Boolean,
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, W, H,
                intArrayOf(0xFF0A0C14.toInt(), 0xFF101322.toInt(), 0xFF090B11.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W, H, bgPaint)

        if (artwork != null) {
            // Downscale-then-upscale softens pixels for a creamy ambient glass backdrop without RenderEffect deps
            val soft = Bitmap.createScaledBitmap(artwork, 24, 24, true)
            val src = Rect(0, 0, soft.width, soft.height)
            val dst = RectF(-40f, -40f, W + 40f, H + 40f)
            val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = if (isPlaying) 115 else 82
            }
            canvas.drawBitmap(soft, src, dst, artPaint)
            soft.recycle()

            // Readability scrim over artwork aura
            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, H,
                    intArrayOf(0x99080A11.toInt(), 0x700A0C16.toInt(), 0xCC06070C.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, W, H, scrimPaint)
        }

        // Dual ambient neon orbs
        val orb1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                W * 0.16f, H * 0.18f, W * 0.58f,
                intArrayOf(withAlpha(accent, if (isPlaying) 76 else 44), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W, H, orb1)

        val orb2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                W * 0.85f, H * 0.82f, W * 0.56f,
                intArrayOf(withAlpha(accentAlt, if (isPlaying) 64 else 38), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W, H, orb2)
    }

    private fun drawHeaderBar(canvas: Canvas, state: MusicWidgetRenderState, accent: Int) {
        val media = state.media
        val displayLabel = media?.appLabel?.ifBlank { null }
            ?: state.linkedAppLabel.ifBlank { null }
            ?: "BreezeMusic"
        val displayIcon = if (media != null) state.appIcon ?: state.linkedAppIcon else state.linkedAppIcon

        val subTag = when {
            media != null && !media.outputName.isNullOrBlank() -> " · ${media.outputName}"
            media != null && media.outputKind == OutputKind.BLUETOOTH -> " · Bluetooth"
            media != null -> if (media.isPlaying) " · Đang phát" else " · Tạm dừng"
            state.linkedAppLabel.isNotBlank() -> " · Đã liên kết"
            else -> " · Widget 4×4"
        }

        // Measure chip width dynamically within left 72% column (max x = 556)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 23f
            color = 0xFFF8FAFC.toInt()
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = regularTypeface
            textSize = 21f
            color = 0xAA94A3B8.toInt()
        }

        val maxTextW = 556f - 28f - 46f - 22f
        val appW = textPaint.measureText(displayLabel)
        val remainingW = (maxTextW - appW).coerceAtLeast(0f)
        val clippedSub = if (remainingW > 40f) {
            TextUtils.ellipsize(subTag, subPaint, remainingW, TextUtils.TruncateAt.END).toString()
        } else ""
        val subW = if (clippedSub.isNotEmpty()) subPaint.measureText(clippedSub) else 0f
        val chipW = min(532f, 20f + 38f + 12f + appW + subW + 24f)

        val chipRect = RectF(28f, 24f, 28f + chipW, 88f)
        drawGlassPill(canvas, chipRect, 32f, fill = 0x26FFFFFF, stroke = 0x33FFFFFF)

        // App icon or music note badge inside left pill
        val iconRect = RectF(42f, 35f, 84f, 77f)
        if (displayIcon != null) {
            drawRoundedBitmap(canvas, displayIcon, iconRect, 14f)
        } else {
            val circleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 90) }
            canvas.drawCircle(iconRect.centerX(), iconRect.centerY(), 20f, circleP)
            drawMusicNoteGlyph(canvas, iconRect.centerX(), iconRect.centerY(), 11f, Color.WHITE)
        }

        // Live dot if playing
        if (media?.isPlaying == true) {
            val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4ADE80.toInt() }
            canvas.drawCircle(iconRect.right - 2f, iconRect.bottom - 2f, 6f, dotP)
        }

        val textX = iconRect.right + 14f
        val textY = chipRect.centerY() + 8f
        val clippedApp = TextUtils.ellipsize(displayLabel, textPaint, maxTextW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedApp, textX, textY, textPaint)
        if (clippedSub.isNotEmpty()) {
            canvas.drawText(clippedSub, textX + textPaint.measureText(clippedApp), textY, subPaint)
        }

        // Right pill (x = 592..772 -> falls squarely inside btn_toggle_lyrics weight 72..100%)
        val modeRect = RectF(592f, 26f, 772f, 86f)
        val fullActive = state.showLyrics && state.fullLyrics
        val lyricsOn = state.showLyrics
        val modeFill = when {
            fullActive -> withAlpha(accent, 115)
            lyricsOn -> 0x28FFFFFF
            else -> 0x16FFFFFF
        }
        val modeStroke = if (fullActive) withAlpha(accent, 210) else 0x38FFFFFF
        drawGlassPill(canvas, modeRect, 30f, fill = modeFill, stroke = modeStroke)

        val label = when {
            fullActive -> "FULL LYRIC"
            lyricsOn -> "LYRIC"
            else -> "ART"
        }
        val modeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 20f
            letterSpacing = 0.06f
            textAlign = Paint.Align.CENTER
            color = if (lyricsOn) 0xFFFFFFFF.toInt() else 0xAA94A3B8.toInt()
        }
        canvas.drawText(label, modeRect.centerX(), modeRect.centerY() + 7f, modeTextPaint)
    }

    private fun drawStandardStage(
        canvas: Canvas,
        media: MediaPayload,
        artwork: Bitmap?,
        lyrics: LyricWindow,
        showLyrics: Boolean,
        accent: Int,
    ) {
        // Upper sub-stage (y = 124..332): Album Art 200x200 + Title/Artist/Album
        val artRect = RectF(32f, 128f, 232f, 328f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accent, 75)
            setShadowLayer(26f, 0f, 12f, 0x99000000.toInt())
        }
        canvas.drawRoundRect(artRect, 34f, 34f, shadowPaint)

        if (artwork != null) {
            drawRoundedBitmap(canvas, artwork, artRect, 34f)
        } else {
            val fallbackP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    artRect.left, artRect.top, artRect.right, artRect.bottom,
                    intArrayOf(withAlpha(accent, 140), 0xFF1E293B.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(artRect, 34f, 34f, fallbackP)
            drawMusicNoteGlyph(canvas, artRect.centerX(), artRect.centerY(), 38f, 0xE6FFFFFF.toInt())
        }

        val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x44FFFFFF
        }
        canvas.drawRoundRect(artRect, 34f, 34f, rimP)

        // Right metadata block
        val textLeft = 258f
        val textWidth = (768f - textLeft).toInt()
        var curY = 142f

        // Playing EQ wave / status tag
        if (media.isPlaying) {
            drawMiniEqBars(canvas, textLeft, curY + 2f, accent)
            val statusP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = 18f
                letterSpacing = 0.08f
                color = 0xFF7DD3FC.toInt()
            }
            canvas.drawText("NOW PLAYING", textLeft + 38f, curY + 18f, statusP)
        } else {
            val statusP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = 18f
                letterSpacing = 0.08f
                color = 0xAA94A3B8.toInt()
            }
            canvas.drawText("PAUSED", textLeft, curY + 18f, statusP)
        }
        curY += 32f

        // Track Title (up to 2 lines cleanly wrapped)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 35f
            color = Color.WHITE
        }
        val titleLayout = buildStaticLayout(media.title, titlePaint, textWidth, maxLines = 2)
        canvas.save()
        canvas.translate(textLeft, curY)
        titleLayout.draw(canvas)
        canvas.restore()
        curY += titleLayout.height + 8f

        // Artist
        if (media.artist.isNotBlank()) {
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mediumTypeface
                textSize = 25f
                color = 0xCCE2E8F0.toInt()
            }
            val clippedArtist = TextUtils.ellipsize(media.artist, artistPaint, textWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedArtist, textLeft, curY + 22f, artistPaint)
            curY += 34f
        }

        // Album / Up Next hint if space remains
        val secondaryInfo = media.album.takeIf { it.isNotBlank() && it != media.title }
            ?: media.upNextTitle?.let { "Tiếp theo: $it" }
        if (!secondaryInfo.isNullOrBlank() && curY < 312f) {
            val albumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regularTypeface
                textSize = 20f
                color = 0x8094A3B8.toInt()
            }
            val clippedAlbum = TextUtils.ellipsize(secondaryInfo, albumPaint, textWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedAlbum, textLeft, curY + 18f, albumPaint)
        }

        // Lower sub-stage (y = 354..548): Synced Lyrics Frosted Card
        val lyricBox = RectF(28f, 354f, 772f, 548f)
        drawGlassPill(canvas, lyricBox, 32f, fill = 0x1EFFFFFF, stroke = 0x2EFFFFFF)

        // Subtle left accent bar inside lyrics card
        val barP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, lyricBox.top + 24f, 0f, lyricBox.bottom - 24f,
                intArrayOf(accent, 0xFF38BDF8.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(lyricBox.left + 16f, lyricBox.top + 28f, lyricBox.left + 22f, lyricBox.bottom - 28f), 3f, 3f, barP)

        if (!showLyrics) {
            val infoTitleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = 24f
                color = 0xE6FFFFFF.toInt()
            }
            val infoSubP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regularTypeface
                textSize = 21f
                color = 0x99CBD5E1.toInt()
            }
            canvas.drawText("Chế độ bìa nhạc tối giản", 56f, 434f, infoTitleP)
            canvas.drawText("Chạm \"ART\" ở góc trên phải để bật lại Lyric đồng bộ", 56f, 472f, infoSubP)
            return
        }

        val innerLeft = 52f
        val innerWidth = (748f - innerLeft).toInt()
        val prev = lyrics.prevLine
        val current = lyrics.currentLine
        val next = lyrics.nextLine

        if (current.isNullOrBlank()) {
            val emptyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mediumTypeface
                textSize = 24f
                color = 0xAA94A3B8.toInt()
            }
            val msg = if (lyrics.isLoading) "♪ Đang đồng bộ lời bài hát..." else "♪ Chưa có lời đồng bộ cho bài hát này"
            val layout = buildStaticLayout(msg, emptyP, innerWidth, maxLines = 2)
            canvas.save()
            canvas.translate(innerLeft, lyricBox.centerY() - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
            return
        }

        val prevP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = regularTypeface
            textSize = 22f
            color = 0x70E2E8F0.toInt()
        }
        val curP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 30f
            color = Color.WHITE
            setShadowLayer(10f, 0f, 0f, withAlpha(accent, 140))
        }
        val nextP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumTypeface
            textSize = 23f
            color = 0x9EE2E8F0.toInt()
        }

        val curLayout = buildStaticLayout(current, curP, innerWidth, maxLines = 2)
        var y = lyricBox.top + 22f

        if (!prev.isNullOrBlank() && curLayout.lineCount == 1) {
            val clippedPrev = TextUtils.ellipsize(prev, prevP, innerWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedPrev, innerLeft, y + 22f, prevP)
            y += 38f
        } else if (curLayout.lineCount == 1) {
            y += 18f
        }

        canvas.save()
        canvas.translate(innerLeft, y)
        curLayout.draw(canvas)
        canvas.restore()
        y += curLayout.height + 12f

        if (!next.isNullOrBlank() && y + 24f < lyricBox.bottom - 10f) {
            val clippedNext = TextUtils.ellipsize(next, nextP, innerWidth.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedNext, innerLeft, y + 22f, nextP)
        }
    }

    private fun drawFullLyricsStage(
        canvas: Canvas,
        media: MediaPayload,
        artwork: Bitmap?,
        lyrics: LyricWindow,
        accent: Int,
    ) {
        // Mini header row (y = 122..190)
        val miniArtRect = RectF(32f, 124f, 96f, 188f)
        if (artwork != null) {
            drawRoundedBitmap(canvas, artwork, miniArtRect, 18f)
        } else {
            drawGlassPill(canvas, miniArtRect, 18f, fill = withAlpha(accent, 90), stroke = 0x44FFFFFF)
            drawMusicNoteGlyph(canvas, miniArtRect.centerX(), miniArtRect.centerY(), 16f, Color.WHITE)
        }

        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 26f
            color = Color.WHITE
        }
        val artistP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = regularTypeface
            textSize = 21f
            color = 0xAA94A3B8.toInt()
        }
        val maxW = 768f - 114f
        val clippedTitle = TextUtils.ellipsize(media.title, titleP, maxW, TextUtils.TruncateAt.END).toString()
        val clippedArtist = TextUtils.ellipsize(media.artist.ifBlank { media.appLabel }, artistP, maxW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedTitle, 114f, 152f, titleP)
        canvas.drawText(clippedArtist, 114f, 181f, artistP)

        // Subtle divider
        val divP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22FFFFFF
            strokeWidth = 1.5f
        }
        canvas.drawLine(32f, 202f, 768f, 202f, divP)

        // Lyrics container (y = 212..552)
        val box = RectF(28f, 214f, 772f, 550f)
        drawGlassPill(canvas, box, 32f, fill = 0x1AFFFFFF, stroke = 0x26FFFFFF)

        val current = lyrics.currentLine
        if (current.isNullOrBlank()) {
            val emptyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mediumTypeface
                textSize = 25f
                color = 0xAA94A3B8.toInt()
            }
            val msg = if (lyrics.isLoading) "♪ Đang tải lời bài hát đồng bộ..." else "♪ Chưa tìm thấy lời đồng bộ cho bài hát này"
            val layout = buildStaticLayout(msg, emptyP, (box.width() - 64f).toInt(), maxLines = 2)
            canvas.save()
            canvas.translate(box.left + 32f, box.centerY() - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
            return
        }

        val textW = (box.width() - 56f).toInt()
        val x = box.left + 28f
        var y = box.top + 24f

        val prevP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = regularTypeface
            textSize = 23f
            color = 0x62E2E8F0.toInt()
        }
        if (!lyrics.prevLine.isNullOrBlank()) {
            val pText = TextUtils.ellipsize(lyrics.prevLine, prevP, textW.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(pText, x, y + 22f, prevP)
            y += 42f
        }

        // Highlight active lyric pill
        val curP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 31f
            color = Color.WHITE
            setShadowLayer(12f, 0f, 0f, withAlpha(accent, 150))
        }
        val curLayout = buildStaticLayout(current, curP, textW - 20, maxLines = 2)
        val highlightRect = RectF(box.left + 14f, y - 6f, box.right - 14f, y + curLayout.height + 14f)
        drawGlassPill(canvas, highlightRect, 20f, fill = withAlpha(accent, 52), stroke = withAlpha(accent, 120))
        canvas.save()
        canvas.translate(x + 8f, y + 4f)
        curLayout.draw(canvas)
        canvas.restore()
        y += curLayout.height + 26f

        // Next 3 lines fading softly
        val alphas = intArrayOf(0xC0, 0x82, 0x4E)
        lyrics.upcomingLines.take(3).forEachIndexed { idx, line ->
            if (y + 28f > box.bottom - 12f) return@forEachIndexed
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (idx == 0) mediumTypeface else regularTypeface
                textSize = 25f
                color = Color.argb(alphas[idx], 226, 232, 240)
            }
            val clipped = TextUtils.ellipsize(line, p, textW.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(clipped, x, y + 22f, p)
            y += 40f
        }
    }

    private fun drawIdleHero(canvas: Canvas, state: MusicWidgetRenderState, accent: Int) {
        val box = RectF(28f, 126f, 772f, 548f)
        drawGlassPill(canvas, box, 38f, fill = 0x1AFFFFFF, stroke = 0x2CFFFFFF)

        // Centered glowing app icon
        val cx = box.centerX()
        val cy = box.top + 134f
        val haloP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, 120f,
                intArrayOf(withAlpha(accent, 105), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, 120f, haloP)

        val iconRect = RectF(cx - 64f, cy - 64f, cx + 64f, cy + 64f)
        if (state.linkedAppIcon != null) {
            drawRoundedBitmap(canvas, state.linkedAppIcon, iconRect, 32f)
        } else {
            drawGlassPill(canvas, iconRect, 32f, fill = withAlpha(accent, 120), stroke = 0x55FFFFFF)
            drawMusicNoteGlyph(canvas, cx, cy, 34f, Color.WHITE)
        }

        val title = if (state.linkedAppLabel.isNotBlank()) {
            "Sẵn sàng trên ${state.linkedAppLabel}"
        } else {
            "BreezeMusic 4×4 & Live Lyric"
        }
        val subtitle = if (state.linkedAppLabel.isNotBlank()) {
            "Chạm ▶ bên dưới để phát hoặc chạm vào đây để mở app"
        } else {
            "Chọn ứng dụng nhạc mặc định trong cài đặt BreezeIsland"
        }

        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 32f
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title, cx, cy + 114f, titleP)

        val subP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = regularTypeface
            textSize = 23f
            color = 0xAA94A3B8.toInt()
        }
        val subLayout = StaticLayout.Builder
            .obtain(subtitle, 0, subtitle.length, subP, 620)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(cx - 310f, cy + 132f)
        subLayout.draw(canvas)
        canvas.restore()

        val tagText = if (state.showLyrics) "SYNCED LYRICS READY" else "COVER SCREEN MUSIC"
        val tagRect = RectF(cx - 140f, box.bottom - 58f, cx + 140f, box.bottom - 20f)
        drawGlassPill(canvas, tagRect, 19f, fill = 0x20FFFFFF, stroke = 0x33FFFFFF)
        val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = 16f
            letterSpacing = 0.08f
            color = 0xCC7DD3FC.toInt()
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(tagText, tagRect.centerX(), tagRect.centerY() + 6f, tagP)
    }

    private fun drawTimeline(
        canvas: Canvas,
        media: MediaPayload?,
        positionMs: Long,
        accent: Int,
        accentAlt: Int,
    ) {
        val left = 36f
        val right = 764f
        val barTop = 608f
        val barBottom = 620f
        val duration = media?.durationMs ?: 0L
        val pos = if (media != null) positionMs.coerceAtLeast(0L) else 0L
        val frac = if (duration > 0L) (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

        val timeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mediumTypeface
            textSize = 21f
            color = 0xBBFFFFFF.toInt()
            fontFeatureSettings = "tnum"
        }
        canvas.drawText(formatDuration(pos), left, 594f, timeP)

        timeP.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (duration > 0L) formatDuration(duration) else "--:--", right, 594f, timeP)

        val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        canvas.drawRoundRect(RectF(left, barTop, right, barBottom), 6f, 6f, railPaint)

        if (media != null && duration > 0L) {
            val fillRight = left + (right - left) * frac
            if (fillRight > left + 6f) {
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        left, 0f, right, 0f,
                        intArrayOf(accent, 0xFF7DD3FC.toInt(), Color.WHITE),
                        null,
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(RectF(left, barTop, fillRight, barBottom), 6f, 6f, fillPaint)
            }
            val thumbGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                setShadowLayer(12f, 0f, 0f, accentAlt)
            }
            canvas.drawCircle(fillRight.coerceIn(left + 6f, right - 6f), (barTop + barBottom) / 2f, 10f, thumbGlow)
        }
    }

    private fun drawTransportControls(
        canvas: Canvas,
        media: MediaPayload?,
        hasLinkedApp: Boolean,
        accent: Int,
    ) {
        val playing = media?.isPlaying == true
        val prevEnabled = media == null || media.canSkipPrevious
        val nextEnabled = media == null || media.canSkipNext

        // Left weight (0..256): Previous
        val prevCx = 136f
        val cy = 720f
        drawGlassPill(canvas, RectF(prevCx - 66f, cy - 42f, prevCx + 66f, cy + 42f), 42f, fill = 0x1EFFFFFF, stroke = 0x2EFFFFFF)
        drawPrevGlyph(canvas, prevCx, cy, 20f, if (prevEnabled) 0xF0FFFFFF.toInt() else 0x55FFFFFF)

        // Center weight (256..544): Play / Pause luminous pill
        val playCx = 400f
        val playRect = RectF(playCx - 110f, cy - 48f, playCx + 110f, cy + 48f)
        val playFillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                playRect.left, playRect.top, playRect.right, playRect.bottom,
                if (playing) {
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFFE2E8F0.toInt())
                } else {
                    intArrayOf(withAlpha(accent, 235), 0xFF38BDF8.toInt())
                },
                null,
                Shader.TileMode.CLAMP,
            )
            setShadowLayer(22f, 0f, 8f, withAlpha(accent, 110))
        }
        canvas.drawRoundRect(playRect, 48f, 48f, playFillP)

        val playIconColor = if (playing) 0xFF0F172A.toInt() else Color.WHITE
        if (playing) {
            drawPauseGlyph(canvas, playCx, cy, 22f, playIconColor)
        } else {
            drawPlayGlyph(canvas, playCx + 3f, cy, 22f, playIconColor)
        }

        // Right weight (544..800): Next
        val nextCx = 664f
        drawGlassPill(canvas, RectF(nextCx - 66f, cy - 42f, nextCx + 66f, cy + 42f), 42f, fill = 0x1EFFFFFF, stroke = 0x2EFFFFFF)
        drawNextGlyph(canvas, nextCx, cy, 20f, if (nextEnabled || hasLinkedApp) 0xF0FFFFFF.toInt() else 0x55FFFFFF)
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.06f)
            .build()

    private fun drawGlassPill(canvas: Canvas, rect: RectF, radius: Float, fill: Int, stroke: Int) {
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        canvas.drawRoundRect(rect, radius, radius, fillP)
        val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = stroke
        }
        canvas.drawRoundRect(rect, radius, radius, strokeP)
    }

    private fun drawRoundedBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF, radius: Float) {
        val layer = canvas.saveLayer(dst, null)
        val maskP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawRoundRect(dst, radius, radius, maskP)

        val imgP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        // Center-crop source rect
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = max(dst.width() / bw, dst.height() / bh)
        val drawW = bw * scale
        val drawH = bh * scale
        val left = dst.centerX() - drawW / 2f
        val top = dst.centerY() - drawH / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), imgP)
        canvas.restoreToCount(layer)
    }

    private fun drawMiniEqBars(canvas: Canvas, x: Float, y: Float, accent: Int) {
        val heights = floatArrayOf(12f, 20f, 15f, 22f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        heights.forEachIndexed { idx, h ->
            val bx = x + idx * 8f
            canvas.drawRoundRect(RectF(bx, y + (22f - h), bx + 4.5f, y + 22f), 2.2f, 2.2f, p)
        }
    }

    private fun drawPlayGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx - r * 0.55f, cy - r * 0.72f)
            lineTo(cx + r * 0.78f, cy)
            lineTo(cx - r * 0.55f, cy + r * 0.72f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val barW = r * 0.34f
        val gap = r * 0.24f
        val top = cy - r * 0.68f
        val bot = cy + r * 0.68f
        canvas.drawRoundRect(RectF(cx - gap - barW, top, cx - gap, bot), 4f, 4f, p)
        canvas.drawRoundRect(RectF(cx + gap, top, cx + gap + barW, bot), 4f, 4f, p)
    }

    private fun drawPrevGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(cx - r * 0.78f, cy - r * 0.65f, cx - r * 0.52f, cy + r * 0.65f), 3f, 3f, p)
        val tri1 = Path().apply {
            moveTo(cx - r * 0.44f, cy)
            lineTo(cx + r * 0.16f, cy - r * 0.62f)
            lineTo(cx + r * 0.16f, cy + r * 0.62f)
            close()
        }
        val tri2 = Path().apply {
            moveTo(cx + r * 0.14f, cy)
            lineTo(cx + r * 0.76f, cy - r * 0.62f)
            lineTo(cx + r * 0.76f, cy + r * 0.62f)
            close()
        }
        canvas.drawPath(tri1, p)
        canvas.drawPath(tri2, p)
    }

    private fun drawNextGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val tri1 = Path().apply {
            moveTo(cx - r * 0.76f, cy - r * 0.62f)
            lineTo(cx - r * 0.14f, cy)
            lineTo(cx - r * 0.76f, cy + r * 0.62f)
            close()
        }
        val tri2 = Path().apply {
            moveTo(cx - r * 0.16f, cy - r * 0.62f)
            lineTo(cx + r * 0.44f, cy)
            lineTo(cx - r * 0.16f, cy + r * 0.62f)
            close()
        }
        canvas.drawPath(tri1, p)
        canvas.drawPath(tri2, p)
        canvas.drawRoundRect(RectF(cx + r * 0.52f, cy - r * 0.65f, cx + r * 0.78f, cy + r * 0.65f), 3f, 3f, p)
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

    private fun boostAccent(raw: Int): Int {
        if (raw == 0) return DEFAULT_ACCENT
        val hsv = FloatArray(3)
        Color.colorToHSV(raw, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceAtLeast(0.78f)
        return Color.HSVToColor(255, hsv)
    }

    private fun shiftColor(base: Int, hueDelta: Float, fallback: Int): Int {
        if (base == DEFAULT_ACCENT) return fallback
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[0] = (hsv[0] + hueDelta * 360f) % 360f
        hsv[1] = (hsv[1] * 0.9f).coerceIn(0.35f, 1f)
        hsv[2] = hsv[2].coerceAtLeast(0.7f)
        return Color.HSVToColor(255, hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return "$mins:${secs.toString().padStart(2, '0')}"
    }
}
