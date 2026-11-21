package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.Reveal
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings

@Composable
fun SearchAutoLaunchScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val vi = s.language == AppLanguage.VIETNAMESE
    val status by ui.graph.searchAutoLaunch.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ui.graph.searchAutoLaunch.refreshStatus()
    }
    LifecycleResumeEffect(Unit) {
        ui.graph.searchAutoLaunch.refreshStatus()
        onPauseOrDispose { }
    }

    val ready = status.shizukuPermitted && status.usageAccessGranted

    SettingsPage(
        title = if (vi) "Mở nhanh khi tìm App" else "Instant Search Launch",
        onBack = ui::back,
    ) {
        // 1. Master Switch
        item {
            Group(
                title = if (vi) "Hoạt động trên Màn hình chính gốc" else "Native Home & Finder Search",
                footer = if (vi) {
                    "Khi bạn vuốt lên ở Màn hình chính One UI / Samsung Finder để tìm ứng dụng, ngay lúc gõ chỉ còn đúng 1 ứng dụng khớp kết quả, máy sẽ tự động mở luôn ứng dụng đó mà không cần bấm chọn."
                } else {
                    "When swiping up on One UI Home / Samsung Finder to search apps, the moment only 1 matching app remains it automatically launches without an extra tap."
                },
            ) {
                SwitchRow(
                    title = if (vi) "Tự mở khi tìm còn 1 ứng dụng" else "Auto-open single search result",
                    checked = s.searchAutoLaunchEnabled,
                    onCheckedChange = { on ->
                        ui.update { it.copy(searchAutoLaunchEnabled = on) }
                        if (on && !ready) {
                            ui.graph.searchAutoLaunch.requestPermissions(context)
                        }
                    },
                    subtitle = if (vi) {
                        if (!s.searchAutoLaunchEnabled) "Đang tắt · Bật để dùng ngay với trình tìm kiếm gốc"
                        else if (ready) "Đang hoạt động · Gắn trực tiếp One UI Home & Finder"
                        else "Đã bật · Cần cấp quyền Shizuku bên dưới để kích hoạt"
                    } else {
                        if (!s.searchAutoLaunchEnabled) "Off · Enable for native Home & Finder search"
                        else if (ready) "Active · Hooked into One UI Home & Finder"
                        else "Enabled · Complete Shizuku setup below"
                    },
                    icon = Icons.Rounded.Search,
                    iconColor = BadgeColors.Blue,
                )
            }
        }

        // 2. Live Status & 1-Tap Shizuku + Usage Access Setup (Zero AccessibilityService)
        item {
            Group(
                title = if (vi) "Kết nối không dùng Trợ năng (Shizuku)" else "Zero-Accessibility Setup (Shizuku)",
                footer = if (vi) {
                    "BreezeIsland dùng dịch vụ Shizuku (UID 2000) chạy nền nhẹ — hoàn toàn KHÔNG dùng Dịch vụ Trợ năng (Accessibility Service). Khi bạn rời Màn hình chính để mở app, kết nối tự ngắt tức thì (< 2ms)."
                } else {
                    "Powered by a lightweight Shizuku service with ZERO AccessibilityService. Disconnects immediately (< 2ms) whenever you leave Home/Finder."
                },
            ) {
                val shizukuSubtitle = when {
                    status.remoteStatus.startsWith("error:") || status.remoteStatus.startsWith("bind_error:") ->
                        if (vi) "Lỗi kết nối: ${status.remoteStatus}" else "Bridge error: ${status.remoteStatus}"
                    status.shizukuPermitted -> if (vi) {
                        if (status.serviceBound) "Đã kết nối trực tiếp với One UI Home & Finder (${status.remoteStatus})"
                        else "Đã cấp quyền · Tự kích hoạt ngay khi bạn về Màn hình chính"
                    } else {
                        if (status.serviceBound) "Connected to One UI Home & Finder (${status.remoteStatus})"
                        else "Granted · Activates automatically on Home Screen"
                    }
                    status.shizukuRunning -> if (vi) "Shizuku đang chạy · Bấm để cho phép 1 chạm" else "Shizuku running · Tap to allow"
                    status.shizukuInstalled -> if (vi) "Hãy mở app Shizuku và bấm Khởi động (Start) trước" else "Open Shizuku and tap Start first"
                    else -> if (vi) "Chưa cài Shizuku · Cài Shizuku từ Play Store để không cần dùng Trợ năng" else "Install Shizuku from Play Store (no Accessibility needed)"
                }

                SettingRow(
                    title = if (vi) {
                        if (status.shizukuPermitted) "Kết nối Shizuku: Sẵn sàng"
                        else "Cấp quyền Shizuku (1 chạm)"
                    } else {
                        if (status.shizukuPermitted) "Shizuku Bridge: Ready"
                        else "Allow Shizuku (1-tap)"
                    },
                    subtitle = shizukuSubtitle,
                    icon = if (status.shizukuPermitted) Icons.Rounded.CheckCircle else Icons.Rounded.VerifiedUser,
                    iconColor = if (status.shizukuPermitted) BadgeColors.Green else BadgeColors.Orange,
                    onClick = { ui.graph.searchAutoLaunch.requestPermissions(context) },
                )

                PermissionRow(
                    title = if (vi) "Quyền Truy cập dữ liệu sử dụng" else "Usage Access",
                    subtitle = if (vi) {
                        "Nhận biết khi bạn ở Màn hình chính để bật và tự ngắt ngay khi mở VNeID / Ngân hàng"
                    } else {
                        "Activates only on Home/Finder and disconnects immediately for other apps"
                    },
                    granted = status.usageAccessGranted,
                    icon = Icons.Rounded.QueryStats,
                    color = BadgeColors.Cyan,
                    onGrant = {
                        if (status.shizukuPermitted) {
                            ui.graph.searchAutoLaunch.requestPermissions(context)
                        } else {
                            ui.open(Permissions.usageAccessSettings(context), Permissions.usageAccessFallback())
                        }
                    },
                    grantLabel = if (vi) "Cấp quyền" else "Allow",
                )

                if (status.lastLaunchedLabel != null) {
                    SettingRow(
                        title = if (vi) "Lần mở nhanh gần nhất: ${status.lastLaunchedLabel}" else "Last auto-launch: ${status.lastLaunchedLabel}",
                        subtitle = if (vi) "Từ khóa tìm kiếm: \"${status.lastLaunchedQuery.orEmpty()}\"" else "Matched query: \"${status.lastLaunchedQuery.orEmpty()}\"",
                        icon = Icons.Rounded.Bolt,
                        iconColor = BadgeColors.Yellow,
                    )
                }
            }
        }

        // 3. Customization Controls
        item {
            Reveal(s.searchAutoLaunchEnabled) {
                Group(
                    title = if (vi) "Tùy chỉnh tốc độ & độ chính xác" else "Speed & Accuracy",
                    footer = if (vi) {
                        "Hỗ trợ tìm kiếm tiếng Việt không dấu (vd: \"dong ho\" -> Đồng hồ) và viết tắt (vd: \"fb\" -> Facebook, \"zl\" -> Zalo). Nếu có 2 app trùng tên (như Dual Messenger Zalo), máy sẽ giữ nguyên để bạn tự chọn."
                    } else {
                        "Supports diacritic-free Vietnamese search and initials. If Dual Messenger shows 2 icons side by side, it waits for your tap."
                    },
                ) {
                    SliderRow(
                        title = if (vi) "Độ trễ trước khi tự mở" else "Auto-launch delay",
                        value = s.searchAutoLaunchDelayMs.toFloat(),
                        range = 0f..600f,
                        onValueChange = { v ->
                            val snapped = ((v / 20f).toInt() * 20L).coerceIn(0L, 600L)
                            ui.update { it.copy(searchAutoLaunchDelayMs = snapped) }
                        },
                        format = { ms ->
                            val rounded = (ms / 20f).toInt() * 20
                            if (rounded <= 0) (if (vi) "Tức thì (0 ms)" else "Instant (0 ms)") else "$rounded ms"
                        },
                        subtitle = if (vi) "Nên để khoảng 200–250 ms để tránh mở nhầm khi bạn đang gõ nhanh" else "200–250 ms prevents accidental launches while typing fast",
                    )

                    ChipsRow(
                        title = if (vi) "Số ký tự tối thiểu trước khi tự mở" else "Minimum characters",
                        options = listOf(1, 2, 3),
                        selected = s.searchAutoLaunchMinChars,
                        label = { n ->
                            if (vi) {
                                if (n == 1) "$n ký tự (Mặc định)" else "$n ký tự"
                            } else {
                                if (n == 1) "1 char (Default)" else "$n chars"
                            }
                        },
                        onSelect = { n -> ui.update { it.copy(searchAutoLaunchMinChars = n) } },
                    )

                    SwitchRow(
                        title = if (vi) "Không tự mở khi đang bấm Xóa (Backspace)" else "Pause while deleting (Backspace)",
                        checked = s.searchAutoLaunchSkipWhenDeleting,
                        onCheckedChange = { v -> ui.update { it.copy(searchAutoLaunchSkipWhenDeleting = v) } },
                        subtitle = if (vi) "Giúp bạn xóa chữ sửa lỗi gõ mà không bị nhảy nhầm vào app khác" else "Prevents launching an app when backspacing a typo",
                        icon = Icons.AutoMirrored.Rounded.Backspace,
                        iconColor = BadgeColors.Indigo,
                    )

                    SwitchRow(
                        title = if (vi) "Báo trên Dynamic Island & Rung nhẹ" else "Dynamic Island pill & haptic tick",
                        checked = s.searchAutoLaunchFeedback,
                        onCheckedChange = { v -> ui.update { it.copy(searchAutoLaunchFeedback = v) } },
                        subtitle = if (vi) "Hiển thị nhanh tên ứng dụng vừa tự mở kèm phản hồi rung tinh tế" else "Show brief confirmation pill and subtle haptic on auto-launch",
                        icon = Icons.Rounded.TouchApp,
                        iconColor = BadgeColors.Pink,
                    )
                }
            }
        }

        // 4. Safety Info Card
        item {
            InfoCard(
                title = if (vi) "An toàn tuyệt đối với VNeID & Ngân hàng" else "100% Safe with VNeID & Banking Apps",
                body = if (vi) {
                    "Tính năng này KHÔNG khai báo và KHÔNG bật Dịch vụ Trợ năng (Accessibility Service) trong Cài đặt máy. Kết nối Shizuku chỉ hoạt động đúng lúc bạn đang ở Màn hình chính One UI / Samsung Finder và tự động ngắt hoàn toàn ngay khi bạn mở bất kỳ ứng dụng nào khác."
                } else {
                    "Zero AccessibilityService declared or enabled in Android Settings. The Shizuku bridge only attaches while on One UI Home / Finder and disconnects immediately whenever any other app opens."
                },
                icon = Icons.Rounded.Security,
                color = BadgeColors.Green,
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
