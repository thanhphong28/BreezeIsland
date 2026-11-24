package com.arnav.island.dex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reads and (when WRITE_SECURE_SETTINGS is held) re-enables Android's Wireless debugging toggle
 * after reboot or Wi-Fi changes without requiring a trip to Developer options.
 */
object DexWirelessDebugging {
    private const val TAG = "DexWirelessDebug"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val VERIFY_TIMEOUT_MS = 3_000L
    private const val POLL_INTERVAL_MS = 250L

    fun isDeveloperOptionsEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) {
        true
    }

    fun isEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    } catch (e: Exception) {
        Diagnostics.w(TAG, "Could not read $ADB_WIFI_ENABLED", e)
        false
    }

    fun canToggle(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun enable(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isEnabled(context)) return@withContext true
        if (!canToggle(context)) return@withContext false

        try {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        } catch (e: Exception) {
            Diagnostics.w(TAG, "Failed to write $ADB_WIFI_ENABLED", e)
            return@withContext false
        }

        var waited = 0L
        while (waited < VERIFY_TIMEOUT_MS) {
            if (isEnabled(context)) return@withContext true
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        false
    }

    suspend fun tryAcquireTogglePermission(context: Context): Boolean {
        if (canToggle(context)) return true
        val cmd = "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        DexAdb.runShellCommand(context, cmd)
        return canToggle(context)
    }
}
