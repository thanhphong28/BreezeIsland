package com.arnav.island.dex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arnav.island.IslandApp
import com.arnav.island.R
import com.arnav.island.storage.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the active on-device Samsung DeX session.
 * Uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on Android 14/15+ and provides a guaranteed
 * persistent notification with 1-tap Stop DeX even if the viewer activity is backgrounded.
 */
class DexService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val isVi: Boolean
        get() = (application as? IslandApp)?.graph?.settings?.state?.value?.language != AppLanguage.ENGLISH

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DexSession.current?.stop()
                (application as? IslandApp)?.graph?.localDex?.onSessionStopped(error = null)
                shutDown()
                return START_NOT_STICKY
            }
            else -> {
                createNotificationChannel()
                startAsForeground(buildNotification())

                val session = DexSession.current
                if (session == null) {
                    shutDown()
                } else {
                    val dexCtrl = (application as? IslandApp)?.graph?.localDex
                    serviceScope.launch {
                        session.state.collectLatest { state ->
                            dexCtrl?.onSessionStateChanged(state)
                            when (state) {
                                is DexSession.State.Stopped -> shutDown()
                                is DexSession.State.Running -> {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, buildNotification(state.displayId))
                                }
                                is DexSession.State.Starting -> {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, buildNotification(-1, state.message))
                                }
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        DexSession.current?.stop()
        (application as? IslandApp)?.graph?.localDex?.onSessionStopped(error = null)
        serviceScope.cancel()
    }

    private fun buildNotification(displayId: Int = -1, startingMessage: String? = null): Notification {
        val vi = isVi
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, DexViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            displayId >= 0 && vi -> "Samsung DeX đang chạy (Màn hình #$displayId)"
            displayId >= 0 -> "Samsung DeX is running (display $displayId)"
            vi -> "Samsung DeX đang khởi động"
            else -> "Samsung DeX is starting"
        }
        val text = startingMessage ?: if (vi) {
            "Chạm để mở màn hình DeX · Bấm Tắt DeX để đóng hoàn toàn"
        } else {
            "Tap to open DeX viewer · Tap Stop DeX to end session"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (vi) "Tắt DeX" else "Stop DeX", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val vi = isVi
        val channel = NotificationChannel(
            CHANNEL_ID,
            if (vi) "Phiên Samsung DeX" else "Samsung DeX Session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = if (vi) {
                "Hiển thị khi Samsung DeX nội bộ đang hoạt động"
            } else {
                "Shown while an on-device Samsung DeX session is running"
            }
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "breeze.dex.session"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.arnav.island.dex.STOP"

        fun start(context: Context, displaySpec: String, transport: DexTransport) {
            if (DexSession.current == null) {
                val session = DexSession(
                    context = context.applicationContext,
                    displaySpec = DexDisplaySpec.normalize(displaySpec),
                    transport = transport,
                )
                DexSession.current = session
                session.start()
            }
            ContextCompat.startForegroundService(context, Intent(context, DexService::class.java))
        }

        fun stop(context: Context) {
            DexSession.current?.stop()
            runCatching {
                context.startService(Intent(context, DexService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
