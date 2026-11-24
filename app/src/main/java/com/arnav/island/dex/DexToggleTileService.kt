package com.arnav.island.dex

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arnav.island.IslandApp
import com.arnav.island.settings.MainActivity

/**
 * One UI Quick Settings tile for toggling Samsung DeX (External Monitor / PC) with a single tap
 * from the Quick Panel.
 */
class DexToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val graph = (application as? IslandApp)?.graph ?: return
        val active = DexSession.current != null
        if (active) {
            graph.localDex.stopDex(this)
            syncTile()
        } else {
            val started = graph.localDex.startDex(this, forcePhoneViewer = false)
            val extConnected = ExternalDisplayFinder.findExternalDisplays(this).isNotEmpty()
            val targetIntent = when {
                !started -> Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_DESTINATION, "local_dex")
                extConnected -> Intent(this, DexTouchpadActivity::class.java)
                graph.settings.state.value.dexOutputMode == DexOutputMode.PHONE_SCREEN ->
                    Intent(this, DexViewerActivity::class.java)
                else -> Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_DESTINATION, "local_dex")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchFromTile(targetIntent)
            syncTile()
        }
    }

    private fun launchFromTile(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                4301,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val session = DexSession.current
        tile.state = if (session != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ext = (application as? IslandApp)?.graph?.localDex?.state?.value?.externalDisplayName
            tile.subtitle = when {
                session == null -> "Off"
                !ext.isNullOrBlank() -> "External"
                session.webServer?.url?.value != null -> "PC Ready"
                else -> "Running"
            }
        }
        tile.updateTile()
    }
}
