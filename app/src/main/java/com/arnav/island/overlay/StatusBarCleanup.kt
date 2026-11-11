package com.arnav.island.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.arnav.island.IslandApp
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics

/**
 * Controls Android's `icon_blacklist` (Secure setting) and Samsung One UI's `Settings.System`
 * status bar notification icon preferences.
 *
 * CRITICAL RULE:
 * Essential system indicators (`wifi`, `mobile`, `battery`, `clock`, etc.) MUST NEVER be hidden
 * or added to `icon_blacklist`. The user expects them to remain visible in the header at all times.
 */
object StatusBarCleanup {
    private const val TAG = "BreezeStatusBar"
    const val KEY = "icon_blacklist"
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    const val GRANT_COMMAND = "adb shell pm grant com.breeze.island android.permission.WRITE_SECURE_SETTINGS"

    /** Essential status bar icons that MUST NEVER be hidden under any circumstances */
    val PROTECTED_ICONS = setOf(
        "wifi",
        "mobile",
        "cellular",
        "signal",
        "sim",
        "network",
        "battery",
        "clock",
        "airplane",
        "vpn",
        "bluetooth",
        "location",
        "cast",
        "hotspot",
        "nfc",
        "data_saver",
    )

    val ICONS: Map<String, String> = linkedMapOf(
        "ongoing_activity" to "One UI Live Notification / Media chip",
        "ongoing_call_chip" to "One UI ongoing call chip",
        "flashlight" to "One UI Flashlight / Now Bar chip",
        "managed_profile" to "Work Profile / Secure Folder",
        "alarm_clock" to "Alarm",
        "volume" to "Sound / vibrate",
        "zen" to "Do Not Disturb",
        "rotate" to "Rotation lock",
        "headset" to "Headset",
    )

    /** Only One UI duplicate ongoing chips (e.g. Samsung media pill, flashlight chip, or call chip) */
    val ACTIVE_ISLAND_SLOTS = listOf(
        "ongoing_activity",
        "ongoing_activity_chip",
        "sec_ongoing_activity",
        "ongoing_call_chip",
        "media_ongoing",
        "live_notification",
        "chip",
        "flashlight",
        "torch",
        "sec_flashlight",
        "sec_torch",
        "now_bar",
        "sec_now_bar",
    )

    private val SPLIT_RIGHT_SLOTS = ACTIVE_ISLAND_SLOTS
    private val SEAMLESS_SLOTS = ACTIVE_ISLAND_SLOTS

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

    /** Ensures protected icons like Wi-Fi, battery, clock are NEVER blacklisted on the device. */
    fun cleanProtectedIconsFromSystem(context: Context) {
        if (!canWrite(context)) return
        runCatching {
            val now = current(context)
            if (now.isNotEmpty()) {
                val cleaned = parse(now).filterNot { it in PROTECTED_ICONS }.joinToString(",")
                if (cleaned != now) {
                    put(context, cleaned)
                    Diagnostics.w(TAG, "Restored protected status bar icons: $now -> $cleaned")
                }
            }
        }
    }

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
        cleanProtectedIconsFromSystem(context)
        syncOneUiSystemSettings(context, s)

        if (!canWrite(context)) return null
        return try {
            val active = s.enabled && (s.statusBarCleanup || s.seamlessStatusBar)
            when {
                active -> {
                    val now = current(context)
                    val backup = if (s.statusBarBackup == IslandSettings.BACKUP_NONE) {
                        val ours = parse(s.statusBarIcons) + SEAMLESS_SLOTS
                        parse(now).filterNot { it in ours }.filterNot { it in PROTECTED_ICONS }.joinToString(",")
                    } else {
                        s.statusBarBackup
                    }
                    val target = buildSet {
                        addAll(parse(backup))
                        if (s.statusBarCleanup) {
                            addAll(parse(s.statusBarIcons).filterNot { it in PROTECTED_ICONS })
                        }
                        if (s.seamlessStatusBar && islandActive) {
                            addAll(ACTIVE_ISLAND_SLOTS)
                        }
                    }.filterNot { it in PROTECTED_ICONS }.joinToString(",")
                    put(context, target)
                    if (s.statusBarBackup == IslandSettings.BACKUP_NONE) s.copy(statusBarBackup = backup) else null
                }
                s.statusBarBackup != IslandSettings.BACKUP_NONE -> {
                    val restored = parse(s.statusBarBackup).filterNot { it in PROTECTED_ICONS }.joinToString(",")
                    put(context, restored)
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
        val shouldClean = s.enabled && s.statusBarCleanup
        runCatching {
            val targetSimple = if (shouldClean) 0 else 1
            if (Settings.System.getInt(cr, "simple_status_bar", -1) != targetSimple) {
                Settings.System.putInt(cr, "simple_status_bar", targetSimple)
            }
        }
    }

    private fun put(context: Context, value: String) {
        val safeValue = parse(value).filterNot { it in PROTECTED_ICONS }.joinToString(",")
        if (current(context) != safeValue) {
            Settings.Secure.putString(context.contentResolver, KEY, safeValue)
        }
    }

    private fun parse(csv: String): Set<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
