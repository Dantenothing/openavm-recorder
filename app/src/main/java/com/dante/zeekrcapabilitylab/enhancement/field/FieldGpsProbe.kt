package com.dante.zeekrcapabilitylab.enhancement.field

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat

data class FieldGpsSample(
    val provider: String,
    val elapsedMs: Long,
    val epochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val mock: Boolean,
    val cached: Boolean,
)

data class FieldGpsQuality(val positionValid: Boolean, val speedValid: Boolean, val reason: String)

/** A cached fix can describe provider history, but cannot prove live field-test reception. */
object FieldGpsQualityGate {
    private val providers = setOf("gps", "network", "fused")

    fun evaluate(sample: FieldGpsSample, nowMs: Long): FieldGpsQuality {
        fun reject(reason: String) = FieldGpsQuality(false, false, reason)
        if (sample.mock) return reject("MOCK")
        if (sample.provider !in providers) return reject("INVALID_PROVIDER")
        if (nowMs < 0 || sample.elapsedMs < 0 || sample.epochMs <= 0) return reject("INVALID_TIME")
        if (sample.elapsedMs > nowMs) return reject("FUTURE")
        // Both operands are nonnegative and ordered, so this subtraction cannot overflow.
        if (nowMs - sample.elapsedMs > 5_000L) return reject("STALE")
        if (sample.cached) return reject("CACHED")
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0
        ) return reject("INVALID_COORDINATES")
        val accuracy = sample.accuracyM ?: return reject("NO_ACCURACY")
        if (!accuracy.isFinite() || accuracy < 0) return reject("INVALID_ACCURACY")
        if (accuracy > 50.0) return reject("POOR_ACCURACY")

        val speed = sample.speedMps ?: return FieldGpsQuality(true, false, "NO_SPEED")
        if (!speed.isFinite() || speed !in 0.0..100.0) return FieldGpsQuality(true, false, "INVALID_SPEED")
        val speedAccuracy = sample.speedAccuracyMps
        if (speedAccuracy != null && (!speedAccuracy.isFinite() || speedAccuracy < 0)) {
            return FieldGpsQuality(true, false, "INVALID_SPEED_ACCURACY")
        }
        if (speedAccuracy != null && speedAccuracy > 3.0) {
            return FieldGpsQuality(true, false, "POOR_SPEED_ACCURACY")
        }
        return FieldGpsQuality(true, true, "VALID")
    }
}

/** Uses the caller's looper and foreground lifecycle; owns no service, thread, or wake lock. */
class FieldGpsProbe(
    context: Context,
    private val onSample: (FieldGpsSample) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private var generation = 0L
    private var running = false
    private var listener: LocationListener? = null
    private var subscribedProviders = emptySet<String>()

    // Callbacks share this monitor with stop: once stop returns, an old listener cannot publish.
    @Synchronized
    fun start(looper: Looper) {
        release()
        running = true
        val token = generation
        if (!hasPermission()) { fail(token, "NO_PERMISSION"); return }
        val service = manager ?: run { fail(token, "NO_PROVIDER"); return }
        val candidates = try {
            if (!LocationManagerCompat.isLocationEnabled(service)) {
                fail(token, "LOCATION_OFF"); return
            }
            enabledProviders(service)
        } catch (_: SecurityException) {
            fail(token, "NO_PERMISSION"); return
        } catch (_: IllegalArgumentException) {
            fail(token, "NO_PROVIDER"); return
        }
        if (candidates.isEmpty()) { fail(token, "NO_PROVIDER"); return }

        val nextListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = receive(token, location, cached = false)
            override fun onProviderDisabled(provider: String) = refreshStatus(token)
            override fun onProviderEnabled(provider: String) = refreshStatus(token)
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = refreshStatus(token)
        }
        listener = nextListener
        for (provider in candidates) {
            if (!current(token)) return
            // Keep explicit checks beside the permission-protected calls for both lint and revocation.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) { fail(token, "NO_PERMISSION"); return }
            try {
                service.requestLocationUpdates(provider, 1_000L, 0f, nextListener, looper)
                subscribedProviders = subscribedProviders + provider
                service.getLastKnownLocation(provider)?.let { receive(token, it, cached = true) }
            } catch (_: SecurityException) {
                fail(token, "NO_PERMISSION"); return
            } catch (_: IllegalArgumentException) {
                // An unsupported OEM provider must not prevent another eligible provider.
            }
        }
        if (!current(token)) return
        if (subscribedProviders.isEmpty()) fail(token, "NO_PROVIDER")
        else onStatus("SUBSCRIBED:${subscribedProviders.joinToString(",")}")
    }

    @Synchronized
    fun stop() = release()

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(service: LocationManager): List<String> {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val available = service.getProviders(true).toSet()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").filter {
            it in available && (it != LocationManager.GPS_PROVIDER || fine)
        }
    }

    private fun current(token: Long) = running && generation == token

    @Synchronized
    private fun receive(token: Long, location: Location, cached: Boolean) {
        if (!current(token)) return
        if (!hasPermission()) { fail(token, "NO_PERMISSION"); return }
        onSample(FieldGpsSample(
            provider = location.provider.orEmpty(),
            elapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
            epochMs = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            speedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null,
            mock = LocationCompat.isMock(location),
            cached = cached,
        ))
    }

    @Synchronized
    private fun refreshStatus(token: Long) {
        if (!current(token)) return
        if (!hasPermission()) { fail(token, "NO_PERMISSION"); return }
        val service = manager ?: run { fail(token, "NO_PROVIDER"); return }
        try {
            val status = if (!LocationManagerCompat.isLocationEnabled(service)) "LOCATION_OFF" else {
                val enabled = enabledProviders(service).filter { it in subscribedProviders }
                if (enabled.isEmpty()) "NO_PROVIDER" else "SUBSCRIBED:${enabled.joinToString(",")}"
            }
            if (current(token)) onStatus(status)
        } catch (_: SecurityException) {
            fail(token, "NO_PERMISSION")
        } catch (_: IllegalArgumentException) {
            if (current(token)) onStatus("NO_PROVIDER")
        }
    }

    private fun fail(token: Long, status: String) {
        if (!current(token)) return
        release()
        onStatus(status)
    }

    private fun release() {
        running = false
        generation++ // Invalidate callbacks before touching the framework subscription.
        val previous = listener
        listener = null
        subscribedProviders = emptySet()
        if (previous != null) {
            try { manager?.removeUpdates(previous) }
            catch (_: SecurityException) { /* Revocation still leaves the callback fence closed. */ }
            catch (_: IllegalArgumentException) { /* Some OEMs reject an already removed listener. */ }
        }
    }
}
