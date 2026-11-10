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
        val currentWx = snapshot?.let { Wx.of(it.current) }

        // ---- decide layout ----
        var m = MainMetrics.compute(hc, tempText, currentWx?.color)
        val minColumn = (hc * 0.42f).coerceIn(30f, 60f)
        val stripe = wc - m.width - SEPARATOR
        val showForecast = stripe >= minColumn
        if (!showForecast) m = m.scaledToFit(wc)

        // ---- main cluster ----
        // Time and temperature share one font size; the icon is scaled and shifted so its drawn
        // artwork spans exactly from the top of the digits to their baseline.
        rv.setViewLayoutWidth(R.id.main_cluster, if (showForecast) m.width else wc, COMPLEX_UNIT_DIP)
        rv.setViewPadding(R.id.main_cluster, px(m.edge), 0, px(m.edge), 0)
        rv.setTextViewTextSize(R.id.time, COMPLEX_UNIT_DIP, m.textSize)
        rv.setTextColor(R.id.time, scheme.main)
        rv.setTextViewTextSize(R.id.current_temp, COMPLEX_UNIT_DIP, m.textSize)
        rv.setTextColor(R.id.current_temp, scheme.secondary)
        rv.setTextViewText(R.id.current_temp, tempText)
        applyUnitMark(rv, m.textSize, if (settings.fahrenheit) "F" else "C", scheme.secondary, snapshot != null)
        if (currentWx != null) {
            rv.setViewVisibility(R.id.current_icon, View.VISIBLE)
            rv.setImageViewResource(R.id.current_icon, currentWx.color) // colour icon, always black outlines
            rv.setContentDescription(R.id.current_icon, currentWx.label)
            rv.setViewLayoutWidth(R.id.current_icon, m.iconSize, COMPLEX_UNIT_DIP)
            rv.setViewLayoutHeight(R.id.current_icon, m.iconSize, COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(R.id.current_icon, RemoteViews.MARGIN_START, m.iconMarginStart, COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(R.id.current_icon, RemoteViews.MARGIN_END, m.iconMarginEnd, COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(R.id.current_icon, RemoteViews.MARGIN_TOP, max(m.iconShift, 0f), COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(R.id.current_icon, RemoteViews.MARGIN_BOTTOM, max(-m.iconShift, 0f), COMPLEX_UNIT_DIP)
        } else {
            rv.setViewVisibility(R.id.current_icon, View.GONE)
            rv.setViewLayoutMargin(R.id.temp_box, RemoteViews.MARGIN_START, m.gapAfterTime, COMPLEX_UNIT_DIP)
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

        // Columns: day/hour, icon and temperature packed tightly and centred vertically, leaving
        // generous top and bottom margins.
        val items = snapshot?.forecast.orEmpty()
        val labelSize = min(hc * 0.12f, fitText(items.map { it.label }, colW * 0.92f))
        val temps = items.map { formatTemp(it.conditions.tempC, settings.fahrenheit) }
        val tempSize = min(hc * 0.17f, fitText(temps, colW * 0.92f))
        val iconSize = min(hc * 0.38f, colW * 0.85f)
        // the outline artwork has ~7% empty space above and below; pull neighbours into it
        val iconMargin = hc * 0.02f - iconSize * 0.07f

        val builder = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        items.forEachIndexed { i, item ->
            val v = RemoteViews(context.packageName, R.layout.forecast_item)
            v.setViewLayoutHeight(R.id.item_root, colW, COMPLEX_UNIT_DIP)
            v.setViewLayoutWidth(R.id.item_content, colW, COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(R.id.item_content, hc, COMPLEX_UNIT_DIP)

            // Text boxes are trimmed to about one em: only empty ascent/descent space is cut.
            v.setViewLayoutHeight(R.id.item_label, labelSize * 1.05f, COMPLEX_UNIT_DIP)
            v.setTextViewTextSize(R.id.item_label, COMPLEX_UNIT_DIP, labelSize)
            v.setTextColor(R.id.item_label, scheme.secondary)
            v.setTextViewText(R.id.item_label, item.label)

            val wx = Wx.of(item.conditions)
            v.setViewLayoutHeight(R.id.item_icon, iconSize, COMPLEX_UNIT_DIP)
            v.setViewLayoutMargin(R.id.item_icon, RemoteViews.MARGIN_TOP, iconMargin, COMPLEX_UNIT_DIP)
            v.setViewLayoutMargin(R.id.item_icon, RemoteViews.MARGIN_BOTTOM, iconMargin, COMPLEX_UNIT_DIP)
            v.setImageViewResource(R.id.item_icon, wx.outline)
            v.setInt(R.id.item_icon, "setColorFilter", scheme.main) // outline icons follow main colour
            v.setContentDescription(R.id.item_icon, wx.label)

            v.setViewLayoutHeight(R.id.item_temp, tempSize * 1.0f, COMPLEX_UNIT_DIP)
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
        val textSize: Float, val edge: Float, val gapAfterTime: Float, val gapAfterIcon: Float,
        val timeW: Float, val tempW: Float,
        /** icon box size, horizontal artwork padding inside the box, and vertical shift */
        val iconSize: Float, val iconPadLeft: Float, val iconPadRight: Float, val iconShift: Float,
        val hasIcon: Boolean,
    ) {
        val iconMarginStart get() = gapAfterTime - iconPadLeft
        val iconMarginEnd get() = gapAfterIcon - iconPadRight
        val width
            get() = edge + timeW + gapAfterTime + tempW + edge + 2f +
                (if (hasIcon) iconSize - iconPadLeft - iconPadRight + gapAfterIcon else 0f)

        fun scaledToFit(available: Float): MainMetrics {
            if (width <= available) return this
            val s = available / width
            return MainMetrics(
                textSize * s, edge * s, gapAfterTime * s, gapAfterIcon * s, timeW * s, tempW * s,
                iconSize * s, iconPadLeft * s, iconPadRight * s, iconShift * s, hasIcon,
            )
        }

        companion object {
            fun compute(hc: Float, tempText: String, iconRes: Int?): MainMetrics {
                // Digit height (top of digits to baseline) and the icon's artwork share this height.
                val digitHeight = hc * 0.34f
                val f = fontMetrics()
                val text = (digitHeight / f.digitHeightPerSize).coerceIn(12f, 64f)
                val dh = f.digitHeightPerSize * text
                // In a vertically centred text box, where the middle of the digits sits.
                val digitMid = f.digitMidOffsetPerSize * text

                var size = 0f; var padL = 0f; var padR = 0f; var shift = 0f
                if (iconRes != null) {
                    val b = IconBounds.of(iconRes) // left, top, right, bottom in a 48-unit viewport
                    size = (dh * 48f / (b[3] - b[1])).coerceAtMost(hc * 1.2f)
                    padL = b[0] / 48f * size
                    padR = (48f - b[2]) / 48f * size
                    val artMid = ((b[1] + b[3]) / 2f - 24f) / 48f * size
                    shift = digitMid - artMid
                }
                return MainMetrics(
                    textSize = text, edge = hc * 0.11f, gapAfterTime = hc * 0.12f, gapAfterIcon = hc * 0.06f,
                    timeW = measure("00:00", text), tempW = measure(tempText, text),
                    iconSize = size, iconPadLeft = padL, iconPadRight = padR, iconShift = shift,
                    hasIcon = iconRes != null,
                )
            }
        }
    }

    /**
     * Small "C"/"F" centred under the degree sign, sitting on the same baseline as the digits, so
     * it adds no width to the cluster.
     */
    private fun applyUnitMark(rv: RemoteViews, tempSize: Float, unit: String, color: Int, visible: Boolean) {
        if (!visible) {
            rv.setViewVisibility(R.id.current_unit, View.GONE)
            return
        }
        val m: FloatArray = synchronized(paint) {
            paint.textSize = 100f
            val deg = android.graphics.Rect(); paint.getTextBounds("°", 0, 1, deg)
            val cap = android.graphics.Rect(); paint.getTextBounds(unit, 0, 1, cap)
            // degree glyph: space between its bottom and the baseline, its horizontal centre
            // measured from the end of the string, and the unit letter's cap height/width
            floatArrayOf(
                -deg.bottom / 100f, (paint.measureText("°") - (deg.left + deg.right) / 2f) / 100f,
                -cap.top / 100f, paint.measureText(unit) / 100f, -paint.fontMetrics.ascent / 100f,
            )
        }
        val (spaceBelowDeg, degCentreFromEnd, capPerSize, unitWPerSize, ascentPerSize) = m
        val unitSize = ((spaceBelowDeg - 0.10f) * tempSize / capPerSize).coerceIn(6f, tempSize * 0.45f)
        // same baseline as the temperature: shift the small text box down by the ascent difference
        val top = ascentPerSize * (tempSize - unitSize)
        val end = degCentreFromEnd * tempSize - unitWPerSize * unitSize / 2f
        rv.setViewVisibility(R.id.current_unit, View.VISIBLE)
        rv.setTextViewText(R.id.current_unit, unit)
        rv.setTextViewTextSize(R.id.current_unit, COMPLEX_UNIT_DIP, unitSize)
        rv.setTextColor(R.id.current_unit, color)
        rv.setViewLayoutMargin(R.id.current_unit, RemoteViews.MARGIN_TOP, top, COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.current_unit, RemoteViews.MARGIN_END, end, COMPLEX_UNIT_DIP)
    }

    private class FontMetrics(val digitHeightPerSize: Float, val digitMidOffsetPerSize: Float)

    @Volatile private var cachedFont: FontMetrics? = null

    /** Measured from the device font: digit height and digit-centre offset per 1dp of text size. */
    private fun fontMetrics(): FontMetrics = cachedFont ?: synchronized(paint) {
        paint.textSize = 100f
        val bounds = android.graphics.Rect()
        paint.getTextBounds("0123456789", 0, 10, bounds)
        val fm = paint.fontMetrics
        // includeFontPadding=false: the text box spans ascent..descent and is centred vertically.
        val boxMid = (fm.ascent + fm.descent) / 2f
        val digitMid = (bounds.top + bounds.bottom) / 2f
        FontMetrics((bounds.bottom - bounds.top) / 100f, (digitMid - boxMid) / 100f).also { cachedFont = it }
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
