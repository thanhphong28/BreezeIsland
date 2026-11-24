package com.arnav.island.settings.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.dex.DexConnectionMode
import com.arnav.island.dex.DexDisplaySpec
import com.arnav.island.dex.DexTransport
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings

@Composable
fun LocalDexScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val vi = s.language == AppLanguage.VIETNAMESE
    val dexState by ui.graph.localDex.state.collectAsStateWithLifecycle()

    var customSpec by remember(s.dexDisplaySpec) { mutableStateOf(s.dexDisplaySpec) }

    LaunchedEffect(Unit) {
        ui.graph.localDex.refreshStatus(forceAdbCheck = true)
    }
    LifecycleResumeEffect(Unit) {
        ui.graph.localDex.refreshStatus(forceAdbCheck = true)
        onPauseOrDispose { }
    }

    val active = dexState.running || dexState.starting
    val readyTransport = dexState.bestReadyTransport(s.dexConnectionMode)
    val isReady = readyTransport != null

    SettingsPage(
        title = if (vi) "Samsung DeX nội bộ" else "Local Samsung DeX",
        onBack = ui::back,
    ) {
        // 1. Master On/Off Switch + Live Viewer Actions
        item {
            Group(
                title = if (vi) "Bật / Tắt Samsung DeX ngay trên Z Flip" else "On-Device Samsung DeX",
                footer = if (vi) {
                    "Chạy Samsung DeX toàn màn hình trực tiếp trên máy (tích hợp từ sam1am/localdex) — không cần cáp HDMI, không cần máy tính, không cần Root và hoàn toàn không dùng Trợ năng."
                } else {
                    "Run Samsung DeX fullscreen right on your phone (powered by sam1am/localdex) — no dock, no cable, no PC, no root, and zero AccessibilityService."
                },
            ) {
                val subtitle = when {
                    dexState.running -> {
                        val via = if (dexState.activeTransport == DexTransport.SHIZUKU) "Shizuku" else "Wireless ADB"
                        val idSuffix = if (dexState.displayId >= 0) " · #${dexState.displayId}" else ""
                        if (vi) "Đang bật ($via$idSuffix) · Gạt tắt để đóng màn hình ảo"
                        else "Running ($via$idSuffix) · Toggle off to end virtual display"
                    }
                    dexState.starting -> dexState.statusMessage.ifEmpty {
                        if (vi) "Đang khởi động Samsung DeX…" else "Starting Samsung DeX…"
                    }
                    isReady -> {
                        val via = if (readyTransport == DexTransport.SHIZUKU) "Shizuku" else "Wireless ADB"
                        if (vi) "Sẵn sàng ($via) · Gạt bật để mở Samsung DeX ngay"
                        else "Ready ($via) · Toggle on to launch Samsung DeX"
                    }
                    dexState.checkingConnection -> if (vi) "Đang kiểm tra kết nối…" else "Checking connection…"
                    else -> if (vi) "Cần cấp quyền Shizuku hoặc ghép nối ADB không dây bên dưới"
                    else "Allow Shizuku or pair Wireless ADB below to start"
                }

                SwitchRow(
                    title = if (vi) "Bật Samsung DeX" else "Enable Samsung DeX",
                    checked = active,
                    onCheckedChange = { enable ->
                        if (!enable) {
                            ui.graph.localDex.stopDex(context)
                        } else {
                            val ok = ui.graph.localDex.startDex(context, openViewer = true)
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    if (vi) "Hãy kết nối Shizuku (1 chạm) hoặc ghép nối Gỡ lỗi không dây bên dưới trước."
                                    else "Please allow Shizuku or pair Wireless ADB below first.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                    subtitle = subtitle,
                    icon = Icons.Rounded.DesktopWindows,
                    iconColor = if (active) BadgeColors.Green else BadgeColors.Blue,
                )

                if (active) {
                    SettingRow(
                        title = if (vi) "Mở lại màn hình Samsung DeX" else "Open Samsung DeX Viewer",
                        subtitle = if (dexState.displayId >= 0) {
                            if (vi) "Màn hình ảo #${dexState.displayId} · Có thể xem từ PC: scrcpy --display-id=${dexState.displayId}"
                            else "Virtual display #${dexState.displayId} · PC command: scrcpy --display-id=${dexState.displayId}"
                        } else {
                            if (vi) "Chạm để quay lại cửa sổ DeX toàn màn hình"
                            else "Tap to return to fullscreen DeX desktop"
                        },
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        iconColor = BadgeColors.Cyan,
                        onClick = { ui.graph.localDex.openViewer(context) },
                    )
                }
            }
        }

        // Error card if startup failed
        val lastErr = dexState.lastError
        if (!lastErr.isNullOrBlank()) {
            item {
                InfoCard(
                    title = if (vi) "Không thể khởi chạy Samsung DeX" else "Could not start Samsung DeX",
                    body = lastErr,
                    icon = Icons.Rounded.Warning,
                    color = BadgeColors.Red,
                    actionLabel = if (vi) "Thử lại" else "Retry",
                    onAction = { ui.graph.localDex.refreshStatus(forceAdbCheck = true) },
                )
            }
        }

        // 2. Dual-Engine Connection Setup (Shizuku + Wireless ADB)
        item {
            Group(
                title = if (vi) "Cầu kết nối trên máy (Không cần PC)" else "On-Phone Connection Bridge",
                footer = if (vi) {
                    "Mẹo: Nếu đã bật Shizuku trên máy, DeX sẽ mở tức thì chỉ với 1 chạm và chạy được cả khi dùng 4G/5G. Hoặc dùng ADB không dây (LocalDex gốc) nếu bạn không cài Shizuku (yêu cầu Wi-Fi)."
                } else {
                    "Tip: With Shizuku active, DeX launches instantly in 1 tap and even works on mobile data. Alternatively, use built-in Wireless ADB (stock LocalDex flow, requires Wi-Fi)."
                },
            ) {
                ChipsRow(
                    title = if (vi) "Phương thức ưu tiên" else "Connection engine",
                    options = DexConnectionMode.entries,
                    selected = s.dexConnectionMode,
                    label = { if (vi) it.labelVi else it.labelEn },
                    onSelect = { mode -> ui.update { it.copy(dexConnectionMode = mode) } },
                )

                val shizukuSub = when {
                    dexState.shizukuPermitted -> if (vi) "Đã sẵn sàng · Chạy DeX 1 chạm không cần Wi-Fi" else "Ready · 1-tap DeX launch without Wi-Fi"
                    dexState.shizukuRunning -> if (vi) "Shizuku đang chạy · Bấm để cấp quyền 1 chạm" else "Shizuku running · Tap to allow"
                    dexState.shizukuInstalled -> if (vi) "Mở app Shizuku và bấm Khởi động (Start)" else "Open Shizuku and tap Start"
                    else -> if (vi) "Chưa cài Shizuku · Tùy chọn cài từ Play Store hoặc dùng ADB không dây" else "Not installed · Optional (or use Wireless ADB below)"
                }
                SettingRow(
                    title = if (vi) {
                        if (dexState.shizukuPermitted) "Shizuku: Đã sẵn sàng" else "Kết nối Shizuku (Khuyên dùng)"
                    } else {
                        if (dexState.shizukuPermitted) "Shizuku: Ready" else "Allow Shizuku (Recommended)"
                    },
                    subtitle = shizukuSub,
                    icon = if (dexState.shizukuPermitted) Icons.Rounded.CheckCircle else Icons.Rounded.VerifiedUser,
                    iconColor = if (dexState.shizukuPermitted) BadgeColors.Green else BadgeColors.Blue,
                    onClick = { ui.graph.localDex.requestShizukuPermission(context) },
                )

                val adbSub = when {
                    dexState.adbConnected -> if (vi) {
                        if (dexState.canAutoToggleWirelessDebug) "Đã kết nối · Tự bật lại Gỡ lỗi không dây sau khi khởi động lại"
                        else "Đã kết nối ADB không dây · Sẵn sàng chạy DeX"
                    } else {
                        if (dexState.canAutoToggleWirelessDebug) "Connected · Auto-re-enables Wireless debugging after reboot"
                        else "Wireless ADB connected · Ready to run DeX"
                    }
                    else -> if (vi) {
                        "Bấm để mở Gỡ lỗi không dây → Ghép nối bằng mã 6 số rồi nhập vào thông báo"
                    } else {
                        "Tap to open Wireless debugging → Pair with 6-digit code in notification"
                    }
                }
                SettingRow(
                    title = if (vi) {
                        if (dexState.adbConnected) "ADB không dây (LocalDex): Đã kết nối"
                        else "Ghép nối ADB không dây (LocalDex)"
                    } else {
                        if (dexState.adbConnected) "Wireless ADB (LocalDex): Connected"
                        else "Pair Wireless ADB (LocalDex)"
                    },
                    subtitle = adbSub,
                    icon = if (dexState.adbConnected) Icons.Rounded.CheckCircle else Icons.Rounded.Wifi,
                    iconColor = if (dexState.adbConnected) BadgeColors.Green else BadgeColors.Indigo,
                    onClick = { ui.graph.localDex.startWirelessPairing(context) },
                )

                if (!dexState.adbConnected && !dexState.shizukuPermitted) {
                    if (!dexState.developerOptionsEnabled) {
                        PermissionRow(
                            title = if (vi) "Bước 1: Bật Cài đặt cho người phát triển" else "Step 1: Enable Developer options",
                            subtitle = if (vi) "Thông tin điện thoại → Thông tin phần mềm → Chạm 7 lần Số hiệu bản tạo"
                            else "About phone → Software information → Tap Build number 7 times",
                            granted = false,
                            icon = Icons.Rounded.DeveloperMode,
                            color = BadgeColors.Orange,
                            onGrant = { ui.open(Intent(Settings.ACTION_SETTINGS)) },
                            grantLabel = if (vi) "Mở Cài đặt" else "Settings",
                        )
                    }
                    if (!dexState.notificationsEnabled) {
                        PermissionRow(
                            title = if (vi) "Bước 2: Bật Thông báo BreezeIsland" else "Step 2: Enable Notifications",
                            subtitle = if (vi) "Cần thông báo để nhập mã ghép nối 6 chữ số mà không rời màn hình Cài đặt"
                            else "Required to enter the 6-digit pairing code directly from the notification",
                            granted = false,
                            icon = Icons.Rounded.NotificationsActive,
                            color = BadgeColors.Pink,
                            onGrant = {
                                ui.open(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            },
                            grantLabel = if (vi) "Bật" else "Enable",
                        )
                    }
                }

                SettingRow(
                    title = if (vi) "Kiểm tra lại trạng thái kết nối" else "Refresh connection status",
                    subtitle = if (dexState.checkingConnection) {
                        if (vi) "Đang kiểm tra Shizuku & ADB không dây…" else "Checking Shizuku & Wireless ADB…"
                    } else {
                        if (vi) "Làm mới trạng thái Shizuku và Gỡ lỗi không dây ngay" else "Re-check Shizuku and Wireless ADB right now"
                    },
                    icon = Icons.Rounded.Refresh,
                    iconColor = BadgeColors.Graphite,
                    onClick = { ui.graph.localDex.refreshStatus(forceAdbCheck = true) },
                )
            }
        }

        // 3. Resolution & DPI (Z Flip 5 optimized)
        item {
            Group(
                title = if (vi) "Độ phân giải & DPI màn hình DeX" else "DeX Display Resolution & DPI",
                footer = if (vi) {
                    "DPI thấp = không gian làm việc rộng hơn (nhiều cửa sổ hơn); DPI cao = chữ & nút bấm to dễ chạm hơn. Thay đổi sẽ áp dụng cho lần bật DeX tiếp theo."
                } else {
                    "Lower DPI = more desktop workspace; higher DPI = larger UI elements. Changes apply the next time DeX starts."
                },
            ) {
                ChipsRow(
                    title = if (vi) "Cấu hình tối ưu sẵn" else "Display presets",
                    options = DexDisplaySpec.PRESETS.map { it.spec },
                    selected = s.dexDisplaySpec,
                    label = { spec ->
                        val p = DexDisplaySpec.PRESETS.firstOrNull { it.spec == spec }
                        if (p != null) (if (vi) p.labelVi else p.labelEn) else spec
                    },
                    onSelect = { spec ->
                        customSpec = spec
                        ui.update { it.copy(dexDisplaySpec = spec) }
                    },
                )

                val selectedPreset = DexDisplaySpec.PRESETS.firstOrNull { it.spec == s.dexDisplaySpec }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (selectedPreset != null) {
                        Text(
                            if (vi) selectedPreset.noteVi else selectedPreset.noteEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = customSpec,
                            onValueChange = { customSpec = it },
                            singleLine = true,
                            label = { Text(if (vi) "Tùy chỉnh (RỘNGxCAO/DPI)" else "Custom (WxH/DPI)") },
                            placeholder = { Text(DexDisplaySpec.DEFAULT_SPEC) },
                            isError = customSpec.isNotBlank() && !DexDisplaySpec.isValid(customSpec),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        )
                        val valid = DexDisplaySpec.isValid(customSpec)
                        val changed = customSpec.trim() != s.dexDisplaySpec
                        if (changed) {
                            Button(
                                enabled = valid,
                                onClick = {
                                    val clean = customSpec.trim()
                                    ui.update { it.copy(dexDisplaySpec = clean) }
                                    Toast.makeText(
                                        context,
                                        if (vi) "Đã lưu cấu hình màn hình DeX: $clean" else "Saved DeX display spec: $clean",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(if (vi) "Lưu" else "Save")
                            }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    customSpec = DexDisplaySpec.DEFAULT_SPEC
                                    ui.update { it.copy(dexDisplaySpec = DexDisplaySpec.DEFAULT_SPEC) }
                                },
                            ) {
                                Text(if (vi) "Mặc định" else "Default")
                            }
                        }
                    }
                }
            }
        }

        // 4. Viewer & Dynamic Island behavior
        item {
            Group(
                title = if (vi) "Trải nghiệm màn hình & Dynamic Island" else "Viewer & Island Behavior",
            ) {
                SwitchRow(
                    title = if (vi) "Khóa xoay ngang khi dùng Samsung DeX" else "Lock landscape orientation for DeX",
                    checked = s.dexLandscapeLock,
                    onCheckedChange = { v -> ui.update { it.copy(dexLandscapeLock = v) } },
                    subtitle = if (vi) {
                        "Tối ưu cho màn hình rộng Galaxy Z Flip 5 — có thể đổi nhanh ngay trong nút ✕"
                    } else {
                        "Ideal for Galaxy Z Flip 5's wide screen — toggle anytime from the floating ✕ menu"
                    },
                    icon = Icons.Rounded.ScreenRotation,
                    iconColor = BadgeColors.Teal,
                )
                SwitchRow(
                    title = if (vi) "Báo trạng thái DeX trên Dynamic Island" else "Show DeX status pill on Dynamic Island",
                    checked = s.dexShowIslandPill,
                    onCheckedChange = { v -> ui.update { it.copy(dexShowIslandPill = v) } },
                    subtitle = if (vi) {
                        "Dynamic Island sẽ tự ẩn gọn khi ở trong màn hình DeX và hiện xác nhận khi bật/tắt"
                    } else {
                        "Dynamic Island hides automatically inside DeX and confirms start/stop cleanly"
                    },
                    icon = Icons.Rounded.PhonelinkSetup,
                    iconColor = BadgeColors.Violet,
                )
            }
        }

        // 5. Gesture Guide + Banking/VNeID Safety Card
        item {
            InfoCard(
                title = if (vi) "Cách điều khiển chuột ảo trong DeX" else "Virtual Mouse Controls in DeX",
                body = if (vi) {
                    "• 1 ngón tay: Click chuột & kéo thả thanh tiêu đề cửa sổ, chỉnh kích thước cửa sổ tự do (Freeform).\n" +
                        "• 2 ngón tay: Cuộn trang (bánh xe chuột).\n" +
                        "• Vuốt Quay lại (Back): Gửi phím Back trực tiếp vào ứng dụng DeX.\n" +
                        "• Nút ✕ nổi góc phải: Chạm để mở menu (Tắt DeX / Thu nhỏ chạy nền / Home / Đa nhiệm), nhấn giữ để kéo sang vị trí khác."
                } else {
                    "• 1 finger: Mouse click & drag window title handles / resize edges.\n" +
                        "• 2 fingers: Scroll wheel.\n" +
                        "• System Back gesture: Forwarded as Back inside DeX.\n" +
                        "• Floating ✕ button: Tap for quick menu (Stop DeX / Background / Home / Recents), long-press to drag anywhere."
                },
                icon = Icons.Rounded.TouchApp,
                color = BadgeColors.Blue,
            )
        }

        item {
            InfoCard(
                title = if (vi) "Tuân thủ chuẩn BreezeIsland · An toàn VNeID & Ngân hàng" else "BreezeIsland Rules · 100% VNeID & Banking Safe",
                body = if (vi) {
                    "Không cần Root, không cần máy tính, không khai báo Dịch vụ Trợ năng (Accessibility Service) và không sửa cài đặt overlay hiển thị toàn cục. Khi bạn tắt DeX, màn hình ảo được giải phóng sạch sẽ ngay lập tức."
                } else {
                    "Zero root, zero PC, zero AccessibilityService, and no global overlay_display_devices hacks. Stopping DeX tears down the virtual display cleanly."
                },
                icon = Icons.Rounded.Security,
                color = BadgeColors.Green,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
