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
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
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
                title = "Thay thế thanh âm lượng mặc định Samsung",
                footer = "Ẩn hoàn toàn thanh âm lượng gốc Samsung ngoài màn hình bằng Shizuku. Không root, không quyền trợ năng, 100% an toàn với ngân hàng & VNeID.",
            ) {
                SwitchRow(
                    title = "Ẩn thanh âm lượng Samsung ngoài màn hình",
                    subtitle = if (hasShizuku) {
                        "Đang hoạt động · Chỉ duy nhất Breeze Sound Island xuất hiện khi bấm phím âm lượng"
                    } else {
                        "Cần kích hoạt Shizuku trên điện thoại (không cần máy tính)"
                    },
                    checked = settings.soundIslandSuppressStock && hasShizuku,
                    icon = Icons.Rounded.Tune,
                    iconColor = BadgeColors.Cyan,
                    onCheckedChange = { checked ->
                        if (checked && !hasShizuku) {
                            if (OnPhoneGranter.isShizukuRunning()) {
                                runCatching { rikka.shizuku.Shizuku.requestPermission(2805) }
                            } else {
                                OnPhoneGranter.openOrInstallShizuku(context)
                            }
                        } else {
                            ui.update { it.copy(soundIslandSuppressStock = checked) }
                            if (checked) {
                                Thread { VolumeSuppressor.suppress(context) }.start()
                            } else {
                                Thread { VolumeSuppressor.restore(context) }.start()
                            }
                        }
                    },
                )
                SettingRow(
                    title = "Khôi phục thanh âm lượng gốc Samsung",
                    subtitle = "Làm mới và khôi phục thanh âm lượng gốc và Trung tâm điều khiển Samsung về mặc định",
                    icon = Icons.Rounded.Close,
                    iconColor = BadgeColors.Red,
                    onClick = {
                        ui.update { it.copy(soundIslandSuppressStock = false) }
                        Thread { VolumeSuppressor.restore(context) }.start()
                    },
                )
                SettingRow(
                    title = "Bật 'Sử dụng phím âm lượng cho media' (Samsung)",
                    subtitle = "Mở Cài đặt Âm thanh One UI -> Đảm bảo bật tính năng này để phím cứng luôn chỉnh Media, đồng bộ 100% hai thanh",
                    icon = Icons.Rounded.Tune,
                    iconColor = BadgeColors.Cyan,
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                )
                if (!hasShizuku) {
                    SettingRow(
                        title = "Kích hoạt Shizuku trên điện thoại (30s)",
                        subtitle = "Gỡ lỗi không dây -> Ghép nối bằng mã để kích hoạt tính năng ẩn thanh âm lượng gốc",
                        icon = Icons.Rounded.Security,
                        iconColor = BadgeColors.Blue,
                        onClick = {
                            if (OnPhoneGranter.isShizukuRunning()) {
                                runCatching { rikka.shizuku.Shizuku.requestPermission(2805) }
                            } else {
                                OnPhoneGranter.openOrInstallShizuku(context)
                            }
                        },
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
                    SoundTestChip("Ringer Mode", Icons.Rounded.NotificationsOff) {
                        IslandOverlayService.activeSoundIsland?.showTestRinger()
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
                title = "Smart Fluid Gestures",
                body = "• Single press: Appears as a sleek ultra-thin Stick.\n• Press > 3 times or hold volume key: Expands into full tactile capsule.\n• Hold touch for ~150ms then drag: Expands and adjusts volume smoothly.\n• Tap on volume bar: Opens Ringer mode instantly without changing volume.\n• Tap in Ringer Mode: Cycles Ring ↔ Vibrate ↔ Mute with bouncy icon animations.\n• Swipe Inward (Horizontal): Switches between Volume and Ringer modes.",
                icon = Icons.Rounded.Tune,
                color = BadgeColors.Indigo,
            )
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
