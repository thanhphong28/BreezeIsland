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
import com.arnav.island.settings.MainActivity
import com.arnav.island.storage.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the active Samsung DeX session, the external-display fullscreen
 * renderer ([DexExternalDisplayHost] for HDMI / USB-C monitors, TVs, projectors & Smart View),
 * and the persistent Stop/Open notification.
 */
class DexService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var externalHost: DexExternalDisplayHost? = null
    private var observerJobs = ArrayList<Job>()

    private val isVi: Boolean
        get() = (application as? IslandApp)?.graph?.settings?.state?.value?.language != AppLanguage.ENGLISH

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                externalHost?.stopWatching()
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
                    val host = externalHost ?: DexExternalDisplayHost(this).also { externalHost = it }
                    val autoAttachExternal = session.outputMode == DexOutputMode.AUTO_EXTERNAL_OR_PC ||
                        session.outputMode == DexOutputMode.EXTERNAL_DISPLAY
                    host.startWatching(session, autoAttach = autoAttachExternal)

                    observerJobs.forEach { it.cancel() }
                    observerJobs.clear()

                    observerJobs += serviceScope.launch {
                        host.attachedDisplay.collectLatest { extInfo ->
                            dexCtrl?.onExternalDisplayChanged(extInfo)
                            refreshNotification(session)
                        }
                    }

                    observerJobs += serviceScope.launch {
                        session.state.collectLatest { state ->
                            dexCtrl?.onSessionStateChanged(state)
                            when (state) {
                                is DexSession.State.Stopped -> {
                                    host.stopWatching()
                                    shutDown()
                                }
                                is DexSession.State.Running -> {
                                    host.updateVideoSize(state.videoWidth, state.videoHeight)
                                    session.webServer?.let { ws ->
                                        dexCtrl?.onWebServerChanged(ws.url.value, ws.port, ws.clientCount.value)
                                        observerJobs += serviceScope.launch {
                                            ws.clientCount.collectLatest { count ->
                                                dexCtrl?.onWebServerChanged(ws.url.value, ws.port, count)
                                                refreshNotification(session)
                                            }
                                        }
                                    }
                                    refreshNotification(session)
                                }
                                is DexSession.State.Starting -> {
                                    getSystemService(NotificationManager::class.java)
                                        .notify(NOTIFICATION_ID, buildNotification(startingMessage = state.message))
                                }
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun refreshNotification(session: DexSession) {
        val extName = externalHost?.attachedDisplay?.value?.label
        val pcUrl = session.webServer?.url?.value
        val pcCount = session.webServer?.clientCount?.value ?: 0
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(
                displayId = session.displayId,
                externalLabel = extName,
                pcUrl = pcUrl,
                pcClients = pcCount,
                outputMode = session.outputMode,
            ),
        )
    }

    private fun startAsForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun shutDown() {
        observerJobs.forEach { it.cancel() }
        observerJobs.clear()
        externalHost?.stopWatching()
        externalHost = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        observerJobs.forEach { it.cancel() }
        externalHost?.stopWatching()
        externalHost = null
        DexSession.current?.stop()
        (application as? IslandApp)?.graph?.localDex?.onSessionStopped(error = null)
        serviceScope.cancel()
    }

    private fun buildNotification(
        displayId: Int = -1,
        startingMessage: String? = null,
        externalLabel: String? = null,
        pcUrl: String? = null,
        pcClients: Int = 0,
        outputMode: DexOutputMode = DexOutputMode.AUTO_EXTERNAL_OR_PC,
    ): Notification {
        val vi = isVi
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val primaryIntent = when {
            externalLabel != null -> Intent(this, DexTouchpadActivity::class.java)
            outputMode == DexOutputMode.PHONE_SCREEN -> Intent(this, DexViewerActivity::class.java)
            else -> Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, "local_dex")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val openPending = PendingIntent.getActivity(
            this,
            2,
            primaryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val touchpadPending = PendingIntent.getActivity(
            this,
            3,
            Intent(this, DexTouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            externalLabel != null && vi -> "Samsung DeX → $externalLabel"
            externalLabel != null -> "Samsung DeX → $externalLabel"
            pcClients > 0 && vi -> "Samsung DeX đang chiếu trên PC ($pcClients)"
            pcClients > 0 -> "Samsung DeX streaming to PC ($pcClients)"
            displayId >= 0 && vi -> "Samsung DeX sẵn sàng (Màn hình #$displayId)"
            displayId >= 0 -> "Samsung DeX ready (Display #$displayId)"
            vi -> "Samsung DeX đang khởi động"
            else -> "Samsung DeX is starting"
        }

        val text = startingMessage ?: when {
            externalLabel != null && vi -> "Chạm để mở Bàn rê chuột DeX · Hoặc tiếp tục dùng điện thoại bình thường"
            externalLabel != null -> "Tap to open DeX Touchpad · Or keep using your phone normally"
            pcUrl != null && vi -> "Mở trên trình duyệt PC: $pcUrl · Hoặc cắm cáp HDMI ra màn hình ngoài"
            pcUrl != null -> "Open on PC browser: $pcUrl · Or plug in an HDMI monitor"
            vi -> "Chạm để quản lý Samsung DeX · Bấm Tắt DeX để đóng"
            else -> "Tap to manage Samsung DeX · Tap Stop DeX to end"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (vi) "Tắt DeX" else "Stop DeX", stopIntent)

        if (externalLabel == null && outputMode != DexOutputMode.PHONE_SCREEN) {
            builder.addAction(0, if (vi) "Bàn rê chuột" else "Touchpad", touchpadPending)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val vi = isVi
        val channel = NotificationChannel(
            CHANNEL_ID,
            if (vi) "Phiên Samsung DeX" else "Samsung DeX Session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = if (vi) {
                "Hiển thị khi Samsung DeX đang hoạt động cho màn hình ngoài hoặc PC"
            } else {
                "Shown while Samsung DeX is active for an external monitor or PC"
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

        fun start(
            context: Context,
            displaySpec: String,
            transport: DexTransport,
            outputMode: DexOutputMode,
        ) {
            if (DexSession.current == null) {
                val session = DexSession(
                    context = context.applicationContext,
                    displaySpec = DexDisplaySpec.normalize(displaySpec),
                    transport = transport,
                    outputMode = outputMode,
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
