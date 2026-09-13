package com.dante.zeekrcapabilitylab.sentry.canary

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SentryCanaryService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val reportWriter = Executors.newSingleThreadExecutor()
    private val ownershipToken = Any()
    private val runId = UUID.randomUUID().toString()
    private var reserved = false
    private var pipeline: SentryCanaryPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPss = 0
    private var pssAtMs = 0L
    private var destroyed = false
    private val runTimeout = Runnable { pipeline?.stop("CANARY_TWO_HOUR_LIMIT") }
    private val startedAtEpochMs = System.currentTimeMillis()
    private val startedAtElapsedMs = SystemClock.elapsedRealtime()
    private val telemetry = CanaryTelemetryAccumulator()
    private var archiveError: String? = null

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.SENTRY_CANARY_ENABLED || !BuildConfig.SENTRY_CAPTURE_ENABLED || intent == null) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent.action) {
            START -> if (pipeline == null) begin()
            STOP -> if (intent.getStringExtra(RUN_ID) == runId) pipeline?.stop()
            TRIGGER -> if (intent.getStringExtra(RUN_ID) == runId) pipeline?.trigger()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        if (CameraRecordingService.isRunning() || !CanaryCameraInterlock.reserveCanary(ownershipToken)) {
            val failed = CanarySnapshot(runId = runId, stopReason = "STOP_NORMAL_RECORDING_AND_WAIT_FOR_CAMERA_RELEASE")
            mutableState.value = failed
            recordStartFailure(failed)
            stopSelf()
            return
        }
        reserved = true
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "OpenAVM Sentry test", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, SentryCanaryActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, SentryCanaryService::class.java).setAction(STOP).putExtra(RUN_ID, runId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("OpenAVM 哨兵测试运行中")
                .setContentText("RAM 监听；仅手动保存事件。点停止可结束测试。")
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "停止", stop).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(NOTIFICATION_ID, notification)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:sentry-canary").apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MS + 60_000)
            }
            mutableState.value = CanarySnapshot(runId = runId, phase = "STARTING", running = true)
            val initial = mutableState.value
            reportWriter.execute {
                runCatching { CanaryEvidenceStore.record(applicationContext, evidence(initial, false)) }.onFailure {
                    main.post { archiveError = "EVIDENCE_START_WRITE_FAILED" }
                }
            }
            pipeline = SentryCanaryPipeline(applicationContext, runId, { snapshot -> main.post { update(snapshot) } }, { snapshot ->
                main.post { completed(snapshot) }
            }).also { it.start() }
            main.postDelayed(runTimeout, MAX_RUN_MS)
        } catch (error: Throwable) {
            val failed = CanarySnapshot(runId = runId, stopReason = "START_${error.javaClass.simpleName}")
            mutableState.value = failed
            recordStartFailure(failed)
            releaseReservation()
            stopSelf()
        }
    }

    private fun update(snapshot: CanarySnapshot) {
        if (snapshot.runId != runId) return
        val now = SystemClock.elapsedRealtime()
        if (now - pssAtMs >= 5_000) { lastPss = Debug.getPss().toInt(); pssAtMs = now }
        val thermal = if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).currentThermalStatus else null
        val measured = snapshot.copy(processPssKiB = lastPss, thermalStatus = thermal, evidenceArchiveError = archiveError)
        mutableState.value = measured.copy(telemetry = telemetry.observe(measured, SystemClock.elapsedRealtime() - startedAtElapsedMs))
        if (!snapshot.running || snapshot.phase == "STOPPING") return
        val runtime = Runtime.getRuntime()
        if (runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * 0.85) pipeline?.stop("HEAP_PRESSURE")
        if (Build.VERSION.SDK_INT >= 29 && getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            pipeline?.stop("THERMAL_SEVERE")
        }
    }

    private fun completed(snapshot: CanarySnapshot) {
        val measured = snapshot.copy(processPssKiB = lastPss, thermalStatus = mutableState.value.thermalStatus, reportReady = true)
        val finalSnapshot = measured.copy(telemetry = telemetry.observe(measured, SystemClock.elapsedRealtime() - startedAtElapsedMs))
        mutableState.value = finalSnapshot.copy(phase = "WRITING_REPORT", running = true, reportReady = false)
        // Small diagnostic metadata is persisted only on completion, never video history.
        reportWriter.execute {
            val target = AtomicFile(reportFile(applicationContext))
            target.baseFile.parentFile?.mkdirs()
            var output: java.io.FileOutputStream? = null
            var reportOkay = false
            try {
                output = target.startWrite()
                output.write(json.encodeToString(finalSnapshot).toByteArray(Charsets.UTF_8))
                target.finishWrite(output)
                reportOkay = true
            } catch (_: Throwable) { output?.let { target.failWrite(it) } }
            val archiveProblem = runCatching {
                CanaryEvidenceStore.record(applicationContext, evidence(finalSnapshot.copy(reportReady = reportOkay), true))
            }.exceptionOrNull()?.let { "EVIDENCE_COMPLETION_WRITE_FAILED" }
            main.post {
                mutableState.value = finalSnapshot.copy(reportReady = reportOkay, evidenceArchiveError = archiveProblem)
                pipeline = null
                releaseReservation()
                main.removeCallbacksAndMessages(null)
                reportWriter.shutdown()
                if (!destroyed) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun evidence(snapshot: CanarySnapshot, completed: Boolean) = CanaryEvidenceRecord(
        id = runId, kind = CanaryEvidenceKind.RAM, build = BuildConfig.VERSION_NAME, sdk = Build.VERSION.SDK_INT,
        startedAtEpochMs = startedAtEpochMs, finishedAtEpochMs = if (completed) System.currentTimeMillis() else null, ram = snapshot,
    )

    private fun recordStartFailure(snapshot: CanarySnapshot) {
        reportWriter.execute {
            runCatching { CanaryEvidenceStore.record(applicationContext, evidence(snapshot, true)) }.onFailure {
                main.post {
                    if (mutableState.value.runId == runId) mutableState.value = mutableState.value.copy(evidenceArchiveError = "EVIDENCE_START_FAILURE_WRITE_FAILED")
                }
            }
        }
    }

    private fun releaseReservation() {
        if (reserved) { CanaryCameraInterlock.canaryClosed(ownershipToken); reserved = false }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        // A queued pipeline/report completion still owns the resource barrier.
        // Removing every callback here could discard that acknowledgement forever.
        main.removeCallbacks(runTimeout)
        val active = pipeline
        if (active != null) {
            // Its completion callback owns the release barrier; onDestroy does not invent one.
            active.stop("SERVICE_DESTROYED")
        } else {
            releaseReservation()
            reportWriter.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "openavm_sentry_canary"
        private const val NOTIFICATION_ID = 7401
        private const val START = "SENTRY_CANARY_START"
        private const val STOP = "SENTRY_CANARY_STOP"
        private const val TRIGGER = "SENTRY_CANARY_TRIGGER"
        private const val RUN_ID = "run_id"
        const val MAX_RUN_MS = 2L * 60 * 60 * 1000
        private val mutableState = MutableStateFlow(CanarySnapshot())
        val state = mutableState.asStateFlow()
        @Volatile private var instance: SentryCanaryService? = null
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
        fun reportFile(context: Context) = File(context.filesDir, "sentry-canary/report.json")
        fun start(context: Context) {
            if (!BuildConfig.SENTRY_CANARY_ENABLED || !BuildConfig.SENTRY_CAPTURE_ENABLED ||
                instance != null || CanaryEvidenceExportJobs.state.value.busy) return
            ContextCompat.startForegroundService(context, Intent(context, SentryCanaryService::class.java).setAction(START))
        }
        fun stop(context: Context) = command(context, STOP)
        fun trigger(context: Context) = command(context, TRIGGER)
        private fun command(context: Context, action: String) {
            val active = instance ?: return
            val intent = Intent(context, SentryCanaryService::class.java).setAction(action).putExtra(RUN_ID, active.runId)
            context.startService(intent)
        }
        fun restoreReport(context: Context) {
            val initial = mutableState.value
            if (initial.runId.isNotEmpty() || instance != null) return
            runCatching { json.decodeFromString<CanarySnapshot>(reportFile(context).readText()) }.getOrNull()?.let {
                mutableState.compareAndSet(initial, it.copy(running = false, ramReady = false, clipReady = false,
                    ramReadinessReason = "INACTIVE", saving = false, phase = "STOPPED", reportReady = true))
            }
        }
    }
}
