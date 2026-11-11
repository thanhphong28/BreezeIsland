package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Security
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
import com.arnav.island.settings.ui.components.RadioRow
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
                    .height(170.dp)
                    .padding(vertical = 4.dp),
                onReady = {
                    preview = it
                    when (s.unlockMethodPreference) {
                        "face" -> it.tests.unlockFace()
                        "fingerprint" -> it.tests.unlockFingerprint()
                        "password" -> it.tests.unlockPassword()
                        else -> it.tests.unlockFace()
                    }
                }
            )
        }

        item {
            Group(title = "Behaviour") {
                SwitchRow(
                    "Unlock animation",
                    s.unlockAnimationEnabled,
                    { v -> ui.update { it.copy(unlockAnimationEnabled = v) } },
                    "Show Dynamic Island animation when waking up and unlocking the device",
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
                title = "Recognition mode",
                footer = "Auto mode detects front camera usage for Face ID, instant unlock timing (<800ms) for Fingerprint, or manual swipe/entry for Padlock without needing any special permissions."
            ) {
                RadioRow(
                    title = "Auto detect (Camera & timing)",
                    subtitle = "Smart detection without Accessibility or root",
                    selected = s.unlockMethodPreference == "auto",
                    onClick = {
                        ui.update { it.copy(unlockMethodPreference = "auto") }
                        preview?.tests?.unlockFace()
                    }
                )
                RadioRow(
                    title = "Prefer Face ID",
                    subtitle = "Apple iPhone-style Face ID frame & smiley face",
                    selected = s.unlockMethodPreference == "face",
                    onClick = {
                        ui.update { it.copy(unlockMethodPreference = "face") }
                        preview?.tests?.unlockFace()
                    }
                )
                RadioRow(
                    title = "Prefer Fingerprint",
                    subtitle = "Tactile fingerprint ridges & electric blue scan",
                    selected = s.unlockMethodPreference == "fingerprint",
                    onClick = {
                        ui.update { it.copy(unlockMethodPreference = "fingerprint") }
                        preview?.tests?.unlockFingerprint()
                    }
                )
                RadioRow(
                    title = "Prefer Password / PIN",
                    subtitle = "Lock opens with tactile spring pop & warm gold glow",
                    selected = s.unlockMethodPreference == "password",
                    onClick = {
                        ui.update { it.copy(unlockMethodPreference = "password") }
                        preview?.tests?.unlockPassword()
                    }
                )
            }
        }

        item {
            Group(title = "Live preview & test") {
                SettingRow(
                    title = "Test Face ID",
                    subtitle = "Triggers Face ID unlock animation",
                    icon = Icons.Rounded.CenterFocusStrong,
                    iconColor = BadgeColors.Green,
                    onClick = {
                        preview?.tests?.unlockFace()
                        ui.graph.testEvents.unlockFace()
                    }
                )
                SettingRow(
                    title = "Test Fingerprint",
                    subtitle = "Triggers Fingerprint unlock animation",
                    icon = Icons.Rounded.Fingerprint,
                    iconColor = BadgeColors.Blue,
                    onClick = {
                        preview?.tests?.unlockFingerprint()
                        ui.graph.testEvents.unlockFingerprint()
                    }
                )
                SettingRow(
                    title = "Test Password",
                    subtitle = "Triggers Padlock open animation",
                    icon = Icons.Rounded.LockOpen,
                    iconColor = BadgeColors.Orange,
                    onClick = {
                        preview?.tests?.unlockPassword()
                        ui.graph.testEvents.unlockPassword()
                    }
                )
            }
        }

        item {
            Group(title = "Security & Banking Protection") {
                InfoCard(
                    title = "100% Safe with Banking & Government Apps",
                    body = "BreezeIsland uses public Android broadcast receivers and standard CameraManager availability callbacks. Absolutely ZERO Accessibility Services, ZERO root, and ZERO biometric data access. Fully compatible with VNeID and all banking apps.",
                    icon = Icons.Rounded.Security,
                    color = BadgeColors.Green,
                )
            }
        }
    }
}
