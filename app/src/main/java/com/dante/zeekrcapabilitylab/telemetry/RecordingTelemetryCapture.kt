package com.dante.zeekrcapabilitylab.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class TelemetryStatus {
    DISABLED, IDLE, WAITING, RECORDING, STALE, NO_PERMISSION, LOCATION_OFF,
    NO_PROVIDER, FOREGROUND_REQUIRED, STORAGE_ERROR, LIMIT_REACHED,
}

/** In-memory UI state contains no coordinates. It is never sent to diagnostics or transfer APIs. */
data class TelemetryCaptureState(
    val status: TelemetryStatus = TelemetryStatus.DISABLED,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val lastElapsedMs: Long? = null,
    val speedMps: Float? = null,
    val provider: String? = null,
)

object RecordingTelemetry {
    private val mutable = MutableStateFlow(TelemetryCaptureState())
    val state = mutable.asStateFlow()
    @Volatile private var owner: Any? = null
    @Synchronized internal fun claim(value: Any) { owner = value }
    @Synchronized internal fun publish(value: Any, state: TelemetryCaptureState) {
        if (owner === value) mutable.value = state
    }
    fun store(context: Context) = TelemetryStore(File(context.noBackupFilesDir, "recording-telemetry"))
    fun hasPermission(context: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun hasPrecisePermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

internal interface TelemetryLocationSource {
    /** null means an eligible provider is available; it does not mean a fix exists. */
    fun unavailable(): TelemetryStatus?
    fun subscribe(looper: Looper, receive: (TelemetryFix, Boolean) -> Unit, gap: () -> Unit): Closeable
}

internal class AndroidTelemetryLocationSource(private val context: Context) : TelemetryLocationSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private fun providers(): List<String> {
        val fine = RecordingTelemetry.hasPrecisePermission(context)
        return manager?.getProviders(true).orEmpty().filter {
            it == LocationManager.NETWORK_PROVIDER || it == "fused" || (fine && it == LocationManager.GPS_PROVIDER)
        }
    }
    override fun unavailable(): TelemetryStatus? = when {
        !RecordingTelemetry.hasPermission(context) -> TelemetryStatus.NO_PERMISSION
        manager == null -> TelemetryStatus.NO_PROVIDER
        !LocationManagerCompat.isLocationEnabled(manager) -> TelemetryStatus.LOCATION_OFF
        providers().isEmpty() -> TelemetryStatus.NO_PROVIDER
        else -> null
    }
    @Suppress("DEPRECATION", "MissingPermission")
    override fun subscribe(looper: Looper, receive: (TelemetryFix, Boolean) -> Unit, gap: () -> Unit): Closeable {
        val service = manager ?: error("NO_PROVIDER")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                receive(TelemetryFix(
                    elapsedMs = location.elapsedRealtimeNanos / 1_000_000,
                    epochMs = location.time, latitude = location.latitude, longitude = location.longitude,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN,
                    provider = location.provider.orEmpty(),
                    speedMps = if (location.hasSpeed()) location.speed else null,
                    speedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                ), location.isFromMockProvider)
            }
            override fun onProviderDisabled(provider: String) { gap() }
            override fun onProviderEnabled(provider: String) { gap() }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { gap() }
        }
        var registered = 0
        try {
            for (provider in providers()) {
                // A provider-specific OEM rejection must not prevent a second usable provider.
                try { service.requestLocationUpdates(provider, 1_000L, 0f, listener, looper); registered++ }
                catch (_: IllegalArgumentException) { }
            }
            check(registered > 0) { "NO_PROVIDER" }
        } catch (e: Exception) {
            runCatching { service.removeUpdates(listener) }
            throw e
        }
        return Closeable { service.removeUpdates(listener) }
    }
}

