package com.clockweather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.view.View
import android.widget.RemoteViews
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the widget's RemoteViews for an exact size (in dp).
 *
 * Layout rules (width is what changes, the widget is always one row tall):
 *  - too narrow for even one forecast column → only the main cluster, centred (≈3 cells)
 *  - otherwise main cluster | separator | forecast stripe; columns are at least
 *    [minColumn] wide, so the stripe scrolls until all 10 fit (≈8 cells).
 */
object WidgetRenderer {
    private const val OUTLINE = 2f
    private const val SEPARATOR = 2f
    private const val MAX_ITEMS = 10

    private val RADIUS_FILL = intArrayOf(
        R.drawable.bg_fill_r0, R.drawable.bg_fill_r5, R.drawable.bg_fill_r10, R.drawable.bg_fill_r15,
        R.drawable.bg_fill_r20, R.drawable.bg_fill_r25, R.drawable.bg_fill_r30,
    )
    private val RADIUS_OUTLINE = intArrayOf(
        R.drawable.bg_outline_r0, R.drawable.bg_outline_r5, R.drawable.bg_outline_r10, R.drawable.bg_outline_r15,
        R.drawable.bg_outline_r20, R.drawable.bg_outline_r25, R.drawable.bg_outline_r30,
    )

    /** One RemoteViews per size the launcher reports (portrait / landscape …). */
    fun build(context: Context, options: Bundle?): RemoteViews {
        val settings = Settings(context)
        val snapshot = WeatherRepository(context).snapshot(settings.forecastMode)
        val sizes = sizesFrom(options)
        return if (sizes.size == 1) render(context, sizes[0], settings, snapshot)
        else RemoteViews(sizes.associateWith { render(context, it, settings, snapshot) })
    }

    @Suppress("DEPRECATION")
    private fun sizesFrom(options: Bundle?): List<SizeF> {
        val list: List<SizeF>? = if (Build.VERSION.SDK_INT >= 33) {
            options?.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        } else {
            options?.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        }
        if (!list.isNullOrEmpty()) return list.filter { it.width > 0 && it.height > 0 }.distinct().take(16)
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (minW > 0 && minH > 0) {
            return listOf(SizeF(minW.toFloat(), maxH.toFloat()), SizeF(maxW.toFloat(), minH.toFloat())).distinct()
        }
        return listOf(SizeF(276f, 80f))
    }

