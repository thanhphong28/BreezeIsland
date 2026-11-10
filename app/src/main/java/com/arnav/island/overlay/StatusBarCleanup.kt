package com.arnav.island.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.arnav.island.IslandApp
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics

/**
 * Controls Android's `icon_blacklist` (Secure setting) and Samsung One UI's `Settings.System`
 * status bar notification icon preferences:
 *
 *  1. **Seamless status bar**:
 *     - While the island is active (`Compact`, `Split`, `Toast`, `Expanded`, `Stack`), One UI's
 *       status bar ongoing chips (`ongoing_activity`, `[▷ Media]`, call chips) are hidden so they
 *       never overlap the Dynamic Island.
 *     - While an expanded card, stack, or banner toast covers the status bar (`cardOpen = true`),
 *       the clock and right-side system icons (`battery`, `wifi`, `mobile`, etc.) also step aside.
 *  2. **One UI System Settings (`WRITE_SETTINGS`, grantable directly on the phone)**:
 *     - Automatically sets `simple_status_bar = 0` ("Show notification icons = None") and
 *       `status_bar_show_notification_icon = 0` on Samsung One UI so notification icons next to
 *       the clock do not crowd or overlap the island.
 *  3. **Status bar cleanup**: hides redundant status bar icons (`alarm_clock`, `volume`, `zen`, etc.).
 */
object StatusBarCleanup {
    private const val TAG = "BreezeStatusBar"
    const val KEY = "icon_blacklist"
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    const val GRANT_COMMAND = "adb shell pm grant com.breeze.island android.permission.WRITE_SECURE_SETTINGS"

    val ICONS: Map<String, String> = linkedMapOf(
        "ongoing_activity" to "One UI Live Notification / Media chip",
        "ongoing_call_chip" to "One UI ongoing call chip",
        "managed_profile" to "Work Profile / Secure Folder",
        "alarm_clock" to "Alarm",
        "volume" to "Sound / vibrate",
        "zen" to "Do Not Disturb",
        "rotate" to "Rotation lock",
        "headset" to "Headset",
        "bluetooth" to "Bluetooth",
        "hotspot" to "Hotspot",
        "nfc" to "NFC",
        "vpn" to "VPN",
        "data_saver" to "Data Saver",
    )

    /** One UI 6–9 ongoing & right-side icon slots that sit next to the Dynamic Island in Compact/Split mode. */
    private val ACTIVE_ISLAND_SLOTS = listOf(
        "ongoing_activity",
        "ongoing_activity_chip",
        "sec_ongoing_activity",
        "ongoing_call_chip",
        "ongoing_privacy_chip",
        "media_ongoing",
        "live_notification",
        "chip",
        "managed_profile",
        "knox_container",
        "secure_folder",
        "work",
        "profile",
        "dual_app",
        "volume",
        "mute",
        "vibrate",
        "ringer",
        "zen",
        "alarm_clock",
        "rotate",
        "headset",
    )

    /** Right-side status bar slots covered when the Split bubble buds to the right of the main pill. */
    private val SPLIT_RIGHT_SLOTS = ACTIVE_ISLAND_SLOTS + listOf(
        "wifi",
        "bluetooth",
        "vpn",
        "data_saver",
        "hotspot",
        "nfc",
        "location",
        "cast",
    )

    /** Slots hidden while an expanded card or wide banner toast covers the status bar header. */
    private val SEAMLESS_SLOTS = SPLIT_RIGHT_SLOTS + listOf(
        "clock",
        "battery",
        "mobile",
        "airplane",
        "sync_active",
        "tty",
        "speakerphone",
    )

    @Volatile
    private var cardOpen = false

    @Volatile
    private var islandActive = false

