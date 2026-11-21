package com.arnav.island.search

import android.app.Application
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.arnav.island.BuildConfig
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.ImageRef
import com.arnav.island.events.IslandEvent
import com.arnav.island.overlay.ForegroundAppMonitor
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.storage.IslandSettings
import com.arnav.island.storage.SettingsRepository
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.Diagnostics
import com.arnav.island.util.ImageStore
import com.arnav.island.util.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class SearchAutoLaunchState(
    val enabled: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuPermitted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val serviceBound: Boolean = false,
    val watchingLauncher: Boolean = false,
    val remoteStatus: String = "idle",
    val lastLaunchedLabel: String? = null,
    val lastLaunchedQuery: String? = null,
)

/**
 * Coordinates the Shizuku-backed [SearchAutoLaunchUserService] so native Home Screen / Samsung
 * Finder app search automatically opens the single remaining app without needing any
 * AccessibilityService.
 *
 * Security & battery design:
 *  - Only polls foreground package while the screen is ON & unlocked and the toggle is ON.
 *  - Only connects UiAutomation inside UID 2000 while the foreground app is a Home Launcher /
 *    Samsung Finder; disconnects immediately on screen off or when leaving Home/Finder.
 */
class SearchAutoLaunchController(
    private val app: Application,
    private val events: EventEngine,
    private val images: ImageStore,
    private val apps: AppInfoCache,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usageStats = app.getSystemService(UsageStatsManager::class.java)
    private val powerManager = app.getSystemService(PowerManager::class.java)
    private val keyguardManager = app.getSystemService(KeyguardManager::class.java)

    private val _state = MutableStateFlow(SearchAutoLaunchState())
    val state: StateFlow<SearchAutoLaunchState> = _state.asStateFlow()

    private var remoteService: ISearchAutoLaunchService? = null
    private var bindingInProgress = false
    private var pollJob: Job? = null
    private var screenReceiverRegistered = false
    private var isScreenInteractive = true
    private var lastAppsPushElapsed = 0L
    private var currentForegroundPkg: String? = null
    private var lastUsageQueryTime = 0L
    @Volatile
    private var launchCooldownUntilElapsed = 0L

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, SearchAutoLaunchUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("search_auto_launch")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val remoteCallback = object : ISearchAutoLaunchCallback.Stub() {
        override fun onAppAutoLaunched(packageName: String?, appLabel: String?, query: String?, clickedByNode: Boolean) {
            val pkg = packageName ?: return
            val label = appLabel ?: apps.label(pkg)
            val q = query.orEmpty()
            currentForegroundPkg = pkg
            launchCooldownUntilElapsed = SystemClock.elapsedRealtime() + 1_500L
            mainHandler.post {
                _state.update {
                    it.copy(
                        lastLaunchedLabel = label,
                        lastLaunchedQuery = q,
                        watchingLauncher = false,
                    )
                }
                if (!clickedByNode) {
                    Launch.openApp(app, pkg)
                }
                val s = settings.state.value
                if (s.searchAutoLaunchFeedback) {
                    if (s.haptics) performQuickTick()
                    postFeedbackPill(pkg, label)
                }
            }
        }

        override fun onStatusChanged(status: String?) {
            val st = status.orEmpty()
            if (st.startsWith("idle:app_switched:")) {
                currentForegroundPkg = st.substringAfter("idle:app_switched:")
            }
            mainHandler.post {
                _state.update {
                    it.copy(
                        remoteStatus = st,
                        watchingLauncher = st == "watching",
                    )
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindingInProgress = false
            val svc = ISearchAutoLaunchService.Stub.asInterface(binder)
            remoteService = svc
            _state.update { it.copy(serviceBound = svc != null) }
            if (svc != null) {
                scope.launch {
                    syncServiceConfiguration(svc, forceApps = true)
                    val s = settings.state.value
                    if (s.searchAutoLaunchEnabled && isLauncherOrFinder(currentForegroundPkg) && isInteractiveUnlocked()) {
                        runCatching { svc.setWatching(true) }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bindingInProgress = false
            remoteService = null
            _state.update { it.copy(serviceBound = false, watchingLauncher = false, remoteStatus = "disconnected") }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    stopPollingAndDisconnect()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    if (settings.state.value.searchAutoLaunchEnabled) {
                        startPollingIfEnabled()
                    }
                }
            }
        }
    }

    init {
        scope.launch {
            settings.flow
                .map {
                    listOf(
                        it.searchAutoLaunchEnabled,
                        it.searchAutoLaunchDelayMs,
                        it.searchAutoLaunchMinChars,
                        it.searchAutoLaunchSkipWhenDeleting,
                        it.searchAutoLaunchFeedback,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    applySettings(settings.state.value)
                }
        }
    }

    fun refreshStatus() {
        val shizukuInstalled = OnPhoneGranter.isShizukuInstalled(app)
        val shizukuRunning = OnPhoneGranter.isShizukuRunning()
        val shizukuPermitted = OnPhoneGranter.canUseShizuku()
        val usageGranted = ForegroundAppMonitor.hasUsageAccess(app)
        val s = settings.state.value

        _state.update {
            it.copy(
                enabled = s.searchAutoLaunchEnabled,
                shizukuInstalled = shizukuInstalled,
                shizukuRunning = shizukuRunning,
                shizukuPermitted = shizukuPermitted,
                usageAccessGranted = usageGranted,
                serviceBound = remoteService != null,
            )
        }

        if (s.searchAutoLaunchEnabled) {
            applySettings(s)
        }
    }

    fun requestPermissions(context: Context, onComplete: () -> Unit = {}) {
        if (OnPhoneGranter.isShizukuRunning()) {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    OnPhoneGranter.tryAutoGrantBiometricPermissions(app)
                    mainHandler.postDelayed({
                        refreshStatus()
                        onComplete()
                    }, 350L)
                } else {
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode != SHIZUKU_REQ_CODE) return
                            Shizuku.removeRequestPermissionResultListener(this)
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                OnPhoneGranter.tryAutoGrantBiometricPermissions(app)
                            }
                            mainHandler.postDelayed({
                                refreshStatus()
                                onComplete()
                            }, 350L)
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    Shizuku.requestPermission(SHIZUKU_REQ_CODE)
                }
                return
            } catch (_: Throwable) {
            }
        }
        OnPhoneGranter.grantOnPhone(context) {
            mainHandler.postDelayed({
                refreshStatus()
                onComplete()
            }, 350L)
        }
    }

    private fun applySettings(s: IslandSettings) {
        _state.update {
            it.copy(
                enabled = s.searchAutoLaunchEnabled,
                shizukuInstalled = OnPhoneGranter.isShizukuInstalled(app),
                shizukuRunning = OnPhoneGranter.isShizukuRunning(),
                shizukuPermitted = OnPhoneGranter.canUseShizuku(),
                usageAccessGranted = ForegroundAppMonitor.hasUsageAccess(app),
            )
        }

        if (!s.searchAutoLaunchEnabled) {
            stopEverything()
            return
        }

        if (OnPhoneGranter.canUseShizuku() && !ForegroundAppMonitor.hasUsageAccess(app)) {
            OnPhoneGranter.tryAutoGrantBiometricPermissions(app)
        }

        registerScreenReceiver()
        isScreenInteractive = powerManager?.isInteractive ?: true

        remoteService?.let { svc ->
            scope.launch { syncServiceConfiguration(svc, forceApps = false) }
        }

        if (isScreenInteractive) {
            startPollingIfEnabled()
        }
    }

    private fun startPollingIfEnabled() {
        val s = settings.state.value
        if (!s.searchAutoLaunchEnabled) return
        if (pollJob?.isActive == true) return

        pollJob = scope.launch {
            var lastWatching = false
            while (isActive && settings.state.value.searchAutoLaunchEnabled && isScreenInteractive) {
                val canShizuku = OnPhoneGranter.canUseShizuku()
                val hasUsage = ForegroundAppMonitor.hasUsageAccess(app)
                _state.update {
                    it.copy(
                        shizukuRunning = OnPhoneGranter.isShizukuRunning(),
                        shizukuPermitted = canShizuku,
                        usageAccessGranted = hasUsage,
                    )
                }

                if (!canShizuku || !hasUsage) {
                    if (lastWatching) {
                        runCatching { remoteService?.setWatching(false) }
                        lastWatching = false
                    }
                    delay(2_000L)
                    continue
                }

                val unlocked = isInteractiveUnlocked()
                val fg = if (unlocked) {
                    withContext(Dispatchers.Default) { queryLatestForegroundPackage() }
                } else {
                    null
                }
                if (fg != null) currentForegroundPkg = fg

                val nowElapsed = SystemClock.elapsedRealtime()
                val shouldWatch = unlocked &&
                    nowElapsed >= launchCooldownUntilElapsed &&
                    isLauncherOrFinder(currentForegroundPkg)
                if (shouldWatch) {
                    val svc = remoteService
                    if (svc == null) {
                        ensureServiceBound()
                    } else if (!lastWatching || !runCatching { svc.isConnected }.getOrDefault(false)) {
                        syncServiceConfiguration(svc, forceApps = false)
                        runCatching { svc.setWatching(true) }
                        lastWatching = true
                    }
                } else if (lastWatching) {
                    runCatching { remoteService?.setWatching(false) }
                    lastWatching = false
                }

                delay(if (shouldWatch) 350L else 220L)
            }
        }
    }

    private fun stopPollingAndDisconnect() {
        pollJob?.cancel()
        pollJob = null
        runCatching { remoteService?.setWatching(false) }
        _state.update { it.copy(watchingLauncher = false) }
    }

    private fun stopEverything() {
        stopPollingAndDisconnect()
        unregisterScreenReceiver()
        unbindService()
    }

    private fun ensureServiceBound() {
        if (remoteService != null || bindingInProgress) return
        if (!OnPhoneGranter.canUseShizuku()) return
        bindingInProgress = true
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            bindingInProgress = false
            Diagnostics.w(TAG, "Failed to bind SearchAutoLaunchUserService", t)
            _state.update { it.copy(remoteStatus = "bind_error:${t.javaClass.simpleName}") }
        }
    }

    private fun unbindService() {
        val hadService = remoteService != null || bindingInProgress
        remoteService = null
        bindingInProgress = false
        if (hadService) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        }
        _state.update { it.copy(serviceBound = false, watchingLauncher = false, remoteStatus = "idle") }
    }

    private suspend fun syncServiceConfiguration(svc: ISearchAutoLaunchService, forceApps: Boolean) {
        val s = settings.state.value
        val launchers = resolveLauncherPackages().toTypedArray()
        runCatching {
            svc.setCallback(remoteCallback)
            svc.setTargetLaunchers(launchers)
            svc.configure(
                s.searchAutoLaunchDelayMs,
                s.searchAutoLaunchMinChars,
                s.searchAutoLaunchSkipWhenDeleting,
            )
        }

        val now = SystemClock.elapsedRealtime()
        if (forceApps || now - lastAppsPushElapsed > 60_000L) {
            val installed = apps.launchableApps()
            val pkgs = Array(installed.size) { installed[it].packageName }
            val lbls = Array(installed.size) { installed[it].label }
            runCatching { svc.setInstalledApps(pkgs, lbls) }
            lastAppsPushElapsed = now
        }
    }

    private fun queryLatestForegroundPackage(): String? {
        val mgr = usageStats ?: return null
        val now = System.currentTimeMillis()
        val from = if (lastUsageQueryTime == 0L) now - 60_000L else (lastUsageQueryTime - 1_500L)
        lastUsageQueryTime = now
        val iter = try {
            mgr.queryEvents(from, now)
        } catch (_: SecurityException) {
            return null
        } ?: return null

        val ev = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTs = 0L
        while (iter.hasNextEvent()) {
            iter.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp >= latestTs) {
                latestTs = ev.timeStamp
                latestPkg = ev.packageName
            }
        }
        return latestPkg
    }

    private fun resolveLauncherPackages(): Set<String> {
        val set = LinkedHashSet<String>()
        set.add("com.sec.android.app.launcher")
        set.add("com.samsung.android.app.galaxyfinder")
        set.add("com.google.android.apps.nexuslauncher")
        set.add("com.android.launcher3")
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            app.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.takeIf { it.isNotBlank() && it != "android" }
                ?.let { set.add(it) }
        }
        return set
    }

    private fun isLauncherOrFinder(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in resolveLauncherPackages() ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("galaxyfinder", ignoreCase = true) ||
            (pkg.startsWith("com.samsung.", ignoreCase = true) && pkg.contains("finder", ignoreCase = true))
    }

    private fun isInteractiveUnlocked(): Boolean {
        val interactive = powerManager?.isInteractive ?: true
        val locked = keyguardManager?.isKeyguardLocked ?: false
        return interactive && !locked
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            screenReceiverRegistered = true
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { app.unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun postFeedbackPill(pkg: String, label: String) {
        scope.launch {
            val iconKey = "app:$pkg@96"
            if (!images.contains(iconKey)) {
                val bmp = apps.icon(pkg, 96)
                if (bmp != null) images.put(iconKey, bmp)
            }
            events.post(
                IslandEvent(
                    id = "system:search_launch",
                    source = EventSource.SYSTEM,
                    type = EventType.CONFIRM,
                    timestamp = System.currentTimeMillis(),
                    persistent = false,
                    durationMs = 1_500L,
                    title = label,
                    subtitle = "Mở nhanh từ Tìm kiếm",
                    icon = Glyph.OPEN,
                    iconImage = if (images.contains(iconKey)) ImageRef(iconKey) else null,
                    animation = EntranceAnimation.MORPH,
                    colors = EventColors(accent = 0xFF38BDF8.toInt()),
                )
            )
        }
    }

    private fun performQuickTick() {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    companion object {
        private const val TAG = "SearchAutoLaunch"
        private const val SHIZUKU_REQ_CODE = 2806
    }
}
