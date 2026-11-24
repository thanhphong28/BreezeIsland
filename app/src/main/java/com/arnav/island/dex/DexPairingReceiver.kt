package com.arnav.island.dex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.arnav.island.IslandApp
import com.arnav.island.storage.AppLanguage

/** Relays the pairing notification's inline reply and Cancel action to [DexPairingService]. */
class DexPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DexPairingService.ACTION_PAIRING_INPUT -> handleCode(context, intent)
            DexPairingService.ACTION_PAIRING_CANCEL -> {
                context.stopService(Intent(context, DexPairingService::class.java))
            }
        }
    }

    private fun handleCode(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val input = remoteInput
            .getCharSequence(DexPairingService.KEY_PAIRING_INPUT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (input.isEmpty()) {
            val vi = (context.applicationContext as? IslandApp)?.graph?.settings?.state?.value?.language != AppLanguage.ENGLISH
            Toast.makeText(
                context,
                if (vi) "Hãy nhập mã ghép nối 6 chữ số" else "Enter the 6-digit pairing code",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val forward = Intent(context, DexPairingService::class.java).apply {
            action = DexPairingService.ACTION_SUBMIT_CODE
            putExtra(DexPairingService.EXTRA_CODE, input)
            putExtra(DexPairingService.EXTRA_PORT, intent.getIntExtra(DexPairingService.EXTRA_PORT, 0))
        }
        ContextCompat.startForegroundService(context, forward)
    }
}
