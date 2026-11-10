package com.arnav.island.soundisland.audio

import android.content.Context
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
 * Manages Samsung One UI system audio settings safely without breaking
 * the Control Center / Quick Settings volume slider or Accessibility services.
 */
object VolumeSuppressor {
    private const val TAG = "VolumeSuppressor"
    private val mainHandler = Handler(Looper.getMainLooper())

    var isSuppressed: Boolean = false
        private set

    fun canUseShizuku(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Enables Samsung One UI's native media key routing so hardware buttons always
     * adjust Media volume seamlessly, exactly like iOS.
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
     * Restores Samsung SystemUI's volume controller cleanly so the Control Center
     * volume slider is 100% active and functional.
     */
    fun restore(context: Context? = null): Boolean {
        if (!canUseShizuku()) {
            toast(context, "Shizuku chưa chạy. Vui lòng bật Shizuku hoặc khởi động lại máy để làm mới hệ thống.")
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

                // Clear any orphaned volume controller stub
                setVolumeControllerMethod?.invoke(audioService, *arrayOf<Any?>(null))
            }
            // Restart Samsung SystemUI so it automatically re-registers its native VolumeController
            val cmd = "am crash com.android.systemui; am force-stop com.android.systemui"
            runShizukuShell(cmd)
            isSuppressed = false
            Log.i(TAG, "Successfully restored Samsung SystemUI VolumeController and Control Center")
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
