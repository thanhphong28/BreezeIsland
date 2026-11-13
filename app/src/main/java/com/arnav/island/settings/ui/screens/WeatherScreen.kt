package com.arnav.island.settings.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.core.IslandCommand
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.Reveal
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.tr
import com.arnav.island.storage.IslandSettings
import kotlin.math.roundToInt

data class PresetCity(
    val name: String,
    val lat: Float,
    val lon: Float,
)

private val VIETNAM_CITIES = listOf(
    PresetCity("TP. Hồ Chí Minh", 10.7769f, 106.7009f),
    PresetCity("Hà Nội", 21.0285f, 105.8542f),
    PresetCity("Đà Nẵng", 16.0544f, 108.2022f),
    PresetCity("Cần Thơ", 10.0452f, 105.7469f),
    PresetCity("Hải Phòng", 20.8449f, 106.6881f),
    PresetCity("Nha Trang", 12.2388f, 109.1967f),
    PresetCity("Đà Lạt", 11.9404f, 108.4583f),
    PresetCity("Huế", 16.4637f, 107.5909f),
    PresetCity("Vũng Tàu", 10.3460f, 107.0843f),
    PresetCity("Biên Hòa", 10.9574f, 106.8426f),
    PresetCity("Buôn Ma Thuột", 12.6667f, 108.0500f),
    PresetCity("Quy Nhơn", 13.7820f, 109.2197f),
    PresetCity("Vinh", 18.6796f, 105.6813f),
    PresetCity("Hạ Long", 20.9599f, 107.0425f),
    PresetCity("Phú Quốc", 10.2899f, 103.9840f),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherScreen(s: IslandSettings, ui: Ui) {
    val weather by ui.graph.weather.latestWeather.collectAsStateWithLifecycle()

    SettingsPage(tr("Weather Island", "Thời tiết trên Đảo"), onBack = ui::back) {
        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = LocalIslandColors.current.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.WbSunny,
                            contentDescription = null,
                            tint = BadgeColors.Yellow,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = weather?.cityName ?: s.weatherCityName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = weather?.let {
                                    "${it.conditionLabel} · C:${it.highTempC}° T:${it.lowTempC}° · Cảm giác ${it.feelsLikeC}°C"
                                } ?: tr("Real-time forecast & rain alerts", "Dự báo thời gian thực & cảnh báo mưa thông minh"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "${weather?.currentTempC ?: 31}°",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (weather != null) {
                        val w = weather!!
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            WeatherStatPill(Icons.Rounded.Umbrella, tr("Rain", "Mưa"), "${w.rainProbPercent}%")
                            WeatherStatPill(Icons.Rounded.WaterDrop, tr("Humidity", "Độ ẩm"), "${w.humidityPercent}%")
                            WeatherStatPill(Icons.Rounded.Air, tr("Wind", "Gió"), "${w.windKmh} km/h")
                            WeatherStatPill(Icons.Rounded.WbSunny, "UV", "${w.uvIndex.roundToInt()}")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            ui.update { it.copy(weatherEnabled = true) }
                            ui.graph.weather.showNow()
                            ui.graph.commands.tryEmit(IslandCommand.ShowWeather())
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Show Weather on Island now", "Hiển thị Thời tiết lên Đảo ngay"))
                    }
                }
            }
        }

        item {
            Group(
                title = tr("Smart Weather modes", "Chế độ hiển thị Thời tiết hợp lý"),
                footer = tr(
                    "Inspired by iOS Weather Live Activities & RainViewer: the island stays quiet when the sky is normal, and pops up automatically when rain is coming or when you start your morning.",
                    "Lấy cảm hứng từ iOS Weather Live Activities & RainViewer: Đảo sẽ giữ yên lặng khi trời bình thường, chỉ tự động bung ra khi sắp có mưa/dông hoặc chào buổi sáng lúc bạn mở máy (nhấn giữ vào đảo lúc rảnh cũng luôn thấy nhiệt độ)."
                ),
            ) {
                SwitchRow(
                    tr("Enable Weather on Island", "Kết nối Thời tiết với Đảo"),
                    s.weatherEnabled,
                    { v -> ui.update { it.copy(weatherEnabled = v) } },
                    tr(
                        "Updates forecast automatically & shows weather in Glance card",
                        "Tự động cập nhật dự báo & hiển thị nhiệt độ trong thẻ Xem nhanh (Glance)"
                    ),
                    Icons.Rounded.Cloud,
                    BadgeColors.Cyan,
                )
                SwitchRow(
                    tr("Rain & storm live alerts", "Cảnh báo mưa & dông bão tự động"),
                    s.weatherRainAlerts,
                    { v -> ui.update { it.copy(weatherRainAlerts = v) } },
                    tr(
                        "Automatically alerts on Dynamic Island when rain probability ≥ 60% in the next 2 hours",
                        "Tự động báo trên Đảo khi phát hiện sắp mưa (≥ 60%) hoặc có dông trong 2 giờ tới"
                    ),
                    Icons.Rounded.Thunderstorm,
                    BadgeColors.Blue,
                )
                SwitchRow(
                    tr("Morning weather briefing", "Bản tin thời tiết buổi sáng"),
                    s.weatherMorningBriefing,
                    { v -> ui.update { it.copy(weatherMorningBriefing = v) } },
                    tr(
                        "Greets you with today's temperature & High/Low on your first morning unlock",
                        "Chào buổi sáng kèm nhiệt độ & Cao/Thấp khi bạn mở khóa điện thoại đầu ngày"
                    ),
                    Icons.Rounded.WbTwilight,
                    BadgeColors.Orange,
                )
                Reveal(s.weatherMorningBriefing) {
                    SliderRow(
                        tr("Morning briefing from", "Bắt đầu chào buổi sáng từ"),
                        s.weatherMorningHour.toFloat(),
                        5f..10f,
                        { v -> ui.update { it.copy(weatherMorningHour = v.roundToInt()) } },
                        { "${it.roundToInt()}:00" },
                        steps = 4,
                    )
                }
                SwitchRow(
                    tr("Pin Live Weather Pill", "Ghim viên thuốc Thời tiết trực tiếp (Live Pill)"),
                    s.weatherLivePill,
                    { v -> ui.update { it.copy(weatherLivePill = v) } },
                    tr(
                        "Always show animated weather icon & temperature on the compact island pill",
                        "Luôn hiển thị icon thời tiết động & nhiệt độ trực tiếp trên viên thuốc Dynamic Island"
                    ),
                    Icons.Rounded.WbSunny,
                    BadgeColors.Yellow,
                )
            }
        }

        item {
            Group(
                title = tr("Location", "Khu vực / Thành phố"),
                footer = tr(
                    "Uses Open-Meteo weather data for your selected city without requiring background GPS permission.",
                    "Sử dụng dữ liệu khí tượng từ Open-Meteo theo thành phố bạn chọn mà không cần xin quyền định vị GPS chạy ngầm gây hao pin."
                ),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = tr("Selected: ${s.weatherCityName}", "Đang chọn: ${s.weatherCityName}"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        VIETNAM_CITIES.forEach { city ->
                            val selected = s.weatherCityName == city.name
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    ui.update {
                                        it.copy(
                                            weatherEnabled = true,
                                            weatherCityName = city.name,
                                            weatherLat = city.lat,
                                            weatherLon = city.lon,
                                        )
                                    }
                                },
                                label = { Text(city.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherStatPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
