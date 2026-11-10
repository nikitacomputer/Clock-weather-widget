package com.clockweather.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

class ClockWeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetUpdater.update(context, manager, it) }
        WeatherWorker.schedule(context)
        if (System.currentTimeMillis() - WeatherRepository(context).fetchedAt > STALE_MS) {
            WeatherWorker.refreshNow(context)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.update(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        WeatherWorker.schedule(context)
        WeatherWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) = WeatherWorker.cancel(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_UNIT -> {
                val s = Settings(context)
                s.fahrenheit = !s.fahrenheit
                WidgetUpdater.updateAll(context)
            }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_LOCALE_CHANGED ->
                WidgetUpdater.updateAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE_UNIT = "com.clockweather.widget.TOGGLE_UNIT"
        private const val STALE_MS = 20 * 60_000L
    }
}

object WidgetUpdater {
    fun update(context: Context, manager: AppWidgetManager, id: Int) {
        manager.updateAppWidget(id, WidgetRenderer.build(context, manager.getAppWidgetOptions(id)))
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, ClockWeatherWidgetProvider::class.java))
            .forEach { update(context, manager, it) }
    }
}
