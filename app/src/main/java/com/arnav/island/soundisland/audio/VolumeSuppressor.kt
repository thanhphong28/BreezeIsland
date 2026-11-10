package com.arnav.island.soundisland.audio

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.arnav.island.util.Diagnostics
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 100% Root-Free & Accessibility-Free Volume Panel Suppressor for Samsung One UI.
 *
 * Utilizes Shizuku's privileged `android.permission.STATUS_BAR_SERVICE` to invoke
 * `AudioService.setVolumeController(controller)` via Binder IPC.
 *
 * This completely disconnects Samsung SystemUI from AudioService's volume dialog dispatch.
 * Result:
 *  - Pressing hardware volume buttons (+ / -) adjusts device audio normally.
 *  - Samsung's default volume panel is COMPLETELY SUPPRESSED (never created, never shown).
 *  - Only Breeze Sound Island appears!
 */
object VolumeSuppressor {
    private const val TAG = "VolumeSuppressor"

    var isSuppressed: Boolean = false
        private set

    fun canUseShizuku(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /**
     * Suppresses Samsung's stock volume dialog.
     */
    fun suppress(): Boolean {
        if (!Shizuku.pingBinder()) {
            Diagnostics.w(TAG, "Shizuku is not running")
            return false
        }

        return try {
            val audioBinder = SystemServiceHelper.getSystemService("audio") ?: run {
                Diagnostics.w(TAG, "Audio service binder not found in SystemServiceHelper")
                return false
            }
            val wrappedBinder = ShizukuBinderWrapper(audioBinder)

            val iAudioServiceClass = Class.forName("android.media.IAudioService")
            val iAudioServiceStubClass = Class.forName("android.media.IAudioService\$Stub")
            val asInterfaceMethod = iAudioServiceStubClass.getMethod("asInterface", IBinder::class.java)
            val audioService = asInterfaceMethod.invoke(null, wrappedBinder)

            val setVolumeControllerMethod = iAudioServiceClass.methods.firstOrNull {
                it.name == "setVolumeController"
            } ?: run {
                Diagnostics.w(TAG, "setVolumeController not found on IAudioService")
                return false
            }

            // Method 1: Set controller to null to disconnect SystemUI VolumeController
            var success = false
            try {
                setVolumeControllerMethod.invoke(audioService, *arrayOf<Any?>(null))
                success = true
                Log.i(TAG, "Successfully disconnected volume controller with null")
            } catch (t: Throwable) {
                Diagnostics.w(TAG, "Disconnect with null failed, trying dummy controller", t)
            }

            // Method 2: If null was rejected, pass a silent dummy controller
            if (!success) {
                val iVolumeControllerClass = try {
                    Class.forName("android.media.IVolumeController")
                } catch (_: Throwable) {
                    null
                }

                val dummyBinder = object : Binder() {
                    init {
                        attachInterface(null, "android.media.IVolumeController")
                    }

                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        return true
                    }
                }

                val controllerArg: Any? = if (iVolumeControllerClass != null) {
                    Proxy.newProxyInstance(
                        iVolumeControllerClass.classLoader,
                        arrayOf(iVolumeControllerClass, IBinder::class.java),
                        object : InvocationHandler {
                            override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                                return when (method.name) {
                                    "asBinder" -> dummyBinder
                                    "equals" -> proxy === args?.getOrNull(0)
                                    "hashCode" -> dummyBinder.hashCode()
                                    "toString" -> "BreezeSilentVolumeController"
                                    else -> null
                                }
                            }
                        }
                    )
                } else {
                    null
                }

                setVolumeControllerMethod.invoke(audioService, controllerArg)
                success = true
                Log.i(TAG, "Successfully connected silent dummy volume controller")
            }

            isSuppressed = true
            Log.i(TAG, "Successfully suppressed Samsung volume panel via Shizuku!")
            true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to suppress volume panel via Shizuku", t)
            false
        }
    }

    /**
     * Restores Samsung SystemUI's volume panel by restarting SystemUI (takes ~0.5s seamlessly).
     */
    fun restore(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val cmd = "pkill -f com.android.systemui || killall com.android.systemui || kill $(pidof com.android.systemui)"
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            process.waitFor()
            isSuppressed = false
            Log.i(TAG, "Restored Samsung volume panel")
            true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to restore volume panel", t)
            false
        }
    }
}
