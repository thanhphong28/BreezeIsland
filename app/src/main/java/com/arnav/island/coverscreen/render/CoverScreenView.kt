package com.arnav.island.coverscreen.render

import android.app.ActivityOptions
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.arnav.island.animation.Spring
import com.arnav.island.animation.SpringSpec
import com.arnav.island.core.AppGraph
import com.arnav.island.coverscreen.CoverPageType
import com.arnav.island.coverscreen.parseCoverPages
import com.arnav.island.events.EventType
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.NotificationPayload
import com.arnav.island.events.StopwatchPayload
import com.arnav.island.events.TimerPayload
import com.arnav.island.events.WeatherCondition
import com.arnav.island.events.WeatherPayload
import com.arnav.island.island.render.presenters.MediaPresenter
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interactive 60–120Hz multi-page Flex Window / Cover Screen view for Galaxy Z Flip 5 (and live preview).
 * Supports custom page ordering (`PET_3D`, `MEDIA`, `APPS`, `WEATHER`, `CLOCK`, `NOTIFICATIONS`),
 * swipe/dot navigation, interactive 3D dog care (pet, feed, play ball, phone shake), and a
 * floating minimized `[🐾]` pill handle on the real cover display.
 */
class CoverScreenView(
    context: Context,
    private val graph: AppGraph,
    private val isOverlayMode: Boolean = false,
    private val targetDisplayId: Int = Display.DEFAULT_DISPLAY,
    private val onMinimizeRequested: (() -> Unit)? = null,
    private val onExpandRequested: (() -> Unit)? = null,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val petRenderer = CoverPet3DRenderer(density)
    private val pageScrollSpring = Spring(0f, SpringSpec(0.30f, 0.86f), restThreshold = 0.002f)

    var settings: IslandSettings = graph.settings.state.value
        set(value) {
            field = value
            pages = parseCoverPages(value.coverPagesCsv)
            currentPageIndex = currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            pageScrollSpring.animateTo(currentPageIndex.toFloat())
            petRenderer.breed = value.coverPetBreed
            petRenderer.petName = value.coverPetName.ifBlank { "Breeze Pup" }
            refreshCachedAppsIfNeeded()
            invalidate()
        }

    var pages: List<CoverPageType> = parseCoverPages(settings.coverPagesCsv)
        private set

    var currentPageIndex: Int = 0
        private set

    var isMinimizedHandle: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Cached launchable apps for the APPS page
    private data class CoverAppItem(
        val packageName: String,
        val label: String,
        val icon: Bitmap?,
        val rect: RectF = RectF(),
    )

    private var cachedAppsCsv: String = "__init__"
    private var appItems: List<CoverAppItem> = emptyList()

    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
    }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        fontFeatureSettings = "tnum"
    }
    private val tmpRect = RectF()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockSecFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, dd/MM", Locale("vi", "VN"))
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // Hit targets on active pages
    private val minimizePillRect = RectF()
    private val pageDotRects = ArrayList<RectF>(8)
    private val mediaPrevRect = RectF()
    private val mediaToggleRect = RectF()
    private val mediaNextRect = RectF()
    private val weatherRefreshRect = RectF()

    // Touch & gesture tracking
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastFrameMs = 0L
    private var gestureMode = 0 // 0 = idle, 1 = petting dog, 2 = swiping page
    private var animRunning = false

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animRunning) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.05f)
            lastFrameMs = now
            petRenderer.step(dt)
            pageScrollSpring.step(dt)
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        petRenderer.breed = settings.coverPetBreed
        petRenderer.petName = settings.coverPetName.ifBlank { "Breeze Pup" }
        refreshCachedAppsIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    fun startAnim() {
        if (animRunning) return
        animRunning = true
        lastFrameMs = SystemClock.elapsedRealtime()
        choreographer.postFrameCallback(frameCallback)
    }

    fun stopAnim() {
        animRunning = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun goToPage(index: Int) {
        if (pages.isEmpty()) return
        currentPageIndex = index.coerceIn(0, pages.size - 1)
        pageScrollSpring.animateTo(currentPageIndex.toFloat())
        invalidate()
    }

    fun nextPage() {
        if (pages.size <= 1) return
        goToPage((currentPageIndex + 1) % pages.size)
    }

    fun previousPage() {
        if (pages.size <= 1) return
        goToPage((currentPageIndex - 1 + pages.size) % pages.size)
    }

    fun feedPet() {
        val petIdx = pages.indexOf(CoverPageType.PET_3D)
        if (petIdx >= 0 && currentPageIndex != petIdx) goToPage(petIdx)
        petRenderer.feedBone()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun playBallWithPet() {
        val petIdx = pages.indexOf(CoverPageType.PET_3D)
        if (petIdx >= 0 && currentPageIndex != petIdx) goToPage(petIdx)
        petRenderer.playBall()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun triggerShake() {
        if (!settings.coverShakeEnabled && isOverlayMode) return
        val petIdx = pages.indexOf(CoverPageType.PET_3D)
        if (petIdx >= 0) {
            if (currentPageIndex != petIdx && isOverlayMode) {
                goToPage(petIdx)
            }
            petRenderer.triggerShake()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun refreshCachedAppsIfNeeded() {
        val csv = settings.coverAppsCsv
        if (csv == cachedAppsCsv) return
        cachedAppsCsv = csv
        graph.scope.launch {
            val all = graph.apps.launchableApps()
            val selectedPkgs = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val chosen = if (selectedPkgs.isNotEmpty()) {
                selectedPkgs.mapNotNull { pkg -> all.firstOrNull { it.packageName == pkg } }
            } else {
                // Sensible out-of-the-box picks on user's phone
                all.filter { !it.isSystem }.take(6).ifEmpty { all.take(6) }
            }
            val iconPx = dp(48f).toInt().coerceAtLeast(64)
            val built = withContext(Dispatchers.Default) {
                chosen.take(8).map { app ->
                    CoverAppItem(
                        packageName = app.packageName,
                        label = app.label,
                        icon = graph.apps.iconBlocking(app.packageName, iconPx),
                    )
                }
            }
            appItems = built
            postInvalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isMinimizedHandle) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onExpandRequested?.invoke()
            }
            return true
        }

        velocityTracker = velocityTracker ?: VelocityTracker.obtain()
        velocityTracker?.addMovement(event)

        val x = event.x
        val y = event.y
        val activePage = pages.getOrNull(currentPageIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastTouchX = x
                gestureMode = 0
                if (!isOverlayMode) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val totalDx = x - downX
                val totalDy = y - downY

                if (gestureMode == 0 && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    val touchingDogBody = activePage == CoverPageType.PET_3D &&
                        petRenderer.hitBounds.contains(downX, downY) &&
                        downX > width * 0.14f && downX < width * 0.86f
                    gestureMode = if (touchingDogBody) {
                        1 // petting/stroking the 3D puppy
                    } else if (abs(totalDx) > abs(totalDy) * 0.85f && pages.size > 1) {
                        2 // swiping pages
                    } else if (activePage == CoverPageType.PET_3D) {
                        1
                    } else {
                        2
                    }
                }

                if (gestureMode == 1) {
                    petRenderer.onPetTapOrStroke(x, y, isDrag = true)
                } else if (gestureMode == 2 && width > 0) {
                    val deltaPage = -dx / width.toFloat()
                    val nextOffset = (pageScrollSpring.value + deltaPage).coerceIn(-0.25f, (pages.size - 1) + 0.25f)
                    pageScrollSpring.snapTo(nextOffset)
                }
                lastTouchX = x
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureMode == 1) {
                    petRenderer.onTouchRelease()
                } else if (gestureMode == 2 && event.actionMasked == MotionEvent.ACTION_UP) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val pos = pageScrollSpring.value
                    val target = when {
                        vx < -450f -> (currentPageIndex + 1).coerceAtMost(pages.size - 1)
                        vx > 450f -> (currentPageIndex - 1).coerceAtLeast(0)
                        else -> pos.roundToInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    }
                    goToPage(target)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    handleTap(x, y)
                }
                velocityTracker?.recycle()
                velocityTracker = null
                gestureMode = 0
                if (!isOverlayMode) parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        if (isOverlayMode && minimizePillRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onMinimizeRequested?.invoke()
            return
        }

        // Bottom page indicator dots
        pageDotRects.forEachIndexed { idx, r ->
            if (r.contains(x, y)) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                goToPage(idx)
                return
            }
        }

        val page = pages.getOrNull(currentPageIndex) ?: return
        when (page) {
            CoverPageType.PET_3D -> {
                if (petRenderer.handleTap(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            CoverPageType.MEDIA -> {
                val mediaEvent = graph.events.current().live.firstOrNull { it.type == EventType.MEDIA }
                when {
                    mediaPrevRect.contains(x, y) -> {
                        mediaEvent?.actions?.firstOrNull { it.id == MediaPresenter.ACTION_PREV }?.invoke()
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    mediaToggleRect.contains(x, y) -> {
                        val toggle = mediaEvent?.actions?.firstOrNull { it.id == MediaPresenter.ACTION_TOGGLE }
                        if (toggle != null) {
                            toggle.invoke()
                        } else {
                            // Try to open user's music app if nothing is active
                            launchOnCoverDisplay("com.sec.android.app.music") ||
                                launchOnCoverDisplay("com.spotify.music") ||
                                launchOnCoverDisplay("com.google.android.apps.youtube.music")
                        }
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    mediaNextRect.contains(x, y) -> {
                        mediaEvent?.actions?.firstOrNull { it.id == MediaPresenter.ACTION_NEXT }?.invoke()
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
            CoverPageType.APPS -> {
                for (item in appItems) {
                    if (item.rect.contains(x, y)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (launchOnCoverDisplay(item.packageName) && isOverlayMode) {
                            onMinimizeRequested?.invoke()
                        }
                        return
                    }
                }
            }
            CoverPageType.WEATHER -> {
                if (weatherRefreshRect.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    graph.weather.showNow()
                }
            }
            CoverPageType.CLOCK -> Unit
            CoverPageType.NOTIFICATIONS -> Unit
        }
    }

    private fun launchOnCoverDisplay(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            val opts = ActivityOptions.makeBasic()
            if (isOverlayMode && targetDisplayId != Display.DEFAULT_DISPLAY) {
                opts.launchDisplayId = targetDisplayId
            }
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            context.startActivity(intent, opts.toBundle())
            true
        } catch (_: Throwable) {
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        if (isMinimizedHandle) {
            drawMinimizedHandle(canvas, w, h)
            return
        }

        // 1. Dynamic Aurora OLED background
        drawCoverBackdrop(canvas, w, h)

        val topBarH = dp(34f)
        val bottomDotsH = dp(24f)
        val contentRect = RectF(0f, topBarH, w, (h - bottomDotsH).coerceAtLeast(topBarH + dp(60f)))

        // 2. Render horizontal scrolled pages
        val scroll = pageScrollSpring.value
        val activeIdx = scroll.roundToInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        canvas.save()
        canvas.clipRect(0f, topBarH, w, h - bottomDotsH)
        for (i in pages.indices) {
            val offsetX = (i - scroll) * w
            if (abs(offsetX) > w * 1.15f) continue
            canvas.save()
            canvas.translate(offsetX, 0f)
            drawPage(canvas, pages[i], contentRect, isFocused = (i == activeIdx))
            canvas.restore()
        }
        canvas.restore()

        // 3. Top cover status bar (Clock, Page Title, Battery, Minimize button)
        drawTopBar(canvas, w, topBarH, pages.getOrNull(activeIdx))

        // 4. Bottom page indicator dots
        drawPageDots(canvas, w, h - bottomDotsH / 2f, activeIdx)
    }

    private fun drawMinimizedHandle(canvas: Canvas, w: Float, h: Float) {
        val r = dp(18f)
        tmpRect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
        cardPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF00E5FF.toInt(), 0xFF651FFF.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(tmpRect, r, r, cardPaint)
        cardPaint.shader = null
        titlePaint.textSize = dp(18f)
        titlePaint.textAlign = Paint.Align.CENTER
        val base = tmpRect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f
        canvas.drawText("🐾", tmpRect.centerX(), base, titlePaint)
    }

    private fun drawCoverBackdrop(canvas: Canvas, w: Float, h: Float) {
        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF080A12.toInt(), 0xFF121629.toInt(), 0xFF0B0E18.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null
    }

    private fun drawTopBar(canvas: Canvas, w: Float, barH: Float, page: CoverPageType?) {
        val cy = barH / 2f + dp(2f)
        if (settings.coverShowStatusBar) {
            monoPaint.textSize = dp(13f)
            monoPaint.color = Color.WHITE
            monoPaint.textAlign = Paint.Align.LEFT
            val timeStr = timeFormat.format(Date())
            val baseline = cy - (monoPaint.descent() + monoPaint.ascent()) / 2f
            canvas.drawText(timeStr, dp(14f), baseline, monoPaint)

            val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            if (level != null) {
                monoPaint.textSize = dp(11.5f)
                monoPaint.color = if (level <= 20) 0xFFFF5252.toInt() else 0xFF69F0AE.toInt()
                monoPaint.textAlign = Paint.Align.RIGHT
                val rightBound = if (isOverlayMode) w - dp(44f) else w - dp(14f)
                canvas.drawText("$level%", rightBound, baseline, monoPaint)
            }
        }

        if (page != null) {
            val isVi = settings.language == AppLanguage.VIETNAMESE
            val title = if (isVi) page.labelVi else page.labelEn
            bodyPaint.textSize = dp(11.5f)
            bodyPaint.color = 0xAAFFFFFF.toInt()
            bodyPaint.textAlign = Paint.Align.CENTER
            val baseline = cy - (bodyPaint.descent() + bodyPaint.ascent()) / 2f
            canvas.drawText(title, w / 2f, baseline, bodyPaint)
        }

        if (isOverlayMode) {
            minimizePillRect.set(w - dp(38f), cy - dp(12f), w - dp(10f), cy + dp(12f))
            cardPaint.color = 0x2EFFFFFF
            canvas.drawRoundRect(minimizePillRect, dp(12f), dp(12f), cardPaint)
            titlePaint.textSize = dp(13f)
            titlePaint.textAlign = Paint.Align.CENTER
            val b = minimizePillRect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f
            canvas.drawText("—", minimizePillRect.centerX(), b, titlePaint)
        } else {
            minimizePillRect.setEmpty()
        }
    }

    private fun drawPageDots(canvas: Canvas, w: Float, cy: Float, activeIdx: Int) {
        pageDotRects.clear()
        val count = pages.size
        if (count <= 0) return
        val spacing = dp(14f)
        val startX = w / 2f - (count - 1) * spacing / 2f
        for (i in 0 until count) {
            val cx = startX + i * spacing
            val isSel = i == activeIdx
            val rx = if (isSel) dp(8f) else dp(3.2f)
            val ry = dp(3.2f)
            cardPaint.color = if (isSel) 0xFF00E5FF.toInt() else 0x4DFFFFFF
            canvas.drawRoundRect(cx - rx, cy - ry, cx + rx, cy + ry, ry, ry, cardPaint)
            pageDotRects.add(RectF(cx - dp(10f), cy - dp(12f), cx + dp(10f), cy + dp(12f)))
        }
    }

    private fun drawPage(canvas: Canvas, page: CoverPageType, r: RectF, isFocused: Boolean) {
        when (page) {
            CoverPageType.PET_3D -> petRenderer.draw(canvas, r, showBottomDock = true)
            CoverPageType.MEDIA -> drawMediaPage(canvas, r)
            CoverPageType.APPS -> drawAppsPage(canvas, r, isFocused)
            CoverPageType.WEATHER -> drawWeatherPage(canvas, r)
            CoverPageType.CLOCK -> drawClockPage(canvas, r)
            CoverPageType.NOTIFICATIONS -> drawNotificationsPage(canvas, r)
        }
    }

    private fun drawMediaPage(canvas: Canvas, r: RectF) {
        val pad = dp(16f)
        val mediaEvent = graph.events.current().live.firstOrNull { it.type == EventType.MEDIA }
        val payload = mediaEvent?.payload as? MediaPayload

        cardPaint.color = 0x1AFFFFFF
        tmpRect.set(r.left + pad, r.top + dp(6f), r.right - pad, r.bottom - dp(8f))
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), cardPaint)

        val artSize = min(dp(64f), tmpRect.height() * 0.34f)
        val artLeft = tmpRect.left + dp(16f)
        val artTop = tmpRect.top + dp(16f)
        val bmp = mediaEvent?.artwork?.let { graph.images.get(it) }
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        if (bmp != null) {
            canvas.save()
            val clip = android.graphics.Path().apply { addRoundRect(artRect, dp(14f), dp(14f), android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawBitmap(bmp, null, artRect, null)
            canvas.restore()
        } else {
            cardPaint.shader = LinearGradient(
                artRect.left, artRect.top, artRect.right, artRect.bottom,
                intArrayOf(0xFF7C4DFF.toInt(), 0xFF00E5FF.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(artRect, dp(14f), dp(14f), cardPaint)
            cardPaint.shader = null
            titlePaint.textSize = dp(24f)
            titlePaint.textAlign = Paint.Align.CENTER
            val b = artRect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f
            canvas.drawText("♫", artRect.centerX(), b, titlePaint)
        }

        // Song title & artist
        val tx = artRect.right + dp(14f)
        val availW = (tmpRect.right - dp(16f) - tx).coerceAtLeast(dp(40f))
        titlePaint.textSize = dp(16f)
        titlePaint.textAlign = Paint.Align.LEFT
        val titleStr = payload?.title?.ifBlank { null } ?: mediaEvent?.title ?: "Chưa có nhạc đang phát"
        val ellTitle = TextUtils.ellipsize(titleStr, titlePaint, availW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellTitle, tx, artRect.centerY() - dp(4f), titlePaint)

        bodyPaint.textSize = dp(13f)
        bodyPaint.color = 0xAAFFFFFF.toInt()
        bodyPaint.textAlign = Paint.Align.LEFT
        val artistStr = payload?.artist?.ifBlank { null } ?: "Nhấn ▶ để mở trình phát nhạc"
        val ellArtist = TextUtils.ellipsize(artistStr, bodyPaint, availW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(ellArtist, tx, artRect.centerY() + dp(16f), bodyPaint)

        // Progress bar
        val barTop = artRect.bottom + dp(18f)
        val barLeft = tmpRect.left + dp(16f)
        val barRight = tmpRect.right - dp(16f)
        val frac = if (payload != null && payload.durationMs > 0L) {
            (payload.positionAt(SystemClock.elapsedRealtime()).toFloat() / payload.durationMs).coerceIn(0f, 1f)
        } else {
            0.35f
        }
        cardPaint.color = 0x26FFFFFF
        canvas.drawRoundRect(barLeft, barTop, barRight, barTop + dp(4f), dp(2f), dp(2f), cardPaint)
        cardPaint.color = 0xFF00E5FF.toInt()
        canvas.drawRoundRect(barLeft, barTop, barLeft + (barRight - barLeft) * frac, barTop + dp(4f), dp(2f), dp(2f), cardPaint)

        // Playback buttons
        val btnCy = (barTop + dp(12f) + tmpRect.bottom) / 2f
        val rSmall = dp(20f)
        val rBig = dp(26f)
        val gap = dp(64f)
        mediaPrevRect.set(tmpRect.centerX() - gap - rSmall, btnCy - rSmall, tmpRect.centerX() - gap + rSmall, btnCy + rSmall)
        mediaToggleRect.set(tmpRect.centerX() - rBig, btnCy - rBig, tmpRect.centerX() + rBig, btnCy + rBig)
        mediaNextRect.set(tmpRect.centerX() + gap - rSmall, btnCy - rSmall, tmpRect.centerX() + gap + rSmall, btnCy + rSmall)

        cardPaint.color = 0x22FFFFFF
        canvas.drawCircle(mediaPrevRect.centerX(), mediaPrevRect.centerY(), rSmall, cardPaint)
        canvas.drawCircle(mediaNextRect.centerX(), mediaNextRect.centerY(), rSmall, cardPaint)

        cardPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(mediaToggleRect.centerX(), mediaToggleRect.centerY(), rBig, cardPaint)

        titlePaint.textSize = dp(16f)
        titlePaint.color = Color.WHITE
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⏮", mediaPrevRect.centerX(), mediaPrevRect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint)
        canvas.drawText("⏭", mediaNextRect.centerX(), mediaNextRect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint)

        titlePaint.color = Color.BLACK
        titlePaint.textSize = dp(18f)
        val playIcon = if (payload?.isPlaying == true) "❚❚" else "▶"
        canvas.drawText(playIcon, mediaToggleRect.centerX(), mediaToggleRect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint)
        titlePaint.color = Color.WHITE
    }

    private fun drawAppsPage(canvas: Canvas, r: RectF, isFocused: Boolean) {
        val pad = dp(14f)
        cardPaint.color = 0x16FFFFFF
        tmpRect.set(r.left + pad, r.top + dp(6f), r.right - pad, r.bottom - dp(8f))
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), cardPaint)

        val items = appItems
        if (items.isEmpty()) {
            bodyPaint.textSize = dp(14f)
            bodyPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Đang tải danh sách ứng dụng…", tmpRect.centerX(), tmpRect.centerY(), bodyPaint)
            return
        }

        val cols = if (items.size <= 4) 2 else 4
        val rows = ((items.size + cols - 1) / cols).coerceAtLeast(1)
        val cellW = (tmpRect.width() - dp(16f)) / cols
        val cellH = (tmpRect.height() - dp(16f)) / rows

        items.forEachIndexed { idx, app ->
            val col = idx % cols
            val row = idx / cols
            val cx = tmpRect.left + dp(8f) + col * cellW + cellW / 2f
            val cy = tmpRect.top + dp(8f) + row * cellH + cellH / 2f - dp(6f)
            val iconSize = min(dp(44f), min(cellW, cellH) * 0.62f)
            val iconRect = RectF(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f)
            if (isFocused) {
                app.rect.set(cx - cellW / 2f, cy - cellH / 2f, cx + cellW / 2f, cy + cellH / 2f)
            }

            if (app.icon != null) {
                canvas.drawBitmap(app.icon, null, iconRect, null)
            } else {
                cardPaint.color = 0x33FFFFFF
                canvas.drawCircle(cx, cy, iconSize / 2f, cardPaint)
            }

            bodyPaint.textSize = dp(10.5f)
            bodyPaint.color = 0xDDFFFFFF.toInt()
            bodyPaint.textAlign = Paint.Align.CENTER
            val label = TextUtils.ellipsize(app.label, bodyPaint, cellW - dp(6f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, cx, iconRect.bottom + dp(13f), bodyPaint)
        }
    }

    private fun drawWeatherPage(canvas: Canvas, r: RectF) {
        val pad = dp(14f)
        cardPaint.color = 0x1AFFFFFF
        tmpRect.set(r.left + pad, r.top + dp(6f), r.right - pad, r.bottom - dp(8f))
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), cardPaint)

        val w: WeatherPayload? = graph.weather.latestWeather.value
        val left = tmpRect.left + dp(16f)
        val top = tmpRect.top + dp(18f)

        val iconEmoji = when (w?.condition) {
            WeatherCondition.CLEAR_DAY -> "☀️"
            WeatherCondition.CLEAR_NIGHT -> "🌙"
            WeatherCondition.PARTLY_CLOUDY -> "⛅"
            WeatherCondition.CLOUDY -> "☁️"
            WeatherCondition.RAIN, WeatherCondition.HEAVY_RAIN -> "🌧️"
            WeatherCondition.THUNDERSTORM -> "⛈️"
            WeatherCondition.SNOW -> "❄️"
            null -> "⛅"
        }

        titlePaint.textSize = dp(32f)
        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(iconEmoji, left, top + dp(26f), titlePaint)

        monoPaint.textSize = dp(34f)
        monoPaint.color = Color.WHITE
        monoPaint.textAlign = Paint.Align.RIGHT
        val tempStr = "${w?.currentTempC ?: 31}°"
        canvas.drawText(tempStr, tmpRect.right - dp(16f), top + dp(28f), monoPaint)

        titlePaint.textSize = dp(16f)
        canvas.drawText(w?.cityName ?: settings.weatherCityName, left + dp(46f), top + dp(12f), titlePaint)
        bodyPaint.textSize = dp(12.5f)
        bodyPaint.color = 0xBBFFFFFF.toInt()
        bodyPaint.textAlign = Paint.Align.LEFT
        val condStr = w?.let { "${it.conditionLabel} · C:${it.highTempC}° T:${it.lowTempC}°" } ?: "Chạm để cập nhật dự báo"
        canvas.drawText(condStr, left + dp(46f), top + dp(30f), bodyPaint)

        // Metrics row
        val statY = top + dp(56f)
        val colW = (tmpRect.width() - dp(24f)) / 3f
        val stats = listOf(
            "Mưa" to "${w?.rainProbPercent ?: 25}%",
            "Độ ẩm" to "${w?.humidityPctOrFallback() ?: 72}%",
            "Gió" to "${w?.windKmh ?: 12}km/h",
        )
        stats.forEachIndexed { idx, (lbl, valStr) ->
            val sx = tmpRect.left + dp(12f) + idx * colW + colW / 2f
            bodyPaint.textSize = dp(10.5f)
            bodyPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(lbl, sx, statY, bodyPaint)
            monoPaint.textSize = dp(13f)
            monoPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(valStr, sx, statY + dp(16f), monoPaint)
        }

        // Refresh pill at bottom
        weatherRefreshRect.set(tmpRect.centerX() - dp(72f), tmpRect.bottom - dp(38f), tmpRect.centerX() + dp(72f), tmpRect.bottom - dp(12f))
        cardPaint.color = 0x2938BDF8
        canvas.drawRoundRect(weatherRefreshRect, dp(13f), dp(13f), cardPaint)
        bodyPaint.textSize = dp(11.5f)
        bodyPaint.color = 0xFF7DD3FC.toInt()
        bodyPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("↻ Cập nhật thời tiết", weatherRefreshRect.centerX(), weatherRefreshRect.centerY() - (bodyPaint.descent() + bodyPaint.ascent()) / 2f, bodyPaint)
    }

    private fun WeatherPayload.humidityPctOrFallback(): Int = humidityPercent

    private fun drawClockPage(canvas: Canvas, r: RectF) {
        val pad = dp(14f)
        cardPaint.color = 0x18FFFFFF
        tmpRect.set(r.left + pad, r.top + dp(6f), r.right - pad, r.bottom - dp(8f))
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), cardPaint)

        val now = Date()
        monoPaint.textSize = dp(42f)
        monoPaint.color = Color.WHITE
        monoPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(clockSecFormat.format(now), tmpRect.centerX(), tmpRect.top + dp(62f), monoPaint)

        bodyPaint.textSize = dp(14f)
        bodyPaint.color = 0xCCFFFFFF.toInt()
        bodyPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(dateFormat.format(now).replaceFirstChar { it.uppercase() }, tmpRect.centerX(), tmpRect.top + dp(86f), bodyPaint)

        // Running timer / stopwatch or next alarm
        val nowWall = System.currentTimeMillis()
        val liveEvents = graph.events.current().live
        val timer = liveEvents.firstOrNull { it.type == EventType.TIMER }?.payload as? TimerPayload
        val stopwatch = liveEvents.firstOrNull { it.type == EventType.STOPWATCH }?.payload as? StopwatchPayload
        val nextAlarm = alarmManager?.nextAlarmClock?.triggerTime?.takeIf { it > nowWall }

        val infoText = when {
            timer != null -> "⏳ Đếm ngược: ${Formatters.countdown(timer.remainingAt(nowWall))}"
            stopwatch != null -> "⏱️ Bấm giờ: ${Formatters.elapsed(stopwatch.elapsedAt(nowWall))}"
            nextAlarm != null -> "⏰ Báo thức tiếp theo: ${timeFormat.format(Date(nextAlarm))}"
            else -> "⏰ Không có báo thức sắp tới"
        }

        cardPaint.color = 0x22FFFFFF
        val pill = RectF(tmpRect.left + dp(20f), tmpRect.bottom - dp(48f), tmpRect.right - dp(20f), tmpRect.bottom - dp(14f))
        canvas.drawRoundRect(pill, dp(17f), dp(17f), cardPaint)
        titlePaint.textSize = dp(13f)
        titlePaint.color = 0xFFFFB74D.toInt()
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(infoText, pill.centerX(), pill.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f, titlePaint)
        titlePaint.color = Color.WHITE
    }

    private fun drawNotificationsPage(canvas: Canvas, r: RectF) {
        val pad = dp(14f)
        cardPaint.color = 0x18FFFFFF
        tmpRect.set(r.left + pad, r.top + dp(6f), r.right - pad, r.bottom - dp(8f))
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), cardPaint)

        val sched = graph.events.current()
        val notifs = buildList {
            sched.toast?.takeIf { it.type == EventType.NOTIFICATION }?.let { add(it) }
            sched.queued.filter { it.type == EventType.NOTIFICATION }.forEach { add(it) }
        }.take(3)

        if (notifs.isEmpty()) {
            bodyPaint.textSize = dp(14f)
            bodyPaint.color = 0x99FFFFFF.toInt()
            bodyPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Không có thông báo mới", tmpRect.centerX(), tmpRect.centerY(), bodyPaint)
            return
        }

        var y = tmpRect.top + dp(18f)
        notifs.forEach { ev ->
            val np = ev.payload as? NotificationPayload
            val rowRect = RectF(tmpRect.left + dp(12f), y, tmpRect.right - dp(12f), y + dp(46f))
            cardPaint.color = 0x1EFFFFFF
            canvas.drawRoundRect(rowRect, dp(14f), dp(14f), cardPaint)

            titlePaint.textSize = dp(13f)
            titlePaint.color = Color.WHITE
            titlePaint.textAlign = Paint.Align.LEFT
            val head = np?.title?.ifBlank { np.appLabel } ?: ev.title
            val ellHead = TextUtils.ellipsize(head, titlePaint, rowRect.width() - dp(24f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellHead, rowRect.left + dp(12f), rowRect.top + dp(19f), titlePaint)

            bodyPaint.textSize = dp(11.5f)
            bodyPaint.color = 0xAAFFFFFF.toInt()
            bodyPaint.textAlign = Paint.Align.LEFT
            val sub = np?.text?.ifBlank { ev.subtitle } ?: ev.subtitle
            val ellSub = TextUtils.ellipsize(sub, bodyPaint, rowRect.width() - dp(24f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellSub, rowRect.left + dp(12f), rowRect.top + dp(36f), bodyPaint)

            y += dp(54f)
        }
    }
}
