package com.arnav.island.events.system

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.SystemKind
import com.arnav.island.events.SystemPayload
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Launch
import java.util.Date

/**
 * Small system moments Android exposes publicly: ringer mode, Do Not Disturb, wired headset,
 * rotation lock, hotspot (best effort), system alarm clock changes and, if enabled, clipboard copies.
 */
class SystemEventsMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val settings: () -> IslandSettings,
) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastKind: SystemKind? = null
    private var lastAt = 0L
    private var lastDndOn: Boolean? = null
    private var lastRotationLocked: Boolean? = null
    private var lastAlarmTriggerTime: Long = -1L
    private var clipboardRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (isInitialStickyBroadcast) return
            val s = settings()
            when (intent.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (s.ringerEnabled) {
                    when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                        AudioManager.RINGER_MODE_SILENT -> post(SystemKind.RINGER_SILENT, "Silent")
                        AudioManager.RINGER_MODE_VIBRATE -> post(SystemKind.RINGER_VIBRATE, "Vibrate")
                        AudioManager.RINGER_MODE_NORMAL -> post(SystemKind.RINGER_NORMAL, "Ring")
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> if (s.dndEnabled) {
                    val filter = notifications.currentInterruptionFilter
                    val on = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                    if (on != lastDndOn) {
                        lastDndOn = on
                        post(if (on) SystemKind.DND_ON else SystemKind.DND_OFF, if (on) "Do not disturb" else "DND off")
                    }
                }
                AudioManager.ACTION_HEADSET_PLUG -> if (s.headsetEnabled) {
                    val plugged = intent.getIntExtra("state", 0) == 1
                    post(if (plugged) SystemKind.HEADSET_IN else SystemKind.HEADSET_OUT, if (plugged) "Connected" else "Unplugged", "Wired headset")
                }
                ACTION_WIFI_AP_STATE_CHANGED -> if (s.hotspotEnabled) {
                    when (intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)) {
                        WIFI_AP_STATE_ENABLED -> post(SystemKind.HOTSPOT_ON, "Hotspot on")
                        WIFI_AP_STATE_DISABLED -> post(SystemKind.HOTSPOT_OFF, "Hotspot off")
                    }
                }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> if (s.alarmsEnabled) {
                    onNextAlarmChanged(s)
                }
            }
        }
    }

    private fun onNextAlarmChanged(s: IslandSettings) {
        val info = try {
            alarmManager?.nextAlarmClock
        } catch (_: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        if (info == null) {
            lastAlarmTriggerTime = -1L
            return
        }
        val creatorPkg = info.showIntent?.creatorPackage?.lowercase().orEmpty()
        if (creatorPkg == context.packageName.lowercase()) return
        val trigger = info.triggerTime
        if (trigger <= now + 15_000L) return
        // Ignore duplicate broadcasts for the same alarm minute
        if (kotlin.math.abs(trigger - lastAlarmTriggerTime) < 30_000L) return
        lastAlarmTriggerTime = trigger

        val isVi = s.language == AppLanguage.VIETNAMESE
        val timeStr = DateFormat.getTimeFormat(context).format(Date(trigger))
        val diffMs = (trigger - now).coerceAtLeast(60_000L)
        val totalMinutes = ((diffMs + 30_000L) / 60_000L).coerceAtLeast(1L)
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        val label = if (isVi) "Báo thức · $timeStr" else "Alarm · $timeStr"
        val detail = if (isVi) {
            buildString {
                append("Đổ chuông sau ")
                if (days > 0) append("$days ngày ")
                if (hours > 0) append("$hours giờ ")
                if (minutes > 0 || (days == 0L && hours == 0L)) append("$minutes phút")
            }.trim()
        } else {
            buildString {
                append("Rings in ")
                if (days > 0) append("${days}d ")
                if (hours > 0) append("${hours}h ")
                if (minutes > 0 || (days == 0L && hours == 0L)) append("${minutes}m")
            }.trim()
        }

        val tap = IslandAction("alarm.open", if (isVi) "Mở Đồng hồ" else "Open Clock") {
            val opened = info.showIntent?.let { Launch.send(context, it) } ?: false
            if (!opened) {
                runCatching {
                    context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        engine.post(
            IslandEvent(
                id = "system:alarm",
                source = EventSource.SYSTEM,
                type = EventType.SYSTEM,
                timestamp = now,
                persistent = false,
                durationMs = 3_400L,
                title = label,
                subtitle = detail,
                tapAction = tap,
                payload = SystemPayload(SystemKind.ALARM_SET, label, detail),
                animation = EntranceAnimation.POP,
                contentKey = trigger.toString(),
            )
        )
    }

    private val rotationObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!settings().rotationEnabled) return
            val locked = readRotationLocked()
            if (locked == lastRotationLocked) return
            lastRotationLocked = locked
            post(if (locked) SystemKind.ROTATION_LOCKED else SystemKind.ROTATION_AUTO, if (locked) "Locked" else "Auto-rotate", "Screen rotation")
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Android 10+ only delivers this while Island is in the foreground; no content is read.
        if (settings().clipboardEnabled) post(SystemKind.CLIPBOARD, "Copied")
    }

    fun start() {
        if (registered) return
        lastAlarmTriggerTime = runCatching { alarmManager?.nextAlarmClock?.triggerTime ?: -1L }.getOrDefault(-1L)
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, rotationObserver)
        lastRotationLocked = readRotationLocked()
        lastDndOn = notifications.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        registered = true
        refresh()
    }

    /** Applies settings that need registration changes (clipboard). */
    fun refresh() {
        val want = settings().clipboardEnabled
        if (want && !clipboardRegistered) {
            clipboard.addPrimaryClipChangedListener(clipListener)
            clipboardRegistered = true
        } else if (!want && clipboardRegistered) {
            clipboard.removePrimaryClipChangedListener(clipListener)
            clipboardRegistered = false
        }
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        context.contentResolver.unregisterContentObserver(rotationObserver)
        if (clipboardRegistered) clipboard.removePrimaryClipChangedListener(clipListener)
        clipboardRegistered = false
        registered = false
    }

    private fun readRotationLocked(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0

    private fun post(kind: SystemKind, label: String, detail: String = "") {
        val now = System.currentTimeMillis()
        if (kind == lastKind && now - lastAt < 700) return
        lastKind = kind
        lastAt = now
        engine.post(
            IslandEvent(
                id = "system:${kind.name.substringBefore('_').lowercase()}",
                source = EventSource.SYSTEM,
                type = EventType.SYSTEM,
                timestamp = now,
                persistent = false,
                durationMs = 1_900,
                title = label,
                subtitle = detail,
                payload = SystemPayload(kind, label, detail),
                animation = EntranceAnimation.POP,
                contentKey = kind.name,
            )
        )
    }

    companion object {
        // Hotspot state is not public API; the broadcast is used only if the platform sends it.
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLED = 13
    }
}
