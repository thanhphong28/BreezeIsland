package com.arnav.island.dex

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class LocalDexState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val statusMessage: String = "",
    val displayId: Int = -1,
    val activeTransport: DexTransport? = null,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuPermitted: Boolean = false,
    val adbConnected: Boolean = false,
    val checkingConnection: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val wirelessDebugEnabled: Boolean = false,
    val canAutoToggleWirelessDebug: Boolean = false,
    val lastError: String? = null,
) {
    fun bestReadyTransport(mode: DexConnectionMode): DexTransport? = when (mode) {
        DexConnectionMode.AUTO -> when {
            shizukuPermitted -> DexTransport.SHIZUKU
            adbConnected -> DexTransport.WIRELESS_ADB
            else -> null
        }
        DexConnectionMode.SHIZUKU -> if (shizukuPermitted) DexTransport.SHIZUKU else null
        DexConnectionMode.WIRELESS_ADB -> if (adbConnected) DexTransport.WIRELESS_ADB else null
    }
}

/**
 * Coordinates the on-device Samsung DeX (LocalDeX) lifecycle inside BreezeIsland:
 *  - Detects both Shizuku (zero-Wi-Fi 1-tap bridge) and Wireless ADB (full sam1am/localdex engine)
 *  - Auto-enables Wireless Debugging via WRITE_SECURE_SETTINGS when previously paired
 *  - Lets the user toggle Samsung DeX ON/OFF from HomeScreen, LocalDexScreen, or Quick Settings
 *  - Posts optional Dynamic Island status pills when DeX starts or stops
 */
