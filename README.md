# Clock + Weather widget (Android)

A one-row home-screen widget: **time · current weather icon · current temperature | scrollable forecast**.

| Width | What is shown |
|---|---|
| 3 cells | main cluster only (time, color weather icon, temperature), centered |
| 4–7 cells | main cluster, separator, horizontally scrollable forecast |
| 8 cells | main cluster, separator, all 10 forecast columns without scrolling |

The layout is computed from the widget's real size in dp, so "cells" are approximate and
adapt to each launcher's grid. Each forecast column is about 15% day/hour label (grey), 45% icon,
and 25% temperature (main color).

## Taps
* **Time** opens the clock app (setting 2.1).
* **Current icon / temperature** switches between °C and °F.
* **Forecast** opens the weather app (setting 2.2).

## Settings
Open the *Clock Weather* app, or long-press the widget and choose the settings/reconfigure option.
A live preview at the top shows each change.

1. Appearance
   1. Background color: hex input, hue/saturation/brightness sliders and presets (default teal `#87CAC1`)
   2. Opacity (default 100%)
   3. Font colors: *black + grey* or *white + grey*. Forecast outline icons follow the main font color.
      The current-weather icon is always in color with black outlines.
   4. Outline and separator color: black or white
   5. Corner radius: 0, 5, 10, 15, 20, 25 or 30 dp
2. Default launch apps
   1. Clock app (default: the system clock app)
   2. Weather app (default: Google Weather, which means the Pixel Weather app, then the Google app's
      weather page, then google.com/search?q=weather)
3. Forecast stripe: next 10 days or next 10 hours

Also: temperature unit and location (device location, or a city you search for).

## Weather data
Other apps (including Google's) don't let third-party apps read their weather data, and Google has no
free public weather API. Forecasts therefore come from **[Open-Meteo](https://open-meteo.com/)**
(free, no API key), whichever app you pick to open on tap. Data refreshes every 30 minutes.
Weather codes map to the 18 icons in `Wx.of()` (`Weather.kt`).

## Implementation notes
* `minSdk 31` (Android 12+). The widget uses size-responsive `RemoteViews`, `RemoteCollectionItems`,
  and the runtime layout-size setters added in Android 12.
* App widgets have no horizontal scroll container. The forecast stripe is a vertical `ListView`
  rotated −90°, with each column rotated back +90°. `WidgetRenderer` sizes and offsets the
  rotated list so it covers the stripe exactly. `WidgetRenderTest` checks this geometry.
* Icons are generated vector drawables. Edit `tools/gen_icons.py` and run
  `python3 tools/gen_icons.py` to regenerate `res/drawable/wx_*.xml`.
* Outline and separator are 2 dp wide.

## Build
```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # renders widget PNGs to app/build/widget-renders/
```
Every push also builds the APK on GitHub Actions (artifact `clock-weather-widget-debug`).
