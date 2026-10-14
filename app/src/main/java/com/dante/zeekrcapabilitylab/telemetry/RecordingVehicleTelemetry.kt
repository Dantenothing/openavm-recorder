package com.dante.zeekrcapabilitylab.telemetry

import android.content.Context
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.enhancement.field.EcarxSignalReader
import com.dante.zeekrcapabilitylab.enhancement.field.FieldTestRuntime
import com.dante.zeekrcapabilitylab.enhancement.field.VehicleSignalCatalog
import com.dante.zeekrcapabilitylab.enhancement.field.VehicleSignalReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VehicleTelemetryStatus {
    DISABLED, IDLE, WAITING, RECORDING, UNAVAILABLE, SDK_TIMEOUT, SDK_BUSY, STORAGE_ERROR, LIMIT_REACHED,
}

data class VehicleTelemetryCaptureState(
    val status: VehicleTelemetryStatus = VehicleTelemetryStatus.DISABLED,
    val accepted: Int = 0,
    val lastElapsedMs: Long? = null,
    val speedKmh: Double? = null,
    val gear: String? = null,
    val validSignals: Int = 0,
    val latestSample: VehicleTelemetrySample? = null,
)

object RecordingVehicleTelemetry {
    private val mutable = MutableStateFlow(VehicleTelemetryCaptureState())
    val state = mutable.asStateFlow()
    private var owner: Any? = null
    @Synchronized internal fun claim(value: Any) { owner = value; mutable.value = VehicleTelemetryCaptureState() }
    @Synchronized internal fun publish(value: Any, snapshot: VehicleTelemetryCaptureState) {
        if (owner === value) mutable.value = snapshot.copy(
            latestSample = snapshot.latestSample.takeIf { snapshot.status == VehicleTelemetryStatus.RECORDING })
    }
    fun store(context: Context) = VehicleTelemetryStore(File(context.noBackupFilesDir, "recording-vehicle-telemetry"))
}

/**
 * One queued/running operation maximum. Cancellation invalidates results, but deliberately does not
 * free the slot while a native getter still runs. A new recording cannot create a replacement worker.
 */
internal class VehicleTelemetryReadGate(private val executor: Executor) {
    private val occupied = AtomicBoolean(false)
    val busy: Boolean get() = occupied.get()
    fun <T> submit(current: () -> Boolean, read: () -> T, receive: (Result<T>) -> Unit): Boolean {
        if (!occupied.compareAndSet(false, true)) return false
        try {
            executor.execute {
                try {
                    if (current()) {
                        val result = runCatching(read)
                        if (current()) receive(result)
                    }
                } finally { occupied.set(false) }
            }
        } catch (_: RuntimeException) { occupied.set(false); return false }
        return true
    }
}

private object VehicleTelemetryThreads {
    val control = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "RecordingVehicleControl").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    val reads = VehicleTelemetryReadGate(Executors.newSingleThreadExecutor { task ->
        Thread(task, "RecordingVehicleSdk").apply { isDaemon = true }
    })
    // Accessed on RecordingVehicleSdk only. No SDK call is made in the object's initializer.
    private var reader: VehicleSignalReader? = null
    fun reader(context: Context): VehicleSignalReader = reader ?: EcarxSignalReader(context).also { reader = it }
}

