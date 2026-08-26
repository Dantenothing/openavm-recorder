package com.dante.zeekrcapabilitylab.service.recorder

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.CameraRuntime
import com.dante.zeekrcapabilitylab.product.EmulatorTestRecording
import com.dante.zeekrcapabilitylab.product.SettingsStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sole owner of CameraDevice / CaptureSession / RecordingPipeline for the segment
 * recorder. All camera/recorder transitions happen on the camera handler thread.
 *
 * Timing contract:
 *  - On successful stop+rename the next MediaRecorder segment is scheduled
 *    immediately on the camera handler; metadata/health/sidecar/storage work
 *    runs asynchronously and never gates the next segment.
 *  - Every timeout is token-checked against the current segment generation and
 *    cancelled on every finalize/fail/stop/camera-loss/release path.
 *  - Segment gaps use SystemClock.elapsedRealtime; wall-clock epoch is kept only
 *    as user evidence in the sidecar.
 */
class RecorderSession(
    private val context: Context,
    private val publishState: (RecorderState) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val segmentsDir = File(context.filesDir, "recordings/segments").apply { mkdirs() }
    private val quarantineDir = File(context.filesDir, "recordings/quarantine").apply { mkdirs() }
    private val journalsDir = File(context.filesDir, "recordings/journals").apply { mkdirs() }
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val incidentStore = IncidentProtectionStore(context)

    /** Stable process identity captured once; never the per-command service startId. */
    private val processStartId: String = ZeekrApp.processStartId
    private val wakeLockHolder = RecorderWakeLockHolder(context) { event, message ->
        EventLogger.logEvent(
            Categories.SYSTEM,
            event,
            payload = mapOf("message" to message),
        )
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    /** Rollover/start gates must never queue behind completed-segment frame decoding. */
    private val storageExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var config: RecorderConfig? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var recordingPipeline: RecordingPipeline? = null
    private var recordingPreviewSurface: Surface? = null
    private var activeEncoderSurface: Surface? = null
    private var previewOutputDesired = true
    private var currentPartial: File? = null
    private var currentJournal: File? = null
    private var segmentNumber = 0
    private var recording = false
    private var stopping = false
    private var releasing = false
    private var startInFlight = false
    private var cameraOpenInFlight = false
    private var storageCheckInFlight = false
    private var protectedPending = false
    private var currentIncidentTag: IncidentTag? = null
    /** True only when this segment is fulfilling the persisted post-event slot. */
    private var currentConsumesPendingIncident = false
    private var segmentStartedAtEpochMs: Long? = null
    private var segmentStartedAtElapsedMs: Long? = null
    private var segmentRecordingStartedAtEpochMs: Long? = null
    private var segmentRecordingStartedAtElapsedMs: Long? = null
    private var previousSegmentStoppedElapsedMs: Long? = null
    private var previousEncodedPresentationTimeUs: Long? = null
    private var frameStats = SegmentFrameStats()
    private var lastTimestampNs: Long? = null
    private var segmentGeneration = 0L
    private var openGeneration = 0L
    private var timeoutRunnable: Runnable? = null
    private var keyFrameRunnable: Runnable? = null
    private var openWatchdogRunnable: Runnable? = null
    private var setupWatchdogRunnable: Runnable? = null
    private var encodedWatchdogRunnable: Runnable? = null
    private var lastEncodedProgressAtElapsedMs = 0L
    private var lastEncodedFrameCount = 0L
    private var lastEncodedFileBytes = 0L
    private var recoveryRetryRunnable: Runnable? = null
    private var recoveryRetryAttempt = 0
    private var availabilityRegistered = false
    /** Finalized mp4 names whose sidecar/health analysis is still in flight. */
    private val pendingSidecarFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var state = RecorderState()

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            val cfg = config ?: return
            if (cameraId != cfg.cameraId || stopping || releasing) return
            if (state.status != RecorderStatus.CAMERA_UNAVAILABLE ||
                startInFlight || cameraOpenInFlight || recording || cameraDevice != null
            ) {
                return
            }
            cancelRecoveryRetry()
            recoveryRetryAttempt = 0
            updateState(
                state.copy(
                    status = RecorderStatus.RECOVERING,
                    lastError = null,
                    message = "Verified camera source available; recovering",
                ),
            )
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_AVAILABLE",
                payload = mapOf("cameraId" to cameraId),
            )
            startInFlight = true
            openCamera(cameraId)
        }
    }

    init {
        updateState(state)
    }

    private fun updateState(s: RecorderState) {
        wakeLockHolder.sync(s.status)
        state = s.copy(wakeLockHeld = wakeLockHolder.isHeld)
        publishState(state)
    }

    fun start(config: RecorderConfig, previewSurface: Surface? = null) {
        postCamera {
            val errors = config.validate(
                allowEmulatorTestSource = config.emulatorTestSource && EmulatorTestRecording.isAvailable(),
            )
            if (errors.isNotEmpty()) {
                val message = "CONFIG_INVALID: ${errors.joinToString("; ")}"
                EventLogger.markError(Categories.SYSTEM, "RECORDER_CONFIG_INVALID", message, null)
                setError(message)
                runCatching { previewSurface?.release() }
                onStopped()
                return@postCamera
            }
            val busy = RecorderCommandPolicy.isActive(state.status) ||
                cameraDevice != null || recording || startInFlight || cameraOpenInFlight
            if (busy) {
                runCatching { previewSurface?.release() }
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_START_IGNORED",
                    payload = mapOf(
                        "status" to state.status,
                        "startInFlight" to startInFlight.toString(),
                        "cameraOpenInFlight" to cameraOpenInFlight.toString(),
                    ),
                )
                return@postCamera
            }
            this.config = config
            registerAvailabilityCallback()
            this.previewOutputDesired = true
            releaseRecordingPreviewSurface()
            this.recordingPreviewSurface = previewSurface?.takeIf { it.isValid }
            if (this.recordingPreviewSurface == null) runCatching { previewSurface?.release() }
            this.stopping = false
            this.releasing = false
            this.protectedPending = false
            this.currentIncidentTag = null
            this.currentConsumesPendingIncident = false
            this.segmentNumber = 0
            this.previousSegmentStoppedElapsedMs = null
            this.previousEncodedPresentationTimeUs = null
            cancelOpenWatchdog()
            cancelSetupWatchdog()
            cancelEncodedWatchdog()
            cancelTimeout()
            cancelRecoveryRetry()
            recoveryRetryAttempt = 0
            updateState(
                state.copy(
                    status = RecorderStatus.STARTING,
                    cameraId = config.cameraId,
                    profile = config.profile,
                    segmentSeconds = config.segmentSeconds,
                    storageLimitBytes = config.storageLimitBytes,
                    segmentNumber = 0,
                    currentFile = null,
                    segmentStartedAtEpochMs = null,
                    lastError = null,
                    lastSidecarPath = null,
                    message = "Starting",
                    previewRequested = this.recordingPreviewSurface != null,
                    previewActive = false,
                    previewFallbackUsed = false,
                    recordingMode = config.recordingMode,
                    sourceVerified = true,
                    calibrationValid = config.recordingMode != RecordingMode.FRONT_ONLY ||
                        config.sourceKind == RecordingSourceKind.DIRECT_FRONT ||
                        config.frontCalibration != null,
                    encodedFrameCount = 0,
                    encodedBytes = 0,
                ),
            )
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_START",
                payload = mapOf(
                    "cameraId" to config.cameraId,
                    "profile" to config.profile.key,
                    "mode" to config.recordingMode.name,
                    "sourceKind" to config.sourceKind.name,
                    "sourceFingerprint" to config.sourceFingerprint.take(12),
                    "segmentSeconds" to config.segmentSeconds.toString(),
                    "storageLimitBytes" to config.storageLimitBytes.toString(),
                    "processStartId" to processStartId,
                ),
            )
            startInFlight = true
            runWork(RecorderWorkKind.STARTUP_RECOVERY) {
                val recovery = SegmentRecovery.reconcile(segmentsDir, quarantineDir, journalsDir)
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_RECOVERY_RECONCILED",
                    payload = mapOf(
                        "completedJournals" to recovery.completedJournals.toString(),
                        "quarantinedPartials" to recovery.quarantinedPartials.toString(),
                        "quarantinedOrphans" to recovery.quarantinedOrphans.toString(),
                        "errors" to recovery.errors.joinToString(" | ").ifBlank { "-" },
                    ),
                )
                cameraHandler?.post {
                    if (stopping || releasing || this.config != config || !startInFlight) return@post
                    openCamera(config.cameraId)
                }
            }
        }
    }

    /**
     * Keeps the configured preview Surface but removes/adds it from the active
     * repeating request. This avoids rebuilding the session or interrupting the
     * MediaRecorder when the app moves between foreground and background.
     */
    fun setPreviewOutputEnabled(enabled: Boolean) {
        postCamera {
            previewOutputDesired = enabled
            val session = captureSession ?: return@postCamera
            val device = cameraDevice ?: return@postCamera
            val encoder = activeEncoderSurface?.takeIf { it.isValid } ?: return@postCamera
            if (!recording) return@postCamera
            val preview = recordingPreviewSurface?.takeIf {
                enabled && !state.previewFallbackUsed && it.isValid
            }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encoder)
                preview?.let(::addTarget)
            }.build()
            try {
                session.setRepeatingRequest(
                    request,
                    createFrameCaptureCallback(segmentGeneration),
                    cameraHandler,
                )
                updateState(state.copy(previewActive = preview != null))
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_PREVIEW_TARGET_CHANGED",
                    payload = mapOf(
                        "enabled" to (preview != null).toString(),
                        "requested" to enabled.toString(),
                        "segment" to segmentNumber.toString(),
                    ),
                )
            } catch (t: Throwable) {
                if (preview != null) {
                    runCatching {
                        val encoderOnly = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(encoder)
                        }.build()
                        session.setRepeatingRequest(
                            encoderOnly,
                            createFrameCaptureCallback(segmentGeneration),
                            cameraHandler,
                        )
                    }
                    updateState(
                        state.copy(
                            previewActive = false,
                            previewFallbackUsed = true,
                            message = "Preview target failed; recording continues",
                        ),
                    )
                }
                EventLogger.markError(
                    Categories.SYSTEM,
                    "RECORDER_PREVIEW_TARGET_CHANGE_FAILED",
                    t.message ?: t.javaClass.simpleName,
                    t,
                )
            }
        }
    }

    fun stop() {
        postCamera {
            stopping = true
            startInFlight = false
            cancelOpenWatchdog()
            cancelSetupWatchdog()
            cancelEncodedWatchdog()
            cancelTimeout()
            cancelRecoveryRetry()
            if (currentPartial != null) {
                finalizeCurrentSegment("STOP", null)
            } else {
                closeCamera()
                updateState(
                    state.copy(
                        status = RecorderStatus.STOPPED,
                        currentFile = null,
                        segmentStartedAtEpochMs = null,
                    ),
                )
                if (!releasing) onStopped()
            }
        }
    }

    fun bookmark() {
        postCamera {
            val requestedAt = System.currentTimeMillis()
            val existing = currentIncidentTag ?: incidentStore.pending(requestedAt)
            val eventId = existing?.eventId ?: IncidentProtectionStore.eventId(requestedAt)
            val eventRequestedAt = existing?.requestedAtEpochMs ?: requestedAt

            if (currentPartial != null) {
                currentIncidentTag = IncidentTag(
                    eventId = eventId,
                    requestedAtEpochMs = eventRequestedAt,
                    role = IncidentProtectionStore.ROLE_CURRENT,
                )
                currentConsumesPendingIncident = false
                protectedPending = true
            }
            // Always reserve one successful segment after the press. The store
            // survives a service recreation, but expires before a later drive.
            val incidentPersisted = incidentStore.saveNext(eventId, eventRequestedAt)
            protectRecentFinalizedSegments(
                count = 2,
                eventId = eventId,
                requestedAtEpochMs = eventRequestedAt,
            )
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_BOOKMARK_EVENT",
                payload = mapOf(
                    "eventId" to eventId,
                    "segment" to segmentNumber.toString(),
                    "currentPending" to (currentPartial != null).toString(),
                    "previousSegments" to "2",
                    "nextSegments" to "1",
                    "persisted" to incidentPersisted.toString(),
                ),
            )
            updateState(
                state.copy(
                    message = if (incidentPersisted) {
                        "Saving event: previous 2 + current + next 1"
                    } else {
                        "Current clip protected; future incident window could not be persisted"
                    },
                    lastError = if (incidentPersisted) state.lastError else "INCIDENT_PERSIST_FAILED",
                ),
            )
        }
    }

    fun retry() {
        postCamera {
            val cfg = config ?: return@postCamera
            if (state.status != RecorderStatus.CAMERA_UNAVAILABLE ||
                startInFlight || cameraOpenInFlight || recording
            ) {
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_RETRY_IGNORED",
                    payload = mapOf(
                        "status" to state.status,
                        "expected" to RecorderStatus.CAMERA_UNAVAILABLE,
                    ),
                )
                return@postCamera
            }
            if (cameraDevice != null) closeCamera()
            stopping = false
            cancelOpenWatchdog()
            cancelSetupWatchdog()
            cancelTimeout()
            cancelRecoveryRetry()
            recoveryRetryAttempt = 0
            updateState(state.copy(status = RecorderStatus.STARTING, lastError = null, message = "Retrying"))
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_RETRY",
                payload = mapOf("cameraId" to cfg.cameraId, "profile" to cfg.profile.key),
            )
            startInFlight = true
            openCamera(cfg.cameraId)
        }
    }

    /**
     * Deterministic, bounded teardown: the cleanup runs on the camera thread and
     * the finalize task is submitted BEFORE the latch releases, so the subsequent
     * executors can drain submitted recovery/finalization work. Never blocks on
     * frame decoding; the
     * wait is capped so a wedged camera thread cannot hang onDestroy.
     */
    fun release() {
        val handler = cameraHandler
        val thread = cameraThread
        if (handler == null || thread == null) {
            // Never started; nothing to tear down.
            unregisterAvailabilityCallback()
            wakeLockHolder.releaseAll()
            shutdownExecutors()
            return
        }
        if (thread.looper.thread === Thread.currentThread()) {
            teardownOnCameraThread()
            unregisterAvailabilityCallback()
            wakeLockHolder.releaseAll()
            shutdownExecutors()
            return
        }
        val latch = CountDownLatch(1)
        val teardownPosted = handler.post {
            try {
                teardownOnCameraThread()
            } finally {
                // finalizeCurrentSegment submits sidecar/journal work before
                // teardown returns. shutdown() then drains that committed work.
                shutdownExecutors()
                latch.countDown()
            }
        }
        if (!teardownPosted) {
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_TEARDOWN_POST_REJECTED",
                "camera looper rejected teardown; no new finalize work can be submitted",
                null,
            )
            unregisterAvailabilityCallback()
            wakeLockHolder.releaseAll()
            shutdownExecutors()
            return
        }
        val teardownCompleted = try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!teardownCompleted) {
            // The camera runnable owns executor shutdown, so a slow codec can
            // still submit and drain final sidecar/journal work after this wait.
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_TEARDOWN_TIMEOUT",
                "camera thread did not finish teardown within 3s; partial evidence may be retained",
                null,
            )
        }
        cameraThread?.quitSafely()
        unregisterAvailabilityCallback()
        wakeLockHolder.releaseAll()
    }

    private fun teardownOnCameraThread() {
        releasing = true
        stopping = true
        startInFlight = false
        cancelOpenWatchdog()
        cancelSetupWatchdog()
        cancelEncodedWatchdog()
        cancelTimeout()
        cancelRecoveryRetry()
        if (currentPartial != null) {
            finalizeCurrentSegment("STOP", null)
        }
        closeCamera()
        releaseRecordingPreviewSurface()
        wakeLockHolder.releaseAll()
    }

    private fun ensureCameraThread() {
        if (cameraThread == null) {
            val thread = HandlerThread("recorder-camera").also { it.start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }
    }

    private fun registerAvailabilityCallback() {
        if (availabilityRegistered) return
        runCatching {
            manager.registerAvailabilityCallback(availabilityCallback, cameraHandler)
            availabilityRegistered = true
        }.onFailure {
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_AVAILABILITY_REGISTER_FAILED",
                it.message ?: it.javaClass.simpleName,
                it,
            )
        }
    }

    private fun unregisterAvailabilityCallback() {
        if (!availabilityRegistered) return
        runCatching { manager.unregisterAvailabilityCallback(availabilityCallback) }
        availabilityRegistered = false
    }

    private fun postCamera(block: () -> Unit) {
        ensureCameraThread()
        cameraHandler?.post(block)
    }

    private fun runIo(block: () -> Unit) {
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute(block)
        } catch (t: RejectedExecutionException) {
            // Recorder is releasing; the segment was already isolated by finalize.
        }
    }

    private fun runStorage(block: () -> Unit) {
        if (storageExecutor.isShutdown) return
        try {
            storageExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Recorder is releasing; no new camera work may start.
        }
    }

    private fun runWork(kind: RecorderWorkKind, block: () -> Unit) {
        when (RecorderWorkLanePolicy.laneFor(kind)) {
            RecorderWorkLane.STORAGE -> runStorage(block)
            RecorderWorkLane.ANALYSIS -> runIo(block)
        }
    }

    private fun queueJournalStage(journalFile: File, stage: SegmentJournalStage) {
        runIo {
            runCatching { SegmentJournalIO.update(journalFile, stage) }
                .onFailure { error ->
                    EventLogger.markError(
                        Categories.SYSTEM,
                        "RECORDER_JOURNAL_UPDATE_FAILED",
                        "${journalFile.name}: ${stage.name}: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
        }
    }

    private fun shutdownExecutors() {
        storageExecutor.shutdown()
        ioExecutor.shutdown()
    }

    private fun openCamera(cameraId: String) {
        val cfg = config ?: run {
            startInFlight = false
            return
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            startInFlight = false
            cameraUnavailable("CAMERA_PERMISSION_DENIED")
            return
        }
        val declared = CameraRuntime.videoSizeCandidates(context, cameraId, cfg.sourceKind)
            .map { ProfileSize(it.width, it.height) }
        if (!RecorderConfig.profileDeclared(cfg.effectiveSourceProfile, declared)) {
            val message = "Source profile ${cfg.effectiveSourceProfile.key} not declared by camera $cameraId"
            EventLogger.markError(Categories.SYSTEM, "RECORDER_PROFILE_NOT_DECLARED", message, null)
            startInFlight = false
            cameraUnavailable(message)
            return
        }
        cameraOpenInFlight = true
        openGeneration++
        val generation = openGeneration
        scheduleOpenWatchdog(generation)
        try {
            manager.openCamera(cameraId, createStateCallback(generation), cameraHandler)
        } catch (e: CameraAccessException) {
            cancelOpenWatchdog()
            cameraOpenInFlight = false
            startInFlight = false
            cameraUnavailable(cameraAccessMessage(e))
        } catch (t: Throwable) {
            cancelOpenWatchdog()
            cameraOpenInFlight = false
            startInFlight = false
            cameraUnavailable(t.message ?: "camera open failed")
        }
    }

    /**
     * Each open request gets a callback capturing its open-generation token.
     * Only the callback whose token still matches may own/tear down shared state;
     * a stale callback only closes its own camera.
     */
    private fun createStateCallback(generation: Long): CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                // Stale callbacks must mutate no shared state: check the token first.
                if (generation != openGeneration) {
                    closeQuietly(camera)
                    return
                }
                cameraOpenInFlight = false
                startInFlight = false
                cancelOpenWatchdog()
                cancelRecoveryRetry()
                recoveryRetryAttempt = 0
                if (stopping || releasing || config == null) {
                    closeQuietly(camera)
                    return
                }
                cameraDevice = camera
                startSegment()
            }

            override fun onDisconnected(camera: CameraDevice) {
                if (generation != openGeneration) {
                    closeQuietly(camera)
                    return
                }
                // Current generation: this callback camera must be closed even if it
                // was never assigned to cameraDevice; avoid a double close for the
                // assigned instance by detaching it first.
                if (cameraDevice === camera) cameraDevice = null
                closeQuietly(camera)
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CAMERA_DISCONNECTED")
                handleCameraLoss("CAMERA_DISCONNECTED")
            }

            override fun onError(camera: CameraDevice, errorCode: Int) {
                if (generation != openGeneration) {
                    closeQuietly(camera)
                    return
                }
                if (cameraDevice === camera) cameraDevice = null
                closeQuietly(camera)
                val message = cameraDeviceErrorMessage(errorCode)
                EventLogger.markError(Categories.SYSTEM, "RECORDER_CAMERA_ERROR", message, null)
                handleCameraLoss(message)
            }
        }

    private fun startSegment() {
        val cfg = config ?: return
        if (stopping || releasing) return
        val device = cameraDevice ?: return
        if (storageCheckInFlight) return
        storageCheckInFlight = true
        val cameraToken = openGeneration
        val requestedAtEpochMs = System.currentTimeMillis()
        val prepared = PreparedSegmentStart(
            segmentNumber = segmentNumber + 1,
            requestedAtEpochMs = requestedAtEpochMs,
            requestedAtElapsedMs = SystemClock.elapsedRealtime(),
            partial = SegmentNaming.partialFile(
                segmentsDir,
                segmentNumber + 1,
                cfg.profile,
                requestedAtEpochMs,
            ),
        )
        runWork(RecorderWorkKind.NEXT_SEGMENT_GATE) {
            val decision = prepareStorage(cfg)
            val journalResult = if (decision.proceed) {
                runCatching {
                    SegmentJournalIO.begin(
                        journalsDir = journalsDir,
                        partial = prepared.partial,
                        finalFile = SegmentNaming.finalFileFor(prepared.partial),
                        nowEpochMs = prepared.requestedAtEpochMs,
                    )
                }
            } else {
                null
            }
            cameraHandler?.post {
                storageCheckInFlight = false
                if (stopping || releasing || config != cfg || cameraDevice !== device ||
                    cameraToken != openGeneration
                ) {
                    journalResult?.getOrNull()?.let { staleJournal -> runIo { staleJournal.delete() } }
                    return@post
                }
                if (!decision.proceed) {
                    storageBlocked(decision.reason ?: "STORAGE_BLOCKED")
                } else if (journalResult?.isFailure != false) {
                    storageBlocked(
                        "JOURNAL_BEGIN_FAILED: ${journalResult?.exceptionOrNull()?.message ?: "unknown"}",
                    )
                } else {
                    startSegmentAfterStorage(
                        cfg = cfg,
                        device = device,
                        prepared = prepared,
                        journal = requireNotNull(journalResult?.getOrNull()),
                    )
                }
            }
        }
    }

    /** Camera-thread setup entered only after storage and durable journal gates succeed. */
    private fun startSegmentAfterStorage(
        cfg: RecorderConfig,
        device: CameraDevice,
        prepared: PreparedSegmentStart,
        journal: File,
    ) {
        if (stopping || releasing || cameraDevice !== device) return
        segmentGeneration++
        val generation = segmentGeneration
        segmentNumber = prepared.segmentNumber
        val nowEpoch = prepared.requestedAtEpochMs
        val nowElapsed = prepared.requestedAtElapsedMs
        segmentStartedAtEpochMs = nowEpoch
        segmentStartedAtElapsedMs = nowElapsed
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        frameStats = SegmentFrameStats()
        lastTimestampNs = null
        if (currentIncidentTag == null) {
            val pendingIncident = incidentStore.pending(nowEpoch)
            if (pendingIncident != null) {
                currentIncidentTag = pendingIncident
                currentConsumesPendingIncident = true
                protectedPending = true
            }
        }
        val partial = prepared.partial
        currentPartial = partial
        currentJournal = journal
        try {
            partial.parentFile?.mkdirs()
            scheduleSetupWatchdog(generation)
            val pipeline = RecordingPipelineFactory.create(
                outputFile = partial,
                config = cfg,
                onRuntimeError = { message ->
                    cameraHandler?.post {
                        if (generation == segmentGeneration && currentPartial === partial && !stopping && !releasing) {
                            finalizeCurrentSegment("PIPELINE_ERROR", message)
                        }
                    }
                },
            )
            val surface = pipeline.cameraSurface
            recordingPipeline = pipeline
            activeEncoderSurface = surface
            captureSession?.close()
            captureSession = null
            fun configureSession(includePreview: Boolean) {
                if (!ownsSetup(generation, partial, pipeline)) return
                val preview = recordingPreviewSurface?.takeIf { includePreview && it.isValid }
                if (includePreview && preview == null) {
                    releaseRecordingPreviewSurface()
                    updateState(state.copy(previewActive = false, previewFallbackUsed = true))
                    configureSession(includePreview = false)
                    return
                }
                val outputs = if (preview != null) listOf(surface, preview) else listOf(surface)
                device.createCaptureSession(
                    outputs,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!ownsSetup(generation, partial, pipeline)) {
                                closeQuietlySession(session)
                                return
                            }
                            captureSession = session
                            val previewTarget = preview?.takeIf { previewOutputDesired }
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(surface)
                                previewTarget?.let(::addTarget)
                            }.build()
                            try {
                                session.setRepeatingRequest(
                                    request,
                                    createFrameCaptureCallback(generation),
                                    cameraHandler,
                                )
                            } catch (t: Throwable) {
                                captureSession = null
                                closeQuietlySession(session)
                                if (previewTarget != null) {
                                    fallbackToRecorderOnly(
                                        generation = generation,
                                        partial = partial,
                                        pipeline = pipeline,
                                        reason = t.message ?: "PREVIEW_REPEATING_REQUEST_FAILED",
                                    ) { configureSession(includePreview = false) }
                                } else {
                                    failSegmentStart(generation, partial, t.message ?: "repeating request failed")
                                }
                                return
                            }
                            try {
                                pipeline.start()
                                currentJournal?.let { journalFile ->
                                    queueJournalStage(journalFile, SegmentJournalStage.CAPTURING)
                                }
                                cancelSetupWatchdog()
                                recording = true
                                segmentRecordingStartedAtEpochMs = System.currentTimeMillis()
                                segmentRecordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
                                updateState(
                                    state.copy(
                                        status = RecorderStatus.RECORDING,
                                        segmentNumber = segmentNumber,
                                        currentFile = partial.name,
                                        segmentStartedAtEpochMs = segmentRecordingStartedAtEpochMs,
                                        lastError = null,
                                        message = null,
                                        previewActive = previewTarget != null,
                                    ),
                                )
                                EventLogger.logEvent(
                                    Categories.SYSTEM,
                                    "RECORDER_SEGMENT_START",
                                    payload = mapOf(
                                        "segment" to segmentNumber.toString(),
                                        "file" to partial.name,
                                        "profile" to cfg.profile.key,
                                        "mode" to cfg.recordingMode.name,
                                        "pipelineVersion" to cfg.pipelineVersion.toString(),
                                        "processStartId" to processStartId,
                                        "previewActive" to (previewTarget != null).toString(),
                                    ),
                                )
                                scheduleEncodedWatchdog(generation)
                                scheduleKeyFrame(generation, cfg.segmentSeconds * 1000L - 250L)
                                scheduleTimeout(generation, cfg.segmentSeconds * 1000L)
                            } catch (t: Throwable) {
                                failSegmentStart(generation, partial, t.message ?: "recorder.start failed")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (!ownsSetup(generation, partial, pipeline)) {
                                quarantine(partial)
                                closeQuietlySession(session)
                                return
                            }
                            closeQuietlySession(session)
                            if (preview != null) {
                                fallbackToRecorderOnly(
                                    generation = generation,
                                    partial = partial,
                                    pipeline = pipeline,
                                    reason = "PREVIEW_RECORD_SESSION_CONFIGURE_FAILED",
                                ) { configureSession(includePreview = false) }
                            } else {
                                cancelSetupWatchdog()
                                failSegmentStart(generation, partial, "RECORD_SESSION_CONFIGURE_FAILED")
                            }
                        }
                    },
                    cameraHandler,
                )
            }
            configureSession(includePreview = recordingPreviewSurface != null)
        } catch (t: Throwable) {
            failSegmentStart(generation, partial, t.message ?: "recorder prepare failed")
        }
    }

    private fun fallbackToRecorderOnly(
        generation: Long,
        partial: File,
        pipeline: RecordingPipeline,
        reason: String,
        retryRecorderOnly: () -> Unit,
    ) {
        if (!ownsSetup(generation, partial, pipeline)) return
        releaseRecordingPreviewSurface()
        updateState(
            state.copy(
                previewActive = false,
                previewFallbackUsed = true,
                message = "Preview unavailable; recording-only fallback",
            ),
        )
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_PREVIEW_FALLBACK",
            payload = mapOf("reason" to reason, "segment" to segmentNumber.toString()),
        )
        retryRecorderOnly()
    }

    /** The async setup callback may only mutate shared state while it still owns this segment. */
    private fun ownsSetup(
        generation: Long,
        partial: File,
        localPipeline: RecordingPipeline,
    ): Boolean = SegmentGuardPolicy.ownsSetup(generation, segmentGeneration, currentPartial, partial) &&
        !stopping && !releasing &&
        recordingPipeline === localPipeline

    private fun scheduleTimeout(generation: Long, delayMs: Long) {
        cancelTimeout()
        val runnable = Runnable {
            if (RecorderTimeoutPolicy.ownsSegment(
                    token = generation,
                    currentGeneration = segmentGeneration,
                    recording = recording,
                    hasCurrentPartial = currentPartial != null,
                )
            ) {
                finalizeCurrentSegment("TIMEOUT", null)
            }
        }
        timeoutRunnable = runnable
        cameraHandler?.postDelayed(runnable, delayMs)
    }

    private fun cancelTimeout() {
        val runnable = timeoutRunnable
        if (runnable != null) {
            cameraHandler?.removeCallbacks(runnable)
            timeoutRunnable = null
        }
        keyFrameRunnable?.let { cameraHandler?.removeCallbacks(it) }
        keyFrameRunnable = null
    }

    private fun scheduleKeyFrame(generation: Long, delayMs: Long) {
        keyFrameRunnable?.let { cameraHandler?.removeCallbacks(it) }
        val runnable = Runnable {
            if (generation == segmentGeneration && recording && !stopping && !releasing) {
                val requested = recordingPipeline?.requestKeyFrame() == true
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_SEGMENT_KEYFRAME_REQUEST",
                    payload = mapOf(
                        "segment" to segmentNumber.toString(),
                        "supported" to requested.toString(),
                    ),
                )
            }
        }
        keyFrameRunnable = runnable
        cameraHandler?.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleOpenWatchdog(generation: Long) {
        cancelOpenWatchdog()
        val runnable = Runnable {
            if (WatchdogPolicy.isCurrent(generation, openGeneration)) {
                openWatchdogFired(generation)
            }
        }
        openWatchdogRunnable = runnable
        cameraHandler?.postDelayed(runnable, WatchdogPolicy.OPEN_TIMEOUT_MS)
    }

    private fun cancelOpenWatchdog() {
        val runnable = openWatchdogRunnable
        if (runnable != null) {
            cameraHandler?.removeCallbacks(runnable)
            openWatchdogRunnable = null
        }
    }

    /** HAL never completed the open: fail with explicit evidence and invalidate the token. */
    private fun openWatchdogFired(generation: Long) {
        if (!WatchdogPolicy.isCurrent(generation, openGeneration)) return
        openGeneration++ // any pending onOpened becomes stale and closes its own camera
        cameraOpenInFlight = false
        startInFlight = false
        EventLogger.markError(
            Categories.SYSTEM,
            "RECORDER_OPEN_TIMEOUT",
            "generation=$generation timeoutMs=${WatchdogPolicy.OPEN_TIMEOUT_MS}",
            null,
        )
        closeCamera()
        cameraUnavailable("CAMERA_OPEN_TIMEOUT")
    }

    private fun scheduleSetupWatchdog(generation: Long) {
        cancelSetupWatchdog()
        val runnable = Runnable {
            if (WatchdogPolicy.isCurrent(generation, segmentGeneration) &&
                !stopping && !releasing && currentPartial != null
            ) {
                setupWatchdogFired(generation)
            }
        }
        setupWatchdogRunnable = runnable
        cameraHandler?.postDelayed(runnable, WatchdogPolicy.SETUP_TIMEOUT_MS)
    }

    private fun cancelSetupWatchdog() {
        val runnable = setupWatchdogRunnable
        if (runnable != null) {
            cameraHandler?.removeCallbacks(runnable)
            setupWatchdogRunnable = null
        }
    }

    /** Session configuring never completed: reuse the fail path (quarantine + FAILED sidecar). */
    private fun setupWatchdogFired(generation: Long) {
        if (!WatchdogPolicy.isCurrent(generation, segmentGeneration) || stopping || releasing) return
        val partial = currentPartial ?: return
        failSegmentStart(generation, partial, "CAMERA_SETUP_TIMEOUT")
    }

    private fun scheduleEncodedWatchdog(generation: Long) {
        cancelEncodedWatchdog()
        lastEncodedProgressAtElapsedMs = SystemClock.elapsedRealtime()
        lastEncodedFrameCount = 0L
        lastEncodedFileBytes = 0L
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (generation != segmentGeneration || !recording || stopping || releasing) return@Runnable
            val progress = recordingPipeline?.progress ?: return@Runnable
            val fileBytes = currentPartial?.takeIf { it.exists() }?.length() ?: 0L
            val now = SystemClock.elapsedRealtime()
            val advanced = progress.encodedFrameCount > lastEncodedFrameCount || fileBytes > lastEncodedFileBytes
            if (advanced) lastEncodedProgressAtElapsedMs = now
            val stalled = EncodedOutputWatchdogPolicy.stalled(
                elapsedSinceProgressMs = now - lastEncodedProgressAtElapsedMs,
                encodedFrameCount = progress.encodedFrameCount,
                previousEncodedFrameCount = lastEncodedFrameCount,
                fileBytes = fileBytes,
                previousFileBytes = lastEncodedFileBytes,
            )
            lastEncodedFrameCount = progress.encodedFrameCount
            lastEncodedFileBytes = fileBytes
            updateState(
                state.copy(
                    encodedFrameCount = progress.encodedFrameCount,
                    encodedBytes = maxOf(progress.encodedBytes, fileBytes),
                ),
            )
            if (stalled) {
                finalizeCurrentSegment("PIPELINE_ERROR", "ENCODED_OUTPUT_STALLED")
            } else {
                cameraHandler?.postDelayed(runnable, EncodedOutputWatchdogPolicy.CHECK_INTERVAL_MS)
            }
        }
        encodedWatchdogRunnable = runnable
        cameraHandler?.postDelayed(runnable, EncodedOutputWatchdogPolicy.CHECK_INTERVAL_MS)
    }

    private fun cancelEncodedWatchdog() {
        encodedWatchdogRunnable?.let { cameraHandler?.removeCallbacks(it) }
        encodedWatchdogRunnable = null
    }

    /**
     * Per-segment capture callback: queued results from a closed previous session
     * must never pollute the current segment's frameStats/timestamps.
     */
    private fun createFrameCaptureCallback(generation: Long) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (generation != segmentGeneration || !recording) return
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                val prev = lastTimestampNs
                lastTimestampNs = ts
                frameStats = frameStats.copy(
                    count = frameStats.count + 1,
                    firstTimestampNs = frameStats.firstTimestampNs ?: ts,
                    lastTimestampNs = ts,
                    maxGapNs = if (prev != null) {
                        maxOf(frameStats.maxGapNs ?: 0L, ts - prev)
                    } else {
                        frameStats.maxGapNs
                    },
                )
            }
        }

    private fun failSegmentStart(generation: Long, partial: File, message: String) {
        cancelSetupWatchdog()
        cancelEncodedWatchdog()
        if (generation != segmentGeneration || stopping || releasing || currentPartial !== partial) {
            // Stale setup failure: isolate the old partial only; never touch a newer segment.
            quarantine(partial)
            return
        }
        val cfg = config ?: return
        cancelTimeout()
        recording = false
        runCatching { recordingPipeline?.release() }
        recordingPipeline = null
        captureSession?.close()
        captureSession = null
        activeEncoderSurface = null
        val effectiveFile = quarantine(partial) ?: partial
        val journal = currentJournal
        journal?.let { queueJournalStage(it, SegmentJournalStage.QUARANTINED) }
        val snapshot = SegmentSnapshot(
            file = effectiveFile,
            finalPath = null,
            cameraId = cfg.cameraId,
            profile = cfg.profile,
            segmentSeconds = cfg.segmentSeconds,
            segmentNumber = segmentNumber,
            processStartId = processStartId,
            requestedAtEpochMs = segmentStartedAtEpochMs,
            requestedAtElapsedRealtimeMs = segmentStartedAtElapsedMs,
            startedAtEpochMs = segmentStartedAtEpochMs,
            stoppedAtEpochMs = System.currentTimeMillis(),
            startedAtElapsedRealtimeMs = segmentStartedAtElapsedMs,
            stoppedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            gapFromPreviousMs = null,
            result = SegmentSidecar.RESULT_FAILED,
            error = message,
            fileBytes = if (effectiveFile.exists()) effectiveFile.length() else 0L,
            protected = protectedPending,
            eventId = currentIncidentTag?.eventId,
            eventRequestedAtEpochMs = currentIncidentTag?.requestedAtEpochMs,
            eventRole = currentIncidentTag?.role,
            frameStats = frameStats,
            storageLimitBytes = cfg.storageLimitBytes,
            minFreeBytes = cfg.minFreeBytes,
            recordingMode = cfg.recordingMode,
            sourceFingerprint = cfg.sourceFingerprint,
            sourceKind = cfg.sourceKind,
            frontCalibration = cfg.frontCalibration,
            encoderProfile = cfg.encoderProfile,
            pipelineVersion = cfg.pipelineVersion,
            pipelineEvidence = null,
            journalFile = journal,
        )
        // Idempotent cleanup of every current-segment field so a later STOP cannot
        // finalize this same failed segment again, and a late callback cannot revive it.
        currentPartial = null
        currentJournal = null
        segmentStartedAtEpochMs = null
        segmentStartedAtElapsedMs = null
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        protectedPending = false
        currentIncidentTag = null
        currentConsumesPendingIncident = false
        lastTimestampNs = null
        frameStats = SegmentFrameStats()
        EventLogger.markError(Categories.SYSTEM, "RECORDER_SEGMENT_START_FAILED", message, null)
        runIo {
            val written = writeSidecarAsync(buildSidecarFromSnapshot(snapshot, cfg.profile, null, null))
            if (written != null) snapshot.journalFile?.let { SegmentJournalIO.complete(it) }
        }
        setError("SEGMENT_START_FAILED: $message")
        closeCamera()
    }

    /**
     * Camera-thread part only: cancel timeout, stop recorder, rename/quarantine,
     * capture an immutable snapshot, then schedule the next segment immediately.
     * The async metadata/health/sidecar work never gates the next segment.
     */
    private fun finalizeCurrentSegment(reason: String, forcedError: String?) {
        cancelSetupWatchdog()
        cancelEncodedWatchdog()
        cancelTimeout()
        val partial = currentPartial ?: run {
            afterFinalize(reason, success = false, error = forcedError ?: "no active segment")
            return
        }
        val journal = currentJournal
        val cfg = config
        val requestedAtEpoch = segmentStartedAtEpochMs
        val requestedAtElapsed = segmentStartedAtElapsedMs
        val actualStartedEpoch = segmentRecordingStartedAtEpochMs ?: requestedAtEpoch
        val actualStartedElapsed = segmentRecordingStartedAtElapsedMs ?: requestedAtElapsed
        val stats = frameStats
        val protected = protectedPending
        val incidentTag = currentIncidentTag
        val consumesPendingIncident = currentConsumesPendingIncident
        protectedPending = false
        currentIncidentTag = null
        currentConsumesPendingIncident = false
        val wasRecording = recording
        currentPartial = null
        currentJournal = null
        recording = false
        segmentStartedAtEpochMs = null
        segmentStartedAtElapsedMs = null
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        updateState(state.copy(status = RecorderStatus.FINALIZING))

        var stopError: String? = forcedError
        var pipelineEvidence: PipelineStopEvidence? = null
        if (RecorderTransitionPolicy.shouldInvokeStop(wasRecording)) {
            try {
                pipelineEvidence = recordingPipeline?.stop()
            } catch (t: Throwable) {
                stopError = stopError ?: (t.message ?: "recorder.stop failed")
            }
        }
        journal?.let { queueJournalStage(it, SegmentJournalStage.ENCODER_STOPPED) }
        runCatching { recordingPipeline?.release() }
        recordingPipeline = null
        pipelineEvidence?.progress?.let { progress ->
            updateState(
                state.copy(
                    encodedFrameCount = progress.encodedFrameCount,
                    encodedBytes = progress.encodedBytes,
                ),
            )
        }
        captureSession?.close()
        captureSession = null
        activeEncoderSurface = null

        val finalFile = SegmentNaming.finalFileFor(partial)
        val partialExists = partial.exists()
        val partialBytes = if (partialExists) partial.length() else 0L
        val renameSucceeded = stopError == null && partialExists && partialBytes > 0L &&
            SegmentJournalIO.promote(partial, finalFile)
        if (renameSucceeded) {
            journal?.let { queueJournalStage(it, SegmentJournalStage.MEDIA_PROMOTED) }
        }
        val outcome = FinalizePolicy.outcome(
            stopError = stopError,
            partialExists = partialExists,
            partialBytes = partialBytes,
            renameSucceeded = renameSucceeded,
        )
        val success = outcome.success
        val effectiveFile = if (success) finalFile else quarantine(partial) ?: partial
        if (!success) journal?.let { queueJournalStage(it, SegmentJournalStage.QUARANTINED) }
        val fileBytes = if (effectiveFile.exists()) effectiveFile.length() else 0L
        val stoppedEpoch = System.currentTimeMillis()
        val stoppedElapsed = SystemClock.elapsedRealtime()
        val gap = SegmentGapPolicy.encodedGapMs(
            previousEncodedPresentationTimeUs,
            pipelineEvidence?.progress?.firstPresentationTimeUs,
        ) ?: SegmentGapPolicy.gapMs(previousSegmentStoppedElapsedMs, actualStartedElapsed)
        previousSegmentStoppedElapsedMs = stoppedElapsed
        pipelineEvidence?.progress?.lastPresentationTimeUs?.let {
            previousEncodedPresentationTimeUs = it
        }

        val snapshot = SegmentSnapshot(
            file = effectiveFile,
            finalPath = if (success) finalFile else null,
            cameraId = cfg?.cameraId ?: state.cameraId ?: "?",
            profile = cfg?.profile ?: state.profile,
            segmentSeconds = cfg?.segmentSeconds ?: state.segmentSeconds,
            segmentNumber = segmentNumber,
            processStartId = processStartId,
            requestedAtEpochMs = requestedAtEpoch,
            requestedAtElapsedRealtimeMs = requestedAtElapsed,
            startedAtEpochMs = actualStartedEpoch,
            stoppedAtEpochMs = stoppedEpoch,
            startedAtElapsedRealtimeMs = actualStartedElapsed,
            stoppedAtElapsedRealtimeMs = stoppedElapsed,
            gapFromPreviousMs = gap,
            result = if (success) SegmentSidecar.RESULT_SUCCESS else SegmentSidecar.RESULT_FAILED,
            error = outcome.error,
            fileBytes = fileBytes,
            protected = protected,
            eventId = incidentTag?.eventId,
            eventRequestedAtEpochMs = incidentTag?.requestedAtEpochMs,
            eventRole = incidentTag?.role,
            frameStats = stats,
            storageLimitBytes = cfg?.storageLimitBytes ?: state.storageLimitBytes,
            minFreeBytes = cfg?.minFreeBytes ?: SettingsStore.get(context).minFreeBytes,
            recordingMode = cfg?.recordingMode ?: RecordingMode.SURROUND_360,
            sourceFingerprint = cfg?.sourceFingerprint.orEmpty(),
            sourceKind = cfg?.sourceKind ?: RecordingSourceKind.COMPOSITE,
            frontCalibration = cfg?.frontCalibration,
            encoderProfile = cfg?.encoderProfile,
            pipelineVersion = cfg?.pipelineVersion ?: RecorderConfig.CURRENT_PIPELINE_VERSION,
            pipelineEvidence = pipelineEvidence,
            journalFile = journal,
        )
        if (success) {
            pendingSidecarFiles += snapshot.finalPath!!.name
            if (consumesPendingIncident && incidentTag != null) {
                incidentStore.consume(incidentTag.eventId)
            }
        }
        EventLogger.logEvent(
            Categories.SYSTEM,
            if (success) "RECORDER_SEGMENT_STOP" else "RECORDER_SEGMENT_FAILED",
            payload = mapOf(
                "segment" to snapshot.segmentNumber.toString(),
                "file" to snapshot.file.name,
                "result" to snapshot.result,
                "error" to (snapshot.error ?: "-"),
                "bytes" to snapshot.fileBytes.toString(),
                "frames" to snapshot.frameStats.count.toString(),
                "gapMs" to (snapshot.gapFromPreviousMs?.toString() ?: "-"),
            ),
        )
        runWork(RecorderWorkKind.COMPLETED_SEGMENT_ANALYSIS) {
            finishSegmentAsync(snapshot, success)
        }
        afterFinalize(reason, success, outcome.error)
    }

    /** IO-thread work for one finalized segment; never touches mutable segment fields. */
    private fun finishSegmentAsync(
        snapshot: SegmentSnapshot,
        success: Boolean,
    ) {
        try {
            val profile = snapshot.profile ?: run {
                cameraHandler?.post { updateState(state.copy(lastError = "SIDECAR_PROFILE_MISSING")) }
                return
            }
            if (success && snapshot.finalPath != null) {
                val finalFile = snapshot.finalPath
                // Provisional sidecar first: storage scans must see an owned mp4 with a
                // sidecar (analysis pending) instead of an unknown file.
                writeSidecarAsync(
                    buildSidecarFromSnapshot(snapshot, profile, null, null).copy(provisional = true),
                )
                val actualTrack = CameraRuntime.readTrackMetadata(finalFile)?.let {
                    ActualTrackInfo(
                        width = it.width,
                        height = it.height,
                        bitrateBps = it.bitrateBps,
                        durationMs = it.durationMs,
                    )
                }
                val verifiedTrack = actualTrack?.width == profile.size.width &&
                    actualTrack.height == profile.size.height &&
                    (actualTrack.durationMs ?: 0L) > 0L
                if (!verifiedTrack) {
                    SegmentSidecarIO.sidecarFileFor(finalFile).delete()
                    val quarantined = quarantine(finalFile) ?: finalFile
                    val written = writeSidecarAsync(
                        buildSidecarFromSnapshot(
                            snapshot.copy(
                                file = quarantined,
                                finalPath = null,
                                result = SegmentSidecar.RESULT_FAILED,
                                error = "OUTPUT_TRACK_VERIFICATION_FAILED",
                            ),
                            profile,
                            actualTrack,
                            null,
                        ),
                    )
                    if (written != null) snapshot.journalFile?.let { SegmentJournalIO.complete(it) }
                    EventLogger.markError(
                        Categories.SYSTEM,
                        "RECORDER_OUTPUT_TRACK_REJECTED",
                        "${finalFile.name}: expected=${profile.size} actual=${actualTrack?.width}x${actualTrack?.height}",
                        null,
                    )
                    return
                }
                if (!finalFile.exists()) {
                    // Evicted mid-analysis: record the loss, never write an orphan sidecar.
                    EventLogger.logEvent(
                        Categories.SYSTEM,
                        "RECORDER_SEGMENT_EVICTED_DURING_ANALYSIS",
                        payload = mapOf("file" to finalFile.name),
                    )
                    return
                }
                val health = sampleAndAnalyzeFrames(finalFile)
                // Bookmark may have been applied (or upload-pinned) after the
                // snapshot was captured; merge with the on-disk sidecar so the
                // enrichment never overwrites protection/pin state.
                val finalSidecar = synchronized(RecorderStorageLock.lock) {
                    val existing = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(finalFile))
                    val effectiveProtected = SidecarProtectionPolicy.effectiveProtected(
                        existingProtected = existing?.protected,
                        snapshotProtected = snapshot.protected,
                    )
                    writeSidecarAsync(
                        buildSidecarFromSnapshot(snapshot, profile, actualTrack, health)
                            .copy(
                                provisional = false,
                                protected = effectiveProtected,
                                uploadPinned = existing?.uploadPinned ?: false,
                                eventId = existing?.eventId ?: snapshot.eventId,
                                eventRequestedAtEpochMs = existing?.eventRequestedAtEpochMs
                                    ?: snapshot.eventRequestedAtEpochMs,
                                eventRole = existing?.eventRole ?: snapshot.eventRole,
                            ),
                    )
                }
                if (finalSidecar != null) snapshot.journalFile?.let { SegmentJournalIO.complete(it) }
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_SEGMENT_HEALTH",
                    payload = mapOf(
                        "segment" to snapshot.segmentNumber.toString(),
                        "health" to (health?.status ?: "-"),
                        "frames" to (health?.sampledFrames?.toString() ?: "-"),
                        "actualTrack" to (
                            actualTrack?.let { "${it.width}x${it.height}@${it.bitrateBps}" }
                                ?: "PENDING_OFFLINE"
                            ),
                    ),
                )
            } else {
                val written = writeSidecarAsync(buildSidecarFromSnapshot(snapshot, profile, null, null))
                if (written != null) snapshot.journalFile?.let { SegmentJournalIO.complete(it) }
            }
            val settings = SettingsStore.get(context)
            if (settings.autoCleanupEnabled) {
                enforceAutomaticCleanup(
                    limitBytes = snapshot.storageLimitBytes,
                    reserveBytes = snapshot.minFreeBytes,
                    estimatedNextSegmentBytes = 0L,
                    retentionHours = settings.retentionHours,
                )
            }
        } finally {
            // Never leave a permanent non-evictable entry, even on profile-null/exception paths.
            snapshot.finalPath?.let { pendingSidecarFiles -= it.name }
        }
    }

    private fun afterFinalize(reason: String, success: Boolean, error: String?) {
        if (reason == "STOP" || stopping) {
            closeCamera()
            updateState(
                state.copy(
                    status = RecorderStatus.STOPPED,
                    currentFile = null,
                    segmentStartedAtEpochMs = null,
                    message = null,
                ),
            )
            if (!releasing) onStopped()
            return
        }
        if (reason == "CAMERA_LOSS") {
            closeCamera()
            cameraUnavailable(error ?: "CAMERA_LOSS")
            return
        }
        if (!success) {
            closeCamera()
            setError("SEGMENT_FAILED: ${error ?: "unknown"}")
            return
        }
        val cfg = config
        if (cameraDevice != null) {
            startSegment()
        } else {
            cfg?.let { openCamera(it.cameraId) }
        }
    }

    private fun handleCameraLoss(message: String) {
        cancelOpenWatchdog()
        cancelSetupWatchdog()
        cancelEncodedWatchdog()
        cancelTimeout()
        startInFlight = false
        cameraOpenInFlight = false
        EventLogger.markError(Categories.SYSTEM, "RECORDER_CAMERA_LOSS", message, null)
        // PREPARING segments (currentPartial != null, recording == false) must also be
        // finalized/quarantined so a late onConfigured cannot start a dead recorder.
        if (SegmentGuardPolicy.shouldFinalizeOnLoss(currentPartial != null)) {
            finalizeCurrentSegment("CAMERA_LOSS", message)
        } else {
            closeCamera()
            cameraUnavailable(message)
        }
    }

    private fun cameraUnavailable(message: String) {
        closeCamera()
        EventLogger.markError(Categories.SYSTEM, "RECORDER_CAMERA_UNAVAILABLE", message, null)
        val retryMessage = scheduleRecoveryRetry(message)
        updateState(
            state.copy(
                status = RecorderStatus.CAMERA_UNAVAILABLE,
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = message,
                message = retryMessage ?: "Camera unavailable; manual Retry available",
            ),
        )
    }

    private fun setError(message: String) {
        cancelRecoveryRetry()
        updateState(
            state.copy(
                status = RecorderFailureStatusPolicy.recorderFailure(),
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = message,
            ),
        )
    }

    private fun storageBlocked(reason: String) {
        cancelRecoveryRetry()
        closeCamera()
        EventLogger.markError(Categories.SYSTEM, "RECORDER_STORAGE_BLOCKED", reason, null)
        updateState(
            state.copy(
                status = RecorderFailureStatusPolicy.storageFailure(),
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = reason,
                message = "Storage blocked; recording stopped",
            ),
        )
        if (!releasing) onStopped()
    }

    private fun protectRecentFinalizedSegments(
        count: Int,
        eventId: String,
        requestedAtEpochMs: Long,
    ) {
        runIo {
            val candidates = segmentsDir.listFiles()
                ?.filter { SegmentNaming.isFinalMp4(it.name) }
                .orEmpty()
                .mapNotNull { file ->
                    val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))
                        ?: return@mapNotNull null
                    val started = sidecar.startedAtEpochMs ?: return@mapNotNull null
                    val stopped = sidecar.stoppedAtEpochMs ?: return@mapNotNull null
                    Triple(file, sidecar, SegmentTimeRange(file.absolutePath, started, stopped))
                }
            val selectedPaths = IncidentWindowPolicy.select(
                segments = candidates.map { it.third },
                requestedAtEpochMs = requestedAtEpochMs,
                beforeMs = count * state.segmentSeconds * 1000L,
                afterMs = 0L,
            ).takeLast(count).toSet()
            val recent = candidates.filter { it.first.absolutePath in selectedPaths }
            var protectedCount = 0
            synchronized(RecorderStorageLock.lock) {
                recent.forEach { (file, originalSidecar, _) ->
                    val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))
                        ?: originalSidecar
                    // Do not steal a segment from an older explicit incident.
                    if (!sidecar.eventId.isNullOrBlank() && sidecar.eventId != eventId) return@forEach
                    SegmentSidecarIO.writeAtomic(
                        file,
                        sidecar.copy(
                            protected = true,
                            eventId = eventId,
                            eventRequestedAtEpochMs = requestedAtEpochMs,
                            eventRole = IncidentProtectionStore.ROLE_PREVIOUS,
                        ),
                    )
                    protectedCount++
                }
            }
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_BOOKMARK_PREVIOUS",
                payload = mapOf(
                    "eventId" to eventId,
                    "requested" to count.toString(),
                    "protected" to protectedCount.toString(),
                ),
            )
            cameraHandler?.post {
                updateState(
                    state.copy(message = "Event saved: $protectedCount previous, current, next pending"),
                )
            }
        }
    }

    private fun quarantineLeftoverPartials() {
        val leftovers = segmentsDir.listFiles()
            ?.filter { SegmentNaming.isPartial(it.name) || it.name.endsWith(".tmp") }
            .orEmpty()
        leftovers.forEach { file ->
            if (SegmentNaming.isPartial(file.name)) {
                val moved = quarantine(file)
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_QUARANTINED_LEFT_OVER",
                    payload = mapOf("file" to file.name, "movedTo" to (moved?.name ?: "-")),
                )
            } else {
                file.delete()
            }
        }
    }

    private fun quarantine(file: File): File? = try {
        val target = File(quarantineDir, file.name)
        if (file.renameTo(target)) target else null
    } catch (t: Throwable) {
        null
    }

    private fun buildSidecarFromSnapshot(
        s: SegmentSnapshot,
        profile: CameraFormatProfile,
        actualTrack: ActualTrackInfo?,
        health: FrameHealthReport?,
    ): SegmentSidecar {
        val settings = SettingsStore.get(context)
        val layout = if (s.recordingMode == RecordingMode.SURROUND_360) {
            SegmentLaneLayoutFactory.forProfile(
                width = profile.size.width,
                height = profile.size.height,
                labels = settings.laneLabels,
                displayOrder = settings.laneOrder,
                rotations = settings.laneRotations,
            )
        } else {
            null
        }
        val encoded = s.pipelineEvidence?.progress
        return SegmentSidecar(
            file = s.file.absolutePath,
            cameraId = s.cameraId,
            profile = profile,
            segmentSeconds = s.segmentSeconds,
            segmentNumber = s.segmentNumber,
            processStartId = s.processStartId,
            requestedAtEpochMs = s.requestedAtEpochMs,
            requestedAtElapsedRealtimeMs = s.requestedAtElapsedRealtimeMs,
            startedAtEpochMs = s.startedAtEpochMs,
            stoppedAtEpochMs = s.stoppedAtEpochMs,
            startedAtElapsedRealtimeMs = s.startedAtElapsedRealtimeMs,
            stoppedAtElapsedRealtimeMs = s.stoppedAtElapsedRealtimeMs,
            gapFromPreviousMs = s.gapFromPreviousMs,
            result = s.result,
            error = s.error,
            fileBytes = s.fileBytes,
            protected = s.protected,
            eventId = s.eventId,
            eventRequestedAtEpochMs = s.eventRequestedAtEpochMs,
            eventRole = s.eventRole,
            actualTrack = actualTrack,
            frameStats = s.frameStats,
            frameHealth = health,
            laneLayout = layout,
            recordingMode = s.recordingMode,
            sourceFingerprint = s.sourceFingerprint,
            sourceKind = s.sourceKind,
            frontCalibration = s.frontCalibration,
            encoderProfile = s.encoderProfile,
            requestedBitrateBps = profile.bitrateBps,
            actualCodecName = s.pipelineEvidence?.codecName,
            actualCodecFormat = s.pipelineEvidence?.actualFormat,
            encodedFrameCount = encoded?.encodedFrameCount,
            encodedBytes = encoded?.encodedBytes,
            firstEncodedPresentationTimeUs = encoded?.firstPresentationTimeUs,
            lastEncodedPresentationTimeUs = encoded?.lastPresentationTimeUs,
            calibrationVersion = s.frontCalibration?.calibrationVersion,
            pipelineVersion = s.pipelineVersion,
        )
    }

    /** Sidecar write failure must never crash the recorder; it degrades to an error log. */
    private fun writeSidecarAsync(sidecar: SegmentSidecar): File? {
        val target = File(sidecar.file)
        return try {
            val file = SegmentSidecarIO.writeAtomic(target, sidecar)
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_SIDECAR_WRITTEN",
                payload = mapOf(
                    "file" to file.absolutePath,
                    "segment" to sidecar.segmentNumber.toString(),
                    "result" to sidecar.result,
                ),
            )
            cameraHandler?.post { updateState(state.copy(lastSidecarPath = file.absolutePath)) }
            file
        } catch (t: Throwable) {
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_SIDECAR_WRITE_FAILED",
                "${sidecar.segmentNumber}: ${t.message ?: "write failed"}",
                t,
            )
            cameraHandler?.post {
                updateState(
                    state.copy(
                        lastError = "SIDECAR_WRITE_FAILED segment ${sidecar.segmentNumber}",
                        message = "Recorder degraded: sidecar write failed",
                    ),
                )
            }
            null
        }
    }

    private fun sampleAndAnalyzeFrames(file: File): FrameHealthReport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return FrameHealthReport(status = FrameHealthReport.STATUS_UNAVAILABLE, sampledFrames = 0)
        }
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (durationMs == null || durationMs <= 0L) {
                return FrameHealthReport(status = FrameHealthReport.STATUS_UNAVAILABLE, sampledFrames = 0)
            }
            val maxPixels = 64 * 64
            val frames = mutableListOf<PixelFrame>()
            for (fraction in listOf(0.1, 0.5, 0.9)) {
                val timeUs = (durationMs * 1000L * fraction).toLong()
                val bmp = retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    64,
                    64,
                )
                if (bmp != null) {
                    val w = bmp.width
                    val h = bmp.height
                    if (w * h <= maxPixels) {
                        val argb = IntArray(w * h)
                        bmp.getPixels(argb, 0, w, 0, 0, w, h)
                        frames += PixelFrame(width = w, height = h, argb = argb)
                    }
                    bmp.recycle()
                }
            }
            FrameHealthAnalyzer.analyze(frames)
        } catch (t: Throwable) {
            EventLogger.markError(Categories.SYSTEM, "RECORDER_FRAME_HEALTH_FAILED", file.name, t)
            FrameHealthReport(status = FrameHealthReport.STATUS_UNAVAILABLE, sampledFrames = 0)
        } finally {
            try {
                retriever?.release()
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }

    /**
     * Runs before every segment on the camera thread: proactively evicts down to
     * max(0, limit - estimated) so the next segment fits, then applies the pure
     * storage decision. Fails closed on any managed-file error. Expensive frame
     * analysis never runs on this thread.
     */
    private fun prepareStorage(cfg: RecorderConfig): StorageDecision {
        return try {
            val estimated = StoragePolicy.estimateSegmentBytes(cfg.profile.bitrateBps, cfg.segmentSeconds)
            val settings = SettingsStore.get(context)
            if (settings.autoCleanupEnabled) {
                enforceAutomaticCleanup(
                    limitBytes = cfg.storageLimitBytes,
                    reserveBytes = cfg.minFreeBytes,
                    estimatedNextSegmentBytes = estimated,
                    retentionHours = settings.retentionHours,
                )
            }
            val quarantineBytes = directoryBytes(quarantineDir)
            if (!StoragePolicy.quarantineWithinBound(quarantineBytes)) {
                return StorageDecision(
                    proceed = false,
                    reason = "QUARANTINE_LIMIT_EXCEEDED bytes=$quarantineBytes limit=${StoragePolicy.MAX_QUARANTINE_BYTES}",
                )
            }
            val usage = recordingArtifactUsageBytes()
            StoragePolicy.canStartSegment(
                usageBytes = usage,
                limitBytes = cfg.storageLimitBytes,
                estimatedBytes = estimated,
                availableBytes = availableStorageBytes(),
                reserveBytes = cfg.minFreeBytes,
            )
        } catch (t: Throwable) {
            val message = "STORAGE_CHECK_FAILED: ${t.message ?: t.javaClass.simpleName}"
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_STORAGE_CHECK_FAILED",
                message,
                t,
            )
            // Hard gate: a managed-file error must not silently proceed.
            StorageDecision(proceed = false, reason = message)
        }
    }

    /**
     * Computes one oldest-first cleanup target from both product constraints.
     * Protected/saved, playback-pinned, transfer-pinned, current and analysis
     * files remain non-candidates inside [enforceStorageLimit].
     */
    private fun enforceAutomaticCleanup(
        limitBytes: Long,
        reserveBytes: Long,
        estimatedNextSegmentBytes: Long,
        retentionHours: Int,
    ) {
        enforceRetentionLimit(retentionHours, System.currentTimeMillis())
        val finalizedUsage = segmentsDir.listFiles()
            ?.filter { SegmentNaming.isFinalMp4(it.name) }
            ?.sumOf { it.length() }
            ?: 0L
        val usage = recordingArtifactUsageBytes()
        val nonEvictableUsage = (usage - finalizedUsage).coerceAtLeast(0L)
        val quotaTarget = (limitBytes - estimatedNextSegmentBytes).coerceAtLeast(0L)
        val available = availableStorageBytes()
        val requiredFree = reserveBytes + estimatedNextSegmentBytes
        val freeSpaceTarget = if (available >= 0L && available < requiredFree) {
            (usage - (requiredFree - available)).coerceAtLeast(0L)
        } else {
            usage
        }
        val target = minOf(usage, quotaTarget, freeSpaceTarget)
        if (target < usage) {
            enforceStorageLimit(limitBytes, (target - nonEvictableUsage).coerceAtLeast(0L))
        }
    }

    private fun recordingArtifactUsageBytes(): Long =
        directoryBytes(File(context.filesDir, "recordings"))

    private fun directoryBytes(directory: File): Long = runCatching {
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(Long.MAX_VALUE)

    @SuppressLint("UsableSpace")
    private fun availableStorageBytes(): Long = try {
        File(context.filesDir, "recordings").usableSpace
    } catch (t: Throwable) {
        -1L
    }

    private fun enforceStorageLimit(limitBytes: Long, target: Long = limitBytes) {
        // Serialized with upload-pin writes so read/select/delete cannot race a
        // pin that was just applied.
        val changed = synchronized(RecorderStorageLock.lock) {
            val files = managedSegmentFilesLocked()
            val evictions = StoragePolicy.selectEvictionsToTarget(files, target)
            deleteEvictionsLocked(
                paths = evictions,
                eventName = "RECORDER_STORAGE_EVICTED",
                evidence = mapOf("limitBytes" to limitBytes.toString()),
            )
        }
        if (changed) RecorderLibrary.notifyChanged()
    }

    private fun enforceRetentionLimit(retentionHours: Int, nowEpochMs: Long) {
        val cutoff = StoragePolicy.retentionCutoffEpochMs(nowEpochMs, retentionHours) ?: return
        val changed = synchronized(RecorderStorageLock.lock) {
            val evictions = StoragePolicy.selectRetentionEvictions(managedSegmentFilesLocked(), cutoff)
            deleteEvictionsLocked(
                paths = evictions,
                eventName = "RECORDER_RETENTION_EVICTED",
                evidence = mapOf(
                    "retentionHours" to retentionHours.toString(),
                    "cutoffEpochMs" to cutoff.toString(),
                ),
            )
        }
        if (changed) RecorderLibrary.notifyChanged()
    }

    /** Caller holds [RecorderStorageLock.lock] for the full read/select/delete transaction. */
    private fun managedSegmentFilesLocked(): List<ManagedSegmentFile> = segmentsDir.listFiles()
        ?.filter { SegmentNaming.isFinalMp4(it.name) }
        ?.map { file ->
            val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))
            val analysisInFlight = file.name in pendingSidecarFiles
            val finalizedOwnedSidecar = sidecar?.takeIf {
                it.file == file.absolutePath &&
                    it.result == SegmentSidecar.RESULT_SUCCESS &&
                    !it.provisional
            }
            ManagedSegmentFile(
                path = file.absolutePath,
                bytes = file.length(),
                lastModifiedMs = file.lastModified(),
                isFinalMp4 = true,
                hasSidecar = finalizedOwnedSidecar != null || analysisInFlight,
                protected = sidecar?.protected ?: false,
                uploadPinned = sidecar?.uploadPinned ?: false,
                analysisInFlight = analysisInFlight,
                playing = PlaybackPinRegistry.isPinned(file),
                stoppedAtEpochMs = finalizedOwnedSidecar?.stoppedAtEpochMs,
            )
        }
        .orEmpty()

    /** Caller holds [RecorderStorageLock.lock]. */
    private fun deleteEvictionsLocked(
        paths: List<String>,
        eventName: String,
        evidence: Map<String, String>,
    ): Boolean {
        var changed = false
        paths.forEach { path ->
            val mp4 = File(path)
            val sidecar = SegmentSidecarIO.sidecarFileFor(mp4)
            if (mp4.delete()) {
                changed = true
                if (sidecar.exists() && !sidecar.delete()) {
                    EventLogger.markError(
                        Categories.SYSTEM,
                        "RECORDER_EVICT_SIDECAR_DELETE_FAILED",
                        sidecar.name,
                        null,
                    )
                }
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    eventName,
                    payload = evidence + ("file" to mp4.name),
                )
            }
        }
        return changed
    }

    private fun closeCamera() {
        cancelEncodedWatchdog()
        cameraOpenInFlight = false
        try {
            captureSession?.close()
            captureSession = null
            activeEncoderSurface = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    private fun releaseRecordingPreviewSurface() {
        val surface = recordingPreviewSurface
        recordingPreviewSurface = null
        runCatching { surface?.release() }
    }

    private fun scheduleRecoveryRetry(reason: String): String? {
        cancelRecoveryRetry()
        if (!isRecoverableCameraContention(reason) || stopping || releasing || config == null) return null
        val attemptIndex = recoveryRetryAttempt
        if (attemptIndex >= RECOVERY_RETRY_DELAYS_MS.size) return null
        val delayMs = RECOVERY_RETRY_DELAYS_MS[attemptIndex]
        val attemptNumber = attemptIndex + 1
        recoveryRetryAttempt = attemptNumber
        val runnable = Runnable {
            recoveryRetryRunnable = null
            val cfg = config
            if (cfg == null || stopping || releasing || state.status != RecorderStatus.CAMERA_UNAVAILABLE ||
                startInFlight || cameraOpenInFlight || recording
            ) {
                return@Runnable
            }
            closeCamera()
            updateState(
                state.copy(
                    status = RecorderStatus.RECOVERING,
                    lastError = null,
                    message = "Recovering camera ($attemptNumber/${RECOVERY_RETRY_DELAYS_MS.size})",
                ),
            )
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_AUTO_RETRY",
                payload = mapOf(
                    "attempt" to attemptNumber.toString(),
                    "reason" to reason,
                    "delayMs" to delayMs.toString(),
                ),
            )
            startInFlight = true
            openCamera(cfg.cameraId)
        }
        recoveryRetryRunnable = runnable
        cameraHandler?.postDelayed(runnable, delayMs)
        return "Camera unavailable; retry $attemptNumber/${RECOVERY_RETRY_DELAYS_MS.size} in ${delayMs / 1000L}s"
    }

    private fun cancelRecoveryRetry() {
        recoveryRetryRunnable?.let { cameraHandler?.removeCallbacks(it) }
        recoveryRetryRunnable = null
    }

    private fun isRecoverableCameraContention(reason: String): Boolean =
        reason.contains("CAMERA_IN_USE") ||
            reason.contains("MAX_CAMERAS_IN_USE") ||
            reason.contains("CAMERA_DISCONNECTED")

    private fun closeQuietly(camera: CameraDevice) {
        try {
            camera.close()
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    private fun closeQuietlySession(session: CameraCaptureSession) {
        try {
            session.close()
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    private fun cameraAccessMessage(e: CameraAccessException): String = when (e.reason) {
        CameraAccessException.CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraAccessException.CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraAccessException.CAMERA_DISCONNECTED -> "CAMERA_DISCONNECTED"
        CameraAccessException.CAMERA_ERROR -> "CAMERA_ERROR"
        else -> "CAMERA_ACCESS_ERROR code=${e.reason}"
    }

    private fun cameraDeviceErrorMessage(errorCode: Int): String = when (errorCode) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE_ERROR"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE_ERROR"
        else -> "CAMERA_ERROR code=$errorCode"
    }

    private companion object {
        val RECOVERY_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)
    }

    /** Immutable per-segment evidence captured on the camera thread before async work. */
    private data class PreparedSegmentStart(
        val segmentNumber: Int,
        val requestedAtEpochMs: Long,
        val requestedAtElapsedMs: Long,
        val partial: File,
    )

    private data class SegmentSnapshot(
        val file: File,
        val finalPath: File?,
        val cameraId: String,
        val profile: CameraFormatProfile?,
        val segmentSeconds: Int,
        val segmentNumber: Int,
        val processStartId: String,
        val requestedAtEpochMs: Long?,
        val requestedAtElapsedRealtimeMs: Long?,
        val startedAtEpochMs: Long?,
        val stoppedAtEpochMs: Long?,
        val startedAtElapsedRealtimeMs: Long?,
        val stoppedAtElapsedRealtimeMs: Long?,
        val gapFromPreviousMs: Long?,
        val result: String,
        val error: String?,
        val fileBytes: Long,
        val protected: Boolean,
        val eventId: String?,
        val eventRequestedAtEpochMs: Long?,
        val eventRole: String?,
        val frameStats: SegmentFrameStats,
        val storageLimitBytes: Long,
        val minFreeBytes: Long,
        val recordingMode: RecordingMode,
        val sourceFingerprint: String,
        val sourceKind: RecordingSourceKind,
        val frontCalibration: FrontCalibration?,
        val encoderProfile: EncoderProfile?,
        val pipelineVersion: Int,
        val pipelineEvidence: PipelineStopEvidence?,
        val journalFile: File?,
    )
}
