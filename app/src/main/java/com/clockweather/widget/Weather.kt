package com.clockweather.widget

import android.content.Context
import android.text.format.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

data class Conditions(val tempC: Double, val code: Int, val isDay: Boolean, val windKmh: Double)

data class ForecastItem(val label: String, val conditions: Conditions)

data class Snapshot(val current: Conditions, val forecast: List<ForecastItem>)

/** The 18 icons; each has a colour-filled and an outline drawable. */
enum class Wx(val color: Int, val outline: Int, val label: String) {
    CLEAR(R.drawable.wx_clear, R.drawable.wx_clear_outline, "Clear"),
    CLEAR_NIGHT(R.drawable.wx_clear_night, R.drawable.wx_clear_night_outline, "Clear"),
    PARTLY_CLOUDY(R.drawable.wx_partly_cloudy, R.drawable.wx_partly_cloudy_outline, "Partly cloudy"),
    PARTLY_CLOUDY_NIGHT(R.drawable.wx_partly_cloudy_night, R.drawable.wx_partly_cloudy_night_outline, "Partly cloudy"),
    CLOUDY(R.drawable.wx_cloudy, R.drawable.wx_cloudy_outline, "Cloudy"),
    OVERCAST(R.drawable.wx_overcast, R.drawable.wx_overcast_outline, "Overcast"),
    FOG(R.drawable.wx_fog, R.drawable.wx_fog_outline, "Fog"),
    DRIZZLE(R.drawable.wx_drizzle, R.drawable.wx_drizzle_outline, "Drizzle"),
    RAIN(R.drawable.wx_rain, R.drawable.wx_rain_outline, "Rain"),
    HEAVY_RAIN(R.drawable.wx_heavy_rain, R.drawable.wx_heavy_rain_outline, "Heavy rain"),
    THUNDERSTORM(R.drawable.wx_thunderstorm, R.drawable.wx_thunderstorm_outline, "Thunderstorm"),
    SNOW(R.drawable.wx_snow, R.drawable.wx_snow_outline, "Snow"),
    SLEET(R.drawable.wx_sleet, R.drawable.wx_sleet_outline, "Sleet"),
    HAIL(R.drawable.wx_hail, R.drawable.wx_hail_outline, "Hail"),
    WINDY(R.drawable.wx_windy, R.drawable.wx_windy_outline, "Windy"),
    TORNADO(R.drawable.wx_tornado, R.drawable.wx_tornado_outline, "Tornado"),
    HOT(R.drawable.wx_hot, R.drawable.wx_hot_outline, "Hot"),
    SUNRISE(R.drawable.wx_sunrise, R.drawable.wx_sunrise_outline, "Sunrise");

    companion object {
        /** Maps a WMO weather code (as used by Open-Meteo) plus wind/temperature to an icon. */
        fun of(c: Conditions): Wx {
            val base = when (c.code) {
                0 -> CLEAR
                1 -> PARTLY_CLOUDY
                2 -> CLOUDY
                3 -> OVERCAST
                45, 48 -> FOG
                51, 53, 55 -> DRIZZLE
                56, 57, 66, 67 -> SLEET
                61, 63, 80, 81 -> RAIN
                65, 82 -> HEAVY_RAIN
                71, 73, 75, 77, 85, 86 -> SNOW
                95, 96 -> THUNDERSTORM
                99 -> HAIL
                else -> CLOUDY
            }
            if (base in setOf(CLEAR, PARTLY_CLOUDY, CLOUDY, OVERCAST) && c.windKmh >= WINDY_KMH) return WINDY
            if (base == CLEAR && c.isDay && c.tempC >= HOT_C) return HOT
            if (!c.isDay) {
                if (base == CLEAR) return CLEAR_NIGHT
                if (base == PARTLY_CLOUDY) return PARTLY_CLOUDY_NIGHT
            }
            return base
        }

        private const val WINDY_KMH = 40.0
        private const val HOT_C = 35.0
    }
}

fun formatTemp(tempC: Double, fahrenheit: Boolean): String {
    val v = if (fahrenheit) tempC * 9 / 5 + 32 else tempC
    var r = v.roundToInt()
    if (r == 0) r = 0 // avoid "-0"
    return "$r°"
}