/** Optional sidecar or visible-preview telemetry. Lifecycle and video callbacks never wait for SDK/disk IO. */
internal class RecordingVehicleTelemetryCapture(
    context: Context,
    private val enabled: () -> Boolean,
    private val liveOnly: Boolean = false,
    private val publishLive: ((VehicleTelemetryCaptureState) -> Unit)? = null,
    private val sampleReader: (() -> VehicleTelemetrySample?)? = null,
) : Closeable {
    private val appContext = context.applicationContext ?: context
    private val store by lazy { RecordingVehicleTelemetry.store(appContext) }
    private val generation = AtomicLong()
    @Volatile private var closed = false
    private data class Request(val sessionId: String?, val recording: Boolean, val enabled: Boolean)
    private var requested: Request? = null // caller, under this object's monitor
    private class Flight {
        val valid = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        var timeout: ScheduledFuture<*>? = null
    }
    // All fields below are touched only by RecordingVehicleControl.
    private var writer: VehicleTelemetryStore.Writer? = null
    private var lastStoredMs: Long? = null
    private var scheduled: ScheduledFuture<*>? = null
    private var flight: Flight? = null
    private var snapshot = VehicleTelemetryCaptureState()
    private val healthOwner = Any() // Diagnostic ownership must not retain a capture or its UI callback.

    init {
        require(!liveOnly || publishLive != null)
        VehicleTelemetryHealth.claim(healthOwner, liveOnly)
        if (!liveOnly) RecordingVehicleTelemetry.claim(this)
    }
    private fun publish(value: VehicleTelemetryCaptureState) {
        VehicleTelemetryHealth.observe(healthOwner, liveOnly, value)
        if (liveOnly) publishLive?.invoke(value) else RecordingVehicleTelemetry.publish(this, value)
    }
    private fun optedIn() = runCatching(enabled).getOrDefault(false)
    private fun now() = SystemClock.elapsedRealtime()
    private fun current(token: Long) = !closed && generation.get() == token
    private fun fieldTestBusy() = FieldTestRuntime.state.value.let { it.active || it.busy }

    @Synchronized fun follow(sessionId: String?, recording: Boolean) {
        if (closed) return
        val next = Request(sessionId, recording, optedIn())
        if (next == requested) return
        requested = next
        val token = generation.incrementAndGet() // fence SDK callbacks before posting any cleanup
        val stoppedAt = now()
        VehicleTelemetryThreads.control.execute {
            if (!current(token)) return@execute
            release(stoppedAt)
            snapshot = VehicleTelemetryCaptureState()
            when {
                !next.enabled -> emit(token, VehicleTelemetryStatus.DISABLED)
                !next.recording -> emit(token, VehicleTelemetryStatus.IDLE)
                liveOnly -> { emit(token, VehicleTelemetryStatus.WAITING); poll(token) }
                next.sessionId == null || !VehicleTelemetryStore.validId(next.sessionId) -> emit(token, VehicleTelemetryStatus.IDLE)
                else -> start(token, next.sessionId)
            }
        }
    }

    private fun start(token: Long, sessionId: String) {
        if (!current(token) || !optedIn()) { emit(token, VehicleTelemetryStatus.DISABLED); return }
        val at = now()
        try { writer = store.open(sessionId, at, System.currentTimeMillis()); lastStoredMs = null }
        catch (error: Exception) { storageFailure(token, error); return }
        emit(token, VehicleTelemetryStatus.WAITING)
        poll(token)
    }

    private fun poll(token: Long) {
        if (!current(token) || (!liveOnly && writer == null)) return
        if (!optedIn()) { release(now()); emit(token, VehicleTelemetryStatus.DISABLED); return }
        if (fieldTestBusy()) {
            if (snapshot.status != VehicleTelemetryStatus.SDK_BUSY) writeGap(now())
            emit(token, VehicleTelemetryStatus.SDK_BUSY)
            schedule(token, 1_000)
            return
        }
        val request = Flight()
        val startedAt = now()
        flight = request
        fun valid() = current(token) && request.valid.get() && optedIn()
        val accepted = VehicleTelemetryThreads.reads.submit(::valid, {
            sampleReader?.let { return@submit it() }
            val reader = VehicleTelemetryThreads.reader(appContext)
            if (!valid()) return@submit null
            if (fieldTestBusy()) { request.busy.set(true); return@submit null }
            if (reader.connect() == "SDK_BUSY") { request.busy.set(true); return@submit null }
            val values = linkedMapOf<String, VehicleTelemetryValue>()
            for (spec in VehicleSignalCatalog.signals.filter { it.key in VehicleTelemetryPolicy.keys }) {
                if (!valid()) return@submit null
                if (fieldTestBusy()) { request.busy.set(true); return@submit null }
                val value = reader.read(spec)
                val returnedAt = now()
                values[spec.key] = VehicleTelemetryPolicy.observed(spec.key, returnedAt, value.value, value.text, value.valid, value.status)
            }
            VehicleTelemetrySample(now(), System.currentTimeMillis(), values)
        }, { result ->
            VehicleTelemetryThreads.control.execute {
                if (!current(token) || flight !== request || !request.valid.compareAndSet(true, false)) return@execute
                request.timeout?.cancel(false); flight = null
                if (!optedIn()) { release(now()); emit(token, VehicleTelemetryStatus.DISABLED); return@execute }
                val sample = result.getOrNull()
                if (sample == null) {
                    writeGap(now())
                    emit(token, if (request.busy.get() || fieldTestBusy()) VehicleTelemetryStatus.SDK_BUSY else VehicleTelemetryStatus.UNAVAILABLE)
                    schedule(token, 1_000)
                } else {
                    if (!append(token, sample)) return@execute
                    val fresh = VehicleTelemetryPlayback.sampleAt(listOf(sample), sample.elapsedMs)
                    val usable = fresh?.values?.count { it.value.status in setOf("OK", "PROVISIONAL_RAW") } ?: 0
                    snapshot = snapshot.copy(accepted = snapshot.accepted + 1, lastElapsedMs = sample.elapsedMs,
                        speedKmh = fresh?.speedKmh, gear = fresh?.gear, validSignals = usable, latestSample = fresh)
                    emit(token, if (usable > 0) VehicleTelemetryStatus.RECORDING else VehicleTelemetryStatus.UNAVAILABLE)
                    schedule(token, if (usable == 0) 1_000 else (VehicleTelemetryPolicy.POLL_INTERVAL_MS - (now() - startedAt)).coerceAtLeast(1))
                }
            }
        })
        if (!accepted) {
            request.valid.set(false); flight = null
            if (snapshot.status != VehicleTelemetryStatus.SDK_BUSY) writeGap(now())
            emit(token, VehicleTelemetryStatus.SDK_BUSY)
            schedule(token, 1_000)
            return
        }
        request.timeout = VehicleTelemetryThreads.control.schedule({
            if (current(token) && flight === request && request.valid.compareAndSet(true, false)) {
                // Invalidate the late result, but keep the single native slot occupied until the
                // getter actually returns. One bounded retry timer can then recover this same run.
                flight = null
                writeGap(now())
                emit(token, if (optedIn()) VehicleTelemetryStatus.SDK_TIMEOUT else VehicleTelemetryStatus.DISABLED)
                if (optedIn()) schedule(token, 5_000) else release(now())
            }
        }, VehicleTelemetryPolicy.SDK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun schedule(token: Long, delayMs: Long) {
        scheduled?.cancel(false)
        if (current(token) && (liveOnly || writer != null)) scheduled = VehicleTelemetryThreads.control.schedule({ poll(token) }, delayMs, TimeUnit.MILLISECONDS)
    }
    private fun append(token: Long, sample: VehicleTelemetrySample): Boolean {
        if (!current(token) || !optedIn()) return false
        if (liveOnly) return true // No journal, GPS subscription, or persistent pedal learning for preview.
        try {
            writer?.append(sample) ?: return false
            lastStoredMs = sample.elapsedMs
            // Optional display learning must never turn a preferences failure into a recording fault.
            runCatching { RecordedPedalHistory.observe(appContext, listOf(sample)) }
            return true
        }
        catch (error: Exception) { storageFailure(token, error); return false }
    }
    private fun emit(token: Long, status: VehicleTelemetryStatus) {
        snapshot = snapshot.copy(status = status,
            speedKmh = snapshot.speedKmh.takeIf { status == VehicleTelemetryStatus.RECORDING },
            gear = snapshot.gear.takeIf { status == VehicleTelemetryStatus.RECORDING },
            validSignals = snapshot.validSignals.takeIf { status == VehicleTelemetryStatus.RECORDING } ?: 0,
            latestSample = snapshot.latestSample.takeIf { status == VehicleTelemetryStatus.RECORDING })
        if (current(token)) publish(snapshot)
    }
    private fun storageFailure(token: Long, error: Exception) {
        release(now())
        emit(token, if (error.message == "HISTORY_LIMIT") VehicleTelemetryStatus.LIMIT_REACHED else VehicleTelemetryStatus.STORAGE_ERROR)
    }
    private fun writeGap(atMs: Long) {
        val target = writer ?: return
        if (lastStoredMs?.let { atMs <= it } == true) return
        runCatching {
            target.append(VehicleTelemetrySample(atMs, System.currentTimeMillis(), emptyMap(), breakBefore = true))
            lastStoredMs = atMs
        }
    }
    private fun release(atMs: Long) {
        scheduled?.cancel(false); scheduled = null
        flight?.let { it.valid.set(false); it.timeout?.cancel(false) }; flight = null
        writeGap(atMs)
        val target = writer
        writer = null; lastStoredMs = null
        runCatching { target?.close() }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet()
        val stoppedAt = now()
        VehicleTelemetryThreads.control.execute {
            release(stoppedAt)
            publish(VehicleTelemetryCaptureState(
                status = if (optedIn()) VehicleTelemetryStatus.IDLE else VehicleTelemetryStatus.DISABLED))
        }
    }
}
