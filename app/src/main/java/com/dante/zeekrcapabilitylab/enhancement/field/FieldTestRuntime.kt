package com.dante.zeekrcapabilitylab.enhancement.field

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.telemetry.RecordingTelemetryDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FieldTestRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-field-reader").apply { isDaemon = true } }
    private val journal = Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-field-report").apply { isDaemon = true } }
    private val mutable = MutableStateFlow(FieldTestUiState())
    val state: StateFlow<FieldTestUiState> = mutable
    private var visible = false
    private var current: Run? = null
    private var service: FieldTestService? = null
    private var store: FieldReportStore? = null
    private var reportCount = 0
    private val workerBusy = AtomicBoolean(false)
    private var workerSince = 0L
    @Volatile private var pendingGetter: String? = null

    private class Run(val evidence: FieldEvidence, val reader: VehicleSignalReader) {
        val cancelled = AtomicBoolean(false)
        var gps: FieldGpsProbe? = null
        var connected = false
        var nextSlowMs = 0L
        var lastSaveMs = 0L
        var lastUiMs = 0L
    }

    fun setVisible(value: Boolean) { main.post { visible = value } }

    fun start(context: Context, durationSeconds: Int, includeCoordinates: Boolean, locationRequested: Boolean) {
        main.post {
            if (!BuildConfig.VEHICLE_FIELD_TEST_ENABLED || !visible || current != null || service != null || workerBusy.get()) return@post
            val app = context.applicationContext
            val duration = if (durationSeconds >= 1800) 1800 else 120
            val now = SystemClock.elapsedRealtime()
            val run = Run(FieldEvidence(UUID.randomUUID().toString(), now, System.currentTimeMillis(), duration,
                includeCoordinates, locationRequested), EcarxSignalReader(app))
            current = run
            store = FieldReportStore(app)
            mutable.value = run.evidence.ui(now, recording()).copy(phase = "STARTING")
            try {
                ContextCompat.startForegroundService(app, Intent(app, FieldTestService::class.java))
            } catch (_: RuntimeException) { finish("FOREGROUND_START_FAILED") }
            main.postDelayed({ if (current === run && service == null) finish("FOREGROUND_START_FAILED") }, 10_000)
        }
    }

    internal fun wantsLocation(): Boolean = current?.evidence?.locationRequested == true
    internal fun hasPendingRun(): Boolean = current != null && BuildConfig.VEHICLE_FIELD_TEST_ENABLED

    internal fun attach(value: FieldTestService, locationAllowed: Boolean) {
        val run = current ?: return
        if (service != null) return
        service = value
        if (run.evidence.locationRequested && locationAllowed) {
            run.gps = FieldGpsProbe(value, onSample = { sample -> main.post {
                if (current === run && !run.cancelled.get()) run.evidence.gps(sample, SystemClock.elapsedRealtime())
            } }, onStatus = { status -> main.post {
                if (current === run && !run.cancelled.get()) run.evidence.gpsStatus = status
            } }).also { it.start(Looper.getMainLooper()) }
        } else if (run.evidence.locationRequested) run.evidence.gpsStatus = "NO_PERMISSION"
        main.post(tick)
    }

    internal fun serviceStopped(value: FieldTestService) {
        if (service === value) {
            service = null
            if (current != null) finish("SERVICE_STOPPED")
            else mutable.value = mutable.value.copy(busy = workerBusy.get())
        }
    }
    internal fun stopFor(reason: String) { main.post { finish(reason) } }
    fun stop() = stopFor("USER_STOP")
    fun mark(label: String) { main.post { current?.evidence?.mark(label, SystemClock.elapsedRealtime()) } }

    private val tick = object : Runnable {
        override fun run() {
            val run = current ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - run.evidence.beganMs >= run.evidence.durationSeconds * 1000L) { finish("TIME_LIMIT"); return }
            if (workerBusy.get() && now - workerSince >= 10_000) { finish("SDK_TIMEOUT"); return }
            run.evidence.tick(now, recording(), recorderContext())
            if (!workerBusy.get()) poll(run, now)
            if (now - run.lastUiMs >= 500) { run.lastUiMs = now; publish(run) }
            if (now - run.lastSaveMs >= 15_000) { run.lastSaveMs = now; save(run) }
            main.postDelayed(this, 250)
        }
    }

    private fun poll(run: Run, now: Long) {
        if (!workerBusy.compareAndSet(false, true)) return
        workerSince = now
        val slow = now >= run.nextSlowMs
        if (slow) run.nextSlowMs = now + 3_000
        worker.execute {
            try {
                if (!run.connected && !run.cancelled.get()) {
                    pendingGetter = "Car.create/managers"
                    val status = run.reader.connect()
                    run.connected = status != "SDK_BUSY"
                    main.post { if (current === run) run.evidence.sdkStatus = status }
                    if (!run.connected) return@execute
                }
                for (spec in VehicleSignalCatalog.signals) {
                    if (run.cancelled.get()) break
                    if (!slow && !spec.fast) continue
                    pendingGetter = spec.key
                    val began = SystemClock.elapsedRealtime()
                    val result = run.reader.read(spec)
                    val ended = SystemClock.elapsedRealtime()
                    main.post {
                        if (current === run && !run.cancelled.get()) run.evidence.signal(result, ended, ended - began)
                    }
                }
            } catch (error: Throwable) {
                val code = "WORKER_FAILED:${error.javaClass.simpleName}"
                main.post { if (current === run) { run.evidence.sdkStatus = code; finish("SDK_ERROR") } }
            } finally {
                pendingGetter = null
                // Keep the single worker reserved until its result is reconciled on main.
                main.post {
                    workerBusy.set(false)
                    if (current === run) publish(run)
                    else {
                        mutable.value = mutable.value.copy(busy = service != null)
                        if (run.cancelled.get()) save(run)
                    }
                }
            }
        }
    }

    private fun publish(run: Run) {
        mutable.value = run.evidence.ui(SystemClock.elapsedRealtime(), recording()).copy(
            busy = workerBusy.get() || (run.cancelled.get() && service != null), savedReports = reportCount, reportAvailable = reportCount > 0,
            notice = mutable.value.notice)
    }

    private fun finish(reason: String) {
        val run = current ?: return
        run.cancelled.set(true)
        main.removeCallbacks(tick)
        run.gps?.stop(); run.gps = null
        run.evidence.finish(SystemClock.elapsedRealtime(), reason)
        publish(run)
        save(run)
        current = null
        service?.stopSelf()
    }

    private fun snapshot(run: Run): JsonObject = buildJsonObject {
        run.evidence.json(SystemClock.elapsedRealtime()).forEach { (key, value) -> put(key, value) }
        put("appVersion", BuildConfig.VERSION_NAME); put("source", BuildConfig.GIT_SHA)
        put("model", Build.MODEL.take(120)); put("firmware", Build.DISPLAY.take(160)); put("androidSdk", Build.VERSION.SDK_INT)
        put("sdkCallPendingAtSnapshot", workerBusy.get()); put("pendingGetter", pendingGetter)
        put("recorderAtSnapshot", recorderContext())
    }

    private fun save(run: Run) {
        val target = store ?: return
        val json = snapshot(run)
        journal.execute {
            val count = runCatching { target.save(run.evidence.id, json) }.getOrNull()
            main.post {
                if (count != null) {
                    reportCount = count
                    mutable.value = mutable.value.copy(savedReports = count, reportAvailable = true)
                } else mutable.value = mutable.value.copy(notice = "STORAGE_ERROR")
            }
        }
    }

    fun export(context: Context, callback: (String?) -> Unit) { main.post {
        if (!BuildConfig.VEHICLE_FIELD_TEST_ENABLED) { callback(null); return@post }
        val target = store ?: FieldReportStore(context.applicationContext).also { store = it }
        current?.let(::save)
        val metadata = buildJsonObject {
            put("appVersion", BuildConfig.VERSION_NAME); put("exportedAtEpochMs", System.currentTimeMillis())
            put("source", BuildConfig.GIT_SHA); put("credentialsIncluded", false); put("videoIncluded", false)
            put("vehicleHardwareValidation", "USER_FIELD_TEST_NOT_LAB_CERTIFICATION")
        }
        journal.execute {
            val result = runCatching {
                val supplemental = buildJsonObject {
                    metadata.forEach { (key, value) -> put(key, value) }
                    put("mapEnvironment", MapEnvironmentProbe.inspect(context.applicationContext))
                    put("recordingTelemetry", RecordingTelemetryDiagnostics.inspect(context.applicationContext))
                }
                target.export(supplemental)
            }.getOrNull()
            main.post { callback(result) }
        }
    } }

    fun loadSaved(context: Context) { main.post {
        if (!BuildConfig.VEHICLE_FIELD_TEST_ENABLED) return@post
        val target = store ?: FieldReportStore(context.applicationContext).also { store = it }
        journal.execute { val count = target.count(); val anyReport = target.hasReport(); main.post {
            reportCount = count
            mutable.value = mutable.value.copy(savedReports = count, reportAvailable = anyReport, recorderRunning = recording())
        } }
    } }
    private fun recording() = CameraRecordingService.state.value.status == RecorderStatus.RECORDING
    private fun recorderContext(): JsonObject = CameraRecordingService.state.value.let { r -> buildJsonObject {
        put("status", r.status); put("segmentNumber", r.segmentNumber); put("cameraId", r.cameraId)
        put("recordingMode", r.recordingMode.name); put("backend", r.recordingBackend)
        put("previewActive", r.previewActive); put("cleanupPending", r.cleanupPending)
        put("cleanupUnconfirmed", r.cleanupUnconfirmed); put("cameraGeneration", r.cameraGeneration)
        put("fileIntegrityVerified", false)
    } }
}
