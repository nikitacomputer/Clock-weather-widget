package com.clockweather.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.appwidget.AppWidgetHostView
import android.widget.ListView
import org.robolectric.RuntimeEnvironment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Renders the real widget RemoteViews offscreen at several widths, checks the geometry of the
 * rotated forecast list and writes PNGs to build/widget-renders for visual inspection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetRenderTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        WeatherRepository(ctx).save(sampleJson().toString())
        Settings(ctx).fahrenheit = false
    }

    private fun render(widthDp: Float, heightDp: Float = 80f): ViewGroup {
        val s = Settings(ctx)
        val rv = WidgetRenderer.render(ctx, SizeF(widthDp, heightDp), s, WeatherRepository(ctx).snapshot(s.forecastMode))
        val d = ctx.resources.displayMetrics.density
        val host = AppWidgetHostView(ctx)
        host.setPadding(0, 0, 0, 0)
        host.addView(rv.apply(ctx, host))
        val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
        repeat(3) {
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return host
    }

    private fun save(host: ViewGroup, name: String) {
        val bmp = Bitmap.createBitmap(host.width + 40, host.height + 40, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(Color.rgb(60, 70, 90))
        c.translate(20f, 20f)
        host.draw(c)
        val dir = File("build/widget-renders").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun rendersAllWidths() {
        // approx. launcher cell widths for 3..8 columns
        for ((cells, w) in listOf(3 to 203f, 4 to 276f, 5 to 349f, 6 to 422f, 7 to 495f, 8 to 568f)) {
            val host = render(w)
            save(host, "daily_${cells}cells")
            val list = host.findViewById<ListView>(R.id.forecast_list)
            val container = host.findViewById<View>(R.id.forecast_container)
            if (cells == 3) {
                assertEquals(View.GONE, container.visibility)
                continue
            }
            assertEquals(View.VISIBLE, container.visibility)
            // The rotated list must exactly cover its container.
            val rf = android.graphics.RectF(0f, 0f, list.width.toFloat(), list.height.toFloat())
            list.matrix.mapRect(rf); rf.offset(list.left.toFloat(), list.top.toFloat())
            val r = Rect(); rf.round(r)
            assertTrue("cells=$cells hit=$r container=${container.width}x${container.height}",
                abs(r.left) <= 2 && abs(r.top) <= 2 && abs(r.right - container.width) <= 2 && abs(r.bottom - container.height) <= 2)
            if (cells == 8) assertTrue("8 cells should show all 10 columns, saw ${list.childCount}", list.childCount >= 10)
        }
    }

    @Test
    fun rendersVariants() {
        val s = Settings(ctx)
        s.forecastMode = ForecastMode.HOURLY
        s.fontScheme = FontScheme.WHITE
        s.outlineWhite = true
        s.cornerRadius = 20
        s.bgColor = 0xFF3B4A5A.toInt()
        s.opacity = 70
        save(render(422f), "hourly_white_r20")
    }

    private fun sampleJson(): JSONObject {
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
        val codes = listOf(0, 1, 2, 3, 45, 51, 61, 65, 95, 71, 66, 99)
        val hTime = JSONArray(); val hTemp = JSONArray(); val hCode = JSONArray(); val hDay = JSONArray(); val hWind = JSONArray()
        for (i in -2 until 48) {
            val t = now.plusHours(i.toLong())
            hTime.put(t.toString()); hTemp.put(10 + i % 7); hCode.put(codes[(i + 2) % codes.size])
            hDay.put(if (t.hour in 7..18) 1 else 0); hWind.put(10)
        }
        val dTime = JSONArray(); val dMax = JSONArray(); val dMin = JSONArray(); val dCode = JSONArray(); val dWind = JSONArray()
        for (i in 0 until 11) {
            dTime.put(now.toLocalDate().plusDays(i.toLong()).toString()); dMax.put(-3 + i * 3); dMin.put(-8 + i)
            dCode.put(codes[i]); dWind.put(if (i == 10) 50 else 12)
        }
        return JSONObject()
            .put("utc_offset_seconds", 0)
            .put("current", JSONObject().put("temperature_2m", 18.4).put("weather_code", 1).put("is_day", 1).put("wind_speed_10m", 8))
            .put("hourly", JSONObject().put("time", hTime).put("temperature_2m", hTemp).put("weather_code", hCode).put("is_day", hDay).put("wind_speed_10m", hWind))
            .put("daily", JSONObject().put("time", dTime).put("temperature_2m_max", dMax).put("temperature_2m_min", dMin).put("weather_code", dCode).put("wind_speed_10m_max", dWind))
    }
}
