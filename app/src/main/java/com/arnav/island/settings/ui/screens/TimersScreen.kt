package com.arnav.island.settings.ui.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.tr
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Launch
import kotlinx.coroutines.delay
import java.util.Date

private val TimerOrange = Color(0xFFFFA23A)

@Composable
fun TimersScreen(s: IslandSettings, p: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val isVi = s.language == AppLanguage.VIETNAMESE
    val nextAlarm by produceState<AlarmManager.AlarmClockInfo?>(initialValue = null, context) {
        val am = context.getSystemService(AlarmManager::class.java)
        while (true) {
            value = runCatching { am?.nextAlarmClock }.getOrNull()
            delay(2_000L)
        }
    }

    SettingsPage(tr("Timers & stopwatch", "Đồng hồ, Báo thức & Bấm giờ"), onBack = ui::back) {
        if (!p.notificationAccess) {
            item {
                InfoCard(
                    title = tr("Notification access needed", "Cần cấp Quyền truy cập thông báo"),
                    body = tr(
                        "Grant Notification Access so BreezeIsland can sync active Timers, Stopwatch, and Alarms directly from your phone's default Clock app.",
                        "Cấp Quyền truy cập thông báo để BreezeIsland tự động kết nối và hiển thị trực tiếp Hẹn giờ, Bấm giờ và Báo thức từ ứng dụng Đồng hồ mặc định của điện thoại.",
                    ),
                    icon = Icons.Rounded.NotificationsActive,
                    color = BadgeColors.Orange,
                    actionLabel = tr("Grant access", "Cấp quyền"),
                    onAction = { ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback()) },
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = LocalIslandColors.current.card,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Alarm, null, tint = TimerOrange)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            tr("System Clock Integration", "Đồng bộ Đồng hồ điện thoại"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val alarmInfo = nextAlarm
                    if (alarmInfo != null && alarmInfo.triggerTime > System.currentTimeMillis()) {
                        val timeStr = DateFormat.getTimeFormat(context).format(Date(alarmInfo.triggerTime))
                        Text(
                            if (isVi) "Báo thức kế tiếp: $timeStr" else "Next alarm: $timeStr",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TimerOrange,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            if (isVi) "Chưa có báo thức nào đang bật" else "No upcoming alarm set",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr(
                            "Start any alarm, timer, or stopwatch inside your phone's Clock app — BreezeIsland automatically displays and controls it right on the Dynamic Island.",
                            "Khi bạn bật Báo thức, Hẹn giờ hoặc Bấm giờ trong ứng dụng Đồng hồ của điện thoại, BreezeIsland sẽ tự động hiển thị và cho phép điều khiển trực tiếp ngay trên Dynamic Island.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { openPhoneClockApp(context, AlarmClock.ACTION_SHOW_ALARMS, nextAlarm) },
                        colors = ButtonDefaults.buttonColors(containerColor = TimerOrange, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Open Phone Clock App", "Mở ứng dụng Đồng hồ của máy"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = { openPhoneClockApp(context, AlarmClock.ACTION_SHOW_TIMERS, nextAlarm) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.Timer, null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Timers", "Hẹn giờ"))
                        }
                        FilledTonalButton(
                            onClick = { openPhoneClockApp(context, "android.intent.action.SHOW_STOPWATCH", nextAlarm) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Rounded.Timelapse, null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Stopwatch", "Bấm giờ"))
                        }
                    }
                }
            }
        }

        item {
            Group(
                title = tr("Dynamic Island Sync", "Hiển thị trên Dynamic Island"),
                footer = tr(
                    "All controls (Pause, Resume, Lap, Reset, Dismiss) on the Dynamic Island control your phone's native Clock app directly.",
                    "Mọi nút bấm (Tạm dừng, Tiếp tục, Vòng chạy, Đặt lại, Tắt báo thức) trên Dynamic Island đều điều khiển trực tiếp ứng dụng Đồng hồ gốc của điện thoại.",
                ),
            ) {
                SwitchRow(
                    tr("Alarm set & ringing alerts", "Thông báo khi bật Báo thức & Chuông báo"),
                    s.alarmsEnabled,
                    { v -> ui.update { it.copy(alarmsEnabled = v) } },
                    tr(
                        "Shows remaining time on the island when you toggle an alarm in Clock",
                        "Hiển thị giờ báo thức và thời gian đổ chuông còn lại trên đảo khi bạn bật báo thức",
                    ),
                    Icons.Rounded.AlarmOn,
                    BadgeColors.Orange,
                )
                SwitchRow(
                    tr("Sync system Timers", "Đồng bộ Hẹn giờ (Timer) của máy"),
                    s.timersEnabled,
                    { v -> ui.update { it.copy(timersEnabled = v) } },
                    tr(
                        "Live countdown from your phone's Clock app",
                        "Hiển thị đếm ngược trực tiếp từ ứng dụng Đồng hồ của điện thoại",
                    ),
                    Icons.Rounded.Timer,
                    BadgeColors.Orange,
                )
                SwitchRow(
                    tr("Sync system Stopwatch", "Đồng bộ Bấm giờ (Stopwatch) của máy"),
                    s.stopwatchEnabled,
                    { v -> ui.update { it.copy(stopwatchEnabled = v) } },
                    tr(
                        "Live stopwatch & laps from your phone's Clock app",
                        "Hiển thị bấm giờ và vòng chạy trực tiếp từ ứng dụng Đồng hồ của điện thoại",
                    ),
                    Icons.Rounded.Timelapse,
                    BadgeColors.Orange,
                )
            }
        }
    }
}

private fun openPhoneClockApp(context: Context, action: String, alarmInfo: AlarmManager.AlarmClockInfo?) {
    val launchedAction = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
    if (launchedAction) return

    val showPi = alarmInfo?.showIntent
    if (showPi != null && Launch.send(context, showPi)) return

    val clockPackages = listOf(
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.oneplus.deskclock",
        "com.android.bbkclock",
    )
    for (pkg in clockPackages) {
        if (Launch.openApp(context, pkg)) return
    }
    runCatching {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
