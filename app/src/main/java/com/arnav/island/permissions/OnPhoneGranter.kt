package com.arnav.island.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.arnav.island.IslandApp
import com.arnav.island.overlay.StatusBarCleanup
import com.arnav.island.overlay.SystemPopups
import com.arnav.island.storage.AppLanguage
import com.arnav.island.util.Diagnostics
import rikka.shizuku.Shizuku

/**
 * 100% On-Phone permission granter (Zero PC, Zero Root, Zero AccessibilityService).
 *
 * Allows granting `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, and One UI status bar cleanup
 * directly on the phone screen:
 *  1. Built-in 1-Tap Shizuku popup on the phone (`Shizuku.requestPermission`) -> runs `pm grant`
 *     directly on the phone in 0.1s and saves the permission permanently in Android OS.
 *  2. Direct Android `ACTION_MANAGE_WRITE_SETTINGS` on-phone switch (`Settings.System`).
 */
object OnPhoneGranter {
    private const val TAG = "OnPhoneGranter"
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ_CODE = 2805
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Status {
        ALREADY_GRANTED,
        GRANTED_NOW,
        WAITING_USER_CONFIRM,
        SHIZUKU_NOT_RUNNING,
        SHIZUKU_NOT_INSTALLED,
        ERROR,
    }

    fun isShizukuInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun isShizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasReadLogsPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Silently grants `READ_LOGS` and `PACKAGE_USAGE_STATS` if Shizuku is already running and authorized.
     */
    fun tryAutoGrantBiometricPermissions(context: Context) {
        if (hasReadLogsPermission(context)) return
        if (!isShizukuRunning()) return
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val pkg = context.packageName
                Thread {
                    runShizukuShell(
                        "pm grant $pkg android.permission.READ_LOGS 2>/dev/null; " +
                            "appops set $pkg READ_LOGS allow 2>/dev/null; " +
                            "pm grant $pkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null; " +
                            "appops set $pkg GET_USAGE_STATS allow 2>/dev/null"
                    )
                }.start()
            }
        } catch (_: Throwable) {
        }
    }

    /** Runs a command through Shizuku and returns its Process if Shizuku is running & permitted. */
    fun openShizukuProcess(command: String): Process? = try {
        if (!isShizukuRunning() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            null
        } else {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Grants `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, and `READ_LOGS` directly on the phone with 1 tap.
     */
    fun grantOnPhone(context: Context, onResult: (Status) -> Unit = {}) {
        val appContext = context.applicationContext
        if (StatusBarCleanup.canWrite(appContext) && StatusBarCleanup.canWriteSystem(appContext) && hasReadLogsPermission(appContext)) {
            applyAllSettings(appContext)
            toast(appContext, vi = "Đã kích hoạt đầy đủ quyền hệ thống & nhận diện vân tay!", en = "System & fingerprint detection permissions are active!")
            onResult(Status.ALREADY_GRANTED)
            return
        }

        if (isShizukuRunning()) {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    executeShizukuGrant(appContext, onResult)
                } else {
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode != REQ_CODE) return
                            Shizuku.removeRequestPermissionResultListener(this)
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                executeShizukuGrant(appContext, onResult)
                            } else {
                                onResult(Status.ERROR)
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    Shizuku.requestPermission(REQ_CODE)
                    onResult(Status.WAITING_USER_CONFIRM)
                }
                return
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "Shizuku grant check failed", t)
            }
        }

        // Fallback: If WRITE_SETTINGS is not granted yet, open the native on-phone switch immediately
        if (!StatusBarCleanup.canWriteSystem(appContext)) {
            try {
                context.startActivity(Permissions.writeSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                toast(
                    appContext,
                    vi = "Hãy gạt công tắc Cho phép trên màn hình này để dọn thanh trạng thái ngay trên điện thoại!",
                    en = "Toggle Allow on this screen to clean the status bar directly on your phone!",
                )
            } catch (_: Exception) {
            }
        }

        if (isShizukuInstalled(appContext)) {
            onResult(Status.SHIZUKU_NOT_RUNNING)
        } else {
            onResult(Status.SHIZUKU_NOT_INSTALLED)
        }
    }

    fun openOrInstallShizuku(context: Context) {
        if (isShizukuInstalled(context)) {
            val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
                return
            }
        }
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure { runCatching { context.startActivity(web) } }
    }

    private fun executeShizukuGrant(context: Context, onResult: (Status) -> Unit) {
        Thread {
            val pkg = context.packageName
            val cmd = buildString {
                append("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; ")
                append("pm grant $pkg android.permission.READ_LOGS 2>/dev/null; ")
                append("appops set $pkg READ_LOGS allow 2>/dev/null; ")
                append("pm grant $pkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null; ")
                append("appops set $pkg WRITE_SETTINGS allow 2>/dev/null; ")
                append("appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; ")
                append("appops set $pkg GET_USAGE_STATS allow 2>/dev/null; ")
                append("cmd deviceidle whitelist +$pkg 2>/dev/null; ")
                append("settings put system simple_status_bar 0 2>/dev/null; ")
                append("settings put system status_bar_show_notification_icon 0 2>/dev/null; ")
                append("settings put system status_bar_notif_count 0 2>/dev/null")
            }
            val ok = runShizukuShell(cmd)
            mainHandler.post {
                if (ok || StatusBarCleanup.canWrite(context)) {
                    applyAllSettings(context)
                    toast(
                        context,
                        vi = "Đã cấp quyền thành công ngay trên điện thoại! Dynamic Island đã đè lên trên cùng.",
                        en = "Permission granted directly on phone! Dynamic Island is now on top.",
                    )
                    onResult(Status.GRANTED_NOW)
                } else {
                    onResult(Status.ERROR)
                }
            }
        }.start()
    }

    private fun runShizukuShell(command: String): Boolean = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        process.waitFor() == 0
    } catch (t: Throwable) {
        Diagnostics.w(TAG, "runShizukuShell failed", t)
        false
    }

    private fun applyAllSettings(context: Context) {
        val graph = (context.applicationContext as? IslandApp)?.graph ?: return
        graph.settings.set { s ->
            val updated = s.copy(
                seamlessStatusBar = true,
                statusBarCleanup = true,
                replaceSystemPopups = true,
            )
            SystemPopups.sync(context, updated, islandShowing = updated.enabled)
            StatusBarCleanup.sync(context, updated) ?: updated
        }
    }

    private fun toast(context: Context, vi: String, en: String) {
        val lang = (context.applicationContext as? IslandApp)?.graph?.settings?.state?.value?.language ?: AppLanguage.VIETNAMESE
        Toast.makeText(context, if (lang == AppLanguage.VIETNAMESE) vi else en, Toast.LENGTH_LONG).show()
    }
}
