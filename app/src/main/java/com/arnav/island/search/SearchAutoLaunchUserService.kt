package com.arnav.island.search

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Shizuku UserService (runs in UID 2000 shell process, ZERO AccessibilityService in Manifest,
 * ZERO entry in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).
 *
 * IMPORTANT threading invariant:
 *  - [callbackThread] supplies the [Looper] exclusively to [UiAutomation] so `UiAutomation.connect()`
 *    can complete its Binder `IAccessibilityServiceClient.init()` handshake without ever deadlocking.
 *  - [workerThread] runs `connect()`, `disconnect()`, and UI-tree evaluation off [callbackThread].
 */
class SearchAutoLaunchUserService : ISearchAutoLaunchService.Stub() {

    private val lock = Any()
    private val callbackThread = HandlerThread("BreezeUiAutoCb").apply { start() }
    private val workerThread = HandlerThread("BreezeSearchWorker").apply { start() }
    private val handler = Handler(workerThread.looper)

    @Volatile
    private var callback: ISearchAutoLaunchCallback? = null

    @Volatile
    private var targetLaunchers: Set<String> = DEFAULT_LAUNCHERS

    @Volatile
    private var installedApps: List<AppEntry> = emptyList()

    @Volatile
    private var labelIndex: Map<String, List<AppEntry>> = emptyMap()

    @Volatile
    private var delayMs: Long = 220L

    @Volatile
    private var minChars: Int = 2

    @Volatile
    private var skipWhenDeleting: Boolean = true

    @Volatile
    private var watching: Boolean = false

    @Volatile
    private var lastStatus: String = "idle"

    private var uiAutomation: UiAutomation? = null
    private var connected: Boolean = false

    // Typing / launch state accessed exclusively on workerThread
    private var lastNormQuery: String = ""
    private var isDeleting: Boolean = false
    private var pendingQuery: String = ""
    private var pendingPkg: String = ""
    private var lastLaunchedQuery: String = ""
    private var lastLaunchElapsed: Long = 0L

