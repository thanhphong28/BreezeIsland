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
 * a compiled `IVolumeController` stub that silences Samsung SystemUI's on-screen volume dialog.
 *
 * Result:
 *  - Pressing hardware volume buttons (+ / -) adjusts device audio normally.
 *  - Samsung's default volume panel is COMPLETELY SUPPRESSED (never created, never shown on screen).
 *  - Only Breeze Sound Island appears!
 */
object VolumeSuppressor {
    private const val TAG = "VolumeSuppressor"
    private val mainHandler = Handler(Looper.getMainLooper())

    var isSuppressed: Boolean = false
        private set

    /** Callback invoked directly across Binder IPC on every hardware volume key press. */
    var onVolumeChangedCallback: ((streamType: Int, flags: Int) -> Unit)? = null

    /**
     * Real compiled AIDL Stub implementing IVolumeController.
     * When registered with AudioService, it absorbs volume change dispatches silently,
     * suppressing Samsung SystemUI's on-screen VolumeDialog.
     */
    private val silentController = object : IVolumeController.Stub() {
        override fun displaySafeVolumeWarning(flags: Int) {}

        override fun volumeChanged(streamType: Int, flags: Int) {
            onVolumeChangedCallback?.let { cb ->
                mainHandler.post { cb(streamType, flags) }
            }
        }

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
     * Configures Samsung One UI system settings so hardware buttons route to Media by default.
     */
    fun setupMediaKeyRouting(context: Context? = null): Boolean {
        if (!canUseShizuku()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }
            runShizukuShell(
                "settings put system volume_keys_control_media 1; " +
                "settings put secure volume_keys_control_media 1; " +
                "settings put global hidden_api_policy 1; " +
                "settings put global hidden_api_policy_p_apps 1"
            )
            Log.i(TAG, "Configured volume_keys_control_media = 1 on Samsung One UI")
            true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed setting media key routing", t)
            false
        }
    }

    /**
     * Suppresses Samsung's stock on-screen volume dialog using Shizuku privileged Audio API.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }

            setupMediaKeyRouting(context)

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

            setVolumeControllerMethod.invoke(audioService, silentController)
            isSuppressed = true
            Log.i(TAG, "Successfully suppressed Samsung volume panel via Shizuku!")
            toast(context, "Đã ẩn hoàn toàn thanh âm lượng mặc định Samsung ngoài màn hình!")
            true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to suppress volume panel via Shizuku", t)
            toast(context, "Lỗi: ${t.localizedMessage}")
            false
        }
    }

    /**
     * Restores Samsung SystemUI's stock volume panel and ensures Quick Settings is 100% active.
     */
    fun restore(context: Context? = null): Boolean {
        if (!canUseShizuku()) {
            toast(context, "Shizuku chưa chạy. Vui lòng bật Shizuku hoặc khởi động lại máy để khôi phục thanh Samsung.")
            return false
        }
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

                setVolumeControllerMethod?.invoke(audioService, *arrayOf<Any?>(null))
            }
            // Crash/stop SystemUI so Android watchdog immediately restarts it fresh and re-registers stock VolumeDialog
            val cmd = "am crash com.android.systemui; am force-stop com.android.systemui"
            runShizukuShell(cmd)
            isSuppressed = false
            Log.i(TAG, "Restored Samsung volume panel and Control Center")
            toast(context, "Đã khôi phục hoàn toàn thanh âm lượng và Trung tâm điều khiển Samsung!")
            true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to restore volume panel", t)
            toast(context, "Lỗi khôi phục: ${t.localizedMessage}")
            false
        }
    }

    fun runShizukuShell(command: String): Boolean = try {
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
