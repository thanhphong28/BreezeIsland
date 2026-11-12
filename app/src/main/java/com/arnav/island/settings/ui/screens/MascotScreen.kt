package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arnav.island.core.IslandCommand
import com.arnav.island.events.MascotMood
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
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
import java.util.Locale

@Composable
fun MascotScreen(s: IslandSettings, ui: Ui) {
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }

    fun triggerMascot(mood: MascotMood = s.mascotMood, withMusic: Boolean = false) {
        preview?.tests?.mascot(mood, s.mascotDurationMs, withMusic = withMusic)
        if (withMusic) {
            ui.graph.testEvents.music(playing = true, rich = false)
        }
        ui.graph.commands.tryEmit(IslandCommand.SummonMascot(mood))
    }

    SettingsPage("Breeze Mascot", onBack = ui::back) {
        item {
            IslandPreview(
                ui.graph,
                s,
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(vertical = 4.dp),
                onReady = {
                    preview = it
                    it.tests.mascot(s.mascotMood, s.mascotDurationMs)
                },
            )
        }

        item {
            Group(
                title = "Breeze Mascot (Thú cưng trên Đảo)",
                footer = "Khi Island đang nghỉ không hiện gì, bạn chỉ cần chạm nhẹ vào vùng camera để gọi Mascot xuất hiện. Chạm tiếp vào Mascot khi đang hiện để đổi biểu cảm tương tác!",
            ) {
                SwitchRow(
                    "Bật Breeze Mascot",
                    s.mascotEnabled,
                    { v -> ui.update { it.copy(mascotEnabled = v) } },
                    "Hiển thị thú cưng mắt phát sáng cùng dải hào quang Aurora dưới camera",
                    Icons.Rounded.EmojiEmotions,
                    BadgeColors.Cyan,
                )
                Reveal(s.mascotEnabled) {
                    SwitchRow(
                        "Chạm vào camera lúc nghỉ để gọi",
                        s.mascotTapToSummon,
                        { v -> ui.update { it.copy(mascotTapToSummon = v) } },
                        "Chạm vào vùng camera khi Island đang rảnh để Mascot xuất hiện ngay",
                        Icons.Rounded.TouchApp,
                        BadgeColors.Blue,
                    )
                }
                Reveal(s.mascotEnabled) {
                    SwitchRow(
                        "Tự động xuất hiện khi dùng máy",
                        s.mascotPeriodicEnabled,
                        { v -> ui.update { it.copy(mascotPeriodicEnabled = v) } },
                        "Dùng điện thoại lâu lâu Mascot sẽ tự nhô ra với các hoạt ảnh vui vẻ",
                        Icons.Rounded.Schedule,
                        BadgeColors.Violet,
                    )
                }
            }
        }

        item {
            Reveal(s.mascotEnabled) {
                Group(
                    title = "Thời gian & Chu kỳ xuất hiện",
                    footer = "Mascot tự động xuất hiện theo chu kỳ ngay cả khi bạn đang nghe nhạc trên Island (Mascot sẽ nhún nhảy cùng nốt nhạc rồi tự trả lại trình phát nhạc).",
                ) {
                    Reveal(s.mascotPeriodicEnabled) {
                        SliderRow(
                            "Chu kỳ tự xuất hiện",
                            s.mascotIntervalMinutes.toFloat(),
                            1f..60f,
                            { v -> ui.update { it.copy(mascotIntervalMinutes = v.toInt().coerceIn(1, 60)) } },
                            { "${it.toInt()} phút" },
                        )
                    }
                    SliderRow(
                        "Thời lượng hiển thị mỗi lần",
                        s.mascotDurationMs / 1000f,
                        2.5f..8.0f,
                        { v -> ui.update { it.copy(mascotDurationMs = (v * 1000).toLong()) } },
                        { String.format(Locale.US, "%.1f giây", it) },
                    )
                }
            }
        }

        item {
            Group(
                title = "Biểu cảm & Hoạt ảnh 120Hz",
                footer = "Chọn 'Ngẫu nhiên' để mỗi lần xuất hiện hoặc mỗi lần chạm, Mascot tự đổi các biểu cảm động: Vui vẻ (^ ^), Nháy mắt tinh nghịch (; O), Đáng yêu thả tim, Hào hứng và Thư giãn.",
            ) {
                ChipsRow(
                    options = MascotMood.entries,
                    selected = s.mascotMood,
                    label = { it.label },
                    onSelect = { mood ->
                        ui.update { it.copy(mascotMood = mood) }
                        triggerMascot(mood)
                    },
                )
                SettingRow(
                    title = "Gọi Breeze Mascot ngay",
                    subtitle = "Chạy thử hoạt ảnh Mascot động trực tiếp trên cả mô phỏng và đảo thật",
                    icon = Icons.Rounded.AutoAwesome,
                    iconColor = BadgeColors.Pink,
                    onClick = { triggerMascot(s.mascotMood) },
                )
                SettingRow(
                    title = "Thử đổi biểu cảm kế tiếp",
                    subtitle = "Giống như khi bạn chạm trực tiếp vào Mascot trên camera",
                    icon = Icons.Rounded.SentimentVerySatisfied,
                    iconColor = BadgeColors.Cyan,
                    onClick = { triggerMascot(MascotMood.AUTO) },
                )
                SettingRow(
                    title = "Thử kết hợp Mascot + Đang nghe nhạc",
                    subtitle = "Mascot xuất hiện nhún nhảy theo điệu nhạc ♪ ♫ rồi trả lại trình phát nhạc",
                    icon = Icons.Rounded.MusicNote,
                    iconColor = BadgeColors.Violet,
                    onClick = { triggerMascot(s.mascotMood, withMusic = true) },
                )
            }
        }

        item {
            InfoCard(
                title = "Mẹo tương tác thú vị",
                body = "• Khi rảnh tay, chạm 1 lần vào lỗ camera để gọi Mascot ra chơi.\n• Khi Mascot đang hiện, chạm liên tiếp vào Mascot để xem nó nhảy lên và đổi biểu cảm.\n• Nhấn giữ vào Mascot để phóng to kích thước cực đại!",
                icon = Icons.Rounded.Favorite,
                color = BadgeColors.Indigo,
            )
        }
    }
}
