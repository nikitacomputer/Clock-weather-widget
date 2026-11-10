package com.clockweather.widget

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/** Resolves which app to open for the clock and the forecast (settings 2.1 / 2.2). */
object AppLauncher {
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val PIXEL_WEATHER = "com.google.android.apps.weather"

    fun launchClock(context: Context) {
        val pm = context.packageManager
        val candidates = mutableListOf<Intent>()
        Settings(context).clockApp?.let { pkg -> pm.getLaunchIntentForPackage(pkg)?.let(candidates::add) }
        val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        pm.resolveActivity(alarms, 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
            ?.let { pm.getLaunchIntentForPackage(it) }
            ?.let(candidates::add)
        candidates += alarms
        startFirst(context, candidates)
    }

    fun launchWeather(context: Context) {
        val pm = context.packageManager
        val candidates = mutableListOf<Intent>()
        Settings(context).weatherApp?.let { pkg -> pm.getLaunchIntentForPackage(pkg)?.let(candidates::add) }
        candidates += googleWeatherIntents(context)
        startFirst(context, candidates)
    }

    /** "Google Weather": the Pixel Weather app, else the Google app's weather page, else the web. */
    private fun googleWeatherIntents(context: Context): List<Intent> {
        val list = mutableListOf<Intent>()
        context.packageManager.getLaunchIntentForPackage(PIXEL_WEATHER)?.let(list::add)
        list += Intent(Intent.ACTION_VIEW, Uri.parse("dynact://velour/weather/ProxyActivity"))
            .setComponent(ComponentName(GOOGLE_APP, "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"))
        list += Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather"))
        return list
    }

    private fun startFirst(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // try the next one
            }
        }
    }
}
