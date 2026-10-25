package com.goelcuek.sehrim

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.*

object Geo {
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    fun fmt(m: Double?): String = when {
        m == null -> ""
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        m < 10000 -> String.format(java.util.Locale("tr"), "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    /** Google encoded polyline (hassasiyet 5) çözücü. */
    fun decode(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0; var lat = 0; var lon = 0
        while (i < s.length) {
            var shift = 0; var result = 0; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && i < s.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && i < s.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(lat / 1e5 to lon / 1e5)
        }
        return out
    }

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val last = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        // 2 dakikadan yeni bir konum varsa onu kullan
        if (last != null && System.currentTimeMillis() - last.time < 120_000) return last
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        val fresh = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    LocationManagerCompat.getCurrentLocation(lm, provider, cancel, Executors.newSingleThreadExecutor()) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: last
    }
}
