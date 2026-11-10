package com.clockweather.widget

import android.content.Context
import android.graphics.Color
import java.util.Locale

/** Text colour pairs (setting 1.3). Forecast outline icons follow [main]. */
enum class FontScheme(val main: Int, val secondary: Int) {
    BLACK(0xFF1A1A1A.toInt(), 0xFF6B6B6B.toInt()),
    WHITE(0xFFFFFFFF.toInt(), 0xFFB9B9B9.toInt()),
}

enum class ForecastMode { DAILY, HOURLY }

enum class LocationMode { DEVICE, MANUAL }

data class Place(val lat: Double, val lon: Double, val name: String)

/** All user settings. They are global: every placed widget shares them. */
class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // 1.1 / 1.2
    var bgColor: Int
        get() = p.getInt("bg_color", DEFAULT_BG)
        set(v) = p.edit().putInt("bg_color", v or 0xFF000000.toInt()).apply()
    var opacity: Int // 0..100
        get() = p.getInt("opacity", 100)
        set(v) = p.edit().putInt("opacity", v.coerceIn(0, 100)).apply()

    // 1.3
    var fontScheme: FontScheme
        get() = enumOr(p.getString("font_scheme", null), FontScheme.BLACK)
        set(v) = p.edit().putString("font_scheme", v.name).apply()

    // 1.4
    var outlineWhite: Boolean
        get() = p.getBoolean("outline_white", false)
        set(v) = p.edit().putBoolean("outline_white", v).apply()
    val outlineColor: Int get() = if (outlineWhite) Color.WHITE else Color.BLACK

    // 1.5
    var cornerRadius: Int
        get() = p.getInt("corner_radius", 0)
        set(v) = p.edit().putInt("corner_radius", RADII.minByOrNull { kotlin.math.abs(it - v) }!!).apply()

    // 1.6: outline width in dp, also used for the separator
    var outlineWidth: Int
        get() = p.getInt("outline_width", 2).coerceIn(1, 5)
        set(v) = p.edit().putInt("outline_width", v.coerceIn(1, 5)).apply()

    // 2.1 / 2.2: package names, null = default
    var clockApp: String?
        get() = p.getString("clock_app", null)
        set(v) = p.edit().putString("clock_app", v).apply()
    var weatherApp: String?
        get() = p.getString("weather_app", null)
        set(v) = p.edit().putString("weather_app", v).apply()

    // 3
    var forecastMode: ForecastMode
        get() = enumOr(p.getString("forecast_mode", null), ForecastMode.DAILY)
        set(v) = p.edit().putString("forecast_mode", v.name).apply()

    var fahrenheit: Boolean
        get() = p.getBoolean("fahrenheit", Locale.getDefault().country in FAHRENHEIT_COUNTRIES)
        set(v) = p.edit().putBoolean("fahrenheit", v).apply()

    var locationMode: LocationMode
        get() = enumOr(p.getString("location_mode", null), LocationMode.DEVICE)
        set(v) = p.edit().putString("location_mode", v.name).apply()

    var manualPlace: Place?
        get() = readPlace("manual")
        set(v) = writePlace("manual", v)

    /** Last device location we managed to get (used when a fresh fix is unavailable). */
    var lastDevicePlace: Place?
        get() = readPlace("last")
        set(v) = writePlace("last", v)

    private fun readPlace(prefix: String): Place? {
        if (!p.contains("${prefix}_lat")) return null
        return Place(
            java.lang.Double.longBitsToDouble(p.getLong("${prefix}_lat", 0)),
            java.lang.Double.longBitsToDouble(p.getLong("${prefix}_lon", 0)),
            p.getString("${prefix}_name", "") ?: "",
        )
    }

    private fun writePlace(prefix: String, place: Place?) {
        val e = p.edit()
        if (place == null) {
            e.remove("${prefix}_lat").remove("${prefix}_lon").remove("${prefix}_name")
        } else {
            e.putLong("${prefix}_lat", java.lang.Double.doubleToRawLongBits(place.lat))
                .putLong("${prefix}_lon", java.lang.Double.doubleToRawLongBits(place.lon))
                .putString("${prefix}_name", place.name)
        }
        e.apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        /** Teal from the app-tile reference. */
        const val DEFAULT_BG = 0xFF87CAC1.toInt()
        val RADII = intArrayOf(0, 5, 10, 15, 20, 25, 30)
        private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")
    }
}
