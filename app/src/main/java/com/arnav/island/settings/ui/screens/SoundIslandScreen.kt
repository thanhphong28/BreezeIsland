package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoNotDisturb
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.soundisland.audio.VolumeSuppressor
import com.arnav.island.soundisland.render.ScreenEdge
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.SegmentedRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.tr
import com.arnav.island.storage.IslandSettings

@Composable
fun SoundIslandScreen(settings: IslandSettings, permissions: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val hasDnd = Permissions.hasDndAccess(context)
    val hasShizuku = VolumeSuppressor.canUseShizuku()

    SettingsPage(
        title = "Breeze Sound Island",
        onBack = { ui.back() },
    ) {
        item {
            Group {
                SwitchRow(
                    title = "Enable Breeze Sound Island",
                    subtitle = "Liquid Glass volume, silent & work controls sliding out when hardware volume keys are pressed",
                    checked = settings.soundIslandEnabled,
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    iconColor = BadgeColors.Orange,
                    onCheckedChange = { checked ->
                        ui.update { it.copy(soundIslandEnabled = checked) }
                        if (checked && !settings.enabled) {
                            ui.setEnabled(true)
                        }
                    },
                )
            }
        }

        item {
            Group(
                title = "Replace Stock Samsung Volume Panel",
                footer = "Disconnects Samsung's stock volume dialog using privileged system audio API. Zero root, zero accessibility, 100% banking and government apps safe.",
            ) {
                SwitchRow(
                    title = "Completely hide Samsung stock volume panel",
                    subtitle = if (hasShizuku) {
                        "Active · Only Breeze Sound Island will appear when volume buttons are pressed"
                    } else {
                        "Requires Shizuku activation directly on phone (no PC needed)"
                    },
                    checked = settings.soundIslandSuppressStock && hasShizuku,
                    icon = Icons.Rounded.Tune,
                    iconColor = BadgeColors.Cyan,
                    onCheckedChange = { checked ->
                        if (checked && !hasShizuku) {
                            OnPhoneGranter.openOrInstallShizuku(context)
                        } else {
                            ui.update { it.copy(soundIslandSuppressStock = checked) }
                            if (checked) {
                                Thread { VolumeSuppressor.suppress() }.start()
                            } else {
                                Thread { VolumeSuppressor.restore() }.start()
                            }
                        }
                    },
                )
                if (!hasShizuku) {
                    SettingRow(
                        title = "Activate Shizuku on phone (30s)",
                        subtitle = "Wireless Debugging -> Pair with code on your phone to unlock full volume panel replacement",
                        icon = Icons.Rounded.Security,
                        iconColor = BadgeColors.Blue,
                        onClick = { OnPhoneGranter.openOrInstallShizuku(context) },
                    )
                }
            }
        }

        item {
            Group(title = "Live preview & test") {
                Text(
                    tr("Tap a button below to trigger the edge notch directly on your screen:"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SoundTestChip("Volume Mode", Icons.AutoMirrored.Rounded.VolumeUp) {
                        IslandOverlayService.activeSoundIsland?.showTestVolume()
                    }
                    SoundTestChip("Silent Mode", Icons.Rounded.NotificationsOff) {
                        IslandOverlayService.activeSoundIsland?.showTestSilent()
                    }
                    SoundTestChip("Work Mode", Icons.Rounded.Work) {
                        IslandOverlayService.activeSoundIsland?.showTestWork()
                    }
                    SoundTestChip("Dismiss", Icons.Rounded.Close) {
                        IslandOverlayService.activeSoundIsland?.hide()
                    }
                }
            }
        }

        item {
            Group(
                title = "Edge & Alignment Calibration",
                footer = "On Galaxy Z Flip5, Samsung's default volume panel is fixed on the right. Selecting 'Left edge' completely prevents them from overlapping.",
            ) {
                SegmentedRow(
                    options = ScreenEdge.entries,
                    selected = settings.soundIslandEdge,
                    label = { edge ->
                        when (edge) {
                            ScreenEdge.LEFT -> "Left edge (No overlap)"
                            ScreenEdge.RIGHT -> "Right edge"
                        }
                    },
                    onSelect = { edge ->
                        ui.update { it.copy(soundIslandEdge = edge) }
                        IslandOverlayService.activeSoundIsland?.showTestVolume()
                    },
                    title = "Screen edge position",
                    subtitle = "Edge where the Liquid Glass notch slides out",
                )
                SliderRow(
                    title = "Edge vertical position",
                    value = settings.soundIslandYPercent,
                    range = 0.10f..0.70f,
                    live = true,
                    onValueChange = { frac ->
                        ui.update { it.copy(soundIslandYPercent = frac) }
                        IslandOverlayService.activeSoundIsland?.showTestVolume()
                    },
                    format = { String.format("%.0f%%", it * 100f) },
                    subtitle = "Fine-tune vertical height along the screen bezel",
                )
            }
        }

        item {
            InfoCard(
                title = "All Ways to Prevent Samsung Volume Overlap",
                body = "• Way 1 (100% Replacement): Enable 'Completely hide Samsung stock volume panel' above via Shizuku on your phone. Samsung's panel is completely suppressed!\n• Way 2 (No Shizuku): Select 'Left edge' above. Both bars appear on opposite sides without any overlap!\n• Way 3 (Samsung Official App): Install SoundAssistant from Galaxy Store (Good Lock) -> 'Customize volume panel' to relocate Samsung's panel to Left.",
                icon = Icons.Rounded.Tune,
                color = BadgeColors.Blue,
            )
        }

        item {
            Group(title = "Work & Focus Profile") {
                SwitchRow(
                    title = "Enable Do Not Disturb (DND)",
                    subtitle = "Automatically toggle system Do Not Disturb mode when Work profile is active",
                    checked = settings.soundIslandWorkDnd,
                    icon = Icons.Rounded.DoNotDisturb,
                    iconColor = BadgeColors.Indigo,
                    onCheckedChange = { dnd ->
                        if (dnd && !hasDnd) {
                            ui.open(Permissions.dndPolicySettings())
                        }
                        ui.update { it.copy(soundIslandWorkDnd = dnd) }
                    },
                )
                if (settings.soundIslandWorkDnd && !hasDnd) {
                    SettingRow(
                        title = "Grant DND Policy Access",
                        subtitle = "Required for BreezeIsland to manage Do Not Disturb state",
                        icon = Icons.Rounded.Security,
                        iconColor = BadgeColors.Red,
                        onClick = { ui.open(Permissions.dndPolicySettings()) },
                    )
                }
                SliderRow(
                    title = "Work Media Volume",
                    value = settings.soundIslandWorkMediaVol.toFloat(),
                    range = 0f..100f,
                    steps = 19,
                    onValueChange = { vol ->
                        ui.update { it.copy(soundIslandWorkMediaVol = vol.toInt()) }
                    },
                    format = { "${it.toInt()}%" },
                    subtitle = "Set ambient music or podcast volume level for focus time",
                )
            }
        }

        item {
            Group(title = "Behavior & Timing") {
                SliderRow(
                    title = "Auto-dismiss delay",
                    value = settings.soundIslandAutoDismissMs.toFloat(),
                    range = 1000f..4000f,
                    steps = 14,
                    onValueChange = { ms ->
                        ui.update { it.copy(soundIslandAutoDismissMs = ms.toLong()) }
                    },
                    format = { String.format("%.1fs", it / 1000f) },
                    subtitle = "Only hardware volume keys trigger the notch; screen edge swipes will never trigger it",
                )
            }
        }

        item {
            InfoCard(
                title = "100% Banking & Government App Compatible",
                body = "Breeze Sound Island runs strictly with standard WindowManager Overlay and public Audio APIs. Absolutely NO Accessibility Service, NO Root, and NO SystemUI tampering. Your banking apps and VNeID remain 100% safe and functional.",
                icon = Icons.Rounded.Security,
                color = BadgeColors.Green,
            )
        }

        item {
            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun SoundTestChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(tr(label)) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
        border = null,
    )
}
