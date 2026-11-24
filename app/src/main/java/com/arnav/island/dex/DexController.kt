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
    val activeOutputMode: DexOutputMode = DexOutputMode.AUTO_EXTERNAL_OR_PC,
    /** External HDMI / USB-C monitor, TV, projector, or Smart View display currently plugged in. */
    val detectedExternalDisplay: ExternalDisplayInfo? = null,
    /** Name of the external display currently rendering Samsung DeX fullscreen. */
    val externalDisplayName: String? = null,
    /** Built-in DeX for PC browser URL (e.g. http://192.168.1.20:8765). */
    val pcWebUrl: String? = null,
    val pcWebPort: Int = DexWebServer.DEFAULT_PORT,
    val pcClientCount: Int = 0,
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
 * Coordinates the Samsung DeX lifecycle inside BreezeIsland:
 *  - Outputs fullscreen Samsung DeX directly to external HDMI / USB-C monitors, TVs, projectors,
 *    or Smart View / Miracast screens (with the phone acting as a true DeX Touchpad + Keyboard)
 *  - Streams Samsung DeX to any Windows / Mac PC browser (`http://<ip>:8765` or USB `adb forward`)
 *    without taking over the phone screen
 *  - Optionally supports on-phone preview whenever explicitly chosen
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
        val extDisplay = ExternalDisplayFinder.findExternalDisplays(app, currentSession?.displayId ?: -1)
            .firstOrNull()
            ?.let(ExternalDisplayFinder::toInfo)

        _state.update {
            it.copy(
                running = currentSession != null && currentSession.state.value is DexSession.State.Running,
                starting = currentSession != null && currentSession.state.value is DexSession.State.Starting,
                displayId = currentSession?.displayId ?: -1,
                activeTransport = currentSession?.transport,
                activeOutputMode = currentSession?.outputMode ?: settings.state.value.dexOutputMode,
                detectedExternalDisplay = extDisplay,
                pcWebUrl = currentSession?.webServer?.url?.value,
                pcWebPort = currentSession?.webServer?.port ?: DexWebServer.DEFAULT_PORT,
                pcClientCount = currentSession?.webServer?.clientCount?.value ?: 0,
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

    fun startDex(context: Context, forcePhoneViewer: Boolean = false): Boolean {
        val existing = DexSession.current
        if (existing != null) {
            if (forcePhoneViewer) {
                openPhoneViewer(context)
            } else if (_state.value.externalDisplayName != null) {
                openTouchpad(context)
            }
            return true
        }

        val s = settings.state.value
        val outputMode = if (forcePhoneViewer) DexOutputMode.PHONE_SCREEN else s.dexOutputMode
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
                        launchSessionNow(context, s.dexDisplaySpec, DexTransport.WIRELESS_ADB, outputMode)
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

        launchSessionNow(context, s.dexDisplaySpec, chosenTransport, outputMode)
        return true
    }

    private fun launchSessionNow(
        context: Context,
        displaySpec: String,
        transport: DexTransport,
        outputMode: DexOutputMode,
    ) {
        val extDisplay = ExternalDisplayFinder.findExternalDisplays(app).firstOrNull()
        val spec = DexDisplaySpec.normalize(displaySpec)

        _state.update {
            it.copy(
                starting = true,
                running = false,
                activeTransport = transport,
                activeOutputMode = outputMode,
                detectedExternalDisplay = extDisplay?.let(ExternalDisplayFinder::toInfo),
                lastError = null,
                statusMessage = if (isVi) "Đang khởi động Samsung DeX…" else "Starting Samsung DeX…",
            )
        }

        DexService.start(context, spec, transport, outputMode)

        // Choose what appears on the PHONE screen:
        //  - External Monitor connected -> open DeX Touchpad on phone (DeX renders fullscreen on the monitor!)
        //  - PC Browser / Auto without HDMI -> DO NOT hijack phone screen; stay in app showing PC URL & hot-plug status!
        //  - Phone Screen mode -> open fullscreen phone viewer explicitly.
        when (outputMode) {
            DexOutputMode.PHONE_SCREEN -> openPhoneViewer(context)
            DexOutputMode.EXTERNAL_DISPLAY,
            DexOutputMode.AUTO_EXTERNAL_OR_PC -> {
                if (extDisplay != null) {
                    openTouchpad(context)
                }
            }
            DexOutputMode.PC_BROWSER -> Unit
        }
    }

    fun openTouchpad(context: Context) {
        if (DexSession.current == null) return
        val intent = Intent(context, DexTouchpadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openPhoneViewer(context: Context) {
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

    internal fun onExternalDisplayChanged(info: ExternalDisplayInfo?) {
        _state.update {
            it.copy(
                detectedExternalDisplay = info ?: ExternalDisplayFinder.findExternalDisplays(app, DexSession.current?.displayId ?: -1)
                    .firstOrNull()
                    ?.let(ExternalDisplayFinder::toInfo),
                externalDisplayName = info?.label,
            )
        }
    }

    internal fun onWebServerChanged(url: String?, port: Int, clientCount: Int) {
        _state.update {
            it.copy(
                pcWebUrl = url,
                pcWebPort = port,
                pcClientCount = clientCount,
            )
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
                val extName = _state.value.externalDisplayName
                val pcUrl = curSession?.webServer?.url?.value
                val shouldPostPill = curSession != null &&
                    notifiedStartForSession !== curSession &&
                    settings.state.value.dexShowIslandPill
                if (shouldPostPill) {
                    notifiedStartForSession = curSession
                    postIslandPill(
                        title = "Samsung DeX",
                        subtitle = when {
                            !extName.isNullOrBlank() -> if (isVi) "Đang xuất ra $extName" else "Output: $extName"
                            !pcUrl.isNullOrBlank() -> if (isVi) "Sẵn sàng cho PC: $pcUrl" else "PC Ready: $pcUrl"
                            else -> if (isVi) "Đang chạy · Màn hình #${sessionState.displayId}" else "Active · Display #${sessionState.displayId}"
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
                        pcWebUrl = pcUrl,
                        pcWebPort = curSession?.webServer?.port ?: DexWebServer.DEFAULT_PORT,
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
        val ext = ExternalDisplayFinder.findExternalDisplays(app).firstOrNull()?.let(ExternalDisplayFinder::toInfo)
        _state.update {
            it.copy(
                running = false,
                starting = false,
                displayId = -1,
                activeTransport = null,
                externalDisplayName = null,
                detectedExternalDisplay = ext,
                pcWebUrl = null,
                pcClientCount = 0,
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
                durationMs = 2_400L,
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
