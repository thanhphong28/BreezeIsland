package com.arnav.island.events.weather

import android.content.Context
import android.content.Intent
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.WeatherCondition
import com.arnav.island.events.WeatherHourlyItem
import com.arnav.island.events.WeatherPayload
import com.arnav.island.island.render.presenters.WeatherPresenter
import com.arnav.island.settings.MainActivity
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Smart Weather monitor for Dynamic Island (modeled after iOS 18 Weather Live Activities & RainViewer):
 * 1. Fetches real-time weather & hourly forecast via Open-Meteo API (no API key or sensitive GPS permission required).
 * 2. Smart Rain / Storm Alert: automatically pops a toast on the island when rain >= 60% or storm is coming within 2 hours.
 * 3. Morning Weather Briefing: automatically greets the user with today's temperature & high/low on their first morning session.
 * 4. Optional Live Weather Pill (`weatherLivePill`): keeps a live temperature & animated weather icon pill on the island.
 * 5. Feeds current weather summary to the Glance card and Settings preview.
 */
class WeatherMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> IslandSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var pollJob: Job? = null
    private var lastFetchMs: Long = 0L
    private var lastFetchCity: String = ""
    private var lastRainAlertMs: Long = 0L
    private var lastMorningDayOfYear: Int = -1

    private val _latestWeather = MutableStateFlow<WeatherPayload?>(null)
    val latestWeather: StateFlow<WeatherPayload?> = _latestWeather.asStateFlow()

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            delay(1_500L)
            refreshIfNeeded(force = true)
            while (isActive) {
                delay(15 * 60 * 1_000L)
                refreshIfNeeded(force = false)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        engine.remove(LIVE_ID)
        engine.remove(TOAST_ID)
    }

    fun onSettingsChanged(s: IslandSettings) {
        if (!s.weatherEnabled) {
            engine.remove(LIVE_ID)
            engine.remove(TOAST_ID)
            return
        }
        val cityChanged = s.weatherCityName != lastFetchCity
        if (cityChanged) {
            scope.launch {
                refreshIfNeeded(force = true)
            }
        } else {
            syncLivePill(s, _latestWeather.value)
        }
    }

    fun onUserPresent() {
        val s = settingsProvider()
        if (!s.weatherEnabled) return
        scope.launch {
            refreshIfNeeded(force = false)
            val payload = _latestWeather.value ?: return@launch
            checkMorningBriefing(s, payload)
        }
    }

    /**
     * Immediately fetches (if needed) and displays the Weather card on Dynamic Island.
     */
    fun showNow(customHeadline: String? = null) {
        scope.launch {
            var payload = _latestWeather.value
            if (payload == null || clock() - lastFetchMs > 10 * 60 * 1_000L) {
                payload = fetchWeatherFromApi(settingsProvider()) ?: buildFallbackWeather(settingsProvider())
                _latestWeather.value = payload
            }
            val finalPayload = if (customHeadline != null) {
                payload.copy(alertHeadline = customHeadline)
            } else {
                payload
            }
            postWeatherToast(finalPayload, durationMs = 7_500L)
        }
    }

    /**
     * Called when a weather notification from Samsung Weather / Google Weather is intercepted.
     */
    fun onExternalWeatherNotification(title: String, sub: String) {
        val s = settingsProvider()
        if (!s.weatherEnabled) return
        val base = _latestWeather.value ?: buildFallbackWeather(s)
        val extractedTemp = TEMP_REGEX.find("$title $sub")?.groupValues?.getOrNull(1)?.toIntOrNull()
        val updated = base.copy(
            currentTempC = extractedTemp ?: base.currentTempC,
            alertHeadline = title.takeIf { it.isNotBlank() },
            conditionLabel = sub.takeIf { it.isNotBlank() } ?: base.conditionLabel
        )
        _latestWeather.value = updated
        postWeatherToast(updated, durationMs = 6_500L)
    }

    fun glanceSummary(): Pair<Glyph, String>? {
        val s = settingsProvider()
        if (!s.weatherEnabled) return null
        val w = _latestWeather.value ?: return null
        val glyph = WeatherPresenter.glyphForCondition(w.condition)
        return glyph to "${w.currentTempC}°C · ${w.conditionLabel}"
    }

    private suspend fun refreshIfNeeded(force: Boolean) {
        val s = settingsProvider()
        if (!s.weatherEnabled) {
            engine.remove(LIVE_ID)
            return
        }
        val now = clock()
        val shouldFetch = force ||
            _latestWeather.value == null ||
            s.weatherCityName != lastFetchCity ||
            (now - lastFetchMs >= 30 * 60 * 1_000L)

        val payload = if (shouldFetch) {
            fetchWeatherFromApi(s)?.also {
                lastFetchMs = now
                lastFetchCity = s.weatherCityName
                _latestWeather.value = it
            } ?: _latestWeather.value ?: buildFallbackWeather(s).also {
                _latestWeather.value = it
            }
        } else {
            _latestWeather.value
        } ?: return

        syncLivePill(s, payload)
        checkRainAlert(s, payload)
        checkMorningBriefing(s, payload)
    }

    private fun syncLivePill(s: IslandSettings, payload: WeatherPayload?) {
        if (!s.weatherEnabled || !s.weatherLivePill || payload == null) {
            engine.remove(LIVE_ID)
            return
        }
        val accent = WeatherPresenter.accentColorFor(payload.condition)
        val openAction = buildOpenWeatherAction()
        engine.post(
            IslandEvent(
                id = LIVE_ID,
                source = EventSource.WEATHER,
                type = EventType.WEATHER,
                priority = EventPriority.MEDIA,
                timestamp = clock(),
                persistent = true,
                title = "${payload.currentTempC}°C · ${payload.cityName}",
                subtitle = payload.conditionLabel,
                icon = WeatherPresenter.glyphForCondition(payload.condition),
                colors = EventColors(accent = accent),
                tapAction = openAction,
                actions = listOf(openAction),
                payload = payload
            )
        )
    }

    private fun checkRainAlert(s: IslandSettings, payload: WeatherPayload) {
        if (!s.weatherRainAlerts) return
        val now = clock()
        if (now - lastRainAlertMs < 3 * 60 * 60 * 1_000L) return

        val upcomingRain = payload.hourly.take(2).firstOrNull {
            it.rainProbPercent >= 60 ||
                it.condition == WeatherCondition.RAIN ||
                it.condition == WeatherCondition.HEAVY_RAIN ||
                it.condition == WeatherCondition.THUNDERSTORM
        }
        val isCurrentlyRaining = payload.condition == WeatherCondition.RAIN ||
            payload.condition == WeatherCondition.HEAVY_RAIN ||
            payload.condition == WeatherCondition.THUNDERSTORM ||
            payload.rainProbPercent >= 65

        if (upcomingRain != null || isCurrentlyRaining) {
            lastRainAlertMs = now
            val headline = when {
                payload.condition == WeatherCondition.THUNDERSTORM || upcomingRain?.condition == WeatherCondition.THUNDERSTORM ->
                    "Cảnh báo dông mưa sắp tới"
                upcomingRain != null ->
                    "Sắp có mưa lúc ${upcomingRain.hourLabel} (${upcomingRain.rainProbPercent}%)"
                else ->
                    "Khả năng mưa cao (${payload.rainProbPercent}%)"
            }
            postWeatherToast(payload.copy(alertHeadline = headline), durationMs = 7_500L)
        }
    }

    private fun checkMorningBriefing(s: IslandSettings, payload: WeatherPayload) {
        if (!s.weatherMorningBriefing) return
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        if (dayOfYear == lastMorningDayOfYear) return
        if (hour in s.weatherMorningHour..(s.weatherMorningHour + 3).coerceAtMost(11)) {
            lastMorningDayOfYear = dayOfYear
            val headline = "Chào buổi sáng · ${payload.cityName}"
            postWeatherToast(payload.copy(alertHeadline = headline), durationMs = 7_500L)
        }
    }

    private fun postWeatherToast(payload: WeatherPayload, durationMs: Long) {
        val accent = WeatherPresenter.accentColorFor(payload.condition)
        val openAction = buildOpenWeatherAction()
        engine.post(
            IslandEvent(
                id = TOAST_ID,
                source = EventSource.WEATHER,
                type = EventType.WEATHER,
                priority = EventPriority.CHARGING,
                timestamp = clock(),
                persistent = false,
                durationMs = durationMs,
                title = "${payload.currentTempC}°C · ${payload.cityName}",
                subtitle = payload.conditionLabel,
                icon = WeatherPresenter.glyphForCondition(payload.condition),
                colors = EventColors(accent = accent),
                tapAction = openAction,
                actions = listOf(openAction),
                payload = payload
            )
        )
    }

    private fun buildOpenWeatherAction(): IslandAction {
        return IslandAction(
            id = WeatherPresenter.ACTION_OPEN,
            label = "Mở Thời tiết",
            glyph = Glyph.OPEN,
            collapses = true
        ) {
            val pm = context.packageManager
            val weatherPackages = listOf(
                "com.sec.android.daemonapp", // Samsung Weather
                "com.samsung.android.weather",
                "com.google.android.googlequicksearchbox",
                "com.miui.weather2",
                "com.coloros.weather2"
            )
            var launched = false
            for (pkg in weatherPackages) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        context.startActivity(intent)
                        launched = true
                    }
                    if (launched) break
                }
            }
            if (!launched) {
                val fallback = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { context.startActivity(fallback) }
            }
        }
    }

    private suspend fun fetchWeatherFromApi(s: IslandSettings): WeatherPayload? =
        withContext(Dispatchers.IO) {
            runCatching {
                val urlStr = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
                        "&hourly=temperature_2m,precipitation_probability,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min,uv_index_max,precipitation_probability_max" +
                        "&timezone=auto&forecast_days=2",
                    s.weatherLat,
                    s.weatherLon
                )
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseOpenMeteoJson(body, s.weatherCityName)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    private fun parseOpenMeteoJson(jsonStr: String, cityName: String): WeatherPayload {
        val root = JSONObject(jsonStr)
        val current = root.getJSONObject("current")
        val tempC = current.optDouble("temperature_2m", 30.0).roundToInt()
        val feelsLikeC = current.optDouble("apparent_temperature", tempC.toDouble()).roundToInt()
        val humidity = current.optInt("relative_humidity_2m", 70)
        val windKmh = current.optDouble("wind_speed_10m", 10.0).roundToInt()
        val isDay = current.optInt("is_day", 1) == 1
        val weatherCode = current.optInt("weather_code", 1)

        val daily = root.optJSONObject("daily")
        val highC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, (tempC + 3).toDouble())?.roundToInt() ?: (tempC + 3)
        val lowC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, (tempC - 4).toDouble())?.roundToInt() ?: (tempC - 4)
        val uvIndex = daily?.optJSONArray("uv_index_max")?.optDouble(0, 6.0)?.toFloat() ?: 6f
        val dailyRainMax = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, 20) ?: 20

        val hourlyObj = root.optJSONObject("hourly")
        val hourlyTimes = hourlyObj?.optJSONArray("time")
        val hourlyTemps = hourlyObj?.optJSONArray("temperature_2m")
        val hourlyRain = hourlyObj?.optJSONArray("precipitation_probability")
        val hourlyCodes = hourlyObj?.optJSONArray("weather_code")

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hourlyItems = ArrayList<WeatherHourlyItem>(4)
        var currentRainProb = dailyRainMax

        if (hourlyTimes != null && hourlyTemps != null) {
            val startIdx = currentHour.coerceIn(0, (hourlyTimes.length() - 1).coerceAtLeast(0))
            if (hourlyRain != null && startIdx < hourlyRain.length()) {
                currentRainProb = hourlyRain.optInt(startIdx, dailyRainMax)
            }
            val offsets = listOf(1, 3, 6, 9)
            for (offset in offsets) {
                val idx = startIdx + offset
                if (idx >= hourlyTimes.length()) break
                val rawTime = hourlyTimes.optString(idx, "")
                val timeLabel = rawTime.substringAfter('T', "").take(5).ifBlank { "${(currentHour + offset) % 24}:00" }
                val hTemp = hourlyTemps.optDouble(idx, tempC.toDouble()).roundToInt()
                val hRain = hourlyRain?.optInt(idx, 0) ?: 0
                val hCode = hourlyCodes?.optInt(idx, weatherCode) ?: weatherCode
                val slotHour = (currentHour + offset) % 24
                hourlyItems += WeatherHourlyItem(
                    hourLabel = timeLabel,
                    tempC = hTemp,
                    condition = mapWmoCondition(hCode, isDay = slotHour in 6..18),
                    rainProbPercent = hRain
                )
            }
        }

        val condition = mapWmoCondition(weatherCode, isDay)
        val conditionLabel = labelForWmoCode(weatherCode, isDay)

        return WeatherPayload(
            cityName = cityName,
            currentTempC = tempC,
            feelsLikeC = feelsLikeC,
            highTempC = highC,
            lowTempC = lowC,
            condition = condition,
            conditionLabel = conditionLabel,
            alertHeadline = null,
            humidityPercent = humidity,
            windKmh = windKmh,
            uvIndex = uvIndex,
            rainProbPercent = currentRainProb,
            hourly = hourlyItems,
            updatedAt = clock()
        )
    }

    private fun buildFallbackWeather(s: IslandSettings): WeatherPayload {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isDay = hour in 6..18
        return WeatherPayload(
            cityName = s.weatherCityName,
            currentTempC = if (isDay) 32 else 27,
            feelsLikeC = if (isDay) 35 else 29,
            highTempC = 34,
            lowTempC = 26,
            condition = if (isDay) WeatherCondition.PARTLY_CLOUDY else WeatherCondition.CLEAR_NIGHT,
            conditionLabel = if (isDay) "Nắng nhẹ có mây" else "Trời quang mây",
            humidityPercent = 72,
            windKmh = 12,
            uvIndex = if (isDay) 7f else 1f,
            rainProbPercent = 25,
            hourly = listOf(
                WeatherHourlyItem("${(hour + 1) % 24}:00", if (isDay) 32 else 27, WeatherCondition.PARTLY_CLOUDY, 20),
                WeatherHourlyItem("${(hour + 3) % 24}:00", if (isDay) 31 else 26, WeatherCondition.CLOUDY, 30),
                WeatherHourlyItem("${(hour + 6) % 24}:00", 28, WeatherCondition.RAIN, 45),
                WeatherHourlyItem("${(hour + 9) % 24}:00", 27, if (isDay) WeatherCondition.CLEAR_NIGHT else WeatherCondition.CLEAR_DAY, 15)
            ),
            updatedAt = clock()
        )
    }

    companion object {
        const val LIVE_ID = "weather:live"
        const val TOAST_ID = "weather:toast"
        private val TEMP_REGEX = Regex("""(-?\d{1,2})\s*°""")

        fun mapWmoCondition(code: Int, isDay: Boolean): WeatherCondition = when (code) {
            0 -> if (isDay) WeatherCondition.CLEAR_DAY else WeatherCondition.CLEAR_NIGHT
            1, 2 -> WeatherCondition.PARTLY_CLOUDY
            3, 45, 48 -> WeatherCondition.CLOUDY
            51, 53, 55, 56, 57, 61, 63, 66, 80, 81 -> WeatherCondition.RAIN
            65, 67, 82 -> WeatherCondition.HEAVY_RAIN
            71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
            95, 96, 99 -> WeatherCondition.THUNDERSTORM
            else -> WeatherCondition.PARTLY_CLOUDY
        }

        fun labelForWmoCode(code: Int, isDay: Boolean): String = when (code) {
            0 -> if (isDay) "Trời nắng quang" else "Trời quang đãng"
            1 -> if (isDay) "Nắng nhẹ" else "Ít mây"
            2 -> "Có mây rải rác"
            3 -> "Nhiều mây"
            45, 48 -> "Sương mù"
            51, 53, 55 -> "Mưa phùn nhẹ"
            61, 80 -> "Mưa rào nhẹ"
            63, 81 -> "Mưa vừa"
            65, 82 -> "Mưa to"
            95, 96, 99 -> "Mưa dông & sấm sét"
            else -> "Có mây"
        }
    }
}
