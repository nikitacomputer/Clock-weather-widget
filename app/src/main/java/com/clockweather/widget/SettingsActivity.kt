package com.clockweather.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.SizeF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private val scope = MainScope()
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var syncingColor = false
    private val hsv = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)

        val root = findViewById<View>(R.id.settings_root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Launched as the widget's configuration screen?
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            findViewById<Button>(R.id.done_button).apply {
                visibility = View.VISIBLE
                setOnClickListener { finishConfiguration() }
            }
        }

        setupColor()
        setupOpacity()
        bindRadio(R.id.font_group, if (settings.fontScheme == FontScheme.BLACK) R.id.font_black else R.id.font_white) {
            settings.fontScheme = if (it == R.id.font_white) FontScheme.WHITE else FontScheme.BLACK
        }
        bindRadio(R.id.outline_group, if (settings.outlineWhite) R.id.outline_white else R.id.outline_black) {
            settings.outlineWhite = it == R.id.outline_white
        }
        setupRadius()
        setupOutlineWidth()
        setupApps()
        bindRadio(R.id.forecast_group, if (settings.forecastMode == ForecastMode.DAILY) R.id.forecast_days else R.id.forecast_hours) {
            settings.forecastMode = if (it == R.id.forecast_hours) ForecastMode.HOURLY else ForecastMode.DAILY
        }
        bindRadio(R.id.unit_group, if (settings.fahrenheit) R.id.unit_f else R.id.unit_c) {
            settings.fahrenheit = it == R.id.unit_f
        }
        setupLocation()
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refreshNow() }
        findViewById<View>(R.id.preview_host).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) renderPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLocationUi()
        updateTimestamp()
        // While we are in the foreground we can always get a location fix.
        if (settings.locationMode == LocationMode.DEVICE && LocationResolver.hasPermission(this) &&
            System.currentTimeMillis() - WeatherRepository(this).fetchedAt > 10 * 60_000L
        ) refreshNow(quiet = true)
        renderPreview()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) finishConfiguration() else super.onBackPressed()
    }

    private fun finishConfiguration() {
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        WidgetUpdater.updateAll(this)
        WeatherWorker.schedule(this)
        finish()
    }

    /** Saves happen in the individual handlers; this redraws widgets and the preview. */
    private fun changed() {
        WidgetUpdater.updateAll(this)
        renderPreview()
    }

    private fun renderPreview() {
        val host = findViewById<FrameLayout>(R.id.preview_host)
        if (host.width == 0) return
        val d = resources.displayMetrics.density
        val size = SizeF(host.width / d, host.height / d)
        try {
            val snapshot = WeatherRepository(this).snapshot(settings.forecastMode)
            val views = WidgetRenderer.render(this, size, settings, snapshot)
            // Collection adapters (the forecast list) are only attached under an AppWidgetHostView.
            val widgetHost = AppWidgetHostView(this)
            widgetHost.setPadding(0, 0, 0, 0)
            widgetHost.addView(views.apply(this, widgetHost))
            host.removeAllViews()
            host.addView(widgetHost, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } catch (e: Exception) {
            host.removeAllViews()
        }
    }

    // ---------- 1.1 colour ----------
    private fun setupColor() {
        val hex = findViewById<EditText>(R.id.hex_input)
        val hue = findViewById<SeekBar>(R.id.seek_hue)
        val sat = findViewById<SeekBar>(R.id.seek_sat)
        val value = findViewById<SeekBar>(R.id.seek_val)

        val presets = findViewById<LinearLayout>(R.id.presets)
        val d = resources.displayMetrics.density
        PRESETS.forEach { c ->
            presets.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((1 * d).toInt(), Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()).apply {
                    marginEnd = (10 * d).toInt()
                }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { setColor(c, updateHex = true, updateSliders = true) }
            })
        }

        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncingColor) return
                val t = s?.toString().orEmpty()
                val c = when (t.length) {
                    6 -> t.toLongOrNull(16)?.toInt()
                    3 -> t.map { "$it$it" }.joinToString("").toLongOrNull(16)?.toInt()
                    else -> null
                } ?: return
                setColor(c or 0xFF000000.toInt(), updateHex = false, updateSliders = true)
            }
        })
        hex.setOnEditorActionListener { v, _, _ -> v.clearFocus(); false }

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncingColor) return
                val c = Color.HSVToColor(floatArrayOf(hue.progress.toFloat(), sat.progress / 100f, value.progress / 100f))
                setColor(c, updateHex = true, updateSliders = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        listOf(hue, sat, value).forEach { it.setOnSeekBarChangeListener(sliderListener) }
        setColor(settings.bgColor, updateHex = true, updateSliders = true, save = false)
    }

    private fun setColor(color: Int, updateHex: Boolean, updateSliders: Boolean, save: Boolean = true) {
        syncingColor = true
        findViewById<View>(R.id.color_swatch).setBackgroundColor(color or 0xFF000000.toInt())
        if (updateHex) findViewById<EditText>(R.id.hex_input).setText(String.format("%06X", color and 0xFFFFFF))
        if (updateSliders) {
            Color.colorToHSV(color, hsv)
            findViewById<SeekBar>(R.id.seek_hue).progress = hsv[0].toInt()
            findViewById<SeekBar>(R.id.seek_sat).progress = (hsv[1] * 100).toInt()
            findViewById<SeekBar>(R.id.seek_val).progress = (hsv[2] * 100).toInt()
        }
        syncingColor = false
        if (save) {
            settings.bgColor = color
            changed()
        }
    }

    // ---------- 1.2 opacity ----------
    private fun setupOpacity() {
        val title = findViewById<TextView>(R.id.opacity_title)
        val seek = findViewById<SeekBar>(R.id.seek_opacity)
        fun label(v: Int) { title.text = "Opacity: $v%" }
        seek.progress = settings.opacity
        label(settings.opacity)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                label(progress)
                if (fromUser) renderPreviewWith { settings.opacity = progress }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { changed() }
        })
    }

    /** Cheap update while dragging: preview only, widgets are redrawn on release. */
    private fun renderPreviewWith(block: () -> Unit) {
        block()
        renderPreview()
    }

    // ---------- 1.5 corner radius ----------
    private fun setupRadius() {
        val spinner = findViewById<Spinner>(R.id.radius_spinner)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Settings.RADII.map { "$it" },
        )
        spinner.setSelection(Settings.RADII.indexOf(settings.cornerRadius).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (settings.cornerRadius != Settings.RADII[pos]) {
                    settings.cornerRadius = Settings.RADII[pos]
                    changed()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------- 1.6 outline width ----------
    private fun setupOutlineWidth() {
        val spinner = findViewById<Spinner>(R.id.outline_width_spinner)
        val widths = (1..5).toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, widths.map { "$it" })
        spinner.setSelection(settings.outlineWidth - 1)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (settings.outlineWidth != widths[pos]) {
                    settings.outlineWidth = widths[pos]
                    changed()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindRadio(groupId: Int, checkedId: Int, onChange: (Int) -> Unit) {
        val group = findViewById<RadioGroup>(groupId)
        group.check(checkedId)
        group.setOnCheckedChangeListener { _, id ->
            onChange(id)
            changed()
        }
    }

    // ---------- 2. launch apps ----------
    private data class AppEntry(val pkg: String?, val label: String, val icon: Drawable?)

    private fun setupApps() {
        val clockBtn = findViewById<Button>(R.id.clock_app_button)
        val weatherBtn = findViewById<Button>(R.id.weather_app_button)
        fun refreshLabels() {
            clockBtn.text = settings.clockApp?.let(::appLabel) ?: "System clock (default)"
            weatherBtn.text = settings.weatherApp?.let(::appLabel) ?: "Google Weather (default)"
        }
        refreshLabels()
        clockBtn.setOnClickListener {
            pickApp("Clock app", "System clock (default)") { settings.clockApp = it; refreshLabels() }
        }
        weatherBtn.setOnClickListener {
            pickApp("Weather app", "Google Weather (default)") { settings.weatherApp = it; refreshLabels() }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        "$pkg (not installed)"
    }

    private fun pickApp(title: String, defaultLabel: String, onPicked: (String?) -> Unit) {
        scope.launch {
            val apps = withContext(Dispatchers.Default) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName to it }
                    .distinctBy { it.first }
                    .filter { it.first != packageName }
                    .map { (pkg, info) -> AppEntry(pkg, info.loadLabel(packageManager).toString(), info.loadIcon(packageManager)) }
                    .sortedBy { it.label.lowercase() }
            }
            val entries = listOf(AppEntry(null, defaultLabel, null)) + apps
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(title)
                .setAdapter(AppAdapter(this@SettingsActivity, entries)) { _, which -> onPicked(entries[which].pkg) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private class AppAdapter(private val ctx: Context, private val items: List<AppEntry>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.app_row, parent, false)
            val e = items[position]
            v.findViewById<TextView>(R.id.app_label).text = e.label
            v.findViewById<ImageView>(R.id.app_icon).setImageDrawable(e.icon ?: ctx.getDrawable(R.mipmap.ic_launcher))
            return v
        }
    }

    // ---------- location ----------
    private fun setupLocation() {
        val group = findViewById<RadioGroup>(R.id.location_group)
        group.check(if (settings.locationMode == LocationMode.MANUAL) R.id.location_manual else R.id.location_device)
        group.setOnCheckedChangeListener { _, id ->
            settings.locationMode = if (id == R.id.location_manual) LocationMode.MANUAL else LocationMode.DEVICE
            updateLocationUi()
            if (settings.locationMode == LocationMode.DEVICE && !LocationResolver.hasPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            } else {
                refreshNow(quiet = true)
            }
        }
        findViewById<Button>(R.id.permission_button).setOnClickListener {
            if (!LocationResolver.hasPermission(this)) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                    REQ_LOCATION,
                )
            } else {
                // Android sends the user to the system settings page for "Allow all the time".
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            }
        }
        val input = findViewById<EditText>(R.id.city_input)
        findViewById<Button>(R.id.city_search).setOnClickListener { searchCity(input.text.toString()) }
        input.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { searchCity(v.text.toString()); true } else false
        }
    }

    private fun updateLocationUi() {
        val manual = settings.locationMode == LocationMode.MANUAL
        val status = findViewById<TextView>(R.id.location_status)
        val permBtn = findViewById<Button>(R.id.permission_button)
        findViewById<View>(R.id.city_row).visibility = if (manual) View.VISIBLE else View.GONE
        if (manual) {
            permBtn.visibility = View.GONE
            status.text = settings.manualPlace?.let { "City: ${it.name}" } ?: "Search for a city below."
            return
        }
        when {
            !LocationResolver.hasPermission(this) -> {
                status.text = "Location permission is needed to show local weather."
                permBtn.text = "Grant location permission"
                permBtn.visibility = View.VISIBLE
            }
            !LocationResolver.hasBackgroundPermission(this) -> {
                status.text = "Location updates only while this screen is open. Allow \"all the time\" so the widget follows you when you travel."
                permBtn.text = "Allow all the time"
                permBtn.visibility = View.VISIBLE
            }
            else -> {
                status.text = "Using device location."
                permBtn.visibility = View.GONE
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateLocationUi()
        if (LocationResolver.hasPermission(this)) refreshNow(quiet = true)
    }

    private fun searchCity(query: String) {
        if (query.isBlank()) return
        scope.launch {
            val results = try {
                WeatherRepository.searchCity(query.trim())
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Search failed: ${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (results.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No places found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Choose a place")
                .setItems(results.map { it.name }.toTypedArray()) { _, which ->
                    settings.manualPlace = results[which]
                    settings.locationMode = LocationMode.MANUAL
                    WeatherRepository(this@SettingsActivity).clear()
                    updateLocationUi()
                    refreshNow()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---------- refresh ----------
    private fun refreshNow(quiet: Boolean = false) {
        if (!quiet) Toast.makeText(this, "Refreshing weather…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val ok = try {
                val place = LocationResolver.resolve(this@SettingsActivity) ?: error("no location")
                WeatherRepository(this@SettingsActivity).save(WeatherRepository.fetch(place))
                true
            } catch (e: Exception) {
                false
            }
            if (!ok && !quiet) {
                Toast.makeText(this@SettingsActivity, "Couldn't update weather. Check location and internet.", Toast.LENGTH_LONG).show()
            }
            WeatherWorker.schedule(this@SettingsActivity)
            updateTimestamp()
            changed()
        }
    }

    private fun updateTimestamp() {
        val at = WeatherRepository(this).fetchedAt
        findViewById<TextView>(R.id.updated_text).text =
            if (at == 0L) "No weather data yet."
            else "Last updated " + DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }

    companion object {
        private const val REQ_LOCATION = 1
        private const val REQ_BACKGROUND = 2
        private val PRESETS = intArrayOf(
            Settings.DEFAULT_BG, 0xFFFFFFFF.toInt(), 0xFFF4EBDD.toInt(), 0xFFF2C14E.toInt(),
            0xFFE8915A.toInt(), 0xFF9DB7E0.toInt(), 0xFF3B4A5A.toInt(), 0xFF000000.toInt(),
        )
    }
}
