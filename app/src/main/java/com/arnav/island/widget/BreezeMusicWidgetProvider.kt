package com.arnav.island.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.arnav.island.IslandApp
import com.arnav.island.events.EventType
import kotlinx.coroutines.launch

/**
 * Native 4x4 AppWidget for Galaxy Z Flip 5 Cover Screen (Flex Window) & Home Screen.
 * Supports playback transport, toggling live synced lyrics, and launching/resuming the
 * user's permanently linked music app.
 */
open class BreezeMusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val graph = (context.applicationContext as? IslandApp)?.graph ?: return
        graph.widgetUpdater.pushNow(appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        val graph = (context.applicationContext as? IslandApp)?.graph ?: return
        graph.widgetUpdater.pushNow()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val graph = (context.applicationContext as? IslandApp)?.graph ?: return
        val linkedPkg = graph.settings.state.value.musicWidgetLinkedPkg

        when (action) {
            ACTION_PLAY_PAUSE -> {
                graph.media.togglePlayPause(linkedPkg)
                graph.widgetUpdater.pushNow()
            }
            ACTION_PREV -> {
                graph.media.skipPrevious(linkedPkg)
                graph.widgetUpdater.pushNow()
            }
            ACTION_NEXT -> {
                graph.media.skipNext(linkedPkg)
                graph.widgetUpdater.pushNow()
            }
            ACTION_OPEN_APP -> {
                graph.media.openActiveOrLinkedApp(linkedPkg)
            }
            ACTION_TOGGLE_LYRICS -> {
                graph.scope.launch {
                    graph.settings.set { s ->
                        when {
                            s.musicWidgetShowLyrics && !s.musicWidgetFullLyrics ->
                                s.copy(musicWidgetFullLyrics = true)
                            s.musicWidgetShowLyrics && s.musicWidgetFullLyrics ->
                                s.copy(musicWidgetShowLyrics = false, musicWidgetFullLyrics = false)
                            else ->
                                s.copy(musicWidgetShowLyrics = true, musicWidgetFullLyrics = false)
                        }
                    }
                    graph.widgetUpdater.pushNow()
                }
            }
            ACTION_LYRICS_AREA -> {
                val hasMedia = graph.events.current().live.any { it.type == EventType.MEDIA }
                val s = graph.settings.state.value
                if (hasMedia && s.musicWidgetShowLyrics) {
                    graph.scope.launch {
                        graph.settings.set { cur -> cur.copy(musicWidgetFullLyrics = !cur.musicWidgetFullLyrics) }
                        graph.widgetUpdater.pushNow()
                    }
                } else {
                    graph.media.openActiveOrLinkedApp(linkedPkg)
                }
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.arnav.island.widget.MUSIC_PLAY_PAUSE"
        const val ACTION_PREV = "com.arnav.island.widget.MUSIC_PREV"
        const val ACTION_NEXT = "com.arnav.island.widget.MUSIC_NEXT"
        const val ACTION_TOGGLE_LYRICS = "com.arnav.island.widget.MUSIC_TOGGLE_LYRICS"
        const val ACTION_LYRICS_AREA = "com.arnav.island.widget.MUSIC_LYRICS_AREA"
        const val ACTION_OPEN_APP = "com.arnav.island.widget.MUSIC_OPEN_APP"
    }
}

/**
 * Dedicated Flex Window (sub_screen) provider using Samsung's exact 352x339dp + keyguard spec so
 * Z Flip Cover Screen widget pickers always list BreezeMusic 4x4 reliably.
 */
class BreezeMusicCoverWidgetProvider : BreezeMusicWidgetProvider()
