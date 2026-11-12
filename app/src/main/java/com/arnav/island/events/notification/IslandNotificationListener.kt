package com.arnav.island.events.notification

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.arnav.island.IslandApp
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.util.Diagnostics

/**
 * Notification access entry point. All logic lives in [NotificationProcessor]; this service only
 * forwards callbacks (on the main thread) and keeps both the listener binding and the overlay
 * service healthy across screen off/on, fold/unfold, and One UI deep-sleep cycles.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val graph get() = (application as IslandApp).graph
    private var wakeReceiverRegistered = false

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ensureOverlayRunning()
            graph.media.refresh()
            graph.notifications.syncStatusBarNotifications()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Explicitly clear all listener hints so all notification sounds, vibrations,
        // and call ringtones always play normally on the device without any delay or silencing.
        runCatching {
            requestListenerHints(0)
        }
        registerWakeReceiver()
        ensureOverlayRunning()
        graph.notifications.attach(this)
        graph.notifications.syncStatusBarNotifications()
        graph.media.refresh()
        try {
            activeNotifications?.forEach { graph.notifications.onPosted(it, currentRanking, initialScan = true) }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "Could not read active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        unregisterWakeReceiver()
        graph.notifications.detach()
        super.onListenerDisconnected()
        // Ask the system to rebind us (e.g. after an app update).
        requestRebind(ComponentName(this, IslandNotificationListener::class.java))
    }

    override fun onDestroy() {
        unregisterWakeReceiver()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        ensureOverlayRunning()
        graph.notifications.onPosted(sbn, rankingMap, initialScan = false)
        if (sbn.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true) {
            graph.media.refresh()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        graph.notifications.onRemoved(sbn, reason)
    }

    private fun ensureOverlayRunning() {
        val s = graph.settings.state.value
        if (s.enabled && Settings.canDrawOverlays(this)) {
            IslandOverlayService.start(this)
        }
    }

    private fun registerWakeReceiver() {
        if (wakeReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        wakeReceiverRegistered = true
    }

    private fun unregisterWakeReceiver() {
        if (!wakeReceiverRegistered) return
        runCatching { unregisterReceiver(wakeReceiver) }
        wakeReceiverRegistered = false
    }

    companion object {
        private const val TAG = "IslandListener"
    }
}
