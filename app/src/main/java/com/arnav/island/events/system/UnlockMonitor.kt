package com.arnav.island.events.system

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.UnlockMethod
import com.arnav.island.events.UnlockPayload
import com.arnav.island.overlay.ForegroundAppMonitor
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Monitors both:
 *  1. Device lockscreen unlocks ([Intent.ACTION_USER_PRESENT])
 *  2. In-app fingerprint & biometric authentications (Banking apps, e-wallets, App Lock, Passkeys)
 *     using a zero-Accessibility multi-layer detector:
 *      - Layer 1: Direct hardware/system biometric event stream (`BiometricService`,
 *        `FingerprintService`, `AuthController`) via `READ_LOGS` or Shizuku.
 *      - Layer 2: `UsageStatsManager` (`UsageEvents`) transition detector for system biometric
 *        sheets (`com.samsung.android.biometrics.app.setting`, `BiometricPrompt`, OEM App Locks).
 */
class UnlockMonitor(
    private val context: Context,
    private val events: EventEngine,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    companion object {
        private const val TAG = "UnlockMonitor"
        const val EVENT_ID = "system:unlock"
        private const val DEBOUNCE_MS = 1_850L
        private const val POST_UNLOCK_GRACE_MS = 2_200L

        private val BIOMETRIC_PACKAGES = setOf(
            "com.samsung.android.biometrics.app.setting",
            "com.samsung.android.samsungpass",
            "com.samsung.android.authfw",
            "com.vivo.fingerprint",
        )

        private val BIOMETRIC_CLASS_KEYWORDS = listOf(
            "biometricprompt",
            "biometricactivity",
            "biometricsysui",
            "authcredential",
            "confirmdevicecredential",
            "devicecredentialhandler",
            "fingerprintdialog",
            "fingerprintauth",
            "applock",
            " verifyfingerprint",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)

    private var registered = false
    private var isScreenInteractive = true
    private var lastTriggerElapsed = 0L
    private var lastDeviceUnlockElapsed = 0L

    private var logcatJob: Job? = null
    private var logcatProcess: Process? = null
    private var usageWatcherJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    stopInAppWatchers()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    startInAppWatchersIfNeeded()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    lastDeviceUnlockElapsed = now
                    onDeviceUnlocked(now)
                    startInAppWatchersIfNeeded()
                }
            }
        }
    }

    fun start() {
        if (registered) return
        try {
            OnPhoneGranter.tryAutoGrantBiometricPermissions(context)
            isScreenInteractive = powerManager?.isInteractive ?: true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            if (isScreenInteractive) {
                startInAppWatchersIfNeeded()
            }
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to start UnlockMonitor", t)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        stopInAppWatchers()
        registered = false
    }

    fun refreshWatchers() {
        if (!registered) return
        OnPhoneGranter.tryAutoGrantBiometricPermissions(context)
        if (isScreenInteractive) {
            startInAppWatchersIfNeeded()
        } else {
            stopInAppWatchers()
        }
    }

    private fun startInAppWatchersIfNeeded() {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled || !s.unlockInAppBiometricEnabled) {
            stopInAppWatchers()
            return
        }
        startLogcatWatcher()
        startUsageEventsWatcher()
    }

    private fun stopInAppWatchers() {
        logcatJob?.cancel()
        logcatJob = null
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null

        usageWatcherJob?.cancel()
        usageWatcherJob = null
    }

    /**
     * Layer 1: Hardware/System Biometric Logcat Watcher.
     * Captures `BiometricService`, `FingerprintService`, and `AuthController` authentication
     * callbacks in real time (< 20ms latency) when `READ_LOGS` or Shizuku is available.
     */
    private fun startLogcatWatcher() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch(Dispatchers.IO) {
            val cmd = "logcat -v brief -T 1 " +
                "BiometricService:I FingerprintService:I Fingerprint21:I " +
                "AuthController:I BiometricScheduler:I BiometricSchedulerOperation:I " +
                "SemBiometricSysUiManager:I SemFpServiceEx:I MiuiFingerprint:I OplusBiometrics:I *:S"

            while (isActive && isScreenInteractive) {
                val hasReadLogs = OnPhoneGranter.hasReadLogsPermission(context)
                val proc = try {
                    if (hasReadLogs) {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    } else {
                        OnPhoneGranter.openShizukuProcess(cmd)
                    }
                } catch (_: Throwable) {
                    null
                }

                if (proc == null) {
                    // Retry periodically in case the user grants permission while the app is open
                    delay(4_000L)
                    continue
                }

                logcatProcess = proc
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (isActive && isScreenInteractive) {
                            line = reader.readLine() ?: break
                            if (isBiometricSuccessLog(line!!)) {
                                mainHandler.post {
                                    onInAppBiometricAuthenticated()
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { proc.destroy() }
                    if (logcatProcess === proc) logcatProcess = null
                }
                delay(1_500L)
            }
        }
    }

    private fun isBiometricSuccessLog(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        // Reject explicit failure / lockout / cancel lines
        if (lower.contains("authenticated=false") ||
            lower.contains("authenticated: false") ||
            lower.contains("onauthenticated(false") ||
            lower.contains("error") ||
            lower.contains("cancel") ||
            lower.contains("reject")
        ) {
            return false
        }

        return lower.contains("onauthenticated(true") ||
            lower.contains("onauthenticated: true") ||
            lower.contains("authenticated=true") ||
            lower.contains("authenticated: true") ||
            lower.contains("handleauthenticated") ||
            lower.contains("handleonauthenticated") ||
            lower.contains("onbiometricauthenticated") ||
            lower.contains("authenticationsucceeded") ||
            lower.contains("onauthenticationsucceeded") ||
            (lower.contains("authcontroller") && lower.contains("sendresultandcleanup")) ||
            (lower.contains("sembiometric") && lower.contains("onauthenticated"))
    }

    /**
     * Layer 2: UsageEvents Watcher for Biometric Dialog & App Lock transitions.
     * Works with standard Usage Access (`PACKAGE_USAGE_STATS`) even without Shizuku/ADB.
     */
    private fun startUsageEventsWatcher() {
        if (usageWatcherJob?.isActive == true) return
        usageWatcherJob = scope.launch(Dispatchers.Default) {
            var lastQueryWall = System.currentTimeMillis()
            var biometricPromptActiveAt = 0L

            while (isActive && isScreenInteractive) {
                delay(420L)
                if (!ForegroundAppMonitor.hasUsageAccess(context)) {
                    delay(2_000L)
                    continue
                }
                val nowWall = System.currentTimeMillis()
                val fromWall = (lastQueryWall - 250L).coerceAtMost(nowWall - 50L)
                lastQueryWall = nowWall

                val eventsIter = try {
                    usageStats?.queryEvents(fromWall, nowWall)
                } catch (_: Throwable) {
                    null
                } ?: continue

                val ev = UsageEvents.Event()
                while (eventsIter.hasNextEvent()) {
                    eventsIter.getNextEvent(ev)
                    val pkg = ev.packageName ?: continue
                    val cls = ev.className ?: ""
                    if (isBiometricComponent(pkg, cls)) {
                        when (ev.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED -> {
                                biometricPromptActiveAt = ev.timeStamp
                            }
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED -> {
                                val started = biometricPromptActiveAt
                                val duration = if (started > 0L) ev.timeStamp - started else 500L
                                if (duration in 80L..25_000L) {
                                    biometricPromptActiveAt = 0L
                                    mainHandler.post {
                                        onInAppBiometricAuthenticated()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isBiometricComponent(pkg: String, cls: String): Boolean {
        val pkgLower = pkg.lowercase(Locale.ROOT)
        val clsLower = cls.lowercase(Locale.ROOT)
        if (pkgLower in BIOMETRIC_PACKAGES) return true
        return BIOMETRIC_CLASS_KEYWORDS.any { keyword -> clsLower.contains(keyword) }
    }

    private fun onDeviceUnlocked(nowElapsed: Long) {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled) return
        if (nowElapsed - lastTriggerElapsed < DEBOUNCE_MS) return
        lastTriggerElapsed = nowElapsed

        val event = buildUnlockEvent(
            method = UnlockMethod.FINGERPRINT,
            durationMs = s.unlockDurationMs,
            inAppAuth = false,
        )
        scope.launch {
            events.removeWhere { it.source == EventSource.TEST }
            events.post(event)
        }
    }

    private fun onInAppBiometricAuthenticated() {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled || !s.unlockInAppBiometricEnabled) return
        if (keyguardManager?.isKeyguardLocked == true) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastDeviceUnlockElapsed < POST_UNLOCK_GRACE_MS) return
        if (nowElapsed - lastTriggerElapsed < DEBOUNCE_MS) return
        lastTriggerElapsed = nowElapsed

        val event = buildUnlockEvent(
            method = UnlockMethod.FINGERPRINT,
            durationMs = s.unlockDurationMs,
            inAppAuth = true,
        )
        scope.launch {
            events.removeWhere { it.source == EventSource.TEST }
            events.post(event)
        }
    }

    fun buildUnlockEvent(
        method: UnlockMethod = UnlockMethod.FINGERPRINT,
        durationMs: Long = 2050L,
        inAppAuth: Boolean = false,
    ): IslandEvent {
        val title = if (inAppAuth) "Đã xác thực" else "Đã mở khóa"
        val subtitle = if (inAppAuth) "Xác thực vân tay 3D" else "Mở khóa vân tay 3D"
        return IslandEvent(
            id = EVENT_ID,
            source = EventSource.SYSTEM,
            type = EventType.UNLOCK,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = durationMs.coerceAtLeast(1950L),
            title = title,
            subtitle = subtitle,
            icon = Glyph.FINGERPRINT,
            payload = UnlockPayload(method, title, subtitle),
            animation = EntranceAnimation.MORPH,
            colors = EventColors(accent = 0xFF00F0FF.toInt()),
        )
    }
}
