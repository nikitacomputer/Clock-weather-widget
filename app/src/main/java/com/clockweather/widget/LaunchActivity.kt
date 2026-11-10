package com.clockweather.widget

import android.app.Activity
import android.os.Bundle

/** Invisible trampoline started by widget taps; opens the chosen app and finishes. */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_CLOCK -> AppLauncher.launchClock(this)
            ACTION_WEATHER -> AppLauncher.launchWeather(this)
        }
        finish()
    }

    companion object {
        const val ACTION_CLOCK = "com.clockweather.widget.OPEN_CLOCK"
        const val ACTION_WEATHER = "com.clockweather.widget.OPEN_WEATHER"
    }
}