    fun render(context: Context, size: SizeF, settings: Settings, snapshot: Snapshot?): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget)
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).roundToInt()

        // ---- background, outline, corners ----
        val radiusIdx = Settings.RADII.indexOf(settings.cornerRadius).coerceAtLeast(0)
        rv.setImageViewResource(R.id.bg_fill, RADIUS_FILL[radiusIdx])
        rv.setInt(R.id.bg_fill, "setColorFilter", settings.bgColor or 0xFF000000.toInt())
        rv.setInt(R.id.bg_fill, "setImageAlpha", settings.opacity * 255 / 100)
        rv.setImageViewResource(R.id.bg_outline, RADIUS_OUTLINE[radiusIdx])
        rv.setInt(R.id.bg_outline, "setColorFilter", settings.outlineColor)

        val padH = OUTLINE + max(4f, settings.cornerRadius * 0.35f)
        val padV = OUTLINE
        rv.setViewPadding(R.id.content, px(padH), px(padV), px(padH), px(padV))
        val wc = max(size.width - 2 * padH, 40f)
        val hc = max(size.height - 2 * padV, 24f)

        val scheme = settings.fontScheme
        val tempText = snapshot?.let { formatTemp(it.current.tempC, settings.fahrenheit) } ?: "--°"

        // ---- decide layout ----
        var m = MainMetrics.compute(hc, tempText)
        val minColumn = (hc * 0.42f).coerceIn(30f, 60f)
        val stripe = wc - m.width - SEPARATOR
        val showForecast = stripe >= minColumn
        if (!showForecast) m = m.scaledToFit(wc)

        // ---- main cluster ----
        rv.setViewLayoutWidth(R.id.main_cluster, if (showForecast) m.width else wc, COMPLEX_UNIT_DIP)
        rv.setViewPadding(R.id.main_cluster, px(m.edge), 0, px(m.edge), 0)
        rv.setTextViewTextSize(R.id.time, COMPLEX_UNIT_DIP, m.textSize)
        rv.setTextColor(R.id.time, scheme.main)
        rv.setViewLayoutMargin(R.id.time, RemoteViews.MARGIN_END, m.gapAfterTime, COMPLEX_UNIT_DIP)
        rv.setViewLayoutWidth(R.id.current_icon, m.iconSize, COMPLEX_UNIT_DIP)
        rv.setViewLayoutHeight(R.id.current_icon, m.iconSize, COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.current_icon, RemoteViews.MARGIN_END, m.gapAfterIcon, COMPLEX_UNIT_DIP)
        rv.setTextViewTextSize(R.id.current_temp, COMPLEX_UNIT_DIP, m.textSize)
        rv.setTextColor(R.id.current_temp, scheme.secondary)
        rv.setTextViewText(R.id.current_temp, tempText)
        if (snapshot != null) {
            val wx = Wx.of(snapshot.current)
            rv.setViewVisibility(R.id.current_icon, View.VISIBLE)
            rv.setImageViewResource(R.id.current_icon, wx.color) // colour icon, always black outlines
            rv.setContentDescription(R.id.current_icon, wx.label)
        } else {
            rv.setViewVisibility(R.id.current_icon, View.GONE)
        }

        // ---- taps ----
        rv.setOnClickPendingIntent(R.id.time, launchIntent(context, LaunchActivity.ACTION_CLOCK, 1))
        rv.setOnClickPendingIntent(
            R.id.current_box,
            if (snapshot != null) toggleUnitIntent(context) else settingsIntent(context),
        )

        // ---- separator + forecast ----
        if (!showForecast) {
            rv.setViewVisibility(R.id.separator, View.GONE)
            rv.setViewVisibility(R.id.forecast_container, View.GONE)
            return rv
        }
        rv.setViewVisibility(R.id.separator, View.VISIBLE)
        rv.setViewVisibility(R.id.forecast_container, View.VISIBLE)
        rv.setInt(R.id.separator, "setBackgroundColor", settings.outlineColor)
        val sepGap = max(4f, hc * 0.12f)
        rv.setViewLayoutMargin(R.id.separator, RemoteViews.MARGIN_TOP, sepGap, COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.separator, RemoteViews.MARGIN_BOTTOM, sepGap, COMPLEX_UNIT_DIP)

        val columns = floor(stripe / minColumn).toInt().coerceIn(1, MAX_ITEMS)
        val colW = stripe / columns

        // The list is laid out as (hc wide × stripe tall) and rotated -90° about its centre, so
        // it must be shifted right by (stripe - hc) / 2 to land exactly on the stripe.
        rv.setViewLayoutWidth(R.id.forecast_list, hc, COMPLEX_UNIT_DIP)
        rv.setViewLayoutHeight(R.id.forecast_list, stripe, COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.forecast_list, RemoteViews.MARGIN_LEFT, (stripe - hc) / 2f, COMPLEX_UNIT_DIP)

        val weatherTap = launchIntent(context, LaunchActivity.ACTION_WEATHER, 2)
        rv.setOnClickPendingIntent(R.id.forecast_container, weatherTap)
        rv.setPendingIntentTemplate(R.id.forecast_list, launchTemplate(context))

        val items = snapshot?.forecast.orEmpty()
        val labelSize = min(hc * 0.125f, fitText(items.map { it.label }, colW * 0.92f))
        val temps = items.map { formatTemp(it.conditions.tempC, settings.fahrenheit) }
        val tempSize = min(hc * 0.18f, fitText(temps, colW * 0.92f))
        val iconSize = min(hc * 0.42f, colW * 0.85f)

        val builder = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        items.forEachIndexed { i, item ->
            val v = RemoteViews(context.packageName, R.layout.forecast_item)
            v.setViewLayoutHeight(R.id.item_root, colW, COMPLEX_UNIT_DIP)
            v.setViewLayoutWidth(R.id.item_content, colW, COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(R.id.item_content, hc, COMPLEX_UNIT_DIP)
            v.setViewPadding(R.id.item_content, 0, px(hc * 0.075f), 0, 0)
            // ~15% day/hour, ~45% icon, ~25% temperature
            v.setViewLayoutHeight(R.id.item_label, hc * 0.15f, COMPLEX_UNIT_DIP)
            v.setTextViewTextSize(R.id.item_label, COMPLEX_UNIT_DIP, labelSize)
            v.setTextColor(R.id.item_label, scheme.secondary)
            v.setTextViewText(R.id.item_label, item.label)

            val wx = Wx.of(item.conditions)
            v.setViewLayoutHeight(R.id.item_icon, hc * 0.45f, COMPLEX_UNIT_DIP)
            val iconPad = px(max(0f, (hc * 0.45f - iconSize) / 2f))
            v.setViewPadding(R.id.item_icon, 0, iconPad, 0, iconPad)
            v.setImageViewResource(R.id.item_icon, wx.outline)
            v.setInt(R.id.item_icon, "setColorFilter", scheme.main) // outline icons follow main colour
            v.setContentDescription(R.id.item_icon, wx.label)

            v.setViewLayoutHeight(R.id.item_temp, hc * 0.25f, COMPLEX_UNIT_DIP)
            v.setTextViewTextSize(R.id.item_temp, COMPLEX_UNIT_DIP, tempSize)
            v.setTextColor(R.id.item_temp, scheme.main)
            v.setTextViewText(R.id.item_temp, temps[i])

            v.setOnClickFillInIntent(R.id.item_root, Intent())
            builder.addItem(i.toLong(), v)
        }
        rv.setRemoteAdapter(R.id.forecast_list, builder.build())
        return rv
    }

    /** Sizes of the fixed left cluster, all in dp. */
    private data class MainMetrics(
        val textSize: Float, val iconSize: Float, val edge: Float,
        val gapAfterTime: Float, val gapAfterIcon: Float, val timeW: Float, val tempW: Float,
    ) {
        val width get() = edge + timeW + gapAfterTime + iconSize + gapAfterIcon + tempW + edge + 2f

        fun scaledToFit(available: Float): MainMetrics {
            if (width <= available) return this
            val s = available / width
            return MainMetrics(textSize * s, iconSize * s, edge * s, gapAfterTime * s, gapAfterIcon * s, timeW * s, tempW * s)
        }

        companion object {
            fun compute(hc: Float, tempText: String): MainMetrics {
                val text = (hc * 0.36f).coerceIn(14f, 46f)
                val icon = (hc * 0.54f).coerceIn(18f, 64f)
                // Same font size for time and temperature; widths measured at that size.
                return MainMetrics(
                    textSize = text, iconSize = icon, edge = hc * 0.11f,
                    gapAfterTime = hc * 0.10f, gapAfterIcon = hc * 0.03f,
                    timeW = measure("00:00", text), tempW = measure(tempText, text),
                )
            }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

    private fun measure(text: String, sizeDp: Float): Float = synchronized(paint) {
        paint.textSize = sizeDp
        paint.measureText(text)
    }

    /** Largest text size (dp) at which every string fits in [width] dp. */
    private fun fitText(texts: List<String>, width: Float): Float {
        val widest = texts.maxOfOrNull { measure(it, 100f) } ?: return 100f
        return if (widest <= 0f) 100f else width / widest * 100f
    }

    private fun launchIntent(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, LaunchActivity::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Collection items need a mutable template; it targets our own explicit activity. */
    private fun launchTemplate(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 3,
            Intent(context, LaunchActivity::class.java).setAction(LaunchActivity.ACTION_WEATHER),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun toggleUnitIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 4,
            Intent(context, ClockWeatherWidgetProvider::class.java).setAction(ClockWeatherWidgetProvider.ACTION_TOGGLE_UNIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun settingsIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 5,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
