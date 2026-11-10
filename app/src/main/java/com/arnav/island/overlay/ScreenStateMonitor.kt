package com.arnav.island.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen on/off and lock state. On Samsung Galaxy Z Flip 5 (One UI 9), the side power button
 * doubles as the fingerprint scanner, so [Intent.ACTION_USER_PRESENT] frequently fires a few
 * milliseconds *before* [Intent.ACTION_SCREEN_ON] while [KeyguardManager.isKeyguardLocked] is
 * still transitioning to false. We preserve the unlocked state across that window and poll
 * follow-up checks so the Dynamic Island never gets stuck hidden after waking up.
 */
class ScreenStateMonitor(private val context: Context) {

    data class ScreenState(val interactive: Boolean, val locked: Boolean) {
        val usable: Boolean get() = interactive && !locked
    }

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastUserPresentAt = -10_000L

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ScreenState> = _state.asStateFlow()
    private var registered = false

    private val pollRunnable = Runnable {
        _state.value = read()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                _state.value = read()
                if (power.isInteractive) scheduleFollowUpChecks()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacksAndMessages(null)
                    lastUserPresentAt = -10_000L
                    _state.value = ScreenState(interactive = false, locked = true)
                }
                Intent.ACTION_USER_PRESENT -> {
                    lastUserPresentAt = SystemClock.elapsedRealtime()
                    _state.value = ScreenState(interactive = true, locked = false)
                    scheduleFollowUpChecks()
                }
                Intent.ACTION_SCREEN_ON -> {
                    _state.value = read(forceInteractive = true)
                    scheduleFollowUpChecks()
                }
                else -> {
                    _state.value = read()
                }
            }
        }
    }

    private fun read(forceInteractive: Boolean = false): ScreenState {
        val interactive = forceInteractive || power.isInteractive
        if (!interactive) return ScreenState(interactive = false, locked = true)
        val recentlyUnlocked = SystemClock.elapsedRealtime() - lastUserPresentAt in 0..4_000L
        val locked = if (recentlyUnlocked) false else keyguard.isKeyguardLocked
        return ScreenState(interactive = true, locked = locked)
    }

    private fun scheduleFollowUpChecks() {
        handler.removeCallbacks(pollRunnable)
        for (delayMs in FOLLOW_UP_DELAYS_MS) {
            handler.postDelayed({ _state.value = read() }, delayMs)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, handler)
        registered = true
        _state.value = read()
    }

    /** Re-reads the keyguard, e.g. after face/fingerprint unlock where USER_PRESENT can lag. */
    fun refresh() {
        _state.value = read()
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        registered = false
    }

    companion object {
        private val FOLLOW_UP_DELAYS_MS = longArrayOf(120L, 350L, 800L, 1_600L, 3_000L)
    }
}
