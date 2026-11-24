package com.arnav.island.dex

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arnav.island.IslandApp
import com.arnav.island.settings.MainActivity

/**
 * One UI Quick Settings tile for toggling on-device Samsung DeX (LocalDeX) with a single tap
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
            val started = graph.localDex.startDex(this, openViewer = false)
            val targetIntent = if (started) {
                Intent(this, DexViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_DESTINATION, "local_dex")
            }
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
            val id = session?.displayId ?: -1
            tile.subtitle = when {
                session == null -> "Off"
                id >= 0 -> "Display #$id"
                else -> "Starting…"
            }
        }
        tile.updateTile()
    }
}