/** Owned by the camera service, but all provider and journal work runs on a separate serial worker. */
internal class RecordingTelemetryCapture(
    private val source: TelemetryLocationSource,
    private val store: TelemetryStore,
    private val enabled: () -> Boolean,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    private val publish: (TelemetryCaptureState) -> Unit,
) : Closeable {
    private val thread = HandlerThread("RecordingLocation").apply { start() }
    private val handler = Handler(thread.looper)
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private data class Request(val sessionId: String?, val recording: Boolean, val enabled: Boolean, val foreground: Boolean)
    private var requested: Request? = null // caller/main thread only
    private var subscription: Closeable? = null // worker only below
    private var writer: TelemetryStore.Writer? = null
    private var filter: TelemetryFilter? = null
    private var watchdog: Runnable? = null
    private var snapshot = TelemetryCaptureState()

    @Synchronized fun follow(sessionId: String?, recording: Boolean, foregroundAllowed: Boolean) {
        if (closed) return
        val next = Request(sessionId, recording, enabled(), foregroundAllowed)
        if (next == requested) return
        requested = next
        val token = generation.incrementAndGet() // invalidate queued callbacks immediately
        handler.post {
            if (!current(token)) return@post
            release()
            snapshot = TelemetryCaptureState()
            when {
                !next.enabled -> emit(token, TelemetryStatus.DISABLED)
                !next.recording -> emit(token, TelemetryStatus.IDLE)
                !next.foreground -> emit(token, unavailable() ?: TelemetryStatus.FOREGROUND_REQUIRED)
                next.sessionId == null || !TelemetryStore.validId(next.sessionId) -> emit(token, TelemetryStatus.IDLE)
                else -> start(next.sessionId, token)
            }
        }
    }
    private fun current(token: Long) = !closed && generation.get() == token
    private fun emit(token: Long, status: TelemetryStatus) {
        snapshot = snapshot.copy(status = status, speedMps = snapshot.speedMps.takeIf { status == TelemetryStatus.RECORDING })
        if (current(token)) publish(snapshot)
    }
    private fun unavailable(): TelemetryStatus? = try { source.unavailable() }
        catch (_: SecurityException) { TelemetryStatus.NO_PERMISSION }
        catch (_: Exception) { TelemetryStatus.NO_PROVIDER }

    private fun start(sessionId: String, token: Long) {
        unavailable()?.let { emit(token, it); return }
        val started = now()
        try { writer = store.open(sessionId, started, wall()) }
        catch (e: Exception) { failStorage(token, e); return }
        filter = TelemetryFilter(started)
        emit(token, TelemetryStatus.WAITING)
        try {
            subscription = source.subscribe(thread.looper, { fix, mock ->
                // Even an OEM callback on the wrong thread cannot touch journal or filter state.
                handler.post { receive(token, fix, mock) }
            }, { handler.post { if (current(token)) filter?.breakTrack() } })
        } catch (_: SecurityException) { release(); emit(token, TelemetryStatus.NO_PERMISSION); return }
        catch (_: Exception) { release(); emit(token, TelemetryStatus.NO_PROVIDER); return }
        tick(token)
    }
    private fun receive(token: Long, fix: TelemetryFix, mock: Boolean) {
        if (!current(token) || writer == null) return
        if (!enabled()) { release(); emit(token, TelemetryStatus.DISABLED); return }
        unavailable()?.let { release(); emit(token, it); return }
        val result = filter?.accept(fix, now(), mock) ?: return
        val accepted = result.fix
        if (accepted == null) { snapshot = snapshot.copy(rejected = snapshot.rejected + 1); return }
        if (!current(token) || !enabled()) return
        try { writer?.append(accepted) }
        catch (e: Exception) { failStorage(token, e); return }
        snapshot = snapshot.copy(accepted = snapshot.accepted + 1, lastElapsedMs = accepted.elapsedMs,
            provider = accepted.provider, speedMps = accepted.speedMps)
        emit(token, TelemetryStatus.RECORDING)
    }
    private fun tick(token: Long) {
        if (!current(token) || writer == null) return
        if (!enabled()) { release(); emit(token, TelemetryStatus.DISABLED); return }
        unavailable()?.let { release(); emit(token, it); return }
        if (snapshot.lastElapsedMs?.let { now() - it > TelemetryPolicy.FRESH_MS } == true) {
            filter?.breakTrack(); emit(token, TelemetryStatus.STALE)
        } else if (current(token)) publish(snapshot)
        watchdog = Runnable { tick(token) }.also { handler.postDelayed(it, 1_000) }
    }
    private fun failStorage(token: Long, error: Exception) {
        release()
        emit(token, if (error.message == "HISTORY_LIMIT") TelemetryStatus.LIMIT_REACHED else TelemetryStatus.STORAGE_ERROR)
    }
    private fun release() {
        watchdog?.let(handler::removeCallbacks); watchdog = null
        runCatching { subscription?.close() }; subscription = null
        runCatching { writer?.close() }; writer = null
        filter = null
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet()
        handler.post {
            release()
            publish(TelemetryCaptureState(status = if (enabled()) TelemetryStatus.IDLE else TelemetryStatus.DISABLED))
            thread.quitSafely()
        }
    }
}
