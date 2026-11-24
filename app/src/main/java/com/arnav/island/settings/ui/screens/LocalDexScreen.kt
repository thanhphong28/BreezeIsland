package com.arnav.island.settings.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.arnav.island.dex.DexOutputMode
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

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(
            context,
            if (vi) "Đã sao chép: $text" else "Copied: $text",
            Toast.LENGTH_SHORT,
        ).show()
    }

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
    val extDetected = dexState.detectedExternalDisplay

    SettingsPage(
        title = if (vi) "Samsung DeX (Màn hình ngoài / PC)" else "Samsung DeX (Monitor / PC)",
        onBack = ui::back,
    ) {
        // 1. Master On/Off Switch + Live Output Status (Monitor / Projector / PC)
        item {
            Group(
                title = if (vi) "Bật / Tắt Samsung DeX cho Galaxy Z Flip" else "Samsung DeX Control",
                footer = if (vi) {
                    "Khi cắm cáp USB-C → HDMI ra màn hình rời / TV / máy chiếu (hoặc kết nối Smart View), Samsung DeX tự động xuất toàn màn hình ra màn hình ngoài và điện thoại biến thành Bàn rê chuột (Touchpad). Khi dùng với máy tính, chỉ cần mở địa chỉ DeX for PC trên Chrome/Edge."
                } else {
                    "Plug into an HDMI monitor, TV, or projector (or connect Smart View) to display Samsung DeX fullscreen externally while your phone acts as a Touchpad. On a PC, simply open the DeX for PC URL in Chrome or Edge."
                },
            ) {
                val subtitle = when {
                    dexState.running && !dexState.externalDisplayName.isNullOrBlank() ->
                        if (vi) "Đang xuất lên: ${dexState.externalDisplayName}"
                        else "Outputting to: ${dexState.externalDisplayName}"
                    dexState.running && dexState.pcClientCount > 0 ->
                        if (vi) "Đang chiếu lên máy tính (${dexState.pcClientCount} trình duyệt) · ${dexState.pcWebUrl.orEmpty()}"
                        else "Streaming to PC (${dexState.pcClientCount} connected) · ${dexState.pcWebUrl.orEmpty()}"
                    dexState.running ->
                        if (vi) "Đang chạy nền · Sẵn sàng xuất ra màn hình ngoài hoặc mở trên PC"
                        else "Running · Ready for external monitor or PC browser"
                    dexState.starting -> dexState.statusMessage.ifEmpty {
                        if (vi) "Đang khởi động Samsung DeX…" else "Starting Samsung DeX…"
                    }
                    extDetected != null && isReady ->
                        if (vi) "Đã nhận ${extDetected.label} · Gạt bật để xuất Samsung DeX ngay"
                        else "Detected ${extDetected.label} · Toggle on to launch DeX externally"
                    isReady -> {
                        val via = if (readyTransport == DexTransport.SHIZUKU) "Shizuku" else "Wireless ADB"
                        if (vi) "Sẵn sàng ($via) · Gạt bật cho màn hình ngoài hoặc PC"
                        else "Ready ($via) · Toggle on for external monitor or PC"
                    }
                    dexState.checkingConnection -> if (vi) "Đang kiểm tra kết nối…" else "Checking connection…"
                    else -> if (vi) "Cần cấp quyền Shizuku hoặc ghép nối ADB không dây bên dưới"
                    else "Allow Shizuku or pair Wireless ADB below first"
                }

                SwitchRow(
                    title = if (vi) "Bật Samsung DeX" else "Enable Samsung DeX",
                    checked = active,
                    onCheckedChange = { enable ->
                        if (!enable) {
                            ui.graph.localDex.stopDex(context)
                        } else {
                            val ok = ui.graph.localDex.startDex(context, forcePhoneViewer = false)
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

                // Live External Monitor / Projector / Smart View status
                SettingRow(
                    title = if (extDetected != null) {
                        if (vi) "Màn hình ngoài: ${extDetected.label}" else "External Display: ${extDetected.label}"
                    } else {
                        if (vi) "Chưa cắm màn hình rời (HDMI / Máy chiếu / Smart View)"
                        else "No external monitor detected (HDMI / Projector / Smart View)"
                    },
                    subtitle = if (extDetected != null) {
                        if (!dexState.externalDisplayName.isNullOrBlank()) {
                            if (vi) "Đang hiển thị Samsung DeX toàn màn hình trên màn hình ngoài · Có thể dùng điện thoại song song"
                            else "Samsung DeX is live fullscreen on the external display · Phone stays free to use"
                        } else {
                            if (vi) "Bật Samsung DeX để xuất toàn màn hình lên màn hình này"
                            else "Turn on Samsung DeX to project fullscreen onto this display"
                        }
                    } else {
                        if (vi) "Cắm cáp USB-C → HDMI hoặc bật Smart View tới TV/Máy chiếu/Windows để tự xuất DeX ngay"
                        else "Plug in USB-C → HDMI or connect Smart View to a TV/Projector/Windows PC — auto-attaches live"
                    },
                    icon = if (extDetected != null) Icons.Rounded.CastConnected else Icons.Rounded.Tv,
                    iconColor = if (extDetected != null) BadgeColors.Green else BadgeColors.Graphite,
                    onClick = { ui.graph.localDex.refreshStatus(forceAdbCheck = false) },
                )

                // Live controls while DeX is running
                if (active) {
                    SettingRow(
                        title = if (vi) "Mở Bàn rê chuột & Bàn phím DeX (Touchpad)" else "Open DeX Touchpad & Keyboard",
                        subtitle = if (vi) "Biến màn hình Z Flip thành bàn rê chuột (click trái/phải, kéo cửa sổ, cuộn, gõ chữ) cho màn hình ngoài"
                        else "Use your Z Flip screen as a trackpad & keyboard for the external DeX screen",
                        icon = Icons.Rounded.Mouse,
                        iconColor = BadgeColors.Cyan,
                        onClick = { ui.graph.localDex.openTouchpad(context) },
                    )

                    SettingRow(
                        title = if (vi) "Xem thử trực tiếp trên màn hình điện thoại" else "Preview on Phone Screen",
                        subtitle = if (vi) "Tùy chọn mở cửa sổ DeX ngay trên Z Flip nếu bạn chưa có màn hình ngoài"
                        else "Optional: open the DeX desktop directly on your phone screen",
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        iconColor = BadgeColors.Violet,
                        onClick = { ui.graph.localDex.openPhoneViewer(context) },
                    )
                }
            }
        }

        // 2. DeX for PC Card (Live Browser URL + USB Cable command when running!)
        if (active) {
            item {
                val pcUrl = dexState.pcWebUrl ?: "http://<phone-ip>:${dexState.pcWebPort}"
                val usbCmd = "adb forward tcp:${dexState.pcWebPort} tcp:${dexState.pcWebPort}"
                Group(
                    title = if (vi) "Chiếu Samsung DeX lên Máy tính (Windows / Mac)" else "Samsung DeX for PC (Windows / Mac)",
                    footer = if (vi) {
                        "Không cần cài phần mềm lên máy tính! Mở Chrome hoặc Edge trên PC và truy cập địa chỉ trên (cùng Wi-Fi) — hỗ trợ đầy đủ chuột thật (click trái/phải, kéo thả, cuộn) và bàn phím máy tính. Nếu cắm dây USB không có Wi-Fi, chạy lệnh adb forward rồi mở http://localhost:${dexState.pcWebPort}."
                    } else {
                        "Zero PC software needed! Open Chrome or Edge on your computer at the URL above (same Wi-Fi) for 60fps DeX with real PC mouse & keyboard. Over USB cable, run the adb forward command and open http://localhost:${dexState.pcWebPort}."
                    },
                ) {
                    SettingRow(
                        title = if (vi) "Mở trên trình duyệt PC: $pcUrl" else "Open on PC browser: $pcUrl",
                        subtitle = if (dexState.pcClientCount > 0) {
                            if (vi) "Đang kết nối ${dexState.pcClientCount} máy tính · Chạm để sao chép link"
                            else "${dexState.pcClientCount} PC browser connected · Tap to copy URL"
                        } else {
                            if (vi) "Chạm để sao chép · Gõ link này vào Chrome / Edge trên máy tính"
                            else "Tap to copy · Open in Chrome or Edge on Windows / Mac"
                        },
                        icon = Icons.Rounded.Computer,
                        iconColor = if (dexState.pcClientCount > 0) BadgeColors.Green else BadgeColors.Blue,
                        onClick = { copyText("DeX URL", pcUrl) },
                    )

                    SettingRow(
                        title = if (vi) "Cắm cáp USB vào PC (độ trễ siêu thấp)" else "USB Cable to PC (ultra-low latency)",
                        subtitle = if (vi) "Chạy trên PC: $usbCmd → mở http://localhost:${dexState.pcWebPort} (chạm để chép)"
                        else "Run on PC: $usbCmd → open http://localhost:${dexState.pcWebPort} (tap to copy)",
                        icon = Icons.Rounded.ContentCopy,
                        iconColor = BadgeColors.Indigo,
                        onClick = { copyText("ADB Forward", usbCmd) },
                    )

                    if (dexState.displayId >= 0) {
                        val scrcpyCmd = "scrcpy --display-id=${dexState.displayId}"
                        SettingRow(
                            title = if (vi) "Hoặc mở bằng scrcpy trên PC" else "Or open with desktop scrcpy",
                            subtitle = "$scrcpyCmd" + if (vi) " (chạm để sao chép)" else " (tap to copy)",
                            icon = Icons.Rounded.PhoneAndroid,
                            iconColor = BadgeColors.Teal,
                            onClick = { copyText("scrcpy", scrcpyCmd) },
                        )
                    }
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

        // 3. Output Target Mode
        item {
            Group(
                title = if (vi) "Chế độ xuất màn hình Samsung DeX" else "DeX Output Target",
                footer = if (vi) {
                    "Mặc định (Tự động): Nếu đang cắm dây HDMI/máy chiếu hoặc nối Smart View sẽ xuất thẳng lên màn hình ngoài & mở Touchpad trên ĐT; nếu không cắm màn hình sẽ bật DeX for PC để bạn mở trên máy tính mà không chiếm màn hình điện thoại."
                } else {
                    "Auto (Default): Outputs fullscreen to an external HDMI/Smart View monitor + phone Touchpad when connected; otherwise runs DeX for PC in background without taking over your phone screen."
                },
            ) {
                ChipsRow(
                    title = if (vi) "Khi bật Samsung DeX sẽ hiển thị ở đâu?" else "Where should DeX appear?",
                    options = DexOutputMode.entries,
                    selected = s.dexOutputMode,
                    label = { if (vi) it.labelVi else it.labelEn },
                    onSelect = { mode -> ui.update { it.copy(dexOutputMode = mode) } },
                )
            }
        }

        // 4. Dual-Engine Connection Setup (Shizuku + Wireless ADB)
        item {
            Group(
                title = if (vi) "Cầu kết nối trên máy (Không Root · Không Trợ năng)" else "On-Phone Bridge (Zero Root · Zero Accessibility)",
                footer = if (vi) {
                    "Ưu tiên Shizuku (chỉ cần 1 chạm và hoạt động cả khi dùng 4G/5G hoặc cắm dây HDMI không cần Wi-Fi). Hoặc ghép nối ADB không dây (LocalDex gốc) nếu không dùng Shizuku."
                } else {
                    "Shizuku is recommended (1-tap setup and works offline over HDMI without Wi-Fi). Alternatively, pair Wireless ADB directly."
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
                    dexState.shizukuPermitted -> if (vi) "Đã sẵn sàng · Bật DeX 1 chạm không cần Wi-Fi" else "Ready · 1-tap DeX launch without Wi-Fi"
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
                    title = if (vi) "Quét lại màn hình ngoài & kết nối" else "Refresh external display & bridge",
                    subtitle = if (dexState.checkingConnection) {
                        if (vi) "Đang kiểm tra màn hình ngoài, Shizuku & ADB…" else "Checking external displays, Shizuku & ADB…"
                    } else {
                        if (vi) "Làm mới danh sách màn hình HDMI/Smart View và trạng thái kết nối"
                        else "Re-scan HDMI/Smart View displays and bridge status"
                    },
                    icon = Icons.Rounded.Refresh,
                    iconColor = BadgeColors.Graphite,
                    onClick = { ui.graph.localDex.refreshStatus(forceAdbCheck = true) },
                )
            }
        }

        // 5. Resolution & DPI (Monitor / Projector / PC optimized)
        item {
            Group(
                title = if (vi) "Độ phân giải & DPI màn hình DeX" else "DeX Resolution & DPI",
                footer = if (vi) {
                    "Màn hình máy tính & máy chiếu nên dùng 1920x1080/160 (rộng rãi chuẩn desktop). Nếu chiếu lên TV xa, chọn 1920x1080/240 để chữ to rõ hơn."
                } else {
                    "For monitors & projectors, 1920x1080/160 gives a crisp desktop layout. For distant TVs, choose 1920x1080/240 for larger UI."
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
                                        if (vi) "Đã lưu độ phân giải DeX: $clean" else "Saved DeX spec: $clean",
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

        // 6. Viewer & Dynamic Island behavior
        item {
            Group(
                title = if (vi) "Tùy chọn hiển thị & Dynamic Island" else "Viewer & Island Behavior",
            ) {
                SwitchRow(
                    title = if (vi) "Khóa xoay ngang khi xem trên ĐT" else "Lock landscape for phone preview",
                    checked = s.dexLandscapeLock,
                    onCheckedChange = { v -> ui.update { it.copy(dexLandscapeLock = v) } },
                    subtitle = if (vi) "Áp dụng khi mở chế độ Xem thử trực tiếp trên điện thoại"
                    else "Applies when using the optional on-phone DeX preview",
                    icon = Icons.Rounded.ScreenRotation,
                    iconColor = BadgeColors.Teal,
                )
                SwitchRow(
                    title = if (vi) "Báo trạng thái DeX trên Dynamic Island" else "Show DeX pill on Dynamic Island",
                    checked = s.dexShowIslandPill,
                    onCheckedChange = { v -> ui.update { it.copy(dexShowIslandPill = v) } },
                    subtitle = if (vi) "Hiển thị xác nhận gọn trên Dynamic Island khi bật/tắt Samsung DeX"
                    else "Shows a brief confirmation pill on Dynamic Island when DeX starts or stops",
                    icon = Icons.Rounded.PhonelinkSetup,
                    iconColor = BadgeColors.Violet,
                )
            }
        }

        // 7. Quick Connection Guide + Banking/VNeID Safety Card
        item {
            InfoCard(
                title = if (vi) "3 cách dùng Samsung DeX trên màn hình lớn với Z Flip 5" else "3 Ways to Run DeX on a Big Screen",
                body = if (vi) {
                    "1. Cắm cáp USB-C → HDMI (Màn hình / TV / Máy chiếu): Cắm cáp vào Z Flip 5 rồi gạt Bật Samsung DeX — DeX hiện toàn màn hình bên màn hình rời, điện thoại thành Touchpad hoặc dùng song song.\n" +
                        "2. Không dây (Smart View / Miracast): Bật Smart View tới TV hoặc Windows (Chiếu lên PC này), rồi gạt Bật Samsung DeX.\n" +
                        "3. Trình duyệt PC (Windows / Mac — không cần cài app): Bật DeX rồi mở link http://<IP>:8765 trên Chrome/Edge (hoặc qua dây USB với adb forward) để dùng chuột & bàn phím máy tính thật."
                } else {
                    "1. USB-C → HDMI (Monitor / TV / Projector): Plug in and toggle DeX ON — DeX appears fullscreen externally while your phone becomes a Touchpad.\n" +
                        "2. Wireless (Smart View / Miracast): Connect Smart View to a TV or Windows PC, then toggle DeX ON.\n" +
                        "3. PC Browser (Chrome / Edge — zero install): Toggle DeX ON and open http://<IP>:8765 (or USB adb forward) with real PC mouse & keyboard."
                },
                icon = Icons.Rounded.Tv,
                color = BadgeColors.Blue,
            )
        }

        item {
            InfoCard(
                title = if (vi) "Tuân thủ chuẩn BreezeIsland · An toàn VNeID & Ngân hàng" else "BreezeIsland Rules · 100% VNeID & Banking Safe",
                body = if (vi) {
                    "Không cần Root, không khai báo Dịch vụ Trợ năng (Accessibility Service), không sửa overlay hiển thị toàn cục. Khi bạn tắt DeX, màn hình ảo và máy chủ PC tự đóng sạch sẽ tức thì."
                } else {
                    "Zero root, zero AccessibilityService, and no global overlay hacks. Stopping DeX closes the virtual display and PC streamer cleanly."
                },
                icon = Icons.Rounded.Security,
                color = BadgeColors.Green,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