    private val evalRunnable = Runnable { evaluateWindow(confirmPending = false) }
    private val confirmLaunchRunnable = Runnable { evaluateWindow(confirmPending = true) }
    private val postLaunchDisconnectRunnable = Runnable {
        synchronized(lock) {
            disconnectLocked("auto_launched")
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    override fun destroy() {
        handler.post {
            synchronized(lock) {
                watching = false
                disconnectLocked("destroyed")
            }
            workerThread.quitSafely()
            callbackThread.quitSafely()
            exitProcess(0)
        }
    }

    override fun setCallback(cb: ISearchAutoLaunchCallback?) {
        callback = cb
    }

    override fun setTargetLaunchers(launcherPackages: Array<out String>?) {
        if (launcherPackages == null || launcherPackages.isEmpty()) return
        targetLaunchers = (DEFAULT_LAUNCHERS + launcherPackages.filter { it.isNotBlank() }).toSet()
    }

    override fun setInstalledApps(packages: Array<out String>?, labels: Array<out String>?) {
        if (packages == null || labels == null) return
        val count = minOf(packages.size, labels.size)
        val entries = ArrayList<AppEntry>(count)
        for (i in 0 until count) {
            val pkg = packages[i]
            val lbl = labels[i]
            if (pkg.isNotBlank() && lbl.isNotBlank()) {
                entries.add(AppEntry(pkg, lbl))
            }
        }
        installedApps = entries
        labelIndex = SearchMatchEngine.buildIndex(entries)
    }

    override fun configure(delayMs: Long, minChars: Int, skipWhenDeleting: Boolean) {
        this.delayMs = delayMs.coerceIn(0L, 1_000L)
        this.minChars = minChars.coerceIn(1, 4)
        this.skipWhenDeleting = skipWhenDeleting
    }

    override fun setWatching(watching: Boolean) {
        this.watching = watching
        handler.post {
            synchronized(lock) {
                if (watching) {
                    connectLocked()
                } else {
                    disconnectLocked("stopped")
                }
            }
        }
    }

    override fun isConnected(): Boolean = connected

    override fun getLastStatus(): String = lastStatus

    private fun updateStatus(status: String) {
        lastStatus = status
        runCatching { callback?.onStatusChanged(status) }
    }

    private fun connectLocked() {
        if (connected && uiAutomation != null) return
        handler.removeCallbacks(postLaunchDisconnectRunnable)
        try {
            // Pass callbackThread.looper while connect() executes on workerThread -> zero deadlock!
            val uiAuto = createUiAutomation(callbackThread.looper)
            invokeConnect(uiAuto)

            val info = runCatching { uiAuto.serviceInfo }.getOrNull() ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 35L
            info.packageNames = null
            runCatching { uiAuto.serviceInfo = info }

            uiAuto.setOnAccessibilityEventListener { event ->
                onEvent(event)
            }

            uiAutomation = uiAuto
            connected = true
            resetTypingState()
            updateStatus("watching")
            handler.postDelayed(evalRunnable, 60L)
        } catch (t: Throwable) {
            connected = false
            uiAutomation = null
            val cause = t.cause ?: t
            updateStatus("error:${cause.javaClass.simpleName}:${cause.message.orEmpty().take(90)}")
        }
    }

    private fun disconnectLocked(reason: String) {
        handler.removeCallbacks(evalRunnable)
        handler.removeCallbacks(confirmLaunchRunnable)
        handler.removeCallbacks(postLaunchDisconnectRunnable)
        val uiAuto = uiAutomation
        uiAutomation = null
        connected = false
        resetTypingState()
        if (uiAuto != null) {
            runCatching { uiAuto.setOnAccessibilityEventListener(null) }
            runCatching { invokeDisconnect(uiAuto) }
        }
        updateStatus("idle:$reason")
    }

    private fun resetTypingState() {
        lastNormQuery = ""
        isDeleting = false
        pendingQuery = ""
        pendingPkg = ""
    }

    private fun onEvent(event: AccessibilityEvent?) {
        if (event == null || !watching || !connected) return
        val pkg = event.packageName?.toString() ?: return

        // Safety gate: immediately disconnect if foreground window switched to a non-launcher app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isTargetLauncher(pkg) && !isImeOrSystemChrome(pkg)) {
                handler.post {
                    synchronized(lock) {
                        if (connected) disconnectLocked("app_switched:$pkg")
                    }
                }
                return
            }
        }

        if (!isTargetLauncher(pkg)) return

        handler.removeCallbacks(evalRunnable)
        val coalesceMs = if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) 25L else 55L
        handler.postDelayed(evalRunnable, coalesceMs)
    }

    private fun evaluateWindow(confirmPending: Boolean) {
        val uiAuto = synchronized(lock) { if (watching && connected) uiAutomation else null } ?: return
        val apps = installedApps
        if (apps.isEmpty()) return

        val roots = findLauncherRoots(uiAuto)
        if (roots.isEmpty()) return

        var decision: SearchDecision = SearchDecision.NoSearchActive
        var bestLiveNodes: List<AccessibilityNodeInfo> = emptyList()

        for (root in roots) {
            val liveNodes = ArrayList<AccessibilityNodeInfo>(128)
            val snapshots = ArrayList<UiNodeSnapshot>(128)
            flattenTree(root, ancestorClickable = false, liveNodes, snapshots, maxNodes = 520)

            val candidate = SearchMatchEngine.evaluateSearchScreen(
                nodes = snapshots,
                installedApps = apps,
                labelIndex = labelIndex,
                minChars = minChars,
            )
            if (candidate != SearchDecision.NoSearchActive) {
                decision = candidate
                bestLiveNodes = liveNodes
                break
            }
        }

        when (decision) {
            SearchDecision.NoSearchActive, SearchDecision.EmptyQuery -> {
                handler.removeCallbacks(confirmLaunchRunnable)
                resetTypingState()
                lastLaunchedQuery = ""
            }
            is SearchDecision.QueryTooShort -> {
                handler.removeCallbacks(confirmLaunchRunnable)
                val norm = SearchMatchEngine.normalize(decision.query)
                if (norm.length < lastNormQuery.length) isDeleting = true
                else if (norm.length > lastNormQuery.length) isDeleting = false
                lastNormQuery = norm
                pendingQuery = ""
                pendingPkg = ""
            }
            is SearchDecision.NoMatches, is SearchDecision.MultipleMatches -> {
                handler.removeCallbacks(confirmLaunchRunnable)
                val q = when (decision) {
                    is SearchDecision.NoMatches -> decision.query
                    is SearchDecision.MultipleMatches -> decision.query
                    else -> ""
                }
                val norm = SearchMatchEngine.normalize(q)
                if (norm.length < lastNormQuery.length) isDeleting = true
                else if (norm.length > lastNormQuery.length) isDeleting = false
                lastNormQuery = norm
                pendingQuery = ""
                pendingPkg = ""
            }
            is SearchDecision.SingleMatch -> {
                val query = decision.query
                val norm = SearchMatchEngine.normalize(query)
                if (norm.length < lastNormQuery.length) {
                    isDeleting = true
                } else if (norm.length > lastNormQuery.length) {
                    isDeleting = false
                }
                lastNormQuery = norm

                if (skipWhenDeleting && isDeleting) {
                    handler.removeCallbacks(confirmLaunchRunnable)
                    pendingQuery = ""
                    pendingPkg = ""
                    return
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastLaunchElapsed < LAUNCH_COOLDOWN_MS) return
                if (norm == lastLaunchedQuery && now - lastLaunchElapsed < SAME_QUERY_COOLDOWN_MS) return

                val matched = decision.match
                val effectiveDelay = delayMs
                if (effectiveDelay > 0L && (!confirmPending || pendingQuery != norm || pendingPkg != matched.app.packageName)) {
                    pendingQuery = norm
                    pendingPkg = matched.app.packageName
                    handler.removeCallbacks(confirmLaunchRunnable)
                    handler.postDelayed(confirmLaunchRunnable, effectiveDelay)
                    return
                }

                handler.removeCallbacks(confirmLaunchRunnable)
                pendingQuery = ""
                pendingPkg = ""
                lastLaunchedQuery = norm
                lastLaunchElapsed = now

                val clicked = performClickOnMatch(bestLiveNodes.getOrNull(matched.nodeIndex))
                if (!clicked) {
                    launchViaShell(matched.app.packageName)
                }
                updateStatus("launched:${matched.app.label}")
                runCatching {
                    callback?.onAppAutoLaunched(matched.app.packageName, matched.app.label, query, clicked)
                }

                handler.removeCallbacks(postLaunchDisconnectRunnable)
                handler.postDelayed(postLaunchDisconnectRunnable, 140L)
            }
        }
    }

    private fun performClickOnMatch(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable) {
                if (runCatching { cur.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                    return true
                }
            }
            cur = runCatching { cur.parent }.getOrNull()
            hops++
        }
        return false
    }

    private fun launchViaShell(packageName: String) {
        runCatching {
            Runtime.getRuntime().exec(
                arrayOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
            )
        }
    }

    /**
     * Returns launcher/finder window roots ordered topmost-first so an open Samsung Finder popup
     * window is always inspected before the underlying Home Screen window.
     */
    private fun findLauncherRoots(uiAuto: UiAutomation): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(3)
        val active = runCatching { uiAuto.rootInActiveWindow }.getOrNull()
        val activePkg = active?.packageName?.toString()
        if (active != null && activePkg != null && isTargetLauncher(activePkg)) {
            result.add(active)
        }

        val windows = runCatching { uiAuto.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }

        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (isTargetLauncher(pkg) && result.none { it == root }) {
                result.add(root)
            }
        }
        return result
    }

    private fun flattenTree(
        node: AccessibilityNodeInfo,
        ancestorClickable: Boolean,
        liveOut: MutableList<AccessibilityNodeInfo>,
        snapOut: MutableList<UiNodeSnapshot>,
        maxNodes: Int,
    ) {
        if (liveOut.size >= maxNodes) return
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val nodeClickable = node.isClickable
        val actionable = nodeClickable || ancestorClickable
        val idx = liveOut.size
        liveOut.add(node)
        snapOut.add(
            UiNodeSnapshot(
                nodeIndex = idx,
                text = node.text?.toString().orEmpty(),
                contentDesc = node.contentDescription?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                isFocused = node.isFocused,
                isEditable = node.isEditable,
                isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.isShowingHintText else false,
                hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else "",
                isClickable = nodeClickable,
                isActionable = actionable,
                isVisible = node.isVisibleToUser,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            )
        )
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until childCount) {
            if (liveOut.size >= maxNodes) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            flattenTree(child, actionable, liveOut, snapOut, maxNodes)
        }
    }

    private fun isTargetLauncher(pkg: String): Boolean {
        if (pkg in targetLaunchers) return true
        val p = pkg.lowercase(Locale.ROOT)
        return p.contains("galaxyfinder") || (p.startsWith("com.samsung.") && p.contains("finder"))
    }

    private fun isImeOrSystemChrome(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return p == "android" ||
            p == "com.android.systemui" ||
            p.contains("honeyboard") ||
            p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("gboard") ||
            p.contains("swiftkey") ||
            p.contains("labankey") ||
            p.contains("unikey")
    }

    private fun createUiAutomation(looper: Looper): UiAutomation {
        val connClass = Class.forName("android.app.UiAutomationConnection")
        val conn = connClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val iConnClass = Class.forName("android.app.IUiAutomationConnection")

        val ctorWithDisplay = runCatching {
            UiAutomation::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Looper::class.java,
                iConnClass,
            ).apply { isAccessible = true }
        }.getOrNull()

        if (ctorWithDisplay != null) {
            return ctorWithDisplay.newInstance(Display.DEFAULT_DISPLAY, looper, conn)
        }

        val ctorLegacy = UiAutomation::class.java.getDeclaredConstructor(
            Looper::class.java,
            iConnClass,
        ).apply { isAccessible = true }
        return ctorLegacy.newInstance(looper, conn)
    }

    private fun invokeConnect(uiAuto: UiAutomation) {
        val withFlags = runCatching {
            UiAutomation::class.java.getDeclaredMethod("connect", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
        if (withFlags != null) {
            withFlags.invoke(uiAuto, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            return
        }
        UiAutomation::class.java.getDeclaredMethod("connect").apply { isAccessible = true }.invoke(uiAuto)
    }

    private fun invokeDisconnect(uiAuto: UiAutomation) {
        UiAutomation::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(uiAuto)
    }

    companion object {
        private const val LAUNCH_COOLDOWN_MS = 1_600L
        private const val SAME_QUERY_COOLDOWN_MS = 2_800L

        private val DEFAULT_LAUNCHERS = setOf(
            "com.sec.android.app.launcher",
            "com.samsung.android.app.galaxyfinder",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.teslacoilsw.launcher",
            "com.microsoft.launcher",
            "app.lawnchair",
            "ch.deletescape.lawnchair.plah",
            "com.miui.home",
            "com.oppo.launcher",
        )
    }
}
