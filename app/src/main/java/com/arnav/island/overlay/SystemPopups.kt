package com.arnav.island.overlay

import android.content.Context
import android.provider.Settings
import com.arnav.island.permissions.Permissions
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics

/**
 * "Replace One UI pop-ups": while the island is on screen and showing notifications, BreezeIsland
 * takes over notification alerts completely so each notification appears only once (in the Dynamic
 * Island) instead of twice:
 *  1. Direct NotificationListener interception in [com.arnav.island.events.notification.NotificationProcessor]
 *     absorbs and dismisses duplicate system pop-up banners immediately (no Root or ADB needed).
 *  2. If "Modify system settings" (`WRITE_SETTINGS`) is granted on the phone, also updates
 *     One UI's system pop-up / edge-lighting flags.
 *  3. If `WRITE_SECURE_SETTINGS` is granted, also toggles global `heads_up_notifications_enabled`.
 */
object SystemPopups {
    private const val TAG = "BreezePopups"
    const val KEY = "heads_up_notifications_enabled"

    fun available(context: Context): Boolean =
        Permissions.hasNotificationAccess(context) ||
            StatusBarCleanup.canWriteSystem(context) ||
            StatusBarCleanup.canWrite(context)

    fun current(context: Context): Int = Settings.Global.getInt(context.contentResolver, KEY, 1)

    /**
     * Applies the right value for [s] given whether the island is currently able to show
     * notifications. Returns settings to persist when the backup changes, else null.
     */
    fun sync(context: Context, s: IslandSettings, islandShowing: Boolean): IslandSettings? {
        val suppress = s.replaceSystemPopups && islandShowing && s.enabled && s.notificationsEnabled

        // Phone-grantable Settings.System path (No Root / No ADB required)
        if (StatusBarCleanup.canWriteSystem(context)) {
            val cr = context.contentResolver
            runCatching {
                Settings.System.putInt(cr, KEY, if (suppress) 0 else 1)
            }
            runCatching {
                Settings.System.putInt(cr, "edge_lighting", if (suppress) 0 else 1)
            }
        }

        if (!StatusBarCleanup.canWrite(context)) return null
        return try {
            val managed = s.replaceSystemPopups
            when {
                managed -> {
                    val backup = if (s.headsUpBackup < 0) current(context).takeIf { it != 0 } ?: 1 else s.headsUpBackup
                    put(context, if (suppress) 0 else backup)
                    if (s.headsUpBackup < 0) s.copy(headsUpBackup = backup) else null
                }
                s.headsUpBackup >= 0 -> {
                    put(context, s.headsUpBackup)
                    s.copy(headsUpBackup = -1)
                }
                else -> null
            }
        } catch (e: SecurityException) {
            Diagnostics.w(TAG, "WRITE_SECURE_SETTINGS was revoked", e)
            null
        }
    }

    private fun put(context: Context, value: Int) {
        if (current(context) != value) Settings.Global.putInt(context.contentResolver, KEY, value)
    }
}
