package com.arnav.island.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.arnav.island.R
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventType
import com.arnav.island.events.MediaPayload
import com.arnav.island.events.media.MediaSessionMonitor
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.SettingsRepository
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.ImageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps every placed BreezeMusic 4x4 widget (Z Flip 5 Cover Screen & Home Screen) updated live
 * with the active/linked player, artwork, synced lyrics, and playback progress. Costs zero
 * wakeups when no widget is placed or when playback is paused/idle.
 */
class BreezeMusicWidgetUpdater(
    private val app: Application,
    private val events: EventEngine,
    private val settings: SettingsRepository,
    private val media: MediaSessionMonitor,
    private val images: ImageStore,
    private val apps: AppInfoCache,
    private val scope: CoroutineScope,
) {
    private val widgetManager: AppWidgetManager? by lazy { AppWidgetManager.getInstance(app) }
    private val providerComponent by lazy { ComponentName(app, BreezeMusicWidgetProvider::class.java) }
    private val coverComponent by lazy { ComponentName(app, BreezeMusicCoverWidgetProvider::class.java) }

    private var tickerJob: Job? = null
    private var renderJob: Job? = null
    private var lastLinkedPkg: String = settings.state.value.musicWidgetLinkedPkg
    private var ensuredMediaStarted = false

    init {
        scope.launch {
            settings.flow
                .map { it.musicWidgetLinkedPkg }
                .distinctUntilChanged()
                .collect { linked ->
                    if (linked != lastLinkedPkg) {
                        lastLinkedPkg = linked
                        media.refresh()
                    }
                }
        }

        scope.launch {
            combine(
                events.schedule.map { sched -> sched.live.firstOrNull { it.type == EventType.MEDIA } },
                settings.flow.map {
                    listOf(
                        it.musicWidgetLinkedPkg,
                        it.musicWidgetShowLyrics,
                        it.musicWidgetFullLyrics,
                        it.musicWidgetTransparentBg,
                    )
                }.distinctUntilChanged(),
            ) { mediaEvent, prefs ->
                mediaEvent to prefs
            }.collect { (mediaEvent, _) ->
                val ids = widgetIds()
                if (ids.isEmpty()) {
                    stopTicker()
                    return@collect
                }
                if (!ensuredMediaStarted) {
                    ensuredMediaStarted = true
                    media.refresh()
                }
                val payload = mediaEvent?.payload as? MediaPayload
                if (payload?.isPlaying == true) startTicker() else stopTicker()
                schedulePush(ids)
            }
        }
    }

    fun pushNow(explicitIds: IntArray? = null) {
        val ids = explicitIds?.takeIf { it.isNotEmpty() } ?: widgetIds()
        if (ids.isEmpty()) return
        if (!ensuredMediaStarted) {
            ensuredMediaStarted = true
            media.refresh()
        }
        val payload = currentMediaPayload()
        if (payload?.isPlaying == true) startTicker() else stopTicker()
        schedulePush(ids)
    }

    fun buildRenderStateSnapshot(settingsSnapshot: IslandSettings): MusicWidgetRenderState {
        val mediaEvent = events.current().live.firstOrNull { it.type == EventType.MEDIA }
        val payload = mediaEvent?.payload as? MediaPayload
        val artwork = images.get(mediaEvent?.artwork)
        val appIcon = images.get(mediaEvent?.iconImage)
            ?: payload?.packageName?.let { apps.cachedIcon(it, 96) ?: apps.iconBlocking(it, 96) }

        val linkedPkg = settingsSnapshot.musicWidgetLinkedPkg.trim()
        val linkedLabel = if (linkedPkg.isNotEmpty()) apps.label(linkedPkg) else ""
        val linkedIcon = if (linkedPkg.isNotEmpty()) {
            apps.cachedIcon(linkedPkg, 128) ?: apps.iconBlocking(linkedPkg, 128)
        } else null

        val posMs = payload?.positionAt(SystemClock.elapsedRealtime()) ?: 0L
        val lyricWindow = if (payload != null) {
            LyricsRepository.resolveLines(
                title = payload.title,
                artist = payload.artist,
                album = payload.album,
                durationMs = payload.durationMs,
                positionMs = posMs,
                liveLineFallback = payload.liveLine,
                scope = scope,
                onLoaded = { schedulePush(widgetIds()) },
            )
        } else {
            LyricWindow()
        }

        return MusicWidgetRenderState(
            media = payload,
            artwork = artwork,
            appIcon = appIcon,
            linkedPackage = linkedPkg,
            linkedAppLabel = linkedLabel,
            linkedAppIcon = linkedIcon,
            showLyrics = true,
            fullLyrics = settingsSnapshot.musicWidgetFullLyrics,
            transparentBg = settingsSnapshot.musicWidgetTransparentBg,
            lyrics = lyricWindow,
            positionMs = posMs,
        )
    }

    private fun currentMediaPayload(): MediaPayload? =
        events.current().live.firstOrNull { it.type == EventType.MEDIA }?.payload as? MediaPayload

    fun activeWidgetCount(): Int = widgetIds().size

    private fun widgetIds(): IntArray {
        val mgr = widgetManager ?: return IntArray(0)
        val main = runCatching { mgr.getAppWidgetIds(providerComponent) }.getOrNull() ?: IntArray(0)
        val cover = runCatching { mgr.getAppWidgetIds(coverComponent) }.getOrNull() ?: IntArray(0)
        return main + cover
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_400L)
                val ids = widgetIds()
                if (ids.isEmpty() || currentMediaPayload()?.isPlaying != true) {
                    tickerJob = null
                    break
                }
                schedulePush(ids)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun schedulePush(ids: IntArray) {
        if (ids.isEmpty()) return
        val mgr = widgetManager ?: return
        renderJob?.cancel()
        renderJob = scope.launch {
            val s = settings.current()
            val bmp = withContext(Dispatchers.Default) {
                val state = buildRenderStateSnapshot(s)
                BreezeMusicWidgetRenderer.renderForRemoteViews(state)
            }
            val views = buildRemoteViews(app, bmp)
            for (id in ids) {
                runCatching { mgr.updateAppWidget(id, views) }
            }
        }
    }

    companion object {
        fun buildRemoteViews(context: Context, renderedCard: android.graphics.Bitmap): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_breeze_music)
            rv.setImageViewBitmap(R.id.widget_canvas_image, renderedCard)
            rv.setOnClickPendingIntent(R.id.btn_open_app, actionPendingIntent(context, BreezeMusicWidgetProvider.ACTION_OPEN_APP, 101))
            rv.setOnClickPendingIntent(R.id.btn_toggle_lyrics, actionPendingIntent(context, BreezeMusicWidgetProvider.ACTION_TOGGLE_LYRICS, 102))
            rv.setOnClickPendingIntent(R.id.btn_lyrics_area, actionPendingIntent(context, BreezeMusicWidgetProvider.ACTION_LYRICS_AREA, 103))
            rv.setOnClickPendingIntent(R.id.btn_prev, actionPendingIntent(context, BreezeMusicWidgetProvider.ACTION_PREV, 104))
            rv.setOnClickPendingIntent(R.id.btn_play_pause, actionPendingIntent(context, BreezeMusicWidgetProvider.ACTION_PLAY_PAUSE, 105))
            rv.setOnClickPendingIntent(R.id.btn_next, actionPendingIntent(context, BreezeMusicWidgetProvider.ACTION_NEXT, 106))
            return rv
        }

        private fun actionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, BreezeMusicWidgetProvider::class.java).apply { this.action = action }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
