package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.Reveal
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.preview.PreviewIsland
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.storage.IslandSettings

@Composable
fun UnlockScreen(s: IslandSettings, ui: Ui) {
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }

    SettingsPage("Device unlock", onBack = ui::back) {
        item {
            IslandPreview(
                ui.graph,
                s,
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(vertical = 4.dp),
                onReady = {
                    preview = it
                    it.tests.unlockFingerprint()
                }
            )
        }

        item {
            Group(title = "Behaviour") {
                SwitchRow(
                    "Unlock animation",
                    s.unlockAnimationEnabled,
                    { v -> ui.update { it.copy(unlockAnimationEnabled = v) } },
                    "Show 3D Dynamic Island animation when waking up and unlocking the device",
                    Icons.Rounded.PhonelinkLock,
                    BadgeColors.Indigo,
                )
                Reveal(s.unlockAnimationEnabled) {
                    SliderRow(
                        "Display duration",
                        s.unlockDurationMs / 1000f,
                        1.2f..3.5f,
                        { v -> ui.update { it.copy(unlockDurationMs = (v * 1000).toLong()) } },
                        { String.format(java.util.Locale.US, "%.1f s", it) }
                    )
                }
            }
        }

        item {
            Group(
                title = "3D Biometric Animation",
                footer = "Holographic 3D Fingerprint biometric scan that executes a smooth 3D Y-axis perspective flip into the golden unlocked padlock."
            ) {
                SettingRow(
                    title = "Test 3D Unlock Animation",
                    subtitle = "Trigger real-time 3D coin flip & padlock unlock",
                    icon = Icons.Rounded.ViewInAr,
                    iconColor = BadgeColors.Cyan,
                    onClick = {
                        preview?.tests?.unlockFingerprint()
                        ui.graph.testEvents.unlockFingerprint()
                    }
                )
            }
        }

        item {
            Group(title = "Security & Banking Protection") {
                InfoCard(
                    title = "100% Safe with Banking & Government Apps",
                    body = "BreezeIsland uses public Android broadcast receivers. Absolutely ZERO Accessibility Services, ZERO root, and ZERO biometric data access. Fully compatible with VNeID and all banking apps.",
                    icon = Icons.Rounded.Security,
                    color = BadgeColors.Green,
                )
            }
        }
    }
}