    @Volatile
    private var splitActive = false

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun canWriteSystem(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun current(context: Context): String =
        Settings.Secure.getString(context.contentResolver, KEY).orEmpty()

    /** Called by the overlay whenever the island's state or bounds change. */
    fun updateOverlayState(
        context: Context,
        active: Boolean,
        split: Boolean,
        coversStatusBar: Boolean,
    ) {
        if (islandActive == active && splitActive == split && cardOpen == coversStatusBar) return
        islandActive = active
        splitActive = split
        cardOpen = coversStatusBar
        val settings = (context.applicationContext as? IslandApp)?.graph?.settings ?: return
        settings.set { s -> sync(context, s) ?: s }
    }

    /** Backwards-compatible helper for callers that only toggle card state. */
    fun setCardOpen(context: Context, open: Boolean) {
        updateOverlayState(context, active = open || islandActive, split = splitActive, coversStatusBar = open)
    }

    /**
     * Applies the right settings for [s]. Returns updated [IslandSettings] when the backup field
     * needs to be persisted, or null when nothing in DataStore changed.
     */
    fun sync(context: Context, s: IslandSettings): IslandSettings? {
        // 1. Apply phone-grantable Settings.System tweaks on Samsung One UI (no ADB needed!)
        syncOneUiSystemSettings(context, s)

        // 2. Apply Settings.Secure icon_blacklist if WRITE_SECURE_SETTINGS is granted
        if (!canWrite(context)) return null
        return try {
            val active = s.enabled && (s.statusBarCleanup || s.seamlessStatusBar)
            when {
                active -> {
                    val now = current(context)
                    val backup = if (s.statusBarBackup == IslandSettings.BACKUP_NONE) {
                        val ours = parse(s.statusBarIcons) + SEAMLESS_SLOTS
                        parse(now).filterNot { it in ours }.joinToString(",")
                    } else {
                        s.statusBarBackup
                    }
                    val target = buildSet {
                        addAll(parse(backup))
                        if (s.statusBarCleanup) addAll(parse(s.statusBarIcons))
                        if (s.seamlessStatusBar && islandActive) {
                            addAll(ACTIVE_ISLAND_SLOTS)
                            if (splitActive) addAll(SPLIT_RIGHT_SLOTS)
                        }
                        if (s.seamlessStatusBar && cardOpen) {
                            addAll(SEAMLESS_SLOTS)
                        }
                    }.joinToString(",")
                    put(context, target)
                    if (s.statusBarBackup == IslandSettings.BACKUP_NONE) s.copy(statusBarBackup = backup) else null
                }
                s.statusBarBackup != IslandSettings.BACKUP_NONE -> {
                    put(context, s.statusBarBackup)
                    s.copy(statusBarBackup = IslandSettings.BACKUP_NONE)
                }
                else -> null
            }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            null
        }
    }

    /**
     * Uses `android.permission.WRITE_SETTINGS` (grantable directly on the phone without PC/ADB)
     * to hide Samsung One UI's redundant status bar notification icons next to the clock.
     */
    private fun syncOneUiSystemSettings(context: Context, s: IslandSettings) {
        if (!canWriteSystem(context)) return
        val cr = context.contentResolver
        val shouldClean = s.enabled && (s.seamlessStatusBar || s.statusBarCleanup)
        runCatching {
            // Samsung One UI: simple_status_bar (0 = None, 1 = 3 recent icons, 2 = number only)
            val targetSimple = if (shouldClean) 0 else 1
            if (Settings.System.getInt(cr, "simple_status_bar", -1) != targetSimple) {
                Settings.System.putInt(cr, "simple_status_bar", targetSimple)
            }
        }
        runCatching {
            val targetShowIcon = if (shouldClean) 0 else 1
            if (Settings.System.getInt(cr, "status_bar_show_notification_icon", -1) != targetShowIcon) {
                Settings.System.putInt(cr, "status_bar_show_notification_icon", targetShowIcon)
            }
        }
        runCatching {
            val targetNotifCount = if (shouldClean) 0 else 1
            if (Settings.System.getInt(cr, "status_bar_notif_count", -1) != targetNotifCount) {
                Settings.System.putInt(cr, "status_bar_notif_count", targetNotifCount)
            }
        }
    }

    private fun put(context: Context, value: String) {
        if (current(context) != value) {
            Settings.Secure.putString(context.contentResolver, KEY, value)
        }
    }

    private fun parse(csv: String): Set<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
