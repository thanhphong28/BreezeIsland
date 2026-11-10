package com.breeze.island.client

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Drop-in client for BreezeIsland's local API. Copy this file into your own app.
 *
 * Setup in YOUR app's AndroidManifest.xml:
 *
 *     <uses-permission android:name="com.breeze.island.permission.POST_ISLAND_ACTIVITY" />
 *     <queries>
 *         <package android:name="com.breeze.island" />
 *     </queries>
 *
 * The permission is a runtime ("dangerous") permission: request it once with
 * ActivityResultContracts.RequestPermission() using [IslandClient.PERMISSION]. Also enable
 * "Local API" in BreezeIsland → Advanced. Everything stays on the device.
 */
object IslandClient {
    const val PACKAGE = "com.breeze.island"
    const val PERMISSION = "com.breeze.island.permission.POST_ISLAND_ACTIVITY"

    private const val ACTION_SHOW = "com.breeze.island.action.SHOW_ACTIVITY"
    private const val ACTION_DISMISS = "com.breeze.island.action.DISMISS_ACTIVITY"

    val GLYPHS = listOf(
        "MUSIC", "BELL", "BOLT", "BATTERY", "BLUETOOTH", "HEADPHONES", "TIMER", "STOPWATCH", "NAV_ARROW",
        "DOWNLOAD", "CHECK", "RECORD", "CHIP", "PLAY", "PAUSE", "FLAG", "INFO", "WARNING", "SPARK", "MESSAGE", "PHONE",
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun show(
        context: Context,
        id: String,
        title: String,
        subtitle: String = "",
        icon: String = "SPARK",
        iconBitmap: Bitmap? = null,
        progress: Float? = null,
        durationMs: Long = 0,
        accentColor: Int = 0,
        priority: String = "normal",
        appLabel: String? = null,
    ): Boolean {
        if (!hasPermission(context)) return false
        val intent = Intent(ACTION_SHOW).setPackage(PACKAGE)
            .putExtra("id", id)
            .putExtra("title", title)
            .putExtra("subtitle", subtitle)
            .putExtra("icon", icon)
            .putExtra("progress", progress ?: -1f)
            .putExtra("duration_ms", durationMs)
            .putExtra("accent", accentColor)
            .putExtra("priority", priority)
        if (iconBitmap != null) intent.putExtra("icon_bitmap", iconBitmap)
        if (appLabel != null) intent.putExtra("app_label", appLabel)
        send(context, intent)
        return true
    }

    fun dismiss(context: Context, id: String): Boolean {
        if (!hasPermission(context)) return false
        send(context, Intent(ACTION_DISMISS).setPackage(PACKAGE).putExtra("id", id))
        return true
    }

    private fun send(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
            context.sendBroadcast(intent, null, options.toBundle())
        } else {
            context.sendBroadcast(intent)
        }
    }
}
