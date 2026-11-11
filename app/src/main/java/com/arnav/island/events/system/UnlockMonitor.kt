package com.arnav.island.events.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
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
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Monitors lockscreen dismissal and device unlocks to trigger iOS-style Dynamic Island
 * unlock animations tailored for Face Unlock, Fingerprint, or Password/PIN/Pattern.
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var frontCameraId: String? = null
    private var registered = false

    private var screenOnTime = 0L
    private var screenOffTime = 0L
    private var frontCameraUsedRecentlyAt = 0L
    private var isScreenInteractive = false

    // Camera availability callback to detect Face Unlock front camera usage
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId == frontCameraId && (!isScreenInteractive || (SystemClock.elapsedRealtime() - screenOnTime in 0..3500L))) {
                frontCameraUsedRecentlyAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    screenOffTime = now
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    screenOnTime = now
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    onDeviceUnlocked(now)
                }
            }
        }
    }

    init {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    break
                }
            }
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to resolve front camera ID", t)
        }
    }

    fun start() {
        if (registered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            cameraManager.registerAvailabilityCallback(cameraCallback, handler)
            registered = true
        } catch (t: Throwable) {
            Diagnostics.w(TAG, "Failed to start UnlockMonitor", t)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
            cameraManager.unregisterAvailabilityCallback(cameraCallback)
        } catch (_: Exception) {}
        registered = false
    }

    private fun onDeviceUnlocked(now: Long) {
        val s = settings()
        if (!s.enabled || !s.unlockAnimationEnabled) return

        val methodPref = s.unlockMethodPreference
        val method = when {
            methodPref == "face" -> UnlockMethod.FACE
            methodPref == "fingerprint" -> UnlockMethod.FINGERPRINT
            methodPref == "password" -> UnlockMethod.PASSWORD
            else -> detectUnlockMethod(now)
        }

        val event = buildUnlockEvent(method, s.unlockDurationMs)
        scope.launch {
            events.post(event)
        }
    }

    private fun detectUnlockMethod(now: Long): UnlockMethod {
        val frontCameraUsedRecently = (now - frontCameraUsedRecentlyAt) in 0..2500L
        if (frontCameraUsedRecently) {
            return UnlockMethod.FACE
        }
        val timeSinceScreenOn = now - screenOnTime
        // Fingerprint: instant unlock from screen off, or unlocked quickly (< 800ms) upon waking
        return if (timeSinceScreenOn < 800L || screenOnTime <= screenOffTime) {
            UnlockMethod.FINGERPRINT
        } else {
            // User took time to swipe up and type PIN or draw pattern
            UnlockMethod.PASSWORD
        }
    }

    fun buildUnlockEvent(method: UnlockMethod, durationMs: Long): IslandEvent {
        val (glyph, title, text, color) = when (method) {
            UnlockMethod.FACE -> Quad(Glyph.FACE_ID, "Face ID", "Mở khóa bằng gương mặt", 0xFF30D158.toInt())
            UnlockMethod.FINGERPRINT -> Quad(Glyph.FINGERPRINT, "Vân tay", "Đã mở khóa bằng vân tay", 0xFF0A84FF.toInt())
            UnlockMethod.PASSWORD -> Quad(Glyph.LOCK_OPEN, "Đã mở khóa", "Mở khóa bằng mật khẩu", 0xFFFFD60A.toInt())
        }

        return IslandEvent(
            id = EVENT_ID,
            source = EventSource.SYSTEM,
            type = EventType.UNLOCK,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = durationMs,
            title = title,
            subtitle = text,
            icon = glyph,
            payload = UnlockPayload(method, title, text),
            animation = EntranceAnimation.MORPH,
            colors = EventColors(accent = color),
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
