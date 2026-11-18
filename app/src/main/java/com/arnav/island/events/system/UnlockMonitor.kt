package com.arnav.island.events.system

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.os.Build
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
import kotlin.math.abs

/**
 * Monitors strictly hardware fingerprint authentications (both lockscreen fingerprint unlock and
 * in-app fingerprint authentication) with zero false positives on Face Unlock, PIN/Pattern,
 * Swipe-to-unlock, or normal app/dialog navigation:
 *  - Layer 1 (Android 15+ Official API): [BiometricManager.getLastAuthenticationTime] for
 *    [BiometricManager.Authenticators.BIOMETRIC_STRONG] ONLY (excludes `DEVICE_CREDENTIAL` and
 *    `BIOMETRIC_WEAK` 2D Face Unlock).
 *  - Layer 2 (Shizuku / READ_LOGS Hardware Fingerprint Stream): Watches explicit fingerprint HAL /
 *    service authentication callbacks (`FingerprintService`, `SemFp`, `udfps`).
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
        private const val DEBOUNCE_MS = 2_000L
        private const val FINGERPRINT_MATCH_WINDOW_MS = 3_000L

        private val FINGERPRINT_LOG_TAG_KEYWORDS = listOf(
            "fingerprint",
            "semfp",
            "udfps",
        )

        private val FINGERPRINT_LOG_SUCCESS_KEYWORDS = listOf(
            "onfingerprintauthenticated",
            "handleauthenticated(true",
            "handleauthenticated: true",
            "onauthenticated(true",
            "onauthenticated: true",
            "authenticationsucceeded",
            "onauthenticationsucceeded",
            "sendauthenticated",
        )

        private val FINGERPRINT_LOG_IGNORE_KEYWORDS = listOf(
            "face",
            "weak",
            "credential",
            "authenticated=false",
            "authenticated: false",
            "onauthenticated(false",
            "handleauthenticated(false",
            "handleauthenticated: false",
            "error",
            "cancel",
            "reject",
            "fail",
            "lockout",
            "acquire",
            "starting",
            "destroy",
            "enroll",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val biometricManager = context.getSystemService(BiometricManager::class.java)

    private var registered = false
    private var isScreenInteractive = true
    private var lastTriggerElapsed = -10_000L
    private var lastDeviceUnlockElapsed = -10_000L
    @Volatile
    private var lastFingerprintHardwareElapsed = -10_000L
    @Volatile
    private var logcatActive = false

    private var biometricApiJob: Job? = null
    private var logcatJob: Job? = null
    private var logcatProcess: Process? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    stopWatchers()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    startWatchersIfNeeded()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    lastDeviceUnlockElapsed = now
                    verifyAndTriggerDeviceFingerprintUnlock(now)
                    startWatchersIfNeeded()
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
                startWatchersIfNeeded()
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
        stopWatchers()
        registered = false
    }

    fun refreshWatchers() {
        if (!registered) return
        OnPhoneGranter.tryAutoGrantBiometricPermissions(context)
        if (isScreenInteractive) {
            startWatchersIfNeeded()
        } else {
            stopWatchers()
        }
    }

    private fun startWatchersIfNeeded() {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled) {
            stopWatchers()
            return
        }
        startBiometricManagerWatcher()
        startLogcatWatcher()
    }

    private fun stopWatchers() {
        biometricApiJob?.cancel()
        biometricApiJob = null

        logcatJob?.cancel()
        logcatJob = null
        logcatActive = false
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
    }

    /**
     * Layer 1: Official Android 15+ (API 35+) [BiometricManager.getLastAuthenticationTime] watcher
     * checking strictly [BiometricManager.Authenticators.BIOMETRIC_STRONG] (Fingerprint).
     * Never checks `DEVICE_CREDENTIAL` (PIN/Pattern) or `BIOMETRIC_WEAK` (2D Face Unlock).
     */
    private fun startBiometricManagerWatcher() {
        if (Build.VERSION.SDK_INT < 35) return
        if (biometricApiJob?.isActive == true) return
        val bm = biometricManager ?: return

        biometricApiJob = scope.launch(Dispatchers.Default) {
            var lastStrongTime = queryLastStrongAuthTime(bm)

            while (isActive && isScreenInteractive) {
                delay(180L)
                val nowElapsed = SystemClock.elapsedRealtime()
                val strongTime = queryLastStrongAuthTime(bm)

                if (strongTime > 0L && strongTime != lastStrongTime) {
                    val prev = lastStrongTime
                    lastStrongTime = strongTime
                    if (prev >= 0L && nowElapsed - strongTime in -500L..FINGERPRINT_MATCH_WINDOW_MS) {
                        lastFingerprintHardwareElapsed = strongTime
                        mainHandler.post {
                            onFingerprintAuthenticated(nowElapsed)
                        }
                    }
                }
            }
        }
    }

    private fun queryLastStrongAuthTime(bm: BiometricManager? = biometricManager): Long {
        if (Build.VERSION.SDK_INT < 35 || bm == null) return -1L
        return try {
            bm.getLastAuthenticationTime(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Layer 2: Strict Fingerprint Hardware Log Watcher (via Shizuku or READ_LOGS).
     */
    private fun startLogcatWatcher() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch(Dispatchers.IO) {
            val cmd = "logcat -b main,system -v brief -T 1 *:D"

            while (isActive && isScreenInteractive) {
                val proc = OnPhoneGranter.openShizukuProcess(cmd)
                    ?: if (Build.VERSION.SDK_INT < 33 && OnPhoneGranter.hasReadLogsPermission(context)) {
                        runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)) }.getOrNull()
                    } else {
                        null
                    }

                if (proc == null) {
                    logcatActive = false
                    delay(3_500L)
                    continue
                }

                logcatProcess = proc
                logcatActive = true
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (isActive && isScreenInteractive) {
                            line = reader.readLine() ?: break
                            if (isFingerprintSuccessLog(line!!)) {
                                val nowElapsed = SystemClock.elapsedRealtime()
                                lastFingerprintHardwareElapsed = nowElapsed
                                mainHandler.post {
                                    onFingerprintAuthenticated(nowElapsed)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    logcatActive = false
                    runCatching { proc.destroy() }
                    if (logcatProcess === proc) logcatProcess = null
                }
                delay(1_500L)
            }
        }
    }

    internal fun isFingerprintSuccessLog(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        val colonIdx = lower.indexOf(':')
        val tagPart = if (colonIdx > 0) lower.substring(0, colonIdx) else lower

        val isFingerprintTag = FINGERPRINT_LOG_TAG_KEYWORDS.any { kw -> tagPart.contains(kw) }
        if (!isFingerprintTag) return false

        if (FINGERPRINT_LOG_IGNORE_KEYWORDS.any { kw -> lower.contains(kw) }) {
            return false
        }

        return FINGERPRINT_LOG_SUCCESS_KEYWORDS.any { kw -> lower.contains(kw) }
    }

    /**
     * Called when [Intent.ACTION_USER_PRESENT] fires. Verifies that an actual fingerprint
     * (`BIOMETRIC_STRONG` or hardware fingerprint log) was used to unlock the device, ignoring
     * Swipe, PIN/Pattern, Smart Lock, and 2D Face Unlock.
     */
    private fun verifyAndTriggerDeviceFingerprintUnlock(unlockElapsed: Long) {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled) return

        if (wasRecentFingerprintAuth(unlockElapsed)) {
            postUnlockAnimation(unlockElapsed, inAppAuth = false)
            return
        }

        // On Android 15+, BiometricService may commit the BIOMETRIC_STRONG timestamp a few ms
        // after ACTION_USER_PRESENT; re-check once after 160ms.
        if (Build.VERSION.SDK_INT >= 35 || logcatActive) {
            mainHandler.postDelayed({
                val now = SystemClock.elapsedRealtime()
                if (wasRecentFingerprintAuth(now)) {
                    postUnlockAnimation(now, inAppAuth = false)
                }
            }, 160L)
        }
    }

    private fun wasRecentFingerprintAuth(nowElapsed: Long): Boolean {
        val strongTime = queryLastStrongAuthTime()
        if (strongTime > 0L && abs(nowElapsed - strongTime) <= FINGERPRINT_MATCH_WINDOW_MS) {
            lastFingerprintHardwareElapsed = strongTime
            return true
        }
        if (lastFingerprintHardwareElapsed > 0L && abs(nowElapsed - lastFingerprintHardwareElapsed) <= FINGERPRINT_MATCH_WINDOW_MS) {
            return true
        }
        return false
    }

    /**
     * Called when either [BiometricManager] (`BIOMETRIC_STRONG`) or the hardware fingerprint log
     * stream reports a new fingerprint authentication.
     */
    private fun onFingerprintAuthenticated(nowElapsed: Long) {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled) return

        val isLockscreenUnlock = keyguardManager?.isKeyguardLocked == true ||
            abs(nowElapsed - lastDeviceUnlockElapsed) <= FINGERPRINT_MATCH_WINDOW_MS

        if (isLockscreenUnlock) {
            postUnlockAnimation(nowElapsed, inAppAuth = false)
        } else if (s.unlockInAppBiometricEnabled) {
            postUnlockAnimation(nowElapsed, inAppAuth = true)
        }
    }

    private fun postUnlockAnimation(nowElapsed: Long, inAppAuth: Boolean) {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled) return
        if (nowElapsed - lastTriggerElapsed < DEBOUNCE_MS) return
        lastTriggerElapsed = nowElapsed

        val event = buildUnlockEvent(
            method = UnlockMethod.FINGERPRINT,
            durationMs = s.unlockDurationMs,
            inAppAuth = inAppAuth,
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
