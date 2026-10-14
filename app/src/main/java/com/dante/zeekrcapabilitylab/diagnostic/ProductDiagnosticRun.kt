package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.mirror.*
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.remote.*
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.Executors

/** Finite adapter over the same product controls used by the owner. No substitute recording pipeline. */
internal class ProductDiagnosticRun(private val context: Context, private val config: RecorderConfig,
                                    private val profile: String, private val online: () -> Boolean) {
    val id: String = UUID.randomUUID().toString()
    private val started = SystemClock.elapsedRealtime()
    private val main = Handler(Looper.getMainLooper())
    private val trace = ArrayList<JsonObject>()
    private val ownedSessions = linkedSetOf<String>()
    private val expectedChannels = setOf(RecordingChannelId.forRole(config.source.sourceRole)) + RecordingChannelPolicy.roles(config).map(RecordingChannelId::forRole)
    private val prefs = MirrorPresentation(context)
    private val resident = prefs.resident
    private val videoVisible = prefs.videoVisible
    private var startRequested = false
    private var saving = false
    private var saved = false
    private var saveError: String? = null
    private var phase = ""
    private var lastSnapshot = 0L
    private val closeTrial = when (profile) {
        "PRODUCT_CLOSE_DRAIN_FIRST" -> TerminalCloseTrial(id, TerminalCloseOrder.DRAIN_FIRST)
        "PRODUCT_CLOSE_DEVICE_FIRST" -> TerminalCloseTrial(id, TerminalCloseOrder.DEVICE_FIRST)
        else -> null
    }
    private val recordingConfig = config.copy(mirrorPreviewEnabled = true)
    private val previewOrder = when (profile) {
        "PRODUCT_PREVIEW_CAMERA_FIRST" -> PreviewInitializationOrder.CAMERA_FIRST
        "PRODUCT_PREVIEW_SURFACE_FIRST" -> PreviewInitializationOrder.SURFACE_FIRST
        "PRODUCT_CLOSE_DRAIN_FIRST", "PRODUCT_CLOSE_DEVICE_FIRST" -> PreviewInitializationOrder.CAMERA_FIRST
        else -> null
    }
    private val engine: ProductTrialEngine
    init {
        check(BuildConfig.RECORDER_SUPPORT_ENABLED)
        check(!CameraRecordingService.isRunning()) { "RECORDING_ALREADY_ACTIVE" }
        prefs.resident = true; prefs.videoVisible = true
        engine = ProductTrialEngine(started, object : ProductTrialEngine.Host {
            override fun sample(): ProductTrialEngine.Sample {
                val state = CameraRecordingService.state.value
                if (startRequested) state.recordingSessionId?.let(ownedSessions::add)
                val belongs = state.recordingSessionId in ownedSessions
                val channels = RecordingChannels.state.value.filter { it.recordingSessionId == state.recordingSessionId }
                // Service state can reset to IDLE before our next sample. Retained channel
                // faults from this run still forbid a second recording after a bad tail.
                val retainedFault = if (closeTrial != null) RecordingChannels.state.value.firstOrNull {
                    it.recordingSessionId in ownedSessions && it.errorCode != null
                }?.errorCode else null
                val mirror = MirrorPreviewRuntime.evidence
                val error = if (belongs && phase in setOf("WAIT_WRITING", "RECORDING")) state.lastError ?:
                    channels.firstOrNull { it.errorCode != null }?.errorCode else null
                return ProductTrialEngine.Sample(idle = idle(),
                    previewFresh = StandaloneMirrorService.isPreviewing(), previewFrames = mirror.frames,
                    recording = CameraRecordingService.isRunning(),
                    allChannelsWriting = belongs && channels.map { it.channelId }.toSet() == expectedChannels &&
                        channels.all { it.status == "WRITING" && it.writtenFrames > 0 },
                    writtenFrames = channels.sumOf { it.writtenFrames },
                    error = (closeTrial?.failure ?: retainedFault ?: if (previewOrder != null) FloatingMirrorService.state.value.error ?: error else error)
                        ?.takeWhile { it.isLetterOrDigit() || it == '_' },
                    online = online())
            }
            override fun preview() {
                val order = previewOrder
                if (order == null) check(FloatingMirrorService.open(context)) { "PRODUCT_PREVIEW_START_REJECTED" }
                else {
                    DiagnosticPreviewOrder.arm(PreviewOrderTrial(id, config.cameraId, order, config.source.sourceRole,
                        closeTrial, recordingConfig.takeIf { closeTrial != null }))
                    check(FloatingMirrorService.openDiagnosticPreview(context, id)) { "PRODUCT_PREVIEW_START_REJECTED" }
                }
            }
            override fun record() { startRequested = true; FloatingMirrorService.startRecording(recordingConfig) }
            override fun stop() {
                if (CameraRecordingService.state.value.recordingSessionId in ownedSessions) CameraRecordingService.stop(context)
                startRequested = false
            }
            override fun close() {
                val current = CameraRecordingService.state.value.recordingSessionId
                if (startRequested) CameraRecordingService.cancelFloatingStart()
                if (current in ownedSessions || startRequested && current != null) CameraRecordingService.stop(context)
                startRequested = false; FloatingMirrorService.close(); StandaloneMirrorService.stopForHandoff()
                DiagnosticPreviewOrder.finish(id)
            }
        }, recordingMs = if (profile == "PRODUCT_SOAK") 780_000 else 75_000,
            previewOnly = profile == "PRODUCT_PREVIEW" || previewOrder != null && closeTrial == null)
    }
    fun start() { main.post(tick) }
    val active get() = !engine.terminal || !saved
    fun cancel(reason: String = "USER_CANCELLED") { engine.cancel(SystemClock.elapsedRealtime(), reason) }
    fun view(): LabRecipeEngine.View = LabRecipeEngine.View(id, active,
        if (saved) engine.cleanup else "PENDING", if (saved) if (saveError == null) engine.outcome else "INCONCLUSIVE" else "PENDING",
        emptySet(), obj("runId" to id, "phase" to engine.phase, "completedRecordings" to engine.completedRecordings,
            "reason" to (saveError ?: engine.reason), "nativeCleanup" to engine.cleanup, "reportSaved" to saved))

    private val tick = object : Runnable {
        override fun run() {
            if (saved) return
            val now = SystemClock.elapsedRealtime()
            runCatching { engine.tick(now) }.onFailure { engine.cancel(now, "PRODUCT_RUNNER_EXCEPTION") }
            if (engine.phase != phase || now - lastSnapshot >= 5_000) {
                phase = engine.phase; lastSnapshot = now
                if (trace.size < 400) trace += SupportRound.reportSample(phase, now - started)
            }
            if (engine.terminal && !saving) {
                saving = true
                // Restore only the presentation choices temporarily changed by this run.
                if (prefs.resident) prefs.resident = resident
                if (prefs.videoVisible) prefs.videoVisible = videoVisible
                val steps = trace.toList()
                io.execute {
                    val result = runCatching { SupportRound.save(context, id, engine.outcome, engine.cleanup,
                        engine.reason, steps, ownedSessions.toSet(), profile) }
                    main.post { saveError = if (result.isFailure) "SUPPORT_REPORT_SAVE_FAILED" else null; saved = true }
                }
            }
            if (!saved) main.postDelayed(this, 500)
        }
    }
    companion object {
        private val io = Executors.newSingleThreadExecutor { Thread(it, "product-diagnostic-report").apply { isDaemon = true } }
        fun idle() = !CameraRecordingService.isRunning() && !StandaloneMirrorService.isRunning() &&
            CanaryCameraInterlock.normalIdle() && MirrorGlPreview.isIdle() && CaptureCleanupRuntime.pendingOwners.value == 0 &&
            CameraWorkCoordinator.state.value.owner == null
    }
}
