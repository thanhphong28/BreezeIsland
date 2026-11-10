package com.arnav.island.soundisland.audio

import android.content.Context
import android.media.AudioManager
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
 * a transparent compiled `IVolumeController` proxy that intercepts Samsung SystemUI's
 * volume dialog dispatch.
 *
 * Result:
 *  - Strips `FLAG_SHOW_UI` from hardware key dispatches to Samsung SystemUI, completely
 *    suppressing the stock on-screen floating volume dialog.
 *  - Forwards volume state events so Samsung's Control Center / Quick Settings volume slider
 *    remains 100% FUNCTIONAL and responsive.
 *  - Zero accessibility services (safe for banking and government apps).
 */
object VolumeSuppressor {
    private const val TAG = "VolumeSuppressor"
    private val mainHandler = Handler(Looper.getMainLooper())

    var isSuppressed: Boolean = false
        private set

    /** Callback invoked directly across Binder IPC on every hardware volume key press. */
    var onVolumeChangedCallback: ((streamType: Int, flags: Int) -> Unit)? = null

    /** Real underlying Samsung SystemUI volume controller. */
    @Volatile
    private var underlyingController: IVolumeController? = null

    /**
     * Transparent proxy controller implementing IVolumeController.
     * Forwards volume changes to Samsung SystemUI without FLAG_SHOW_UI so that:
     * 1. Samsung's stock on-screen volume panel is NEVER shown.
     * 2. Samsung's Quick Settings / Control Center volume slider continues to work 100%!
     */
    private val proxyController = object : IVolumeController.Stub() {
        override fun displaySafeVolumeWarning(flags: Int) {
            runCatching { underlyingController?.displaySafeVolumeWarning(flags) }
        }

        override fun volumeChanged(streamType: Int, flags: Int) {
            // 1. Notify Breeze Sound Island
            onVolumeChangedCallback?.let { cb ->
                mainHandler.post { cb(streamType, flags) }
            }

            // 2. Forward to Samsung SystemUI without FLAG_SHOW_UI (0x1)
            // This maintains SystemUI's internal stream levels so the Control Center slider works smoothly,
            // while preventing the floating volume dialog from appearing on the screen.
            val flagsWithoutUI = flags and AudioManager.FLAG_SHOW_UI.inv()
            runCatching { underlyingController?.volumeChanged(streamType, flagsWithoutUI) }
        }

        override fun masterMuteChanged(flags: Int) {
            runCatching { underlyingController?.masterMuteChanged(flags) }
        }

        override fun setLayoutDirection(layoutDirection: Int) {
            runCatching { underlyingController?.setLayoutDirection(layoutDirection) }
        }

        override fun dismiss() {
            runCatching { underlyingController?.dismiss() }
        }

        override fun setA11yMode(a11yMode: Int) {
            runCatching { underlyingController?.setA11yMode(a11yMode) }
        }

        override fun displayCsdWarning(csdWarning: Int, displayDurationMs: Int) {
            runCatching { underlyingController?.displayCsdWarning(csdWarning, displayDurationMs) }
        }
    }

    fun canUseShizuku(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Suppresses Samsung's stock on-screen volume dialog while keeping Quick Settings fully working.
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

            // 2. Ensure Samsung routes hardware keys to Media by default in system & secure settings
            runShizukuShell("settings put global hidden_api_policy 1; settings put global hidden_api_policy_p_apps 1; settings put system volume_keys_control_media 1; settings put secure volume_keys_control_media 1")

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

            val getVolumeControllerMethod = iAudioServiceClass.methods.firstOrNull {
                it.name == "getVolumeController"
            }

            // 4. Retrieve the active SystemUI volume controller
            var activeController: IVolumeController? = null
            if (getVolumeControllerMethod != null) {
                val raw = getVolumeControllerMethod.invoke(audioService)
                activeController = when (raw) {
                    is IVolumeController -> raw
                    is IBinder -> IVolumeController.Stub.asInterface(raw)
                    else -> null
                }
            }

            // If already set to our proxy, we are all good
            if (activeController?.asBinder() == proxyController.asBinder()) {
                isSuppressed = true
                Log.i(TAG, "Proxy volume controller is already active")
                return true
            }

            // If active controller is null (e.g. from previous bad session), restart SystemUI to restore Samsung's controller
            if (activeController == null) {
                Log.i(TAG, "Active controller is null, restarting SystemUI to capture fresh controller...")
                runShizukuShell("am crash com.android.systemui; am force-stop com.android.systemui")
                Thread.sleep(750L) // Wait for SystemUI watchdog to restart and register
                if (getVolumeControllerMethod != null) {
                    val raw = getVolumeControllerMethod.invoke(audioService)
                    activeController = when (raw) {
                        is IVolumeController -> raw
                        is IBinder -> IVolumeController.Stub.asInterface(raw)
                        else -> null
                    }
                }
            }

            if (activeController != null && activeController.asBinder() != proxyController.asBinder()) {
                underlyingController = activeController
                Log.i(TAG, "Captured Samsung SystemUI volume controller: $activeController")
            }

            // 5. Register our transparent proxy controller with AudioService
            setVolumeControllerMethod.invoke(audioService, proxyController)
            isSuppressed = true
            Log.i(TAG, "Successfully attached proxy volume controller! Quick Settings is preserved.")
            toast(context, "Đã ẩn thanh âm lượng ngoài màn hình (Trung tâm điều khiển hoạt động bình thường)")
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
        if (!Shizuku.pingBinder()) {
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

                val original = underlyingController
                if (original != null) {
                    setVolumeControllerMethod?.invoke(audioService, original)
                    Log.i(TAG, "Restored original Samsung SystemUI volume controller directly")
                } else {
                    setVolumeControllerMethod?.invoke(audioService, *arrayOf<Any?>(null))
                    // Restart SystemUI so it registers its controller fresh
                    val cmd = "am crash com.android.systemui; am force-stop com.android.systemui"
                    runShizukuShell(cmd)
                }
            }
            underlyingController = null
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
