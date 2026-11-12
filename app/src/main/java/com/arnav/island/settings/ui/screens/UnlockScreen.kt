package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arnav.island.overlay.ForegroundAppMonitor
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.permissions.Permissions
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
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings

@Composable
fun UnlockScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val vi = s.language == AppLanguage.VIETNAMESE
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    var permTick by remember { mutableIntStateOf(0) }

    val hasHardwareLog = remember(permTick) {
        OnPhoneGranter.hasReadLogsPermission(context)
    }
    val hasUsageAccess = remember(permTick) {
        ForegroundAppMonitor.hasUsageAccess(context)
    }

    SettingsPage(
        title = if (vi) "Hiệu ứng mở khóa 3D" else "3D Unlock & Biometrics",
        onBack = ui::back,
    ) {
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
            Group(title = if (vi) "Hoạt động & Nhận diện" else "Behaviour & Detection") {
                SwitchRow(
                    if (vi) "Hiệu ứng mở khóa 3D" else "3D Unlock animation",
                    s.unlockAnimationEnabled,
                    { v -> ui.update { it.copy(unlockAnimationEnabled = v) } },
                    if (vi) {
                        "Hiện hoạt ảnh vân tay & khóa 3D trên Dynamic Island khi mở khóa màn hình"
                    } else {
                        "Show 3D Dynamic Island animation when waking up and unlocking the device"
                    },
                    Icons.Rounded.PhonelinkLock,
                    BadgeColors.Indigo,
                )
                Reveal(s.unlockAnimationEnabled) {
                    SwitchRow(
                        if (vi) "Vân tay trong ứng dụng & Ngân hàng" else "In-app & Banking fingerprint",
                        s.unlockInAppBiometricEnabled,
                        { v -> ui.update { it.copy(unlockInAppBiometricEnabled = v) } },
                        if (vi) {
                            "Hiện hoạt ảnh 3D mỗi khi bạn dùng vân tay mở khóa app, đăng nhập Ngân hàng, Ví điện tử..."
                        } else {
                            "Trigger 3D animation whenever you authenticate with fingerprint inside banking apps or app locks"
                        },
                        Icons.Rounded.Fingerprint,
                        BadgeColors.Cyan,
                    )
                    SliderRow(
                        if (vi) "Thời lượng hiển thị" else "Display duration",
                        s.unlockDurationMs / 1000f,
                        1.6f..3.5f,
                        { v -> ui.update { it.copy(unlockDurationMs = (v * 1000).toLong()) } },
                        { String.format(java.util.Locale.US, "%.1f s", it) }
                    )
                }
            }
        }

        if (s.unlockAnimationEnabled && s.unlockInAppBiometricEnabled) {
            item {
                Group(
                    title = if (vi) "Độ nhạy nhận diện vân tay trong App" else "In-App Fingerprint Detection Engine",
                    footer = if (vi) {
                        "Kết hợp cảm biến phần cứng (qua Shizuku/ADB) và Usage Access mà không dùng Accessibility Service."
                    } else {
                        "Combines hardware biometric callbacks (via Shizuku/ADB) and Usage Access with zero Accessibility Service."
                    }
                ) {
                    SettingRow(
                        title = if (vi) {
                            if (hasHardwareLog) "Nhận diện phần cứng trực tiếp: Đã bật (100%)"
                            else "Kích hoạt nhận diện phần cứng 100% (1 chạm)"
                        } else {
                            if (hasHardwareLog) "Hardware Biometric Sensor Hook: Active (100%)"
                            else "Enable 100% Hardware Biometric Detection (1-tap)"
                        },
                        subtitle = if (vi) {
                            if (hasHardwareLog) "Bắt chính xác mọi lần chạm vân tay trong mọi app Ngân hàng, MoMo, Khóa ứng dụng (< 20ms)"
                            else "Bấm để cấp quyền qua Shizuku giúp phát hiện 100% mọi lần quét vân tay trong Ngân hàng & App"
                        } else {
                            if (hasHardwareLog) "Detects every fingerprint scan across all banking apps and app locks (< 20ms)"
                            else "Tap to grant via Shizuku for instant 100% in-app fingerprint detection"
                        },
                        icon = if (hasHardwareLog) Icons.Rounded.CheckCircle else Icons.Rounded.VerifiedUser,
                        iconColor = if (hasHardwareLog) BadgeColors.Green else BadgeColors.Orange,
                        onClick = {
                            OnPhoneGranter.grantOnPhone(context) {
                                permTick++
                            }
                        }
                    )

                    if (!hasUsageAccess) {
                        SettingRow(
                            title = if (vi) "Cấp quyền Truy cập dữ liệu sử dụng (Dự phòng)" else "Grant Usage Access (Fallback)",
                            subtitle = if (vi) {
                                "Giúp nhận diện hộp thoại vân tay hệ thống ngay cả khi không bật Shizuku"
                            } else {
                                "Detects system biometric dialogs even without Shizuku"
                            },
                            icon = Icons.Rounded.Fingerprint,
                            iconColor = BadgeColors.Blue,
                            onClick = {
                                runCatching { context.startActivity(Permissions.usageAccessSettings(context)) }
                                    .onFailure { runCatching { context.startActivity(Permissions.usageAccessFallback()) } }
                            }
                        )
                    }
                }
            }
        }

        item {
            Group(
                title = if (vi) "Xem thử hoạt ảnh 3D" else "3D Biometric Animation Preview",
                footer = if (vi) {
                    "Quét laser vân tay 3D -> Vòng xoay Gyro 3D 3 lớp chuẩn Face ID -> Lật sang ổ khóa 3D khối đặc tự động bật chốt."
                } else {
                    "3D laser fingerprint scan -> 3-axis Face ID gyroscope rings -> 3D volumetric padlock pop."
                }
            ) {
                SettingRow(
                    title = if (vi) "Chạy thử Mở khóa 3D ngay" else "Test 3D Unlock Animation",
                    subtitle = if (vi) "Kích hoạt hoạt ảnh mở khóa 3D trên cả khung xem trước và đảo thật" else "Trigger real-time 3D biometric scan & padlock unlock",
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
            Group(title = if (vi) "An toàn với Ngân hàng & VNeID" else "Security & Banking Protection") {
                InfoCard(
                    title = if (vi) "An toàn 100% với Ngân hàng & VNeID" else "100% Safe with Banking & Government Apps",
                    body = if (vi) {
                        "BreezeIsland KHÔNG sử dụng Accessibility Service (Trợ năng), không can thiệp giao diện và không đọc dữ liệu sinh trắc học. Tương thích tuyệt đối với VNeID và mọi ứng dụng ngân hàng."
                    } else {
                        "BreezeIsland uses ZERO Accessibility Services, ZERO root, and ZERO biometric data access. Fully compatible with VNeID and all banking apps."
                    },
                    icon = Icons.Rounded.Security,
                    color = BadgeColors.Green,
                )
            }
        }
    }
}
