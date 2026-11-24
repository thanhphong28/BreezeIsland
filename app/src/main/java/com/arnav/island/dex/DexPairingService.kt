package com.arnav.island.dex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import com.arnav.island.IslandApp
import com.arnav.island.R
import com.arnav.island.settings.MainActivity
import com.arnav.island.storage.AppLanguage
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service driving the wireless debugging pairing notification (inline 6-digit code
 * reply + mDNS `_adb-tls-pairing._tcp` port discovery), updated for Android 14/15+ typed FGS
 * (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) and full VI/EN bilingual support.
 */
class DexPairingService : Service() {

    private enum class State { WAITING_FOR_PORT, READY, PAIRING, FAILED }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adbMdns: DexAdbMdns? = null
    private var idleTimeoutJob: Job? = null
    private var pairingJob: Job? = null

    private var state = State.WAITING_FOR_PORT
    private var pairingPort = 0
    private var failureMessage: String? = null

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private val isVi: Boolean
        get() = (application as? IslandApp)?.graph?.settings?.state?.value?.language != AppLanguage.ENGLISH

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        startDiscovery()

        if (intent?.action == ACTION_SUBMIT_CODE) {
            submitCode(
                code = intent.getStringExtra(EXTRA_CODE).orEmpty(),
                port = intent.getIntExtra(EXTRA_PORT, 0),
            )
        } else {
            startIdleTimeout()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun startDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (adbMdns != null) return
        adbMdns = DexAdbMdns(this, DexAdbMdns.TLS_PAIRING) { port ->
            serviceScope.launch {
                if (port <= 0 || state == State.PAIRING) return@launch
                pairingPort = port
                state = State.READY
                failureMessage = null
                updateNotification()
                startIdleTimeout()
            }
        }.apply { start() }
    }

    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            stopSelf()
        }
    }

    private fun submitCode(code: String, port: Int) {
        if (pairingJob?.isActive == true) return
        idleTimeoutJob?.cancel()
        state = State.PAIRING
        failureMessage = null
        updateNotification()

        pairingJob = serviceScope.launch {
            val effectivePort = if (port > 0) port else pairingPort
            DexAdb.pair(this@DexPairingService, code, effectivePort)
                .onSuccess {
                    (application as? IslandApp)?.graph?.settings?.set {
                        it.copy(dexHasPairedBefore = true)
                    }
                    DexAdb.invalidateStatusCache()
                    showResultNotification(
                        title = if (isVi) "Ghép nối DeX thành công" else "DeX pairing successful",
                        text = if (isVi) "Chạm để quay lại BreezeIsland" else "Tap to return to BreezeIsland",
                    )
                    Toast.makeText(
                        this@DexPairingService,
                        if (isVi) "Đã ghép nối Wireless ADB thành công!" else "Wireless ADB paired successfully!",
                        Toast.LENGTH_LONG,
                    ).show()
                    returnToApp()
                    stopSelf()
                }
                .onFailure { error ->
                    val message = error.message ?: if (isVi) "Ghép nối thất bại." else "Pairing failed."
                    Diagnostics.w(TAG, "Pairing failed: $message", error)
                    state = State.FAILED
                    failureMessage = message
                    updateNotification()
                    Toast.makeText(this@DexPairingService, message, Toast.LENGTH_LONG).show()
                    startIdleTimeout()
                }
        }
    }

    private fun returnToApp() {
        runCatching { startActivity(mainActivityIntent()) }
    }

    private fun mainActivityIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_DESTINATION, "local_dex")
        }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val vi = isVi
        val title = when (state) {
            State.WAITING_FOR_PORT -> if (vi) "Đang chờ hộp thoại mã ghép nối…" else "Waiting for pairing dialog…"
            State.READY -> if (vi) "Nhập mã ghép nối 6 chữ số" else "Enter 6-digit pairing code"
            State.PAIRING -> if (vi) "Đang ghép nối với máy…" else "Pairing with device…"
            State.FAILED -> if (vi) "Ghép nối thất bại" else "Pairing failed"
        }
        val summary = when (state) {
            State.WAITING_FOR_PORT -> if (vi) {
                "Vào Gỡ lỗi không dây và bấm \"Ghép nối thiết bị bằng mã ghép nối\"."
            } else {
                "Open Wireless debugging and tap \"Pair device with pairing code\"."
            }
            State.READY -> if (vi) {
                "Bấm Trả lời bên dưới và nhập mã 6 chữ số."
            } else {
                "Tap Reply below and enter the 6-digit code."
            }
            State.PAIRING -> if (vi) "Đang xác thực kết nối nội bộ…" else "Authenticating local connection…"
            State.FAILED -> failureMessage ?: if (vi) "Đã xảy ra lỗi." else "Something went wrong."
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setOngoing(state == State.PAIRING)
            .addAction(
                0,
                if (vi) "Hủy" else "Cancel",
                cancelPendingIntent(),
            )

        if (state == State.READY || state == State.FAILED) {
            val remoteInput = RemoteInput.Builder(KEY_PAIRING_INPUT)
                .setLabel(if (vi) "Mã 6 chữ số" else "6-digit code")
                .build()

            val replyIntent = Intent(this, DexPairingReceiver::class.java).apply {
                action = ACTION_PAIRING_INPUT
                putExtra(EXTRA_PORT, pairingPort)
            }
            val replyPending = PendingIntent.getBroadcast(
                this,
                REQUEST_REPLY,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val actionLabel = when {
                state == State.FAILED && vi -> "Thử lại"
                state == State.FAILED -> "Retry"
                vi -> "Nhập mã"
                else -> "Reply"
            }
            builder.addAction(
                NotificationCompat.Action.Builder(0, actionLabel, replyPending)
                    .addRemoteInput(remoteInput)
                    .build()
            )
        }

        return builder.build()
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        mainActivityIntent(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        REQUEST_CANCEL,
        Intent(this, DexPairingReceiver::class.java).setAction(ACTION_PAIRING_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showResultNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            if (isVi) "Ghép nối Samsung DeX" else "Samsung DeX Pairing",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = if (isVi) {
                "Nhập mã ghép nối Gỡ lỗi không dây cho Samsung DeX"
            } else {
                "Enter the wireless debugging pairing code for Samsung DeX"
            }
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        adbMdns?.stop()
        adbMdns = null
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "DexPairingService"
        private const val REQUEST_REPLY = 41
        private const val REQUEST_CANCEL = 42
        private const val REQUEST_OPEN_APP = 43
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        const val CHANNEL_ID = "breeze.dex.pairing"
        const val NOTIFICATION_ID = 4101
        const val RESULT_NOTIFICATION_ID = 4102
        const val KEY_PAIRING_INPUT = "breeze_dex_pairing_input"
        const val ACTION_PAIRING_INPUT = "com.arnav.island.dex.PAIRING_INPUT"
        const val ACTION_PAIRING_CANCEL = "com.arnav.island.dex.PAIRING_CANCEL"
        const val ACTION_SUBMIT_CODE = "com.arnav.island.dex.SUBMIT_PAIRING_CODE"
        const val EXTRA_CODE = "pairing_code"
        const val EXTRA_PORT = "pairing_port"
    }
}