class DexController(
    private val app: Application,
    private val events: EventEngine,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(LocalDexState())
    val state: StateFlow<LocalDexState> = _state.asStateFlow()

    private var permissionGrantAttempted = false
    private var notifiedStartForSession: DexSession? = null

    private val isVi: Boolean
        get() = settings.state.value.language != AppLanguage.ENGLISH

    fun refreshStatus(forceAdbCheck: Boolean = false) {
        val currentSession = DexSession.current
        val shizukuInstalled = OnPhoneGranter.isShizukuInstalled(app)
        val shizukuRunning = OnPhoneGranter.isShizukuRunning()
        val shizukuPermitted = OnPhoneGranter.canUseShizuku()
        val devEnabled = DexWirelessDebugging.isDeveloperOptionsEnabled(app)
        val notifEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled()
        val wirelessOn = DexWirelessDebugging.isEnabled(app)
        val canToggle = DexWirelessDebugging.canToggle(app)

        _state.update {
            it.copy(
                running = currentSession != null && currentSession.state.value is DexSession.State.Running,
                starting = currentSession != null && currentSession.state.value is DexSession.State.Starting,
                displayId = currentSession?.displayId ?: -1,
                activeTransport = currentSession?.transport,
                shizukuInstalled = shizukuInstalled,
                shizukuRunning = shizukuRunning,
                shizukuPermitted = shizukuPermitted,
                developerOptionsEnabled = devEnabled,
                notificationsEnabled = notifEnabled,
                wirelessDebugEnabled = wirelessOn,
                canAutoToggleWirelessDebug = canToggle,
                checkingConnection = currentSession == null,
            )
        }

        if (currentSession != null) return

        scope.launch {
            var adbStatus = withContext(Dispatchers.IO) {
                DexAdb.getConnectionStatus(app, forceCheck = forceAdbCheck)
            }

            // If previously paired and we hold WRITE_SECURE_SETTINGS, auto-re-enable Wireless Debugging.
            if (adbStatus != DexAdb.ConnectionStatus.CONNECTED &&
                settings.state.value.dexHasPairedBefore &&
                !DexWirelessDebugging.isEnabled(app) &&
                DexWirelessDebugging.canToggle(app)
            ) {
                val reEnabled = DexWirelessDebugging.enable(app)
                if (reEnabled) {
                    adbStatus = withContext(Dispatchers.IO) {
                        DexAdb.getConnectionStatus(app, forceCheck = true)
                    }
                }
            }

            val connected = adbStatus == DexAdb.ConnectionStatus.CONNECTED
            if (connected) {
                app.stopService(Intent(app, DexPairingService::class.java))
                if (!settings.state.value.dexHasPairedBefore) {
                    settings.set { it.copy(dexHasPairedBefore = true) }
                }
                if (!permissionGrantAttempted && !DexWirelessDebugging.canToggle(app)) {
                    permissionGrantAttempted = true
                    launch(Dispatchers.IO) {
                        DexWirelessDebugging.tryAcquireTogglePermission(app)
                    }
                }
            }

            _state.update {
                it.copy(
                    adbConnected = connected,
                    wirelessDebugEnabled = DexWirelessDebugging.isEnabled(app),
                    canAutoToggleWirelessDebug = DexWirelessDebugging.canToggle(app),
                    checkingConnection = false,
                )
            }
        }
    }

    /**
     * Toggles Samsung DeX on or off immediately.
     * Returns true if the requested transition started; false if setup/permission is still needed.
     */
    fun setDexEnabled(context: Context, enable: Boolean, openViewer: Boolean = true): Boolean {
        if (!enable) {
            stopDex(context)
            return true
        }
        return startDex(context, openViewer = openViewer)
    }

    fun startDex(context: Context, openViewer: Boolean = true): Boolean {
        if (DexSession.current != null) {
            if (openViewer) openViewer(context)
            return true
        }

        val s = settings.state.value
        val shizukuReady = OnPhoneGranter.canUseShizuku()
        val mode = s.dexConnectionMode

        val chosenTransport: DexTransport? = when (mode) {
            DexConnectionMode.AUTO -> when {
                shizukuReady -> DexTransport.SHIZUKU
                _state.value.adbConnected -> DexTransport.WIRELESS_ADB
                else -> null
            }
            DexConnectionMode.SHIZUKU -> if (shizukuReady) DexTransport.SHIZUKU else null
            DexConnectionMode.WIRELESS_ADB -> if (_state.value.adbConnected) DexTransport.WIRELESS_ADB else null
        }

        if (chosenTransport == null) {
            // Try a quick live check for Wireless ADB if user opted for AUTO or WIRELESS_ADB and previously paired.
            if (mode != DexConnectionMode.SHIZUKU && s.dexHasPairedBefore) {
                scope.launch {
                    _state.update {
                        it.copy(
                            starting = true,
                            lastError = null,
                            statusMessage = if (isVi) "Đang bật lại Gỡ lỗi không dây…" else "Reconnecting Wireless ADB…",
                        )
                    }
                    if (!DexWirelessDebugging.isEnabled(app) && DexWirelessDebugging.canToggle(app)) {
                        DexWirelessDebugging.enable(app)
                    }
                    val status = withContext(Dispatchers.IO) {
                        DexAdb.getConnectionStatus(app, forceCheck = true)
                    }
                    if (status == DexAdb.ConnectionStatus.CONNECTED) {
                        _state.update { it.copy(adbConnected = true) }
                        launchSessionNow(context, s.dexDisplaySpec, DexTransport.WIRELESS_ADB, openViewer)
                    } else {
                        _state.update {
                            it.copy(
                                starting = false,
                                adbConnected = false,
                                statusMessage = "",
                                lastError = if (isVi) {
                                    "Chưa kết nối được Shizuku hoặc ADB không dây. Hãy cấp quyền Shizuku hoặc ghép nối Gỡ lỗi không dây bên dưới."
                                } else {
                                    "Neither Shizuku nor Wireless ADB is connected. Allow Shizuku or pair Wireless debugging below."
                                },
                            )
                        }
                    }
                }
                return true
            }
            return false
        }

        launchSessionNow(context, s.dexDisplaySpec, chosenTransport, openViewer)
        return true
    }

    private fun launchSessionNow(
        context: Context,
        displaySpec: String,
        transport: DexTransport,
        openViewer: Boolean,
    ) {
        val spec = DexDisplaySpec.normalize(displaySpec)
        _state.update {
            it.copy(
                starting = true,
                running = false,
                activeTransport = transport,
                lastError = null,
                statusMessage = if (isVi) "Đang khởi động Samsung DeX…" else "Starting Samsung DeX…",
            )
        }
        DexService.start(context, spec, transport)
        if (openViewer) openViewer(context)
    }

    fun openViewer(context: Context) {
        if (DexSession.current == null) return
        val intent = Intent(context, DexViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun stopDex(context: Context) {
        val wasActive = DexSession.current != null || _state.value.running || _state.value.starting
        DexService.stop(context)
        onSessionStopped(error = null)
        if (wasActive && settings.state.value.dexShowIslandPill) {
            postIslandPill(
                title = if (isVi) "Đã tắt Samsung DeX" else "Samsung DeX stopped",
                subtitle = if (isVi) "Màn hình ảo đã được giải phóng" else "Virtual display closed cleanly",
                accent = 0xFF94A3B8.toInt(),
            )
        }
    }

    fun requestShizukuPermission(context: Context, onComplete: () -> Unit = {}) {
        if (OnPhoneGranter.isShizukuRunning()) {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    refreshStatus()
                    onComplete()
                } else {
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode != SHIZUKU_REQ_CODE) return
                            Shizuku.removeRequestPermissionResultListener(this)
                            mainHandler.postDelayed({
                                refreshStatus()
                                onComplete()
                            }, 300L)
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    Shizuku.requestPermission(SHIZUKU_REQ_CODE)
                }
                return
            } catch (_: Throwable) {
            }
        }
        OnPhoneGranter.openOrInstallShizuku(context)
    }

    fun startWirelessPairing(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            Toast.makeText(
                context,
                if (isVi) "Hãy bật Thông báo cho BreezeIsland để nhập mã 6 chữ số."
                else "Enable BreezeIsland notifications to enter the 6-digit pairing code.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        ContextCompat.startForegroundService(context, Intent(context, DexPairingService::class.java))
        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(devIntent) }
            .onFailure {
                runCatching { context.startActivity(fallback) }.onFailure {
                    Toast.makeText(
                        context,
                        if (isVi) "Mở Cài đặt → Cài đặt cho người phát triển → Gỡ lỗi không dây"
                        else "Open Settings → Developer options → Wireless debugging",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
    }

    internal fun onSessionStateChanged(sessionState: DexSession.State) {
        when (sessionState) {
            is DexSession.State.Starting -> {
                _state.update {
                    it.copy(
                        starting = true,
                        running = false,
                        statusMessage = sessionState.message,
                        lastError = null,
                    )
                }
            }
            is DexSession.State.Running -> {
                val curSession = DexSession.current
                val shouldPostPill = curSession != null &&
                    notifiedStartForSession !== curSession &&
                    settings.state.value.dexShowIslandPill
                if (shouldPostPill) {
                    notifiedStartForSession = curSession
                    postIslandPill(
                        title = "Samsung DeX",
                        subtitle = if (sessionState.displayId >= 0) {
                            if (isVi) "Đang chạy · Màn hình #${sessionState.displayId}"
                            else "Active · Display #${sessionState.displayId}"
                        } else {
                            if (isVi) "Đang chạy trên thiết bị" else "Running on-device"
                        },
                        accent = 0xFF38BDF8.toInt(),
                    )
                }
                _state.update {
                    it.copy(
                        starting = false,
                        running = true,
                        displayId = sessionState.displayId,
                        activeTransport = sessionState.transport,
                        statusMessage = "",
                        lastError = null,
                    )
                }
            }
            is DexSession.State.Stopped -> {
                onSessionStopped(sessionState.error)
            }
        }
    }

    internal fun onSessionStopped(error: String?) {
        notifiedStartForSession = null
        _state.update {
            it.copy(
                running = false,
                starting = false,
                displayId = -1,
                activeTransport = null,
                statusMessage = "",
                lastError = error,
            )
        }
    }

    private fun postIslandPill(title: String, subtitle: String, accent: Int) {
        events.post(
            IslandEvent(
                id = "system:local_dex",
                source = EventSource.SYSTEM,
                type = EventType.CONFIRM,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = 2_000L,
                title = title,
                subtitle = subtitle,
                icon = Glyph.OPEN,
                animation = EntranceAnimation.MORPH,
                colors = EventColors(accent = accent),
            )
        )
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 2807
    }
}
