package com.arnav.island.events.system

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
 *     using a 5-layer zero-Accessibility detector:
 *      - Layer 1 (Android 15+ Official API): [BiometricManager.getLastAuthenticationTime] for
 *        `BIOMETRIC_STRONG` & `DEVICE_CREDENTIAL` (requires only install-time `USE_BIOMETRIC`).
 *      - Layer 2 (Shizuku Hardware/System Log Stream): Multi-buffer (`main,system,events`) log
 *        watcher running as UID 2000 (`shell`) to bypass Android 13+ `LogcatManagerService`.
 *      - Layer 3 (Biometric Activity & Foreground Service UsageEvents): Detects both Activity and
 *        ForegroundService (`FOREGROUND_SERVICE_START`/`STOP`) lifecycle of system biometric
 *        providers (`com.samsung.android.biometrics.app.setting`, `Samsung Pass`, `BiometricPrompt`).
 *      - Layer 4 (In-App Login -> Home Activity Transition): Detects banking & finance apps
 *        transitioning from a Login/Auth/Biometric Activity to their Main/Home/Dashboard Activity.
 *      - Layer 5 (Modal Biometric Overlay Pause/Resume): Detects when a foreground app briefly
 *        pauses (`ACTIVITY_PAUSED`) for a translucent biometric dialog and resumes (`ACTIVITY_RESUMED`)
 *        on the exact same Activity without switching apps.
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
        private const val DEBOUNCE_MS = 1_800L
        private const val POST_UNLOCK_GRACE_MS = 2_000L

        private val BIOMETRIC_PACKAGES = setOf(
            "com.samsung.android.biometrics.app.setting",
            "com.samsung.android.samsungpass",
            "com.samsung.android.authfw",
            "com.samsung.android.spay",
            "com.vivo.fingerprint",
            "com.vivo.secime.service",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
        )

        private val BIOMETRIC_CLASS_KEYWORDS = listOf(
            "biometricprompt",
            "biometricactivity",
            "biometricsysui",
            "biometricfragment",
            "authcredential",
            "confirmdevicecredential",
            "devicecredentialhandler",
            "fingerprintdialog",
            "fingerprintauth",
            "applock",
            "verifyfingerprint",
            "fido",
            "passkey",
        )

        private val LOGIN_ACTIVITY_KEYWORDS = listOf(
            "login",
            "signin",
            "sign_in",
            "auth",
            "biometric",
            "fingerprint",
            "passcode",
            "pinactivity",
            "pinlock",
            "lockactivity",
            "lockscreen",
            "unlockactivity",
            "verify",
            "credential",
        )

        private val NON_HOME_ACTIVITY_KEYWORDS = listOf(
            "splash",
            "launch",
            "welcome",
            "onboarding",
            "intro",
            "permission",
            "login",
            "signin",
            "sign_in",
            "auth",
            "biometric",
            "fingerprint",
            "passcode",
            "pinactivity",
            "pinlock",
            "lockactivity",
            "lockscreen",
            "unlockactivity",
            "verify",
            "credential",
            "otp",
            "register",
            "signup",
        )

        private val LOG_TAG_KEYWORDS = listOf(
            "biometric",
            "fingerprint",
            "semfp",
            "sbiometric",
            "sehbiometric",
            "authcontroller",
            "authcontainer",
            "authbiometric",
            "authsession",
            "authenticationclient",
            "udfps",
            "keyguardupdatemonitor",
            "keystore2",
        )

        private val LOG_SUCCESS_KEYWORDS = listOf(
            "onauthenticated",
            "handleauthenticated",
            "handleonauthenticated",
            "authenticationsucceeded",
            "onauthenticationsucceeded",
            "onbiometricauthenticated",
            "onfingerprintauthenticated",
            "state_authenticated",
            "msg_authenticated",
            "msg_biometric_authenticated",
            "deliverauthenticationresult",
            "sendauthenticated",
            "sendresultandcleanup",
            "authenticated(true",
            "authenticated: true",
            "authenticated=true",
            "addauthtoken",
        )

        private val LOG_IGNORE_KEYWORDS = listOf(
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
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val biometricManager = context.getSystemService(BiometricManager::class.java)

    private var registered = false
    private var isScreenInteractive = true
    private var lastTriggerElapsed = 0L
    private var lastDeviceUnlockElapsed = 0L

    private var biometricApiJob: Job? = null
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
        startBiometricManagerWatcher()
        startLogcatWatcher()
        startUsageEventsWatcher()
    }

    private fun stopInAppWatchers() {
        biometricApiJob?.cancel()
        biometricApiJob = null

        logcatJob?.cancel()
        logcatJob = null
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null

        usageWatcherJob?.cancel()
        usageWatcherJob = null
    }

    /**
     * Layer 1: Official Android 15+ (API 35+) [BiometricManager.getLastAuthenticationTime] watcher.
     * Requires ONLY `android.permission.USE_BIOMETRIC` (granted automatically at install time).
     * Detects every strong biometric authentication across all banking apps and system prompts.
     */
    private fun startBiometricManagerWatcher() {
        if (Build.VERSION.SDK_INT < 35) return
        if (biometricApiJob?.isActive == true) return
        val bm = biometricManager ?: return

        biometricApiJob = scope.launch(Dispatchers.Default) {
            var lastStrongTime = queryLastAuthTime(bm, BiometricManager.Authenticators.BIOMETRIC_STRONG)
            var lastCredTime = queryLastAuthTime(bm, BiometricManager.Authenticators.DEVICE_CREDENTIAL)

            while (isActive && isScreenInteractive) {
                delay(180L)
                val nowElapsed = SystemClock.elapsedRealtime()

                val strongTime = queryLastAuthTime(bm, BiometricManager.Authenticators.BIOMETRIC_STRONG)
                if (strongTime > 0L && strongTime != lastStrongTime) {
                    val prev = lastStrongTime
                    lastStrongTime = strongTime
                    if (prev >= 0L && nowElapsed - strongTime in -500L..3_500L) {
                        mainHandler.post {
                            onInAppBiometricAuthenticated()
                        }
                    }
                }

                val credTime = queryLastAuthTime(bm, BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                if (credTime > 0L && credTime != lastCredTime) {
                    val prev = lastCredTime
                    lastCredTime = credTime
                    if (prev >= 0L && nowElapsed - credTime in -500L..3_500L) {
                        mainHandler.post {
                            onInAppBiometricAuthenticated()
                        }
                    }
                }
            }
        }
    }

    private fun queryLastAuthTime(bm: BiometricManager, authenticator: Int): Long {
        if (Build.VERSION.SDK_INT < 35) return -1L
        return try {
            bm.getLastAuthenticationTime(authenticator)
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Layer 2: Multi-buffer (`main,system,events`) Hardware/System Biometric Log Watcher.
     * Uses Shizuku (`UID 2000` shell) on Android 13+ so `LogcatManagerService` never blocks
     * background log reading or shows consent dialogs.
     */
    private fun startLogcatWatcher() {
        if (logcatJob?.isActive == true) return
        logcatJob = scope.launch(Dispatchers.IO) {
            val cmd = "logcat -b main,system,events -v brief -T 1 *:D"

            while (isActive && isScreenInteractive) {
                val proc = OnPhoneGranter.openShizukuProcess(cmd)
                    ?: if (Build.VERSION.SDK_INT < 33 && OnPhoneGranter.hasReadLogsPermission(context)) {
                        runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)) }.getOrNull()
                    } else {
                        null
                    }

                if (proc == null) {
                    delay(3_500L)
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
        val colonIdx = lower.indexOf(':')
        val tagPart = if (colonIdx > 0) lower.substring(0, colonIdx) else lower

        val isBiometricTag = LOG_TAG_KEYWORDS.any { kw -> tagPart.contains(kw) }
        if (!isBiometricTag) return false

        if (LOG_IGNORE_KEYWORDS.any { kw -> lower.contains(kw) }) {
            return false
        }

        return LOG_SUCCESS_KEYWORDS.any { kw -> lower.contains(kw) }
    }

    /**
     * Layer 3, 4 & 5: `UsageEvents` Watcher for:
     *  - Layer 3: System Biometric Dialogs & UDFPS Foreground Services (`FOREGROUND_SERVICE_START` / `STOP`,
     *    `ACTIVITY_RESUMED` / `PAUSED` / `STOPPED` on `com.samsung.android.biometrics.app.setting`, etc.)
     *  - Layer 4: In-App Login/Auth Activity -> Home/Main Activity transitions inside banking & finance apps.
     */
    private fun startUsageEventsWatcher() {
        if (usageWatcherJob?.isActive == true) return
        usageWatcherJob = scope.launch(Dispatchers.Default) {
            var lastProcessedTime = System.currentTimeMillis()
            var biometricPromptActiveAt = 0L
            var currentPkg: String? = null
            var currentLoginClass: String? = null
            var currentLoginEnteredAt = 0L
            var translucentPausedPkg: String? = null
            var translucentPausedClass: String? = null
            var translucentPausedAt = 0L

            while (isActive && isScreenInteractive) {
                delay(280L)
                if (!ForegroundAppMonitor.hasUsageAccess(context)) {
                    delay(2_000L)
                    continue
                }
                val nowWall = System.currentTimeMillis()
                val fromWall = (nowWall - 3_200L).coerceAtMost(lastProcessedTime)

                val eventsIter = try {
                    usageStats?.queryEvents(fromWall, nowWall)
                } catch (_: Throwable) {
                    null
                } ?: continue

                val ev = UsageEvents.Event()
                var maxSeenTime = lastProcessedTime
                while (eventsIter.hasNextEvent()) {
                    eventsIter.getNextEvent(ev)
                    if (ev.timeStamp <= lastProcessedTime) continue
                    if (ev.timeStamp > maxSeenTime) maxSeenTime = ev.timeStamp

                    val pkg = ev.packageName ?: continue
                    val cls = ev.className ?: ""

                    // Layer 3: System Biometric Sheet / UDFPS Foreground Service
                    if (isBiometricComponent(pkg, cls)) {
                        when (ev.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED,
                            UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                                biometricPromptActiveAt = ev.timeStamp
                            }
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED,
                            UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                                val started = biometricPromptActiveAt
                                val duration = if (started > 0L) ev.timeStamp - started else 600L
                                if (duration in 50L..35_000L) {
                                    biometricPromptActiveAt = 0L
                                    translucentPausedPkg = null
                                    mainHandler.post {
                                        onInAppBiometricAuthenticated()
                                    }
                                }
                            }
                        }
                        continue
                    }

                    val isRegularApp = pkg != context.packageName &&
                        !pkg.startsWith("com.android.systemui") &&
                        !pkg.contains("launcher") &&
                        !pkg.startsWith("com.android.permissioncontroller") &&
                        !pkg.startsWith("com.google.android.permissioncontroller")

                    if (isRegularApp) {
                        when (ev.eventType) {
                            UsageEvents.Event.ACTIVITY_PAUSED -> {
                                translucentPausedPkg = pkg
                                translucentPausedClass = cls
                                translucentPausedAt = ev.timeStamp
                            }
                            UsageEvents.Event.ACTIVITY_STOPPED -> {
                                // Fullscreen transition or app exit -> not a translucent biometric sheet
                                if (pkg == translucentPausedPkg && cls == translucentPausedClass) {
                                    translucentPausedPkg = null
                                    translucentPausedClass = null
                                }
                            }
                            UsageEvents.Event.ACTIVITY_RESUMED -> {
                                // Layer 5: Translucent Biometric Dialog Pause -> Resume on the exact same Activity
                                if (pkg == translucentPausedPkg && cls == translucentPausedClass) {
                                    val pauseDuration = ev.timeStamp - translucentPausedAt
                                    translucentPausedPkg = null
                                    translucentPausedClass = null
                                    if (pauseDuration in 250L..18_000L) {
                                        mainHandler.post {
                                            onInAppBiometricAuthenticated()
                                        }
                                    }
                                } else {
                                    translucentPausedPkg = null
                                    translucentPausedClass = null
                                }

                                // Layer 4: Banking / App Login Activity -> Main/Home Activity transition
                                val clsLower = cls.lowercase(Locale.ROOT)
                                val isLoginScreen = LOGIN_ACTIVITY_KEYWORDS.any { kw -> clsLower.contains(kw) }
                                val isNonHomeScreen = NON_HOME_ACTIVITY_KEYWORDS.any { kw -> clsLower.contains(kw) }

                                if (isLoginScreen) {
                                    currentPkg = pkg
                                    currentLoginClass = cls
                                    currentLoginEnteredAt = ev.timeStamp
                                } else if (pkg == currentPkg && currentLoginClass != null && cls != currentLoginClass && !isNonHomeScreen) {
                                    val spentOnLogin = ev.timeStamp - currentLoginEnteredAt
                                    currentLoginClass = null
                                    if (spentOnLogin in 300L..90_000L) {
                                        mainHandler.post {
                                            onInAppBiometricAuthenticated()
                                        }
                                    }
                                } else if (pkg != currentPkg) {
                                    currentPkg = pkg
                                    currentLoginClass = null
                                }
                            }
                        }
                    }
                }
                lastProcessedTime = maxSeenTime
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
