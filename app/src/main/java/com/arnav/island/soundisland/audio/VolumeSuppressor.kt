package com.arnav.island.soundisland.audio

import android.content.Context
import android.media.IVolumeController
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.arnav.island.util.Diagnostics
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 100% Root-Free & Accessibility-Free Volume Panel Suppressor for Samsung One UI.
 *
 * Utilizes Shizuku's privileged `android.permission.STATUS_BAR_SERVICE` to register
 * a compiled `IVolumeController` stub that intercepts and silences Samsung SystemUI's
 * volume dialog dispatch.
 *
 * Result:
 *  - Pressing hardware volume buttons (+ / -) adjusts device audio normally.
 *  - Samsung's default volume panel is COMPLETELY SUPPRESSED (never created, never shown).
 *  - Only Breeze Sound Island appears!
 */
object VolumeSuppressor {
    private const val TAG = "VolumeSuppressor"
    private val mainHandler = Handler(Looper.getMainLooper())

    var isSuppressed: Boolean = false
        private set

    /**
     * Real compiled AIDL Stub implementing IVolumeController.
     * When registered with AudioService, it absorbs volume change dispatches silently,
     * completely replacing Samsung SystemUI's VolumeDialog.
     */
    private val silentController = object : IVolumeController.Stub() {
        override fun displaySafeVolumeWarning(flags: Int) {}
        override fun volumeChanged(streamType: Int, flags: Int) {}
        override fun masterMuteChanged(flags: Int) {}
        override fun setLayoutDirection(layoutDirection: Int) {}
        override fun dismiss() {}
        override fun setA11yMode(a11yMode: Int) {}
        override fun displayCsdWarning(csdWarning: Int, displayDurationMs: Int) {}
    }

    fun canUseShizuku(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Suppresses Samsung's stock volume dialog.
     */
    fun suppress(context: Context? = null): Boolean {
        if (!Shizuku.pingBinder()) {
            Diagnostics.w(TAG, "Shizuku is not running")
            toast(context, "Shizuku chưa chạy!")
            return false
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Diagnostics.w(TAG, "Shizuku permission not granted")
            toast(context, "Chưa cấp quyền Shizuku cho BreezeIsland!")
            return false
        }

        return try {
            // 1. Bypass Hidden API restrictions for this process
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }

            // 2. Relax Hidden API policy system-wide via Shizuku shell
            runShizukuShell("settings put global hidden_api_policy 1; settings put global hidden_api_policy_p_apps 1")

            // 3. Obtain audio system binder via Shizuku privileged wrapper
            val audioBinder = SystemServiceHelper.getSystemService("audio") ?: run {
                Diagnostics.w(TAG, "Audio service binder not found in SystemServiceHelper")
                toast(context, "Không tìm thấy AudioService!")
                return false
            }
            val wrappedBinder = ShizukuBinderWrapper(audioBinder)

            val iAudioServiceStubClass = Class.forName("android.media.IAudioService\$Stub")
            val asInterfaceMethod = iAudioServiceStubClass.getMethod("asInterface", IBinder::class.java)
            val audioService = asInterfaceMethod.invoke(null, wrappedBinder)

            val iAudioServiceClass = Class.forName("android.media.IAudioService")
            val setVolumeControllerMethod = iAudioServiceClass.methods.firstOrNull {
                it.name == "setVolumeController"
            } ?: run {
                Diagnostics.w(TAG, "setVolumeController not found on IAudioService")
                toast(context, "Không tìm thấy hàm setVolumeController!")
                return false
            }

            // 4. Register our compiled silentController stub with AudioService
            var registered = false
            try {
                setVolumeControllerMethod.invoke(audioService, silentController)
                registered = true
                Log.i(TAG, "Successfully registered compiled silentController with AudioService!")
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "Failed to register silentController stub, trying null", t)
                try {
                    setVolumeControllerMethod.invoke(audioService, *arrayOf<Any?>(null))
                    registered = true
                    Log.i(TAG, "Successfully disconnected volume controller with null")
                } catch (t2: Throwable) {
                    Diagnostics.w(TAG, "Both silentController and null failed", t2)
                }
            }

            if (registered) {
                isSuppressed = true
                Log.i(TAG, "Successfully suppressed Samsung volume panel via Shizuku!")
                toast(context, "Đã ẩn thành công thanh âm lượng mặc định Samsung!")
                true
            } else {
                toast(context, "Không thể đăng ký bộ điều khiển âm lượng!")
                false
            }
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to suppress volume panel via Shizuku", t)
            toast(context, "Lỗi: ${t.localizedMessage}")
            false
        }
    }

    /**
     * Restores Samsung SystemUI's volume panel by restarting SystemUI (takes ~0.5s seamlessly).
     */
    fun restore(context: Context? = null): Boolean {
        if (!Shizuku.pingBinder()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }
            val audioBinder = SystemServiceHelper.getSystemService("audio")
            if (audioBinder != null) {
                val wrappedBinder = ShizukuBinderWrapper(audioBinder)
                val iAudioServiceStubClass = Class.forName("android.media.IAudioService\$Stub")
                val asInterfaceMethod = iAudioServiceStubClass.getMethod("asInterface", IBinder::class.java)
                val audioService = asInterfaceMethod.invoke(null, wrappedBinder)
                val iAudioServiceClass = Class.forName("android.media.IAudioService")
                val setVolumeControllerMethod = iAudioServiceClass.methods.firstOrNull { it.name == "setVolumeController" }
                runCatching { setVolumeControllerMethod?.invoke(audioService, *arrayOf<Any?>(null)) }
            }
            val cmd = "pkill -f com.android.systemui || killall com.android.systemui || kill $(pidof com.android.systemui)"
            runShizukuShell(cmd)
            isSuppressed = false
            Log.i(TAG, "Restored Samsung volume panel")
            toast(context, "Đã khôi phục thanh âm lượng mặc định Samsung")
            true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to restore volume panel", t)
            false
        }
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
    } catch (_: Throwable) {
        false
    }

    private fun toast(context: Context?, msg: String) {
        if (context == null) return
        mainHandler.post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