/** Fetches forecasts from Open-Meteo (free, no API key) and caches the raw response. */
class WeatherRepository(private val context: Context) {
    private val p = context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)

    val fetchedAt: Long get() = p.getLong("fetched_at", 0L)

    fun save(json: String) {
        p.edit().putString("json", json).putLong("fetched_at", System.currentTimeMillis()).apply()
    }

    fun clear() = p.edit().clear().apply()

    fun snapshot(mode: ForecastMode): Snapshot? {
        val json = p.getString("json", null) ?: return null
        return try {
            parse(JSONObject(json), mode, System.currentTimeMillis() - fetchedAt)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(root: JSONObject, mode: ForecastMode, ageMs: Long): Snapshot? {
        val zone = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
        val now = LocalDateTime.now(zone)
        val hourly = root.getJSONObject("hourly")
        val hTimes = hourly.getJSONArray("time")
        val hours = (0 until hTimes.length()).mapNotNull { i ->
            val t = LocalDateTime.parse(hTimes.getString(i))
            val temp = hourly.getJSONArray("temperature_2m").optDouble(i)
            if (temp.isNaN()) return@mapNotNull null
            t to Conditions(
                temp,
                hourly.getJSONArray("weather_code").optInt(i, 3),
                hourly.getJSONArray("is_day").optInt(i, 1) == 1,
                hourly.getJSONArray("wind_speed_10m").optDouble(i, 0.0).orZero(),
            )
        }

        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val current = root.optJSONObject("current")
            ?.takeIf { ageMs < 75 * 60_000L }
            ?.let {
                Conditions(
                    it.getDouble("temperature_2m"), it.optInt("weather_code", 3),
                    it.optInt("is_day", 1) == 1, it.optDouble("wind_speed_10m", 0.0).orZero(),
                )
            }
            ?: hours.firstOrNull { it.first == thisHour }?.second
            ?: return null

        val forecast = when (mode) {
            ForecastMode.HOURLY -> {
                val fmt = if (DateFormat.is24HourFormat(context)) DateTimeFormatter.ofPattern("HH:mm")
                else DateTimeFormatter.ofPattern("ha", Locale.getDefault())
                hours.filter { it.first.isAfter(now) }.take(10).map { (t, c) ->
                    ForecastItem(t.format(fmt).replace(" ", ""), c)
                }
            }
            ForecastMode.DAILY -> {
                val daily = root.getJSONObject("daily")
                val dTimes = daily.getJSONArray("time")
                val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
                (0 until dTimes.length()).mapNotNull { i ->
                    val d = LocalDate.parse(dTimes.getString(i))
                    val max = daily.getJSONArray("temperature_2m_max").optDouble(i)
                    if (!d.isAfter(now.toLocalDate()) || max.isNaN()) return@mapNotNull null
                    ForecastItem(
                        d.format(fmt).trimEnd('.'),
                        Conditions(
                            max, daily.getJSONArray("weather_code").optInt(i, 3), true,
                            daily.getJSONArray("wind_speed_10m_max").optDouble(i, 0.0).orZero(),
                        ),
                    )
                }.take(10)
            }
        }
        return Snapshot(current, forecast)
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    companion object {
        suspend fun fetch(place: Place): String = httpGet(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${"%.4f".format(Locale.US, place.lat)}" +
                "&longitude=${"%.4f".format(Locale.US, place.lon)}" +
                "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,is_day,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max" +
                "&timezone=auto&forecast_days=11",
        )

        /** City search for the manual-location setting. */
        suspend fun searchCity(query: String): List<Place> {
            val body = httpGet(
                "https://geocoding-api.open-meteo.com/v1/search?count=10&format=json" +
                    "&language=${Locale.getDefault().language}" +
                    "&name=${URLEncoder.encode(query, "UTF-8")}",
            )
            val results = JSONObject(body).optJSONArray("results") ?: JSONArray()
            return (0 until results.length()).map { i ->
                val r = results.getJSONObject(i)
                val name = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                Place(r.getDouble("latitude"), r.getDouble("longitude"), name)
            }
        }

        private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
