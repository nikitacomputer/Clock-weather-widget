package com.clockweather.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationResolver {

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The place to fetch weather for, or null if none is known yet. */
    suspend fun resolve(context: Context): Place? {
        val settings = Settings(context)
        if (settings.locationMode == LocationMode.MANUAL) {
            settings.manualPlace?.let { return it }
        }
        if (hasPermission(context)) {
            val loc = try {
                current(context) ?: lastKnown(context)
            } catch (e: SecurityException) {
                null // e.g. in the background without "allow all the time"
            }
            if (loc != null) {
                val place = Place(loc.latitude, loc.longitude, "")
                settings.lastDevicePlace = place
                return place
            }
        }
        return settings.lastDevicePlace ?: settings.manualPlace
    }

    @Suppress("MissingPermission")
    private suspend fun current(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in lm.allProviders && lm.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun lastKnown(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}
