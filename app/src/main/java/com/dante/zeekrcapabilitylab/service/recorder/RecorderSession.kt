package com.dante.zeekrcapabilitylab.service.recorder
import com.dante.zeekrcapabilitylab.util.Utils

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.usbexport.UsbDirectRecordingCapabilityStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackEvent
import com.dante.zeekrcapabilitylab.usbexport.UsbFastTrackReportStore
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCommitEngine
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentRetentionManager
import com.dante.zeekrcapabilitylab.usbexport.UsbRecordingFreeSpace
import com.dante.zeekrcapabilitylab.product.CameraRuntime
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPinRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap

/**
 * Sole owner of CameraDevice / CaptureSession / MediaRecorder for the segment
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
    private val externallyManagedPresence: Boolean = false,
) {
    private data class PendingPacedStart(
        val session: CameraCaptureSession,
        val request: CaptureRequest,
        val encoder: Surface,
        val generation: Long,
        val token: Long,
        val intervalNs: Long,
        val firstDeadlineNs: Long,
    )

    private data class PendingTimeLapseFinalize(
        var reason: String,
        var forcedError: String?,
        val segmentGeneration: Long,
        val session: CameraCaptureSession?,
        val camera: CameraDevice?,
        val token: Long,
    )

    private val segmentsDir = File(context.filesDir, "recordings/segments").apply { mkdirs() }
    private val internalOutputSink: RecordingOutputSink = InternalRecordingOutputSink(segmentsDir)
    private val usbRecoveryJournal = UsbRecordingRecoveryJournal(context)
    private val usbCapabilityStore = UsbDirectRecordingCapabilityStore(context)
    private val usbCommitEngine = UsbSegmentCommitEngine(context)
    private val usbRetentionManager = UsbSegmentRetentionManager(context)
    private val usbTokenDir = File(context.filesDir, "recordings/usb-tokens")
    private var activeUsbTarget: UsbExportTarget? = null
    private var sessionStoragePlan: RecordingSessionStoragePlan? = null
    private var usbOutputSink: RecordingOutputSink? = null
    private var currentSegmentCanary = false
    private val quarantineDir = File(context.filesDir, "recordings/quarantine").apply { mkdirs() }
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val incidentStore = IncidentProtectionStore(context)

    /** Stable process identity captured once; never the per-command service startId. */
    private val processStartId: String = ZeekrApp.processStartId
    private val recordingSessionIdentity = RecordingSessionIdentity()
    private val wakeLockHolder = RecorderWakeLockHolder(context) { event, message ->
        EventLogger.logEvent(
            Categories.SYSTEM,
            event,
            payload = mapOf("message" to message),
        )
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var config: RecorderConfig? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingPreviewSurface: Surface? = null
    private var pendingRecordingPreviewSurface: Surface? = null
    private var activeEncoderSurface: Surface? = null
    private var previewOutputDesired = true
    private var currentPartial: File? = null
    private var currentOutput: RecordingOutputHandle? = null
    private var usbFallbackPendingReason: String? = null
    private var lastUsbFallbackReason: String? = null
    private var pendingUsbCommits = 0
    private var sessionCanaryPassed = false
    private var segmentNumber = 0
    private var recording = false
    @Volatile private var stopping = false
    @Volatile private var releasing = false
    private var startInFlight = false
    private var cameraOpenInFlight = false
    private var protectedPending = false
    private var currentIncidentTag: IncidentTag? = null
    /** True only when this segment is fulfilling the persisted post-event slot. */
    private var currentConsumesPendingIncident = false
    private var segmentStartedAtEpochMs: Long? = null
    private var segmentStartedAtElapsedMs: Long? = null
    private var segmentRecordingStartedAtEpochMs: Long? = null
    private var segmentRecordingStartedAtElapsedMs: Long? = null
    private var previousSegmentStoppedElapsedMs: Long? = null
    private var frameStats = SegmentFrameStats()
    private var lastTimestampNs: Long? = null
    private var lastFrameReceivedAtElapsedMs: Long? = null
    private var segmentGeneration = 0L
    private var previewReplacementGeneration = 0L
    private var finalizeSequence = 0L
    private var openGeneration = 0L
    private var manualSessionGeneration = 0L
    private var timeoutRunnable: Runnable? = null
    private var usbPresenceWatchdogRunnable: Runnable? = null
    private var openWatchdogRunnable: Runnable? = null
    private var setupWatchdogRunnable: Runnable? = null
    private var previewReplacementWatchdogRunnable: Runnable? = null
    private var previewReplacementWatchdogToken: Long? = null
    private val cameraRecovery = CameraRecoveryStateMachine()
    private var cameraRecoveryTimerRunnable: Runnable? = null
    private val vehicleAway = VehicleAwayStateMachine()
    private var vehicleAwayTimerRunnable: Runnable? = null
    private var vehiclePowerReconciliationRunnable: Runnable? = null
    private var pacedEncoderRunnable: Runnable? = null
    private var pacedEncoderWatchdogRunnable: Runnable? = null
    private var pacedEncoderWatchdogDeadlineNs: Long? = null
    private var captureCadenceGeneration = 0L
    private var timeLapseQuiesced = false
    private var pacedCaptureInFlightSequenceId: Int? = null
    private var pacedCaptureInFlightSession: CameraCaptureSession? = null
    private var pendingPacedStart: PendingPacedStart? = null
    private var pendingTimeLapseFinalize: PendingTimeLapseFinalize? = null
    private val retiredPreviewSurfaces = mutableMapOf<CameraCaptureSession, MutableSet<Surface>>()
    private val previewSurfacesAwaitingDevice = mutableSetOf<Surface>()
    private val repeatingSequences = mutableMapOf<CameraCaptureSession, MutableSet<Int>>()
    @Volatile private var closeTransaction: CaptureCloseTransaction? = null
    @Volatile private var cleanupUnconfirmed = false
    @Volatile private var releaseRequested = false
    private var disposalStarted = false
    @Volatile private var disposalFinished = false
    private var releaseCompletion: (() -> Unit)? = null
    private var timeLapseTeardownToken = 0L
    private var timeLapseQuiesceWatchdogRunnable: Runnable? = null
    private var lastVehiclePowerSnapshot: VehiclePowerSnapshot? = null
    private var lastVehiclePowerSnapshotLogAtMs = 0L
    /** Finalized mp4 names whose sidecar/health analysis is still in flight. */
    private val pendingSidecarFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var state = RecorderState()
    private var cameraDiagnosticsRegistered = false
    private var diagnosticsActiveCameraId: String? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null

    init {
        CaptureCleanupRuntime.initialize(context)
        updateState(state)
    }

    private fun updateState(s: RecorderState) {
        wakeLockHolder.sync(s.status)
        state = s.copy(wakeLockHeld = wakeLockHolder.isHeld)
        publishState(state)
    }

    fun start(config: RecorderConfig, previewSurface: Surface? = null) {
        postCamera {
            val errors = config.validate()
            if (errors.isNotEmpty()) {
                val message = "CONFIG_INVALID: ${errors.joinToString("; ")}"
                EventLogger.markError(Categories.SYSTEM, "RECORDER_CONFIG_INVALID", message, null)
                setError(message)
                runCatching { previewSurface?.release() }
                onStopped()
                return@postCamera
            }
            val busy = RecorderCommandPolicy.isActive(state.status) ||
                cameraDevice != null || recording || startInFlight || cameraOpenInFlight ||
                closeTransaction != null || releaseRequested || cleanupUnconfirmed
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
            this.previewOutputDesired = true
            releaseRecordingPreviewSurface()
            releasePendingRecordingPreviewSurface()
            this.recordingPreviewSurface = previewSurface?.takeIf { it.isValid }
            if (this.recordingPreviewSurface == null) runCatching { previewSurface?.release() }
            this.stopping = false
            this.releasing = false
            this.timeLapseQuiesced = false
            this.protectedPending = false
            this.currentIncidentTag = null
            this.currentConsumesPendingIncident = false
            this.segmentNumber = 0
            this.previousSegmentStoppedElapsedMs = null
            val recordingSessionId = recordingSessionIdentity.beginNewSession()
            val sessionStartedAtEpochMs = System.currentTimeMillis()
            manualSessionGeneration++
            val selectedUsb = if (config.storagePreference == RecordingStoragePreference.USB_PREFERRED) {
            runCatching { UsbPendingRecordingRecovery.recoverMounted(context) }
                UsbExportVolumeResolver.mountedTargets(context).singleOrNull()
            } else {
                null
            }
            activeUsbTarget = selectedUsb
            sessionStoragePlan = RecordingSessionStoragePlan(
                preference = config.storagePreference,
                active = selectedUsb?.let {
                    RecordingStorageIdentity(
                        kind = RecordingStorageKind.USB_MEDIASTORE,
                        storageUuid = it.storageUuid,
                        volumeName = it.volumeName,
                        description = it.description,
                    )
                } ?: RecordingStorageIdentity(RecordingStorageKind.INTERNAL),
                usbQuotaBytes = config.usbQuotaBytes,
            )
            usbOutputSink = selectedUsb?.let {
                UsbMediaStoreRecordingOutputSink(
                    context = context,
                    target = it,
                    recordingSessionId = recordingSessionId,
                    recordingMode = config.recordingMode,
                    tokenDir = usbTokenDir,
                    journal = usbRecoveryJournal,
                )
            }
            usbFallbackPendingReason = null
            lastUsbFallbackReason = null
            pendingUsbCommits = 0
            sessionCanaryPassed = false
            cameraRecovery.beginManualSession(manualSessionGeneration, config.cameraId)
            vehicleAway.beginManualSession(manualSessionGeneration)
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_RESUME_GATE_ARMED",
                payload = mapOf(
                    "generation" to manualSessionGeneration.toString(),
                    "reason" to "MANUAL_START",
                ),
            )
            cancelOpenWatchdog()
            cancelSetupWatchdog()
            cancelTimeout()
            cancelUsbPresenceWatchdog()
            cancelCameraRecoveryTimer()
            cancelVehicleAwayTimer()
            cancelVehiclePowerReconciliation()
            lastVehiclePowerSnapshot = null
            quarantineLeftoverPartials()
            updateState(
                state.copy(
                    status = RecorderStatus.STARTING,
                    cameraId = config.cameraId,
                    profile = config.profile,
                    sourceRole = config.source.sourceRole,
                    layoutKind = config.source.layoutKind,
                    segmentSeconds = config.segmentSeconds,
                    storageLimitBytes = config.storageLimitBytes,
                    storagePreference = config.storagePreference,
                    usbQuotaBytes = config.usbQuotaBytes,
                    activeStorageKind = sessionStoragePlan?.active?.kind ?: RecordingStorageKind.INTERNAL,
                    segmentNumber = 0,
                    currentFile = null,
                    segmentStartedAtEpochMs = null,
                    lastError = null,
                    lastSidecarPath = null,
                    message = if (selectedUsb != null) "Starting · USB" else "Starting · internal storage",
                    previewRequested = this.recordingPreviewSurface != null,
                    previewActive = false,
                    previewFallbackUsed = false,
                    recordingSessionId = recordingSessionId,
                    sessionStartedAtEpochMs = sessionStartedAtEpochMs,
                    recordingMode = config.recordingMode,
                    timeLapseMultiplier = config.timeLapseMultiplier,
                    effectiveSegmentSeconds = if (selectedUsb != null) USB_SEGMENT_SECONDS else config.effectiveSegmentSeconds(),
                ),
            )
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_START",
                payload = mapOf(
                    "cameraId" to config.cameraId,
                    "profile" to config.profile.key,
                    "segmentSeconds" to config.segmentSeconds.toString(),
                    "effectiveSegmentSeconds" to (if (selectedUsb != null) USB_SEGMENT_SECONDS else config.effectiveSegmentSeconds()).toString(),
                    "recordingMode" to config.recordingMode.name,
                    "timeLapseMultiplier" to config.timeLapseMultiplier.toString(),
                    "captureRateFps" to (config.captureRateFpsOrNull()?.toString() ?: "-"),
                    "storageLimitBytes" to config.storageLimitBytes.toString(),
                    "storagePreference" to config.storagePreference.name,
                    "usbQuotaBytes" to config.usbQuotaBytes.toString(),
                    "activeStorageKind" to (sessionStoragePlan?.active?.kind ?: RecordingStorageKind.INTERNAL).name,
                    "processStartId" to processStartId,
                    "recordingSessionId" to recordingSessionId,
                ),
            )
            startCameraConflictDiagnostics(config)
            startInFlight = true
            openCamera(config.cameraId)
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
            if (!enabled) {
                updateState(state.copy(previewRequested = false, previewActive = false))
            }
            val session = captureSession ?: return@postCamera
            val device = cameraDevice ?: return@postCamera
            val encoder = activeEncoderSurface?.takeIf { it.isValid } ?: return@postCamera
            if (!recording) return@postCamera
            val preview = recordingPreviewSurface?.takeIf {
                enabled && !state.previewFallbackUsed && it.isValid
            }
            try {
                applyActiveCaptureFlow(
                    session = session,
                    device = device,
                    encoder = encoder,
                    preview = preview,
                    generation = segmentGeneration,
                )
                updateState(
                    state.copy(
                        previewRequested = preview != null,
                        previewActive = preview != null,
                    ),
                )
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
                    val fallbackFailure = runCatching {
                        applyActiveCaptureFlow(
                            session = session,
                            device = device,
                            encoder = encoder,
                            preview = null,
                            generation = segmentGeneration,
                        )
                    }.exceptionOrNull()
                    updateState(
                        state.copy(
                            previewActive = false,
                            previewFallbackUsed = true,
                            message = "Preview target failed; recording continues",
                        ),
                    )
                    if (fallbackFailure != null) {
                        handleCameraLoss(
                            fallbackFailure.message ?: "ENCODER_CAPTURE_FLOW_FAILED",
                        )
                    }
                } else {
                    handleCameraLoss(t.message ?: "ENCODER_CAPTURE_FLOW_FAILED")
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

    /**
     * Rebuilds only the Camera2 capture session around the already-running
     * encoder Surface plus a newly composed UI Surface. MediaRecorder is never
     * stopped or replaced. Every callback is tied to both replacement and
     * segment generations so it cannot take over after a one-minute rollover.
     */
    fun replacePreviewSurface(replacement: Surface) {
        postCamera {
            if (!replacement.isValid || stopping || releasing) {
                runCatching { replacement.release() }
                return@postCamera
            }
            val device = cameraDevice
            val encoder = activeEncoderSurface
            val canRebuild = ActivePreviewReplacementPolicy.canRebuild(
                replacementValid = replacement.isValid,
                recording = recording && closeTransaction == null && !cleanupUnconfirmed && !releaseRequested,
                cameraReady = device != null,
                encoderReady = encoder?.isValid == true,
            )
            if (!canRebuild || device == null || encoder == null) {
                val queueForNextSegment = ActivePreviewReplacementPolicy.shouldQueueForNextSegment(
                    status = state.status,
                    stopping = stopping,
                    releasing = releasing,
                )
                if (queueForNextSegment) {
                    releasePendingRecordingPreviewSurface()
                    pendingRecordingPreviewSurface = replacement
                    previewOutputDesired = true
                    updateState(
                        state.copy(
                            previewRequested = true,
                            previewActive = false,
                            message = "Preview will reconnect on the next segment",
                        ),
                    )
                } else {
                    runCatching { replacement.release() }
                }
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    if (queueForNextSegment) {
                        "RECORDER_PREVIEW_REPLACEMENT_QUEUED"
                    } else {
                        "RECORDER_PREVIEW_REPLACEMENT_REJECTED"
                    },
                    payload = mapOf(
                        "recording" to recording.toString(),
                        "cameraReady" to (device != null).toString(),
                        "encoderReady" to (encoder?.isValid == true).toString(),
                    ),
                )
                return@postCamera
            }

            val previous = recordingPreviewSurface
            recordingPreviewSurface = replacement
            previewOutputDesired = true
            updateState(
                state.copy(
                    previewRequested = true,
                    previewActive = false,
                    previewFallbackUsed = false,
                    message = "Restoring recording preview",
                ),
            )
            val replacementToken = ++previewReplacementGeneration
            val replacementSegment = segmentGeneration
            cancelPacedEncoderCaptures()
            if (previous !== replacement && previous != null) retirePreviewSurface(previous)
            runCatching { captureSession?.stopRepeating() }
            runCatching { captureSession?.close() }
            captureSession = null
            configureActiveRecordingSession(
                device = device,
                encoder = encoder,
                preview = replacement,
                replacementToken = replacementToken,
                replacementSegment = replacementSegment,
            )
        }
    }

    private fun configureActiveRecordingSession(
        device: CameraDevice,
        encoder: Surface,
        preview: Surface?,
        replacementToken: Long,
        replacementSegment: Long,
    ) {
        if (!ownsActivePreviewReplacement(
                replacementToken,
                replacementSegment,
                encoder,
            )
        ) {
            return
        }
        val validPreview = preview?.takeIf { previewOutputDesired && it.isValid }
        if (preview != null && validPreview == null) {
            fallbackActivePreviewReplacement(
                device,
                encoder,
                preview,
                replacementToken,
                replacementSegment,
                "REPLACEMENT_SURFACE_INVALID",
            )
            return
        }
        val outputs = if (validPreview != null) listOf(encoder, validPreview) else listOf(encoder)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!ownsActivePreviewReplacement(
                        replacementToken,
                        replacementSegment,
                        encoder,
                    )
                ) {
                    closeQuietlySession(session)
                    return
                }
                cancelPreviewReplacementWatchdog(replacementToken)
                val previewTarget = validPreview?.takeIf {
                    recordingPreviewSurface === it && previewOutputDesired && it.isValid
                }
                if (validPreview != null && previewTarget == null) {
                    closeQuietlySession(session)
                    fallbackActivePreviewReplacement(
                        device,
                        encoder,
                        validPreview,
                        replacementToken,
                        replacementSegment,
                        "REPLACEMENT_SURFACE_ABANDONED_DURING_CONFIGURE",
                    )
                    return
                }
                try {
                    captureSession = session
                    applyActiveCaptureFlow(
                        session = session,
                        device = device,
                        encoder = encoder,
                        preview = previewTarget,
                        generation = replacementSegment,
                    )
                    updateState(
                        state.copy(
                            previewRequested = previewTarget != null,
                            previewActive = previewTarget != null,
                            previewFallbackUsed = previewTarget == null,
                            message = if (previewTarget == null) {
                                "Preview restore failed; recording continues"
                            } else {
                                null
                            },
                        ),
                    )
                    EventLogger.logEvent(
                        Categories.SYSTEM,
                        "RECORDER_PREVIEW_REPLACEMENT_ACTIVE",
                        payload = mapOf(
                            "replacementToken" to replacementToken.toString(),
                            "segmentGeneration" to replacementSegment.toString(),
                            "previewActive" to (previewTarget != null).toString(),
                        ),
                    )
                } catch (t: Throwable) {
                    if (captureSession === session) captureSession = null
                    cancelPacedEncoderCaptures()
                    closeQuietlySession(session)
                    if (previewTarget != null) {
                        fallbackActivePreviewReplacement(
                            device,
                            encoder,
                            previewTarget,
                            replacementToken,
                            replacementSegment,
                            t.message ?: "REPLACEMENT_REPEATING_REQUEST_FAILED",
                        )
                    } else {
                        handleCameraLoss(t.message ?: "ENCODER_SESSION_RESTORE_FAILED")
                    }
                }
            }

            override fun onReady(session: CameraCaptureSession) {
                onCaptureSessionProducerIdle(session, "SESSION_READY")
            }

            override fun onClosed(session: CameraCaptureSession) {
                onCaptureSessionProducerIdle(session, "SESSION_CLOSED")
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                closeQuietlySession(session)
                if (!ownsActivePreviewReplacement(
                        replacementToken,
                        replacementSegment,
                        encoder,
                    )
                ) {
                    return
                }
                cancelPreviewReplacementWatchdog(replacementToken)
                if (validPreview != null) {
                    fallbackActivePreviewReplacement(
                        device,
                        encoder,
                        validPreview,
                        replacementToken,
                        replacementSegment,
                        "REPLACEMENT_SESSION_CONFIGURE_FAILED",
                    )
                } else {
                    handleCameraLoss("ENCODER_SESSION_RESTORE_CONFIGURE_FAILED")
                }
            }
        }
        schedulePreviewReplacementWatchdog(
            token = replacementToken,
            replacementSegment = replacementSegment,
            device = device,
            encoder = encoder,
            attemptedPreview = validPreview != null,
        )
        try {
            device.createCaptureSession(outputs, callback, cameraHandler)
        } catch (t: Throwable) {
            cancelPreviewReplacementWatchdog(replacementToken)
            if (validPreview != null) {
                fallbackActivePreviewReplacement(
                    device,
                    encoder,
                    validPreview,
                    replacementToken,
                    replacementSegment,
                    t.message ?: "REPLACEMENT_SESSION_CREATE_FAILED",
                )
            } else {
                handleCameraLoss(t.message ?: "ENCODER_SESSION_RESTORE_CREATE_FAILED")
            }
        }
    }

    private fun fallbackActivePreviewReplacement(
        device: CameraDevice,
        encoder: Surface,
        preview: Surface,
        replacementToken: Long,
        replacementSegment: Long,
        reason: String,
    ) {
        if (!ownsActivePreviewReplacement(replacementToken, replacementSegment, encoder)) return
        if (recordingPreviewSurface === preview) releaseRecordingPreviewSurface()
        updateState(
            state.copy(
                previewRequested = false,
                previewActive = false,
                previewFallbackUsed = true,
                message = "Preview restore failed; recording continues",
            ),
        )
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_PREVIEW_REPLACEMENT_FALLBACK",
            payload = mapOf(
                "reason" to reason,
                "replacementToken" to replacementToken.toString(),
                "segmentGeneration" to replacementSegment.toString(),
            ),
        )
        configureActiveRecordingSession(
            device = device,
            encoder = encoder,
            preview = null,
            replacementToken = replacementToken,
            replacementSegment = replacementSegment,
        )
    }

    private fun ownsActivePreviewReplacement(
        token: Long,
        replacementSegment: Long,
        encoder: Surface,
    ): Boolean = ActivePreviewReplacementPolicy.ownsCallback(
        token = token,
        currentToken = previewReplacementGeneration,
        segmentGeneration = replacementSegment,
        currentSegmentGeneration = segmentGeneration,
        recording = recording && pendingTimeLapseFinalize == null && !stopping && !releasing,
        encoderMatches = activeEncoderSurface === encoder,
    )

    private fun schedulePreviewReplacementWatchdog(
        token: Long,
        replacementSegment: Long,
        device: CameraDevice,
        encoder: Surface,
        attemptedPreview: Boolean,
    ) {
        cancelPreviewReplacementWatchdog()
        val runnable = Runnable {
            if (!ownsActivePreviewReplacement(token, replacementSegment, encoder)) return@Runnable
            previewReplacementWatchdogRunnable = null
            previewReplacementWatchdogToken = null
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_PREVIEW_REPLACEMENT_TIMEOUT",
                "token=$token segmentGeneration=$replacementSegment preview=$attemptedPreview",
                null,
            )
            if (attemptedPreview) {
                releaseRecordingPreviewSurface()
                updateState(
                    state.copy(
                        previewRequested = false,
                        previewActive = false,
                        previewFallbackUsed = true,
                        message = "Preview restore timed out; recording continues",
                    ),
                )
                val fallbackToken = ++previewReplacementGeneration
                configureActiveRecordingSession(
                    device = device,
                    encoder = encoder,
                    preview = null,
                    replacementToken = fallbackToken,
                    replacementSegment = replacementSegment,
                )
            } else {
                handleCameraLoss("ENCODER_SESSION_RESTORE_TIMEOUT")
            }
        }
        previewReplacementWatchdogRunnable = runnable
        previewReplacementWatchdogToken = token
        cameraHandler?.postDelayed(runnable, PREVIEW_REPLACEMENT_TIMEOUT_MS)
    }

    private fun cancelPreviewReplacementWatchdog(token: Long? = null) {
        if (token != null && previewReplacementWatchdogToken != token) return
        previewReplacementWatchdogRunnable?.let { cameraHandler?.removeCallbacks(it) }
        previewReplacementWatchdogRunnable = null
        previewReplacementWatchdogToken = null
    }

    fun stop() {
        postCamera {
            stopSessionOnCameraThread(
                finalizeReason = "STOP",
                authorityReason = "MANUAL_STOP",
                forcedError = null,
                vehicleAwayConfirmed = false,
            )
        }
    }

    private fun stopSessionOnCameraThread(
        finalizeReason: String,
        authorityReason: String,
        forcedError: String?,
        vehicleAwayConfirmed: Boolean,
    ) {
        if (stopping) return
        val generation = manualSessionGeneration
        stopping = true
        startInFlight = false

        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_STOP",
            payload = mapOf(
                "generation" to generation.toString(),
                "finalizeReason" to finalizeReason,
                "authorityReason" to authorityReason,
                "vehicleAwayConfirmed" to vehicleAwayConfirmed.toString(),
            ),
        )

        // Session authority is invalidated before Camera/MediaRecorder teardown.
        // Every recovery callback below is generation-bound and therefore stale.
        if (vehicleAwayConfirmed) {
            cameraRecovery.disarmResume(generation, authorityReason)
        } else {
            cameraRecovery.cancelManualSession(generation)
        }
        vehicleAway.endSession(generation, authorityReason)
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_RESUME_GATE_DISARMED",
            payload = mapOf(
                "generation" to generation.toString(),
                "reason" to authorityReason,
            ),
        )
        manualSessionGeneration++
        openGeneration++
        cancelOpenWatchdog()
        cancelSetupWatchdog()
        cancelUsbPresenceWatchdog()
        cancelTimeout()
        cancelCameraRecoveryTimer()
        cancelVehicleAwayTimer()
        cancelVehiclePowerReconciliation()
        cancelPreviewReplacementWatchdog()
        if (currentPartial != null) {
            finalizeCurrentSegment(finalizeReason, forcedError)
        } else {
            recordingSessionIdentity.endSession()
            closeCamera()
            enforceTerminalRecordingResourceInvariant("STOP_WITHOUT_PARTIAL")
            stopCameraConflictDiagnostics()
            updateState(
                state.copy(
                    status = RecorderStatus.STOPPED,
                    currentFile = null,
                    segmentStartedAtEpochMs = null,
                    message = null,
                ),
            )
            if (!releasing) onStopped()
        }
    }

    /**
     * A removed MediaStore volume invalidates the recorder's duplicated USB file descriptor.
     * Abandon that one incomplete segment without calling MediaRecorder.stop(); some vendor
     * implementations terminate the process when stop tries to finalize an already-lost FD.
     */
    fun onRemovableStorageUnavailable(action: String, directoryPath: String?) {
        postCamera {
            val target = activeUsbTarget ?: return@postCamera
            val output = currentOutput as? UsbMediaStoreRecordingOutputHandle
            if (output == null && (stopping || releasing || releaseRequested || cleanupUnconfirmed)) return@postCamera
            val planUsesUsb = sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE
            if (!planUsesUsb && output == null) return@postCamera
            if (!matchesActiveUsbDirectory(target, directoryPath)) return@postCamera
            val stillMounted = runCatching {
                UsbExportVolumeResolver.isRemovableVolumeMounted(context, target.storageUuid)
            }.getOrDefault(false)
            if (stillMounted) {
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_USB_REMOVAL_HINT_IGNORED",
                    payload = mapOf(
                        "action" to action,
                        "path" to (directoryPath ?: "-"),
                        "reason" to "TARGET_STILL_MOUNTED",
                    ),
                )
                return@postCamera
            }
            val reason = "USB_VOLUME_UNAVAILABLE:${action.substringAfterLast('.')}:" +
                (directoryPath ?: "unknown")
            if (output != null) {
                abandonUsbSegmentWithoutStop(output, reason, source = "MEDIA_BROADCAST")
            } else if (planUsesUsb) {
                consumeUsbFallback(reason)
            }
        }
    }

    fun onVehiclePowerSnapshot(snapshot: VehiclePowerSnapshot, source: String) {
        postCamera {
            reconcileVehiclePowerSnapshot(snapshot, source)
        }
    }

    private fun reconcileVehiclePowerSnapshot(
        snapshot: VehiclePowerSnapshot = VehiclePowerSnapshotReader.read(context),
        source: String,
        usbFallbackReason: String? = null,
    ): Boolean {
        if (externallyManagedPresence) return false
        val nowMs = SystemClock.elapsedRealtime()
        val previous = lastVehiclePowerSnapshot
        val powerAction = vehicleAway.onPowerSnapshot(
            generation = manualSessionGeneration,
            appForeground = snapshot.appForeground,
            interactive = snapshot.interactive,
            mainDisplayOn = snapshot.mainDisplayOn,
            nowMs = nowMs,
        )
        // A storage fallback is continuation of this manual session, never a new
        // permission to record after vehicle power-down. Keep checking once a
        // fallback was admitted, including power edges arriving during teardown.
        val fallbackAction = if (usbFallbackReason != null || sessionStoragePlan?.fallbackConsumed == true) {
            vehicleAway.onUsbFallback(manualSessionGeneration)
        } else {
            VehicleAwayAction.None
        }
        val action = if (fallbackAction is VehicleAwayAction.Confirm) fallbackAction else powerAction
        val blockedFallbackReason = if (fallbackAction is VehicleAwayAction.Confirm) {
            usbFallbackReason ?: lastUsbFallbackReason ?: "USB_FALLBACK_CONTINUATION"
        } else null
        if (blockedFallbackReason != null) {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_USB_FALLBACK_SUPPRESSED",
                payload = vehicleAwayDiagnosticPayload() + mapOf(
                    "source" to source,
                    "usbReason" to blockedFallbackReason,
                    "decision" to "TERMINAL_STOP",
                    "segment" to segmentNumber.toString(),
                ),
            )
        }
        val timeLapseStopped = applyTimeLapsePowerGate(snapshot)
        lastVehiclePowerSnapshot = snapshot
        val changed = previous != snapshot
        val heartbeatDue = nowMs - lastVehiclePowerSnapshotLogAtMs >= POWER_RECONCILIATION_LOG_INTERVAL_MS
        if (changed || action != VehicleAwayAction.None || heartbeatDue || source == "CAMERA_LOSS") {
            lastVehiclePowerSnapshotLogAtMs = nowMs
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_POWER_SNAPSHOT_RECONCILED",
                payload = vehicleAwayDiagnosticPayload() + mapOf(
                    "source" to source,
                    "interactive" to snapshot.interactive.toString(),
                    "mainDisplayState" to snapshot.mainDisplayState,
                    "decision" to vehicleAwayActionName(action),
                ),
            )
        }
        val stopped = timeLapseStopped || applyVehicleAwayAction(action, usbFallbackReason = blockedFallbackReason)
        syncVehiclePowerReconciliation()
        return stopped
    }

    private fun applyTimeLapsePowerGate(snapshot: VehiclePowerSnapshot): Boolean {
        when (
            TimeLapsePowerGatePolicy.action(
                recordingMode = config?.recordingMode ?: state.recordingMode,
                recording = config != null && !stopping && !releasing && !releaseRequested && !cleanupUnconfirmed,
                appForeground = snapshot.appForeground,
                interactive = snapshot.interactive,
                mainDisplayOn = snapshot.mainDisplayOn,
            )
        ) {
            TimeLapsePowerGateAction.NONE -> return false
            TimeLapsePowerGateAction.TERMINATE_SESSION -> {
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_TIME_LAPSE_VEHICLE_AWAY_TERMINATE",
                    payload = mapOf(
                        "generation" to manualSessionGeneration.toString(),
                        "segment" to segmentNumber.toString(),
                        "appForeground" to snapshot.appForeground.toString(),
                        "interactive" to snapshot.interactive.toString(),
                        "mainDisplayOn" to snapshot.mainDisplayOn.toString(),
                    ),
                )
                stopSessionOnCameraThread(
                    finalizeReason = "TIME_LAPSE_VEHICLE_AWAY",
                    authorityReason = "TIME_LAPSE_POWER_OFF_EDGE",
                    forcedError = null,
                    vehicleAwayConfirmed = true,
                )
                return true
            }
        }
    }

    private fun scheduleTimeLapseQuiesceWatchdog() {
        cancelTimeLapseQuiesceWatchdog()
        val sequenceId = pacedCaptureInFlightSequenceId ?: return
        val generation = manualSessionGeneration
        val segment = segmentGeneration
        val runnable = Runnable {
            timeLapseQuiesceWatchdogRunnable = null
            if (
                generation != manualSessionGeneration ||
                segment != segmentGeneration ||
                pacedCaptureInFlightSequenceId != sequenceId ||
                !timeLapseQuiesced ||
                !recording ||
                stopping ||
                releasing
            ) {
                return@Runnable
            }
            if (reconcileVehiclePowerSnapshot(source = "TIME_LAPSE_QUIESCE_WATCHDOG")) return@Runnable
            if (!timeLapseQuiesced || pacedCaptureInFlightSequenceId != sequenceId) return@Runnable
            handleCameraLoss("TIME_LAPSE_QUIESCE_DRAIN_TIMEOUT sequence=$sequenceId")
        }
        timeLapseQuiesceWatchdogRunnable = runnable
        cameraHandler?.postDelayed(runnable, TIME_LAPSE_QUIESCE_DRAIN_TIMEOUT_MS)
    }

    private fun cancelTimeLapseQuiesceWatchdog() {
        timeLapseQuiesceWatchdogRunnable?.let { cameraHandler?.removeCallbacks(it) }
        timeLapseQuiesceWatchdogRunnable = null
    }

    private fun syncVehiclePowerReconciliation() {
        val away = vehicleAway.snapshot
        val shouldRun = !stopping && !releasing && !away.appForeground &&
            away.phase in setOf(VehicleAwayPhase.ACTIVE, VehicleAwayPhase.PENDING)
        if (!shouldRun) {
            cancelVehiclePowerReconciliation()
            return
        }
        if (vehiclePowerReconciliationRunnable != null) return
        val generation = manualSessionGeneration
        val runnable = Runnable {
            vehiclePowerReconciliationRunnable = null
            if (generation != manualSessionGeneration || stopping || releasing) return@Runnable
            reconcileVehiclePowerSnapshot(source = "BACKGROUND_RECONCILIATION")
        }
        vehiclePowerReconciliationRunnable = runnable
        cameraHandler?.postDelayed(runnable, POWER_RECONCILIATION_INTERVAL_MS)
    }

    private fun cancelVehiclePowerReconciliation() {
        vehiclePowerReconciliationRunnable?.let { cameraHandler?.removeCallbacks(it) }
        vehiclePowerReconciliationRunnable = null
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
            incidentStore.saveNext(eventId, eventRequestedAt)
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
                ),
            )
            updateState(
                state.copy(
                    message = "Saving event: previous 2 + current + next 1",
                ),
            )
        }
    }

    fun retry() {
        postCamera {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_RETRY_IGNORED",
                payload = mapOf(
                    "status" to state.status,
                    "reason" to "A new manual Start is required after a terminal Session",
                ),
            )
        }
    }

    /** Request teardown without blocking the UI or discarding late native acknowledgements. */
    fun release() = requestRelease(null)

    fun releaseForModeHandoff(onComplete: () -> Unit) {
        check(externallyManagedPresence)
        requestRelease(onComplete)
    }

    private fun requestRelease(onComplete: (() -> Unit)?) {
        releaseRequested = true
        if (onComplete != null) releaseCompletion = onComplete
        // This deadline is independent of the camera worker, including a vendor call stuck in native code.
        CaptureCleanupRuntime.handler.postDelayed({
            if (!disposalFinished) {
                cleanupUnconfirmed = true
                wakeLockHolder.releaseAll()
                CaptureCleanupRuntime.trace("release-" + System.identityHashCode(this),
                    "UNCONFIRMED", "Worker/IO cleanup did not settle within 15 seconds")
            }
        }, 15_000)
        postCamera {
            teardownOnCameraThread()
            maybeFinishDisposal()
        }
    }

    private fun maybeFinishDisposal() {
        if (!releaseRequested || disposalStarted || closeTransaction != null || cameraDevice != null || cameraOpenInFlight) return
        disposalStarted = true
        // One IO barrier; late cleanup callbacks may reach here after the bounded wait has ended.
        runIo {
            cameraHandler?.post {
                stopCameraConflictDiagnostics()
                releaseRecordingPreviewSurface()
                releasePendingRecordingPreviewSurface()
                wakeLockHolder.releaseAll()
                disposalFinished = true
                ioExecutor.shutdown()
                releaseCompletion?.invoke()
                releaseCompletion = null
                cameraThread?.quitSafely()
            }
        }
    }

    private fun teardownOnCameraThread() {
        releasing = true
        stopping = true
        startInFlight = false
        cameraRecovery.cancelManualSession(manualSessionGeneration)
        vehicleAway.endSession(manualSessionGeneration, "SERVICE_RELEASE")
        manualSessionGeneration++
        openGeneration++
        cancelOpenWatchdog()
        cancelSetupWatchdog()
        cancelTimeout()
        cancelUsbPresenceWatchdog()
        cancelCameraRecoveryTimer()
        cancelVehicleAwayTimer()
        cancelVehiclePowerReconciliation()
        if (currentPartial != null) finalizeCurrentSegment("STOP", null)
        else { recordingSessionIdentity.endSession(); closeCamera() }
    }

    private fun ensureCameraThread() {
        if (cameraThread == null) {
            val thread = HandlerThread("recorder-camera").also { it.start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }
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

    private fun startCameraConflictDiagnostics(config: RecorderConfig) {
        if (cameraDiagnosticsRegistered) return
        diagnosticsActiveCameraId = config.cameraId
        logCameraConcurrencySnapshot(config)
        val handler = cameraHandler
        if (handler == null) {
            diagnosticsActiveCameraId = null
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_DIAGNOSTICS_REGISTRATION",
                severity = Severity.WARN,
                payload = mapOf("result" to "NO_CAMERA_HANDLER"),
            )
            return
        }
        val callbackGeneration = manualSessionGeneration
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                logCameraAvailability(cameraId, available = true)
                handleTargetCameraAvailability(callbackGeneration, cameraId, available = true)
            }

            override fun onCameraUnavailable(cameraId: String) {
                logCameraAvailability(cameraId, available = false)
                handleTargetCameraAvailability(callbackGeneration, cameraId, available = false)
            }

            override fun onCameraAccessPrioritiesChanged() {
                if (!cameraDiagnosticsRegistered || callbackGeneration != manualSessionGeneration) return
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_CAMERA_ACCESS_PRIORITIES_CHANGED",
                    payload = cameraDiagnosticStatePayload(),
                )
            }
        }
        cameraAvailabilityCallback = callback
        val failure = runCatching {
            manager.registerAvailabilityCallback(callback, handler)
        }.exceptionOrNull()
        cameraDiagnosticsRegistered = failure == null
        if (failure != null) {
            cameraAvailabilityCallback = null
            diagnosticsActiveCameraId = null
        }
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_DIAGNOSTICS_REGISTRATION",
            severity = if (failure == null) Severity.INFO else Severity.WARN,
            payload = mapOf(
                "result" to if (failure == null) "REGISTERED" else "ERROR",
                "error" to (failure?.javaClass?.simpleName ?: "-"),
            ),
        )
    }

    private fun stopCameraConflictDiagnostics() {
        val callback = cameraAvailabilityCallback
        cameraAvailabilityCallback = null
        if (!cameraDiagnosticsRegistered) {
            diagnosticsActiveCameraId = null
            return
        }
        val payload = cameraDiagnosticStatePayload().toMutableMap()
        cameraDiagnosticsRegistered = false
        val failure = callback?.let {
            runCatching { manager.unregisterAvailabilityCallback(it) }.exceptionOrNull()
        }
        payload["result"] = if (failure == null) "UNREGISTERED" else "ERROR"
        payload["error"] = failure?.javaClass?.simpleName ?: "-"
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_DIAGNOSTICS_UNREGISTERED",
            severity = if (failure == null) Severity.INFO else Severity.WARN,
            payload = payload,
        )
        diagnosticsActiveCameraId = null
    }

    private fun logCameraConcurrencySnapshot(config: RecorderConfig) {
        val cameraIdsResult = runCatching { manager.cameraIdList.toList() }
        val cameraIds = cameraIdsResult.getOrDefault(emptyList())
        val concurrentResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                manager.concurrentCameraIds.mapTo(linkedSetOf()) { it.toSet() }
            }
        } else {
            null
        }
        val concurrentSets = concurrentResult?.getOrNull()
        val concurrentQuery = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "UNSUPPORTED_API"
            concurrentResult?.isSuccess == true -> "OK"
            else -> "ERROR:${concurrentResult?.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
        }
        val physicalIdsByCamera = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cameraIds.associateWith { cameraId ->
                runCatching {
                    manager.getCameraCharacteristics(cameraId).physicalCameraIds.toSet()
                }.getOrDefault(emptySet())
            }
        } else {
            emptyMap()
        }
        fun pairSupport(first: String, second: String): String =
            concurrentSets?.let {
                CameraConflictDiagnostics.supportsPair(it, first, second).toString()
            } ?: "UNKNOWN"

        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_CONCURRENCY_SNAPSHOT",
            payload = mapOf(
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "activeCameraId" to config.cameraId,
                "profile" to config.profile.key,
                "cameraIdsQuery" to if (cameraIdsResult.isSuccess) "OK" else "ERROR",
                "cameraIds" to CameraConflictDiagnostics.encodeCameraIds(cameraIds),
                "concurrentQuery" to concurrentQuery,
                "concurrentSets" to (
                    concurrentSets?.let(CameraConflictDiagnostics::encodeConcurrentSets)
                        ?: "UNKNOWN"
                    ),
                "supports0+1" to pairSupport("0", "1"),
                "supports0+2" to pairSupport("0", "2"),
                "supports1+2" to pairSupport("1", "2"),
                "physicalIds" to CameraConflictDiagnostics.encodePhysicalIds(physicalIdsByCamera),
            ),
        )
    }

    private fun logCameraAvailability(cameraId: String, available: Boolean) {
        if (!cameraDiagnosticsRegistered ||
            !CameraConflictDiagnostics.shouldLogAvailability(cameraId, diagnosticsActiveCameraId)
        ) {
            return
        }
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_AVAILABILITY",
            payload = cameraDiagnosticStatePayload() + mapOf(
                "cameraId" to cameraId,
                "available" to available.toString(),
            ),
        )
    }

    private fun cameraDiagnosticStatePayload(): Map<String, String> = mapOf(
        "activeCameraId" to (diagnosticsActiveCameraId ?: "-"),
        "recorderStatus" to state.status.toString(),
        "recording" to recording.toString(),
        "appForeground" to ZeekrApp.isForeground.value.toString(),
        "segment" to segmentNumber.toString(),
        "manualSessionGeneration" to manualSessionGeneration.toString(),
        "recoveryPhase" to cameraRecovery.snapshot.phase.toString(),
        "recoveryAttempts" to cameraRecovery.snapshot.attemptsMade.toString(),
    )

    private fun openCamera(cameraId: String) {
        if (releaseRequested || cleanupUnconfirmed || closeTransaction != null || stopping || releasing) return
        val cfg = config ?: run {
            startInFlight = false
            return
        }
        if (reconcileVehiclePowerSnapshot(source = "CAMERA_OPEN")) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            startInFlight = false
            cameraUnavailable("CAMERA_PERMISSION_DENIED")
            return
        }
        val declared = CameraRuntime.videoSizeCandidates(context, cameraId)
            .map { ProfileSize(it.width, it.height) }
        if (!RecorderConfig.profileDeclared(cfg.profile, declared)) {
            val message = "Profile ${cfg.profile.key} not declared by camera $cameraId"
            EventLogger.markError(Categories.SYSTEM, "RECORDER_PROFILE_NOT_DECLARED", message, null)
            startInFlight = false
            cameraUnavailable(message)
            return
        }
        cameraOpenInFlight = true
        openGeneration++
        val generation = openGeneration
        val sessionToken = manualSessionGeneration
        scheduleOpenWatchdog(generation)
        try {
            CanaryCameraOpenAdapter.open(manager, cameraId, createStateCallback(generation, sessionToken), cameraHandler)
        } catch (e: CameraAccessException) {
            cancelOpenWatchdog()
            cameraOpenInFlight = false
            startInFlight = false
            cameraUnavailable(
                cameraAccessMessage(e),
                recoverableContention = isRecoverableCameraAccessReason(e.reason),
            )
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
    private fun createStateCallback(
        generation: Long,
        sessionToken: Long,
    ): CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                // Stale callbacks must mutate no shared state: check the token first.
                if (generation != openGeneration || sessionToken != manualSessionGeneration) {
                    closeQuietly(camera)
                    return
                }
                cameraOpenInFlight = false
                startInFlight = false
                cancelOpenWatchdog()
                if (stopping || releasing || releaseRequested || cleanupUnconfirmed || closeTransaction != null || config == null) {
                    closeQuietly(camera)
                    return
                }
                cameraDevice = camera
                startSegment()
            }

            override fun onDisconnected(camera: CameraDevice) {
                if (generation != openGeneration || sessionToken != manualSessionGeneration) {
                    closeQuietly(camera)
                    return
                }
                // Current generation: this callback camera must be closed even if it
                // was never assigned to cameraDevice; avoid a double close for the
                // assigned instance by detaching it first.
                cameraDevice = camera
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CAMERA_DISCONNECTED")
                handleCameraLoss("CAMERA_DISCONNECTED", recoverableContention = true)
            }

            override fun onError(camera: CameraDevice, errorCode: Int) {
                if (generation != openGeneration || sessionToken != manualSessionGeneration) {
                    closeQuietly(camera)
                    return
                }
                cameraDevice = camera
                val message = cameraDeviceErrorMessage(errorCode)
                EventLogger.markError(Categories.SYSTEM, "RECORDER_CAMERA_ERROR", message, null)
                handleCameraLoss(
                    message,
                    recoverableContention = isRecoverableCameraDeviceError(errorCode),
                )
            }

            override fun onClosed(camera: CameraDevice) {
                onCameraDeviceProducerClosed(camera)
            }
        }

    private fun startSegment() {
        val cfg = config ?: return
        if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return
        if (pendingTimeLapseFinalize != null) return
        if (reconcileVehiclePowerSnapshot(source = "SEGMENT_BOUNDARY")) return
        val device = cameraDevice ?: return
        val sessionToken = manualSessionGeneration
        usbFallbackPendingReason?.let { if (!consumeUsbFallback(it)) return }
        if (pendingUsbCommits >= 2 && !consumeUsbFallback("USB_COMMIT_BACKLOG")) return
        val usingUsb = sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE
        val decision = if (usingUsb) {
            StorageDecision(proceed = true, reason = null)
        } else {
            prepareStorage(cfg)
        }
        if (!decision.proceed) {
            storageBlocked(decision.reason ?: "STORAGE_BLOCKED")
            return
        }
        segmentGeneration++
        timeLapseQuiesced = false
        previewReplacementGeneration++
        cancelPreviewReplacementWatchdog()
        pendingRecordingPreviewSurface?.let { pending ->
            pendingRecordingPreviewSurface = null
            if (pending.isValid) {
                releaseRecordingPreviewSurface()
                recordingPreviewSurface = pending
                previewOutputDesired = true
                updateState(
                    state.copy(
                        previewRequested = true,
                        previewActive = false,
                        previewFallbackUsed = false,
                    ),
                )
            } else {
                runCatching { pending.release() }
            }
        }
        val generation = segmentGeneration
        segmentNumber++
        val nowEpoch = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        segmentStartedAtEpochMs = nowEpoch
        segmentStartedAtElapsedMs = nowElapsed
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        frameStats = SegmentFrameStats()
        lastTimestampNs = null
        lastFrameReceivedAtElapsedMs = null
        if (currentIncidentTag == null) {
            val pendingIncident = incidentStore.pending(nowEpoch)
            if (pendingIncident != null) {
                currentIncidentTag = pendingIncident
                currentConsumesPendingIncident = true
                protectedPending = true
            }
        }
        val output = if (sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE) {
            val target = requireNotNull(activeUsbTarget)
            val estimated = StoragePolicy.estimateSegmentBytes(
                cfg.profile.bitrateBps,
                if (cfg.recordingMode == RecordingMode.TIME_LAPSE) {
                    (USB_SEGMENT_SECONDS + cfg.timeLapseMultiplier - 1) / cfg.timeLapseMultiplier
                } else {
                    USB_SEGMENT_SECONDS
                },
            ) + USB_SEGMENT_METADATA_ALLOWANCE_BYTES
            val expectedBundleId = UsbExportPolicy.bundleId(
                recordingSessionIdentity.requireCurrentId(),
                segmentNumber,
                nowEpoch,
            )
            try {
                usbRetentionManager.admit(
                    target = target,
                    incomingBytes = estimated,
                    quotaBytes = cfg.usbQuotaBytes,
                    protectedBundleId = expectedBundleId,
                )
                requireNotNull(usbOutputSink).openSegment(segmentNumber, cfg.profile, nowEpoch)
            } catch (failure: Throwable) {
                if (!consumeUsbFallback("USB_OPEN_FAILED:${failure.message ?: failure.javaClass.simpleName}")) return
                internalOutputSink.openSegment(segmentNumber, cfg.profile, nowEpoch)
            }
        } else {
            internalOutputSink.openSegment(segmentNumber, cfg.profile, nowEpoch)
        }
        val partial = requireNotNull(output.localWorkingFile) {
            "Recording output must expose a stable ownership marker"
        }
        currentSegmentCanary = (output as? UsbMediaStoreRecordingOutputHandle)?.let {
            usbCapabilityStore.requiresCanary(it.pendingVideo.target)
        } == true
        if (currentSegmentCanary && output is UsbMediaStoreRecordingOutputHandle) {
            UsbFastTrackReportStore.append(
                context,
                UsbFastTrackEvent(
                    operationId = output.pendingVideo.operationId,
                    bundleId = output.pendingVideo.bundleId,
                    storageUuid = output.pendingVideo.target.storageUuid,
                    volumeName = output.pendingVideo.target.volumeName,
                    targetDescription = output.pendingVideo.target.description,
                    segmentNumber = output.pendingVideo.segmentNumber,
                    storageKind = "USB_MEDIASTORE",
                    state = "CANARY_STARTED",
                    canaryRequired = true,
                    relativePath = com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy.RELATIVE_PATH,
                    requestedName = output.pendingVideo.displayName,
                    itemUri = output.pendingVideo.itemUri,
                ),
            )
        }
        currentOutput = output
        currentPartial = partial
        scheduleSetupWatchdog(generation)
        try {
            val recorder = MediaRecorder()
            // Assign before configuration so every prepare/configuration failure releases it.
            mediaRecorder = recorder
            if (output is UsbMediaStoreRecordingOutputHandle) {
                recorder.setOnErrorListener { callbackRecorder, what, extra ->
                    cameraHandler?.post {
                        if (generation != segmentGeneration || mediaRecorder !== callbackRecorder ||
                            ((stopping || releasing) && closeTransaction == null)
                        ) {
                            return@post
                        }
                        val active = currentOutput as? UsbMediaStoreRecordingOutputHandle
                            ?: return@post
                        val reason = "USB_MEDIA_RECORDER_ERROR:what=$what:extra=$extra"
                        abandonUsbSegmentWithoutStop(active, reason, source = "RECORDER_CALLBACK")
                    }
                }
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(cfg.profile.size.width, cfg.profile.size.height)
            try {
                recorder.setVideoFrameRate(30)
            } catch (t: Throwable) {
                // Keep recorder default when 30fps is rejected.
            }
            cfg.captureRateFpsOrNull()?.let { captureRate ->
                try {
                    recorder.setCaptureRate(captureRate)
                } catch (t: Throwable) {
                    throw IllegalStateException(
                        "TIME_LAPSE_CAPTURE_RATE_REJECTED source=${cfg.source.sourceRole} rate=$captureRate",
                        t,
                    )
                }
            }
            recorder.setVideoEncodingBitRate(cfg.profile.bitrateBps)
            output.bind(recorder)
            recorder.prepare()
            val surface = recorder.surface
            activeEncoderSurface = surface
            cancelPacedEncoderCaptures()
            captureSession?.close()
            captureSession = null
            fun configureSession(includePreview: Boolean) {
                if (!ownsSetup(generation, partial, recorder, sessionToken)) return
                val preview = recordingPreviewSurface?.takeIf { includePreview && it.isValid }
                if (includePreview && preview == null) {
                    releaseRecordingPreviewSurface()
                    updateState(state.copy(previewActive = false, previewFallbackUsed = true))
                    configureSession(includePreview = false)
                    return
                }
                val outputs = if (preview != null) listOf(surface, preview) else listOf(surface)
                val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!ownsSetup(generation, partial, recorder, sessionToken)) {
                                closeQuietlySession(session)
                                return
                            }
                            captureSession = session
                            if (reconcileVehiclePowerSnapshot(source = "RECORDER_CONFIGURED")) return
                            val previewTarget = preview?.takeIf { previewOutputDesired }
                            val cadencePlan = try {
                                installCaptureFlowBeforeRecorderStart(
                                    session = session,
                                    device = device,
                                    encoder = surface,
                                    preview = previewTarget,
                                    generation = generation,
                                    cfg = cfg,
                                )
                            } catch (t: Throwable) {
                                captureSession = null
                                closeQuietlySession(session)
                                if (previewTarget != null) {
                                    fallbackToRecorderOnly(
                                        generation = generation,
                                        partial = partial,
                                        recorder = recorder,
                                        sessionToken = sessionToken,
                                        reason = t.message ?: "PREVIEW_CAPTURE_FLOW_FAILED",
                                    ) { configureSession(includePreview = false) }
                                } else {
                                    failSegmentStart(generation, partial, t.message ?: "capture flow failed")
                                }
                                return
                            }
                            try {
                                recorder.start()
                                cancelSetupWatchdog()
                                recording = true
                                segmentRecordingStartedAtEpochMs = System.currentTimeMillis()
                                segmentRecordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
                                val recoveryOutcome = cameraRecovery.markRecordingStarted(
                                    sessionToken,
                                    segmentRecordingStartedAtElapsedMs!!,
                                )
                                updateState(
                                    state.copy(
                                        status = RecorderStatus.RECORDING,
                                        segmentNumber = segmentNumber,
                                        currentFile = output.displayName,
                                        segmentStartedAtEpochMs = segmentRecordingStartedAtEpochMs,
                                        lastError = null,
                                        activeStorageKind = output.storage.kind,
                                        message = when {
                                            recoveryOutcome == CameraRecoveryStateMachine.RecordingStartOutcome.RESUMED -> "Recording resumed"
                                            currentSegmentCanary -> "Recording · USB canary"
                                            output.storage.kind == RecordingStorageKind.USB_MEDIASTORE -> "Recording · USB"
                                            else -> null
                                        },
                                        previewActive = previewTarget != null,
                                    ),
                                )
                                EventLogger.logEvent(
                                    Categories.SYSTEM,
                                    "RECORDER_SEGMENT_START",
                                    payload = mapOf(
                                        "segment" to segmentNumber.toString(),
                                        "file" to output.displayName,
                                        "storageKind" to output.storage.kind.name,
                                        "usbFallbackConsumed" to (sessionStoragePlan?.fallbackConsumed == true).toString(),
                                        "usbFallbackReason" to (lastUsbFallbackReason ?: "-"),
                                        "profile" to cfg.profile.key,
                                        "recordingMode" to cfg.recordingMode.name,
                                        "timeLapseMultiplier" to cfg.timeLapseMultiplier.toString(),
                                        "captureRateFps" to (cfg.captureRateFpsOrNull()?.toString() ?: "-"),
                                        "captureSubmissionMode" to cadencePlan.submissionMode.name,
                                        "processStartId" to processStartId,
                                        "previewActive" to (previewTarget != null).toString(),
                                    ),
                                )
                                if (recoveryOutcome == CameraRecoveryStateMachine.RecordingStartOutcome.RESUMED) {
                                    EventLogger.logEvent(
                                        Categories.SYSTEM,
                                        "RECORDER_CAMERA_RECOVERY_SUCCEEDED",
                                        payload = recoveryDiagnosticPayload() + mapOf(
                                            "segment" to segmentNumber.toString(),
                                        ),
                                    )
                                }
                                scheduleCameraRecoveryTimer()
                                val segmentWallSeconds =
                                    if (output.storage.kind == RecordingStorageKind.USB_MEDIASTORE) {
                                        USB_SEGMENT_SECONDS
                                    } else {
                                        cfg.effectiveSegmentSeconds()
                                    }
                                scheduleTimeout(generation, segmentWallSeconds * 1000L)
                                if (output is UsbMediaStoreRecordingOutputHandle) {
                                    scheduleUsbPresenceWatchdog(generation, output)
                                }
                            } catch (t: Throwable) {
                                failSegmentStart(generation, partial, t.message ?: "recorder.start failed")
                            }
                        }

                        override fun onReady(session: CameraCaptureSession) {
                            onCaptureSessionProducerIdle(session, "SESSION_READY")
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            onCaptureSessionProducerIdle(session, "SESSION_CLOSED")
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (!ownsSetup(generation, partial, recorder, sessionToken)) {
                                if (currentPartial !== partial) quarantine(partial)
                                closeQuietlySession(session)
                                return
                            }
                            closeQuietlySession(session)
                            if (preview != null) {
                                fallbackToRecorderOnly(
                                    generation = generation,
                                    partial = partial,
                                    recorder = recorder,
                                    sessionToken = sessionToken,
                                    reason = "PREVIEW_RECORD_SESSION_CONFIGURE_FAILED",
                                ) { configureSession(includePreview = false) }
                            } else {
                                cancelSetupWatchdog()
                                failSegmentStart(generation, partial, "RECORD_SESSION_CONFIGURE_FAILED")
                            }
                        }
                    }
                try {
                    device.createCaptureSession(outputs, sessionCallback, cameraHandler)
                } catch (t: Throwable) {
                    if (preview != null) {
                        fallbackToRecorderOnly(
                            generation = generation,
                            partial = partial,
                            recorder = recorder,
                            sessionToken = sessionToken,
                            reason = t.message ?: "PREVIEW_SESSION_CREATE_FAILED",
                        ) { configureSession(includePreview = false) }
                    } else {
                        failSegmentStart(
                            generation,
                            partial,
                            t.message ?: "capture session create failed",
                        )
                    }
                }
            }
            configureSession(
                includePreview = SegmentPreviewPolicy.includeInNewSession(
                    previewConfigured = recordingPreviewSurface != null,
                    previewDesired = previewOutputDesired,
                ),
            )
        } catch (t: Throwable) {
            failSegmentStart(generation, partial, t.message ?: "recorder prepare failed")
        }
    }

    private fun fallbackToRecorderOnly(
        generation: Long,
        partial: File,
        recorder: MediaRecorder,
        sessionToken: Long,
        reason: String,
        retryRecorderOnly: () -> Unit,
    ) {
        if (!ownsSetup(generation, partial, recorder, sessionToken)) return
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
        localRecorder: MediaRecorder,
        sessionToken: Long,
    ): Boolean = SegmentGuardPolicy.ownsSetup(generation, segmentGeneration, currentPartial, partial) &&
        sessionToken == manualSessionGeneration &&
        !stopping && !releasing && !releaseRequested && !cleanupUnconfirmed && closeTransaction == null &&
        mediaRecorder === localRecorder

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

    private fun scheduleUsbPresenceWatchdog(
        generation: Long,
        output: UsbMediaStoreRecordingOutputHandle,
    ) {
        cancelUsbPresenceWatchdog()
        val runnable = Runnable {
            usbPresenceWatchdogRunnable = null
            if (generation != segmentGeneration || currentOutput !== output ||
                !recording || stopping || releasing
            ) {
                return@Runnable
            }
            val mounted = UsbExportVolumeResolver.isRemovableVolumeMounted(
                context,
                output.pendingVideo.target.storageUuid,
            )
            if (!mounted) {
                val reason = "USB_VOLUME_UNAVAILABLE:PRESENCE_WATCHDOG"
                abandonUsbSegmentWithoutStop(output, reason, source = "PRESENCE_WATCHDOG")
                return@Runnable
            }
            scheduleUsbPresenceWatchdog(generation, output)
        }
        usbPresenceWatchdogRunnable = runnable
        cameraHandler?.postDelayed(runnable, USB_PRESENCE_WATCHDOG_INTERVAL_MS)
    }

    private fun cancelUsbPresenceWatchdog() {
        usbPresenceWatchdogRunnable?.let { cameraHandler?.removeCallbacks(it) }
        usbPresenceWatchdogRunnable = null
    }

    private fun cancelTimeout() {
        val runnable = timeoutRunnable
        if (runnable != null) {
            cameraHandler?.removeCallbacks(runnable)
            timeoutRunnable = null
        }
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

    private fun captureRequest(
        device: CameraDevice,
        vararg targets: Surface,
    ): CaptureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        targets.forEach(::addTarget)
    }.build()

    /**
     * Installs the initial request flow before MediaRecorder.start(). Normal mode
     * keeps its proven repeating encoder request. Time-lapse primes the encoder
     * once, while an optional preview-only repeating request keeps the UI fluid.
     */
    private fun installCaptureFlowBeforeRecorderStart(
        session: CameraCaptureSession,
        device: CameraDevice,
        encoder: Surface,
        preview: Surface?,
        generation: Long,
        cfg: RecorderConfig,
    ): CaptureCadencePlan {
        cancelPacedEncoderCaptures()
        val plan = TimeLapseCaptureCadencePolicy.plan(
            recordingMode = cfg.recordingMode,
            multiplier = cfg.timeLapseMultiplier,
        )
        when (plan.submissionMode) {
            CaptureSubmissionMode.REPEATING_ENCODER -> {
                val request = if (preview != null) {
                    captureRequest(device, encoder, preview)
                } else {
                    captureRequest(device, encoder)
                }
                session.setRepeatingRequest(
                    request,
                    createFrameCaptureCallback(generation),
                    cameraHandler,
                ).also { repeatingSequences.getOrPut(session) { mutableSetOf() }.add(it) }
            }
            CaptureSubmissionMode.PACED_SINGLE_ENCODER -> {
                setTimeLapsePreviewRepeating(session, device, preview)
                // Preserve the existing request-before-recorder.start ordering so
                // vendor MediaRecorder implementations see an active input path. The
                // primer is also the first owned sequence; cadence starts only after
                // that sequence drains, so two encoder requests can never overlap.
                submitPacedEncoderPrimer(
                    session = session,
                    device = device,
                    encoder = encoder,
                    generation = generation,
                    plan = plan,
                )
            }
        }
        return plan
    }

    /** Applies target changes while MediaRecorder is already running. */
    private fun applyActiveCaptureFlow(
        session: CameraCaptureSession,
        device: CameraDevice,
        encoder: Surface,
        preview: Surface?,
        generation: Long,
    ) {
        val cfg = config ?: throw IllegalStateException("RECORDER_CONFIG_MISSING")
        cancelPacedEncoderCaptures()
        val plan = TimeLapseCaptureCadencePolicy.plan(
            recordingMode = cfg.recordingMode,
            multiplier = cfg.timeLapseMultiplier,
        )
        when (plan.submissionMode) {
            CaptureSubmissionMode.REPEATING_ENCODER -> {
                val request = if (preview != null) {
                    captureRequest(device, encoder, preview)
                } else {
                    captureRequest(device, encoder)
                }
                session.setRepeatingRequest(
                    request,
                    createFrameCaptureCallback(generation),
                    cameraHandler,
                ).also { repeatingSequences.getOrPut(session) { mutableSetOf() }.add(it) }
            }
            CaptureSubmissionMode.PACED_SINGLE_ENCODER -> {
                if (timeLapseQuiesced) {
                    session.stopRepeating()
                    return
                }
                setTimeLapsePreviewRepeating(session, device, preview)
                startPacedEncoderCaptures(
                    session = session,
                    device = device,
                    encoder = encoder,
                    generation = generation,
                    plan = plan,
                    initialDelayNs = 0L,
                )
            }
        }
    }

    private fun setTimeLapsePreviewRepeating(
        session: CameraCaptureSession,
        device: CameraDevice,
        preview: Surface?,
    ) {
        if (preview == null) {
            session.stopRepeating()
        } else {
            session.setRepeatingRequest(
                captureRequest(device, preview),
                createFrameCaptureCallback(null),
                cameraHandler,
            ).also { repeatingSequences.getOrPut(session) { mutableSetOf() }.add(it) }
        }
    }

    private fun submitPacedEncoderPrimer(
        session: CameraCaptureSession,
        device: CameraDevice,
        encoder: Surface,
        generation: Long,
        plan: CaptureCadencePlan,
    ) {
        check(pacedCaptureInFlightSequenceId == null) { "PACED_PRIMER_OVERLAP" }
        val intervalNs = plan.encoderIntervalNs
            ?: throw IllegalArgumentException("PACED_CAPTURE_INTERVAL_MISSING")
        cancelPacedEncoderCaptures()
        val token = ++captureCadenceGeneration
        val request = captureRequest(device, encoder)
        val deadlineNs = SystemClock.elapsedRealtimeNanos()
        var submittedSequenceId = -1
        var failureMessage: String? = null
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                callbackSession: CameraCaptureSession,
                callbackRequest: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (generation == segmentGeneration && recording) recordFrameResult(generation, result)
            }

            override fun onCaptureFailed(
                callbackSession: CameraCaptureSession,
                callbackRequest: CaptureRequest,
                failure: CaptureFailure,
            ) {
                failureMessage =
                    "TIME_LAPSE_PRIMER_FAILED reason=${failure.reason} frame=${failure.frameNumber}"
            }

            override fun onCaptureSequenceCompleted(
                callbackSession: CameraCaptureSession,
                sequenceId: Int,
                frameNumber: Long,
            ) {
                finishPacedPrimer(
                    session = session,
                    device = device,
                    encoder = encoder,
                    generation = generation,
                    token = token,
                    plan = plan,
                    sequenceId = sequenceId,
                    failureMessage = failureMessage,
                    aborted = false,
                )
            }

            override fun onCaptureSequenceAborted(
                callbackSession: CameraCaptureSession,
                sequenceId: Int,
            ) {
                finishPacedPrimer(
                    session = session,
                    device = device,
                    encoder = encoder,
                    generation = generation,
                    token = token,
                    plan = plan,
                    sequenceId = sequenceId,
                    failureMessage = failureMessage,
                    aborted = true,
                )
            }
        }
        submittedSequenceId = session.capture(request, callback, cameraHandler)
        pacedCaptureInFlightSequenceId = submittedSequenceId
        pacedCaptureInFlightSession = session
        armPacedEncoderWatchdog(
            session = session,
            encoder = encoder,
            generation = generation,
            token = token,
            intervalNs = intervalNs,
            deadlineNs = deadlineNs,
        )
    }

    private fun finishPacedPrimer(
        session: CameraCaptureSession,
        device: CameraDevice,
        encoder: Surface,
        generation: Long,
        token: Long,
        plan: CaptureCadencePlan,
        sequenceId: Int,
        failureMessage: String?,
        aborted: Boolean,
    ) {
        if (pendingTimeLapseFinalize?.session === session) closeTransaction?.sequenceEnded(sequenceId)
        if (!clearPacedCaptureInFlight(session, sequenceId)) return
        cancelPacedEncoderWatchdog()
        val ownsPrimer = ownsPacedCapture(session, encoder, generation, token)
        when {
            failureMessage != null && ownsPrimer -> handleCameraLoss(failureMessage)
            aborted && ownsPrimer -> handleCameraLoss("TIME_LAPSE_PRIMER_ABORTED sequence=$sequenceId")
            ownsPrimer -> startPacedEncoderCaptures(
                session = session,
                device = device,
                encoder = encoder,
                generation = generation,
                plan = plan,
                initialDelayNs = plan.encoderIntervalNs ?: 0L,
            )
            else -> startPendingPacedCaptureIfIdle()
        }
    }

    private fun startPacedEncoderCaptures(
        session: CameraCaptureSession,
        device: CameraDevice,
        encoder: Surface,
        generation: Long,
        plan: CaptureCadencePlan,
        initialDelayNs: Long,
    ) {
        val intervalNs = plan.encoderIntervalNs
            ?: throw IllegalArgumentException("PACED_CAPTURE_INTERVAL_MISSING")
        cancelPacedEncoderCaptures()
        val token = ++captureCadenceGeneration
        val firstDeadlineNs = SystemClock.elapsedRealtimeNanos() + initialDelayNs
        val request = captureRequest(device, encoder)
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAPTURE_CADENCE_STARTED",
            payload = mapOf(
                "segment" to segmentNumber.toString(),
                "generation" to generation.toString(),
                "mode" to plan.submissionMode.name,
                "requestedEncoderFps" to plan.requestedEncoderFps.toString(),
                "intervalNs" to intervalNs.toString(),
                "previewRepeating" to state.previewActive.toString(),
            ),
        )
        pendingPacedStart = PendingPacedStart(
            session = session,
            request = request,
            encoder = encoder,
            generation = generation,
            token = token,
            intervalNs = intervalNs,
            firstDeadlineNs = firstDeadlineNs,
        )
        startPendingPacedCaptureIfIdle()
    }

    private fun startPendingPacedCaptureIfIdle() {
        if (pacedCaptureInFlightSequenceId != null) return
        val pending = pendingPacedStart ?: return
        pendingPacedStart = null
        if (!ownsPacedCapture(pending.session, pending.encoder, pending.generation, pending.token)) return
        schedulePacedEncoderCapture(
            session = pending.session,
            request = pending.request,
            encoder = pending.encoder,
            generation = pending.generation,
            token = pending.token,
            intervalNs = pending.intervalNs,
            deadlineNs = pending.firstDeadlineNs,
        )
    }

    private fun schedulePacedEncoderCapture(
        session: CameraCaptureSession,
        request: CaptureRequest,
        encoder: Surface,
        generation: Long,
        token: Long,
        intervalNs: Long,
        deadlineNs: Long,
    ) {
        if (!ownsPacedCapture(session, encoder, generation, token)) return
        val delayNs = (deadlineNs - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0L)
        val delayMs = (delayNs + 999_999L) / 1_000_000L
        val runnable = Runnable {
            pacedEncoderRunnable = null
            if (!ownsPacedCapture(session, encoder, generation, token)) return@Runnable
            armPacedEncoderWatchdog(
                session = session,
                encoder = encoder,
                generation = generation,
                token = token,
                intervalNs = intervalNs,
                deadlineNs = deadlineNs,
            )
            try {
                var submittedSequenceId = -1
                var failureMessage: String? = null
                val callback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            callbackSession: CameraCaptureSession,
                            callbackRequest: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            if (ownsPacedCapture(session, encoder, generation, token)) {
                                recordFrameResult(generation, result)
                            }
                        }

                        override fun onCaptureFailed(
                            callbackSession: CameraCaptureSession,
                            callbackRequest: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            failureMessage =
                                "TIME_LAPSE_CAPTURE_FAILED reason=${failure.reason} frame=${failure.frameNumber}"
                        }

                        override fun onCaptureSequenceCompleted(
                            callbackSession: CameraCaptureSession,
                            sequenceId: Int,
                            frameNumber: Long,
                        ) {
                            finishPacedEncoderSequence(
                                session = session,
                                request = request,
                                encoder = encoder,
                                generation = generation,
                                token = token,
                                intervalNs = intervalNs,
                                deadlineNs = deadlineNs,
                                sequenceId = sequenceId,
                                failureMessage = failureMessage,
                                aborted = false,
                            )
                        }

                        override fun onCaptureSequenceAborted(
                            callbackSession: CameraCaptureSession,
                            sequenceId: Int,
                        ) {
                            finishPacedEncoderSequence(
                                session = session,
                                request = request,
                                encoder = encoder,
                                generation = generation,
                                token = token,
                                intervalNs = intervalNs,
                                deadlineNs = deadlineNs,
                                sequenceId = sequenceId,
                                failureMessage = failureMessage,
                                aborted = true,
                            )
                        }
                    }
                submittedSequenceId = session.capture(request, callback, cameraHandler)
                pacedCaptureInFlightSequenceId = submittedSequenceId
                pacedCaptureInFlightSession = session
            } catch (t: Throwable) {
                cancelPacedEncoderWatchdog(deadlineNs)
                if (!ownsPacedCapture(session, encoder, generation, token)) return@Runnable
                handleCameraLoss(
                    "TIME_LAPSE_CAPTURE_SUBMIT_FAILED ${t.message ?: t.javaClass.simpleName}",
                    recoverableContention = t is CameraAccessException &&
                        isRecoverableCameraAccessReason(t.reason),
                )
            }
        }
        pacedEncoderRunnable = runnable
        cameraHandler?.postDelayed(runnable, delayMs)
    }

    private fun finishPacedEncoderSequence(
        session: CameraCaptureSession,
        request: CaptureRequest,
        encoder: Surface,
        generation: Long,
        token: Long,
        intervalNs: Long,
        deadlineNs: Long,
        sequenceId: Int,
        failureMessage: String?,
        aborted: Boolean,
    ) {
        if (pendingTimeLapseFinalize?.session === session) closeTransaction?.sequenceEnded(sequenceId)
        if (!clearPacedCaptureInFlight(session, sequenceId)) return
        cancelPacedEncoderWatchdog(deadlineNs)
        val ownsSequence = ownsPacedCapture(session, encoder, generation, token)
        when {
            failureMessage != null && ownsSequence -> handleCameraLoss(failureMessage)
            aborted && ownsSequence -> handleCameraLoss("TIME_LAPSE_CAPTURE_ABORTED sequence=$sequenceId")
            ownsSequence -> {
                val completedAtNs = SystemClock.elapsedRealtimeNanos()
                val nextDeadlineNs = TimeLapseCaptureCadencePolicy.nextDeadlineNs(
                    previousDeadlineNs = deadlineNs,
                    completedAtNs = completedAtNs,
                    intervalNs = intervalNs,
                )
                schedulePacedEncoderCapture(
                    session = session,
                    request = request,
                    encoder = encoder,
                    generation = generation,
                    token = token,
                    intervalNs = intervalNs,
                    deadlineNs = nextDeadlineNs,
                )
            }
            else -> startPendingPacedCaptureIfIdle()
        }
    }

    private fun clearPacedCaptureInFlight(
        session: CameraCaptureSession,
        sequenceId: Int,
    ): Boolean {
        if (pacedCaptureInFlightSession !== session || pacedCaptureInFlightSequenceId != sequenceId) return false
        pacedCaptureInFlightSession = null
        pacedCaptureInFlightSequenceId = null
        if (timeLapseQuiesced) cancelTimeLapseQuiesceWatchdog()
        return true
    }

    private fun armPacedEncoderWatchdog(
        session: CameraCaptureSession,
        encoder: Surface,
        generation: Long,
        token: Long,
        intervalNs: Long,
        deadlineNs: Long,
    ) {
        cancelPacedEncoderWatchdog()
        val timeoutMs = maxOf(
            PACED_CAPTURE_MIN_TIMEOUT_MS,
            intervalNs / 1_000_000L + PACED_CAPTURE_EXTRA_TIMEOUT_MS,
        )
        val watchdog = Runnable {
            if (pacedEncoderWatchdogDeadlineNs != deadlineNs ||
                !ownsPacedCapture(session, encoder, generation, token)
            ) {
                return@Runnable
            }
            pacedEncoderWatchdogRunnable = null
            pacedEncoderWatchdogDeadlineNs = null
            handleCameraLoss("TIME_LAPSE_CAPTURE_TIMEOUT timeoutMs=$timeoutMs")
        }
        pacedEncoderWatchdogRunnable = watchdog
        pacedEncoderWatchdogDeadlineNs = deadlineNs
        cameraHandler?.postDelayed(watchdog, timeoutMs)
    }

    private fun cancelPacedEncoderWatchdog(deadlineNs: Long? = null) {
        if (deadlineNs != null && pacedEncoderWatchdogDeadlineNs != deadlineNs) return
        pacedEncoderWatchdogRunnable?.let { cameraHandler?.removeCallbacks(it) }
        pacedEncoderWatchdogRunnable = null
        pacedEncoderWatchdogDeadlineNs = null
    }

    private fun ownsPacedCapture(
        session: CameraCaptureSession,
        encoder: Surface,
        generation: Long,
        token: Long,
    ): Boolean = CaptureCadenceOwnershipPolicy.owns(
        token = token,
        currentToken = captureCadenceGeneration,
        segmentGeneration = generation,
        currentSegmentGeneration = segmentGeneration,
        sameSession = captureSession === session,
        sameEncoder = activeEncoderSurface === encoder,
        recording = recording,
        stopping = stopping,
        releasing = releasing,
        captureAllowed = !timeLapseQuiesced && !cleanupUnconfirmed && !releaseRequested,
    )

    private fun cancelPacedEncoderCaptures() {
        captureCadenceGeneration++
        pacedEncoderRunnable?.let { cameraHandler?.removeCallbacks(it) }
        pacedEncoderRunnable = null
        pendingPacedStart = null
        cancelPacedEncoderWatchdog()
    }

    /**
     * Per-segment capture callback: queued results from a closed previous session
     * must never pollute the current segment's frameStats/timestamps.
     */
    private fun createFrameCaptureCallback(generation: Long?) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (generation != null) recordFrameResult(generation, result)
            }
            override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                repeatingSequences[session]?.remove(sequenceId)
                if (pendingTimeLapseFinalize?.session === session) closeTransaction?.sequenceEnded(sequenceId)
            }
            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                repeatingSequences[session]?.remove(sequenceId)
                if (pendingTimeLapseFinalize?.session === session) closeTransaction?.sequenceEnded(sequenceId)
            }
        }

    private fun recordFrameResult(generation: Long, result: TotalCaptureResult) {
        if (generation != segmentGeneration || !recording) return
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val prev = lastTimestampNs
        lastTimestampNs = ts
        lastFrameReceivedAtElapsedMs = SystemClock.elapsedRealtime()
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

    private fun failSegmentStart(generation: Long, partial: File, message: String) {
        if (generation != segmentGeneration || currentPartial !== partial) return
        beginResourceClose("START_FAILED", message, outputLost = false)
    }

    /**
     * Camera-thread part only: cancel timeout, stop recorder, rename/quarantine,
     * capture an immutable snapshot, then schedule the next segment immediately.
     * The async metadata/health/sidecar work never gates the next segment.
     */
    private fun finalizeCurrentSegment(reason: String, forcedError: String?) {
        beginResourceClose(reason, forcedError, outputLost = false)
    }

    private fun beginResourceClose(reason: String, forcedError: String?, outputLost: Boolean) {
        val terminal = stopping || releasing || releaseRequested || reason == "STOP"
        pendingTimeLapseFinalize?.let {
            if (terminal) it.reason = reason
            if (forcedError != null) it.forcedError = forcedError
            closeTransaction?.merge(terminal = terminal, outputLost = outputLost)
            return
        }
        val closeRequestedElapsedMs = SystemClock.elapsedRealtime()
        val lastFrameAgeMs = lastFrameReceivedAtElapsedMs?.let { closeRequestedElapsedMs - it }
        cancelSetupWatchdog()
        cancelTimeout()
        cancelUsbPresenceWatchdog()
        cancelPreviewReplacementWatchdog()
        cancelTimeLapseQuiesceWatchdog()
        cancelPacedEncoderCaptures()
        timeLapseQuiesced = true
        val session = captureSession ?: pacedCaptureInFlightSession
        val camera = cameraDevice
        val recorder = mediaRecorder
        val output = currentOutput
        val encoder = activeEncoderSurface
        val hasPartial = currentPartial != null
        val pending = PendingTimeLapseFinalize(reason, forcedError, segmentGeneration,
            session, camera, ++timeLapseTeardownToken)
        pendingTimeLapseFinalize = pending
        val hold = Any()
        val traceId = processStartId + "-" + System.identityHashCode(this) + "-" + pending.token
        val tx = CaptureCloseTransaction(
            control = CaptureCleanupRuntime.control,
            native = HandlerCloseDispatcher(requireNotNull(cameraHandler)),
            output = ExecutorCloseDispatcher(ioExecutor),
            resources = object : CaptureCloseResources {
                override fun stopRepeating() { session?.stopRepeating() }
                override fun abortCaptures() { session?.abortCaptures() }
                override fun closeSession() { session?.close() }
                override fun closeDevice() { camera?.close() }
                override fun stopRecorder() { recorder?.stop() }
                override fun resetRecorder() { recorder?.reset() }
                override fun releaseRecorder() { recorder?.release(); encoder?.release() }
                override fun closeOutput(lost: Boolean) {
                    if (lost && output is UsbMediaStoreRecordingOutputHandle) output.abandonUnavailableTarget()
                    else output?.close()
                }
            },
            hasSession = session != null, hasDevice = camera != null, wasRecording = recording,
            sequences = repeatingSequences[session].orEmpty() + setOfNotNull(pacedCaptureInFlightSequenceId),
            terminal = terminal, lost = outputLost,
            trace = { step, detail -> CaptureCleanupRuntime.trace(traceId, step, detail) },
            unconfirmed = { failure ->
                // Revoke continuation on the control thread, even if native work is blocked.
                cleanupUnconfirmed = true
                stopping = true
                wakeLockHolder.releaseAll()
                publishState(state.copy(status = RecorderStatus.ERROR, wakeLockHeld = false,
                    lastError = failure, message = Utils.t("Camera cleanup is unconfirmed. Automatic reopening stopped; copy diagnostics.", "相机收尾未确认；已停止自动重开，请复制诊断")))
            },
            completed = { result ->
                cameraHandler?.post {
                    if (pendingTimeLapseFinalize !== pending) return@post
                    if (result.terminal) stopping = true
                    if (!result.safeToContinue) return@post
                    CaptureCleanupRuntime.trace(traceId, "RECORDER_CLOSE_SETTLED",
                        "durationMs=" + (SystemClock.elapsedRealtime() - closeRequestedElapsedMs) +
                            " terminal=" + (result.terminal || stopping) + " outputLost=" + result.outputLost)
                    mediaRecorder = null
                    repeatingSequences.remove(session)
                    if (captureSession === session) captureSession = null
                    if (result.deviceClosed && cameraDevice === camera) cameraDevice = null
                    if (activeEncoderSurface === encoder) activeEncoderSurface = null
                    pacedCaptureInFlightSequenceId = null
                    pacedCaptureInFlightSession = null
                    pendingTimeLapseFinalize = null
                    closeTransaction = null
                    CaptureCleanupRuntime.settled(hold, true)
                    if (result.outputLost) {
                        currentPartial = null; currentOutput = null; recording = false
                        currentSegmentCanary = false
                        segmentStartedAtEpochMs = null; segmentStartedAtElapsedMs = null
                        segmentRecordingStartedAtEpochMs = null; segmentRecordingStartedAtElapsedMs = null
                        updateState(state.copy(currentFile = null, segmentStartedAtEpochMs = null))
                        afterFinalize(pending.reason, true, pending.forcedError)
                    } else if (hasPartial) {
                        commitFinalizeCurrentSegment(pending.reason, pending.forcedError, result)
                    }
                    maybeFinishDisposal()
                }
            },
        )
        closeTransaction = tx
        CaptureCleanupRuntime.trace(traceId, "CONTEXT",
            "reason=" + reason + " mode=" + config?.recordingMode + " segment=" + segmentNumber +
                " sessionGeneration=" + manualSessionGeneration + " segmentGeneration=" + segmentGeneration +
                " camera=" + camera?.id + " session=" + System.identityHashCode(session) +
                " recorder=" + System.identityHashCode(recorder) + " storage=" + output?.storage?.kind +
                " recording=" + recording + " frames=" + frameStats.count + " lastFrameAgeMs=" + lastFrameAgeMs)
        CaptureCleanupRuntime.retain(hold, camera?.id ?: config?.cameraId ?: "default", camera, tx)
        updateState(state.copy(status = RecorderStatus.FINALIZING, message = Utils.t("Finishing recording and releasing the camera", "正在确认相机与录像收尾")))
        tx.begin()
    }

    private fun onCaptureSessionProducerIdle(session: CameraCaptureSession, evidence: String) {
        if (evidence == "SESSION_CLOSED") {
            retiredPreviewSurfaces.remove(session)?.forEach { runCatching { it.release() } }
            repeatingSequences.remove(session)
        }
        if (pendingTimeLapseFinalize?.session === session) {
            if (evidence == "SESSION_CLOSED") closeTransaction?.sessionClosed()
            else closeTransaction?.sessionReady()
            return
        }
        if (evidence == "SESSION_CLOSED") {
            if (captureSession === session) captureSession = null
            if (pacedCaptureInFlightSession === session) {
                pacedCaptureInFlightSession = null
                pacedCaptureInFlightSequenceId = null
                cancelPacedEncoderWatchdog()
                startPendingPacedCaptureIfIdle()
            }
        }
    }

    private fun onCameraDeviceProducerClosed(camera: CameraDevice) {
        CaptureCleanupRuntime.deviceClosed(camera)
        if (cameraDevice === camera || pendingTimeLapseFinalize?.camera === camera) {
            retiredPreviewSurfaces.values.flatten().forEach { runCatching { it.release() } }
            retiredPreviewSurfaces.clear()
            previewSurfacesAwaitingDevice.forEach { runCatching { it.release() } }
            previewSurfacesAwaitingDevice.clear()
        }
        if (pendingTimeLapseFinalize?.camera === camera) closeTransaction?.deviceClosed()
        if (cameraDevice === camera) cameraDevice = null
        if (releaseRequested) { cameraOpenInFlight = false; maybeFinishDisposal() }
    }

    private fun commitFinalizeCurrentSegment(reason: String, forcedError: String?, closeResult: CaptureCloseResult) {
        cancelSetupWatchdog()
        cancelTimeout()
        cancelUsbPresenceWatchdog()
        cancelPacedEncoderCaptures()
        val finalizeId = ++finalizeSequence
        val finalizeEnteredElapsed = SystemClock.elapsedRealtime()
        val partial = currentPartial ?: run {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_SEGMENT_FINALIZE_SKIPPED",
                payload = mapOf(
                    "finalizeId" to finalizeId.toString(),
                    "reason" to reason,
                    "forcedError" to (forcedError ?: "-"),
                ),
            )
            afterFinalize(reason, success = false, error = forcedError ?: "no active segment")
            return
        }
        val cfg = config
        val output = currentOutput
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
        val bytesBeforeStop = runCatching { partial.length() }.getOrDefault(-1L)
        val recordingElapsedBeforeStop = actualStartedElapsed?.let {
            (finalizeEnteredElapsed - it).coerceAtLeast(0L)
        }
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_SEGMENT_FINALIZE_BEGIN",
            payload = mapOf(
                "finalizeId" to finalizeId.toString(),
                "reason" to reason,
                "segment" to segmentNumber.toString(),
                "generation" to segmentGeneration.toString(),
                "wasRecording" to wasRecording.toString(),
                "bytesBeforeStop" to bytesBeforeStop.toString(),
                "recordingElapsedMs" to (recordingElapsedBeforeStop?.toString() ?: "-"),
                "previewDesired" to previewOutputDesired.toString(),
                "previewConfigured" to (recordingPreviewSurface != null).toString(),
                "previewActive" to state.previewActive.toString(),
                "forcedError" to (forcedError ?: "-"),
            ),
        )
        currentPartial = null
        currentOutput = null
        recording = false
        segmentStartedAtEpochMs = null
        segmentStartedAtElapsedMs = null
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        updateState(state.copy(status = RecorderStatus.FINALIZING))

        val recorderStopError = closeResult.errors["stop"] ?: closeResult.errors["reset"] ?: closeResult.errors["outputSync"]
        // Native resources and descriptor have already settled in the shared close transaction.
        if (output is UsbMediaStoreRecordingOutputHandle) {
            finalizeUsbSegment(
                reason = reason,
                forcedError = forcedError,
                recorderStopError = recorderStopError,
                output = output,
                cfg = cfg,
                requestedAtEpoch = requestedAtEpoch,
                requestedAtElapsed = requestedAtElapsed,
                actualStartedEpoch = actualStartedEpoch,
                actualStartedElapsed = actualStartedElapsed,
                stats = stats,
                protected = protected,
                incidentTag = incidentTag,
                consumesPendingIncident = consumesPendingIncident,
                finalizeEnteredElapsed = finalizeEnteredElapsed,
            )
            return
        }
        activeEncoderSurface = null

        val finalFile = SegmentNaming.finalFileFor(partial)
        val partialExists = partial.exists()
        val partialBytes = if (partialExists) partial.length() else 0L
        val interruptedTrackValid = if (
            forcedError != null && wasRecording && recorderStopError == null
        ) {
            CameraRuntime.readTrackMetadata(partial)?.let { track ->
                (track.width ?: 0) > 0 && (track.height ?: 0) > 0 && (track.durationMs ?: 0L) > 0L
            } == true
        } else {
            true
        }
        val stopError = InterruptedSegmentFinalizePolicy.effectiveStopError(
            wasRecording = wasRecording,
            interruptionError = forcedError,
            recorderStopError = recorderStopError,
            videoTrackValid = interruptedTrackValid,
        )
        val renameSucceeded = stopError == null && partialExists && partialBytes > 0L && partial.renameTo(finalFile)
        val outcome = FinalizePolicy.outcome(
            stopError = stopError,
            partialExists = partialExists,
            partialBytes = partialBytes,
            renameSucceeded = renameSucceeded,
        )
        val success = outcome.success
        val effectiveFile = if (success) finalFile else quarantine(partial) ?: partial
        val fileBytes = if (effectiveFile.exists()) effectiveFile.length() else 0L
        val stoppedEpoch = System.currentTimeMillis()
        val stoppedElapsed = SystemClock.elapsedRealtime()
        val gap = SegmentGapPolicy.gapMs(previousSegmentStoppedElapsedMs, actualStartedElapsed)
        previousSegmentStoppedElapsedMs = stoppedElapsed

        val snapshot = SegmentSnapshot(
            file = effectiveFile,
            finalPath = if (success) finalFile else null,
            cameraId = cfg?.cameraId ?: state.cameraId ?: "?",
            profile = cfg?.profile ?: state.profile,
            sourceRole = cfg?.source?.sourceRole ?: state.sourceRole ?: RecordingSourceRole.SURROUND,
            layoutKind = cfg?.source?.layoutKind ?: state.layoutKind ?: RecordingLayoutKind.FOUR_LANE_V1,
            mappingRevision = cfg?.source?.mappingRevision ?: 0,
            laneLayout = cfg?.source?.laneLayout,
            segmentSeconds = cfg?.segmentSeconds ?: state.segmentSeconds,
            effectiveSegmentSeconds = cfg?.effectiveSegmentSeconds() ?: state.effectiveSegmentSeconds,
            recordingMode = cfg?.recordingMode ?: state.recordingMode,
            timeLapseMultiplier = cfg?.timeLapseMultiplier ?: state.timeLapseMultiplier,
            requestedCaptureRateFps = cfg?.captureRateFpsOrNull(),
            finalizeReason = reason,
            segmentNumber = segmentNumber,
            processStartId = processStartId,
            recordingSessionId = recordingSessionIdentity.requireCurrentId(),
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
                "finalizeId" to finalizeId.toString(),
                "file" to snapshot.file.name,
                "result" to snapshot.result,
                "error" to (snapshot.error ?: "-"),
                "bytes" to snapshot.fileBytes.toString(),
                "frames" to snapshot.frameStats.count.toString(),
                "gapMs" to (snapshot.gapFromPreviousMs?.toString() ?: "-"),
                "finalizeDurationMs" to (stoppedElapsed - finalizeEnteredElapsed).toString(),
            ),
        )
        runIo { finishSegmentAsync(snapshot, success) }
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
                writeSidecarAsync(buildSidecarFromSnapshot(snapshot, profile, null, null))
            }
            val settings = SettingsStore.get(context)
            if (settings.autoCleanupEnabled) {
                enforceAutomaticCleanup(
                    limitBytes = snapshot.storageLimitBytes,
                    reserveBytes = settings.minFreeBytes,
                    estimatedNextSegmentBytes = 0L,
                )
            }
        } finally {
            // Never leave a permanent non-evictable entry, even on profile-null/exception paths.
            snapshot.finalPath?.let { pendingSidecarFiles -= it.name }
        }
    }

    private fun matchesActiveUsbDirectory(target: UsbExportTarget, observed: String?): Boolean {
        val expected = target.directoryPath
        if (observed.isNullOrBlank()) return true
        fun normalize(value: String): String = value
            .trim()
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(java.util.Locale.ROOT)
        val normalizedObserved = normalize(observed)
        if (!expected.isNullOrBlank() && normalize(expected) == normalizedObserved) return true
        // Some vendor broadcasts expose /mnt/media_rw/<UUID>_USB2 instead of /storage/<UUID>.
        return normalizedObserved.contains(target.storageUuid.lowercase(java.util.Locale.ROOT))
    }

    /**
     * Drops only the current, uncommitted USB segment. Completed manifests stay intact and the
     * exact pending URI remains in the recovery journal until the same volume is mounted again.
     */
    private fun abandonUsbSegmentWithoutStop(
        output: UsbMediaStoreRecordingOutputHandle,
        reason: String,
        source: String,
    ) {
        if (currentOutput !== output) return
        CaptureCleanupRuntime.trace("usb-" + output.pendingVideo.operationId, "OUTPUT_LOST",
            source + ":" + reason + "; pending URI retained")
        beginResourceClose("USB_OUTPUT_LOST", reason, outputLost = true)
        // Mark the lost descriptor FIRST. Vehicle-away arbitration below may
        // merge terminal intent, but must never enqueue stop/reset on a lost FD.
        consumeUsbFallback(reason)
    }

    private fun consumeUsbFallback(reason: String): Boolean {
        if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return false
        if (reconcileVehiclePowerSnapshot(source = "USB_FALLBACK", usbFallbackReason = reason)) return false
        if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return false
        usbFallbackPendingReason = null
        val fallback = sessionStoragePlan?.fallbackToInternal()
            ?: return sessionStoragePlan?.active?.kind == RecordingStorageKind.INTERNAL
        sessionStoragePlan = fallback
        lastUsbFallbackReason = reason
        usbOutputSink = null
        updateState(
            state.copy(
                activeStorageKind = RecordingStorageKind.INTERNAL,
                message = "USB unavailable; this trip uses internal storage",
            ),
        )
        UsbFastTrackReportStore.append(
            context,
            UsbFastTrackEvent(
                storageUuid = activeUsbTarget?.storageUuid,
                volumeName = activeUsbTarget?.volumeName,
                targetDescription = activeUsbTarget?.description,
                storageKind = "INTERNAL",
                state = "FALLBACK_CONSUMED",
                fallbackConsumed = true,
                relativePath = com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy.RELATIVE_PATH,
                message = reason,
            ),
        )
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_USB_FALLBACK",
            payload = mapOf("reason" to reason, "segment" to segmentNumber.toString()),
        )
        return true
    }

    private fun finalizeUsbSegment(
        reason: String,
        forcedError: String?,
        recorderStopError: String?,
        output: UsbMediaStoreRecordingOutputHandle,
        cfg: RecorderConfig?,
        requestedAtEpoch: Long?,
        requestedAtElapsed: Long?,
        actualStartedEpoch: Long?,
        actualStartedElapsed: Long?,
        stats: SegmentFrameStats,
        protected: Boolean,
        incidentTag: IncidentTag?,
        consumesPendingIncident: Boolean,
        finalizeEnteredElapsed: Long,
    ) {
        val stoppedEpoch = System.currentTimeMillis()
        val stoppedElapsed = SystemClock.elapsedRealtime()
        val gap = SegmentGapPolicy.gapMs(previousSegmentStoppedElapsedMs, actualStartedElapsed)
        previousSegmentStoppedElapsedMs = stoppedElapsed
        if (recorderStopError != null) {
            val targetReadable = runCatching {
                UsbExportVolumeResolver.resolveExact(context, output.pendingVideo.target) != null &&
                    com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend(context)
                        .metadata(android.net.Uri.parse(output.pendingVideo.itemUri)) != null
            }.getOrDefault(false)
            runCatching { output.abort() }
            currentSegmentCanary = false
            if (!targetReadable && reason != "STOP" && !stopping && !releasing) {
                if (!consumeUsbFallback("USB_STOP_FAILED_TARGET_MISSING:$recorderStopError")) return
                afterFinalize(reason, success = true, error = forcedError)
            } else {
                afterFinalize(reason, success = false, error = recorderStopError)
            }
            return
        }
        val marker = output.localWorkingFile
        val snapshot = SegmentSnapshot(
            file = marker,
            finalPath = null,
            cameraId = cfg?.cameraId ?: state.cameraId ?: "?",
            profile = cfg?.profile ?: state.profile,
            sourceRole = cfg?.source?.sourceRole ?: state.sourceRole ?: RecordingSourceRole.SURROUND,
            layoutKind = cfg?.source?.layoutKind ?: state.layoutKind ?: RecordingLayoutKind.FOUR_LANE_V1,
            mappingRevision = cfg?.source?.mappingRevision ?: 0,
            laneLayout = cfg?.source?.laneLayout,
            segmentSeconds = cfg?.segmentSeconds ?: state.segmentSeconds,
            effectiveSegmentSeconds = USB_SEGMENT_SECONDS,
            recordingMode = cfg?.recordingMode ?: state.recordingMode,
            timeLapseMultiplier = cfg?.timeLapseMultiplier ?: state.timeLapseMultiplier,
            requestedCaptureRateFps = cfg?.captureRateFpsOrNull(),
            finalizeReason = reason,
            segmentNumber = output.pendingVideo.segmentNumber,
            processStartId = processStartId,
            recordingSessionId = output.pendingVideo.recordingSessionId,
            requestedAtEpochMs = requestedAtEpoch,
            requestedAtElapsedRealtimeMs = requestedAtElapsed,
            startedAtEpochMs = actualStartedEpoch,
            stoppedAtEpochMs = stoppedEpoch,
            startedAtElapsedRealtimeMs = actualStartedElapsed,
            stoppedAtElapsedRealtimeMs = stoppedElapsed,
            gapFromPreviousMs = gap,
            result = SegmentSidecar.RESULT_SUCCESS,
            error = null,
            fileBytes = 0L,
            protected = protected,
            eventId = incidentTag?.eventId,
            eventRequestedAtEpochMs = incidentTag?.requestedAtEpochMs,
            eventRole = incidentTag?.role,
            frameStats = stats,
            storageLimitBytes = cfg?.storageLimitBytes ?: state.storageLimitBytes,
        )
        val profile = snapshot.profile ?: run {
            output.abort()
            afterFinalize(reason, success = false, error = "USB_PROFILE_MISSING")
            return
        }
        val sidecar = buildSidecarFromSnapshot(snapshot, profile, null, null)
        val canary = currentSegmentCanary
        currentSegmentCanary = false
        pendingUsbCommits++
        if (canary) {
            updateState(state.copy(status = RecorderStatus.FINALIZING, message = "Verifying first USB segment"))
        }
        runIo {
            val outcome = runCatching {
                usbCommitEngine.commitDirect(
                    pending = output.pendingVideo,
                    sidecar = sidecar,
                    canaryRequired = canary,
                )
            }
            outcome.onSuccess { committed ->
                val ownershipPersisted = com.dante.zeekrcapabilitylab.usbexport.UsbCommittedBundleStore(context).mark(
                    output.pendingVideo.target.storageUuid,
                    committed.bundleId,
                    committed.observedOwnerPackage,
                )
                if (ownershipPersisted) {
                    output.markCommitted()
                } else {
                    updateState(
                        state.copy(lastError = "USB_COMMIT_OWNERSHIP_MARK_PENDING_RECOVERY"),
                    )
                }
            }.onFailure { output.abort() }
            cameraHandler?.post {
                pendingUsbCommits = (pendingUsbCommits - 1).coerceAtLeast(0)
                if (outcome.isSuccess) {
                    if (canary) {
                        usbCapabilityStore.markPassed(
                            output.pendingVideo.target,
                            outcome.getOrNull()?.observedOwnerPackage,
                        )
                        sessionCanaryPassed = true
                    }
                    if (consumesPendingIncident && incidentTag != null) {
                        incidentStore.consume(incidentTag.eventId)
                    }
                    updateState(
                        state.copy(
                            libraryRevision = state.libraryRevision + 1L,
                            lastError = null,
                            message = if (stopping) null else "USB segment committed",
                        ),
                    )
                } else {
                    val failure = outcome.exceptionOrNull()
                    val failureReason = "USB_COMMIT_FAILED:${failure?.message ?: failure?.javaClass?.simpleName}"
                    usbFallbackPendingReason = failureReason
                    updateState(state.copy(lastError = failureReason))
                    if (canary && !stopping && !releasing) {
                        if (!consumeUsbFallback(failureReason)) return@post
                    }
                }
                if (canary) {
                    // A failed canary is a storage degradation, not a camera/encoder failure.
                    afterFinalize(reason, success = true, error = forcedError)
                }
            }
        }
        if (!canary) {
            afterFinalize(reason, success = true, error = forcedError)
        }
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_USB_SEGMENT_SUBMITTED",
            payload = mapOf(
                "segment" to output.pendingVideo.segmentNumber.toString(),
                "canary" to canary.toString(),
                "finalizeDurationMs" to (stoppedElapsed - finalizeEnteredElapsed).toString(),
            ),
        )
    }

    private fun afterFinalize(reason: String, success: Boolean, error: String?) {
        if (reason == "STOP" || stopping) {
            recordingSessionIdentity.endSession()
            closeCamera()
            enforceTerminalRecordingResourceInvariant("FINALIZE_STOP")
            stopCameraConflictDiagnostics()
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
            enterCameraRecoveryWaiting(error ?: "CAMERA_LOSS")
            return
        }
        if (reason == "RECOVERY_ATTEMPT_CONTENTION" || reason == "RECOVERY_ATTEMPT_TERMINAL") {
            closeCamera()
            cameraUnavailable(
                error ?: reason,
                recoverableContention = reason == "RECOVERY_ATTEMPT_CONTENTION",
            )
            return
        }
        if (!success) {
            closeCamera()
            recordingSessionIdentity.endSession()
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

    private fun applyVehicleAwayAction(
        action: VehicleAwayAction,
        cameraLossError: String? = null,
        usbFallbackReason: String? = null,
    ): Boolean {
        when (action) {
            VehicleAwayAction.None -> return false
            is VehicleAwayAction.Schedule -> {
                cancelVehicleAwayTimer()
                val delayMs = (action.atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val runnable = Runnable {
                    vehicleAwayTimerRunnable = null
                    if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return@Runnable
                    applyVehicleAwayAction(
                        vehicleAway.onTimer(
                            action.generation,
                            action.pendingToken,
                            SystemClock.elapsedRealtime(),
                        ),
                    )
                }
                vehicleAwayTimerRunnable = runnable
                cameraHandler?.postDelayed(runnable, delayMs)
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_VEHICLE_AWAY_PENDING",
                    payload = vehicleAwayDiagnosticPayload(),
                )
                return false
            }
            is VehicleAwayAction.Cancel -> {
                cancelVehicleAwayTimer()
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_VEHICLE_AWAY_CANCELLED",
                    payload = vehicleAwayDiagnosticPayload() + mapOf("reason" to action.reason),
                )
                return false
            }
            is VehicleAwayAction.Confirm -> {
                cancelVehicleAwayTimer()
                if (action.generation != manualSessionGeneration || stopping || releasing) {
                    EventLogger.logEvent(
                        Categories.SYSTEM,
                        "RECORDER_VEHICLE_AWAY_CONFIRM_STALE",
                        severity = Severity.WARN,
                        payload = vehicleAwayDiagnosticPayload() + mapOf("reason" to action.reason),
                    )
                    return false
                }
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_VEHICLE_AWAY_CONFIRMED",
                    payload = vehicleAwayDiagnosticPayload() + mapOf("reason" to action.reason),
                )
                stopSessionOnCameraThread(
                    finalizeReason = when {
                        usbFallbackReason != null -> "VEHICLE_AWAY_USB_FALLBACK"
                        cameraLossError != null -> "VEHICLE_AWAY_CAMERA_LOSS"
                        else -> "VEHICLE_AWAY"
                    },
                    authorityReason = "VEHICLE_AWAY_CONFIRMED",
                    forcedError = cameraLossError,
                    vehicleAwayConfirmed = true,
                )
                return true
            }
        }
    }

    private fun cancelVehicleAwayTimer() {
        vehicleAwayTimerRunnable?.let { cameraHandler?.removeCallbacks(it) }
        vehicleAwayTimerRunnable = null
    }

    private fun vehicleAwayDiagnosticPayload(): Map<String, String> {
        val away = vehicleAway.snapshot
        return mapOf(
            "generation" to away.generation.toString(),
            "phase" to away.phase.name,
            "appForeground" to away.appForeground.toString(),
            "screenOn" to away.screenOn.toString(),
            "mainDisplayOn" to away.mainDisplayOn.toString(),
            "pendingToken" to away.pendingToken.toString(),
            "pendingSinceElapsedMs" to (away.pendingSinceMs?.toString() ?: "-"),
            "confirmAtElapsedMs" to (away.confirmAtMs?.toString() ?: "-"),
            "backgroundSinceElapsedMs" to (away.backgroundSinceMs?.toString() ?: "-"),
            "backgroundPowerOffEvidence" to away.backgroundPowerOffEvidence.toString(),
            "sawScreenOffWhileBackground" to away.sawScreenOffWhileBackground.toString(),
            "sawMainDisplayOffWhileBackground" to
                away.sawMainDisplayOffWhileBackground.toString(),
            "powerOffEvidenceAtElapsedMs" to (away.powerOffEvidenceAtMs?.toString() ?: "-"),
            "lastReason" to (away.lastReason ?: "-"),
        )
    }

    private fun vehicleAwayActionName(action: VehicleAwayAction): String = when (action) {
        VehicleAwayAction.None -> "NONE"
        is VehicleAwayAction.Schedule -> "SCHEDULE"
        is VehicleAwayAction.Cancel -> "CANCEL_${action.reason}"
        is VehicleAwayAction.Confirm -> "CONFIRM_${action.reason}"
    }

    private fun handleCameraLoss(
        message: String,
        recoverableContention: Boolean = false,
    ) {
        cancelOpenWatchdog()
        cancelSetupWatchdog()
        cancelTimeout()
        startInFlight = false
        cameraOpenInFlight = false
        val token = manualSessionGeneration
        // Camera loss is the most important arbitration point. Never route it using
        // a snapshot cached before a missed power/display callback.
        if (reconcileVehiclePowerSnapshot(source = "CAMERA_LOSS")) return
        val vehicleAwayAction = vehicleAway.onCameraLoss(token)
        if (vehicleAwayAction is VehicleAwayAction.Confirm) {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_LOSS_ROUTE",
                payload = vehicleAwayDiagnosticPayload() + mapOf(
                    "route" to "TERMINAL_STOP",
                    "reason" to vehicleAwayAction.reason,
                    "cameraLoss" to message,
                ),
            )
        }
        if (applyVehicleAwayAction(vehicleAwayAction, cameraLossError = message)) {
            return
        }
        val wasResuming = cameraRecovery.snapshot.phase == CameraRecoveryPhase.RESUMING
        if (wasResuming) {
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_CAMERA_RECOVERY_ATTEMPT_LOST",
                message,
                null,
            )
            if (SegmentGuardPolicy.shouldFinalizeOnLoss(currentPartial != null)) {
                finalizeCurrentSegment(
                    if (recoverableContention) {
                        "RECOVERY_ATTEMPT_CONTENTION"
                    } else {
                        "RECOVERY_ATTEMPT_TERMINAL"
                    },
                    message,
                )
            } else {
                closeCamera()
                cameraUnavailable(message, recoverableContention)
            }
            return
        }
        val recoveryArmed = BuildConfig.CAMERA_INTERRUPTION_RECOVERY_ENABLED &&
            recoverableContention &&
            cameraRecovery.beginRecoverableLoss(token, message, SystemClock.elapsedRealtime())
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_LOSS_ROUTE",
            payload = vehicleAwayDiagnosticPayload() + mapOf(
                "route" to if (recoveryArmed) "PR12_RECOVERY" else "TERMINAL_CAMERA_ERROR",
                "reason" to if (recoveryArmed) "NO_VEHICLE_AWAY_EVIDENCE" else message,
                "cameraLoss" to message,
            ),
        )
        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "RECORDER_CAMERA_LOSS",
            severity = Severity.ERROR,
            payload = recoveryDiagnosticPayload() + mapOf(
                "recoverableContention" to recoverableContention.toString(),
                "recoveryArmed" to recoveryArmed.toString(),
            ),
            errorMessage = message,
        )
        // PREPARING segments (currentPartial != null, recording == false) must also be
        // finalized/quarantined so a late onConfigured cannot start a dead recorder.
        if (SegmentGuardPolicy.shouldFinalizeOnLoss(currentPartial != null)) {
            finalizeCurrentSegment("CAMERA_LOSS", message)
        } else {
            closeCamera()
            if (recoveryArmed) {
                enterCameraRecoveryWaiting(message)
            } else {
                terminateCameraUnavailable(message)
            }
        }
    }

    private fun cameraUnavailable(
        message: String,
        recoverableContention: Boolean = false,
    ) {
        closeCamera()
        EventLogger.markError(Categories.SYSTEM, "RECORDER_CAMERA_UNAVAILABLE", message, null)
        if (BuildConfig.CAMERA_INTERRUPTION_RECOVERY_ENABLED &&
            cameraRecovery.snapshot.phase == CameraRecoveryPhase.RESUMING
        ) {
            val action = cameraRecovery.attemptFailed(
                manualSessionGeneration,
                recoverable = recoverableContention,
                reason = message,
                nowMs = SystemClock.elapsedRealtime(),
            )
            if (action !is CameraRecoveryAction.Abandon &&
                cameraRecovery.snapshot.phase == CameraRecoveryPhase.WAITING_CAMERA
            ) {
                updateState(
                    state.copy(
                        status = RecorderStatus.WAITING_CAMERA,
                        currentFile = null,
                        segmentStartedAtEpochMs = null,
                        lastError = message,
                        message = "Camera still busy; waiting before the next bounded attempt.",
                        previewRequested = false,
                        previewActive = false,
                    ),
                )
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_CAMERA_RECOVERY_RETRY_SCHEDULED",
                    payload = recoveryDiagnosticPayload() + mapOf("reason" to message),
                )
                applyCameraRecoveryAction(action)
                return
            }
            if (action is CameraRecoveryAction.Abandon) {
                terminateCameraUnavailable(action.reason)
                return
            }
        }
        terminateCameraUnavailable(message)
    }

    private fun enterCameraRecoveryWaiting(message: String) {
        val action = cameraRecovery.finalizeCompleted(
            manualSessionGeneration,
            SystemClock.elapsedRealtime(),
        )
        if (action is CameraRecoveryAction.Abandon ||
            cameraRecovery.snapshot.phase != CameraRecoveryPhase.WAITING_CAMERA
        ) {
            terminateCameraUnavailable(
                (action as? CameraRecoveryAction.Abandon)?.reason ?: message,
            )
            return
        }
        closeCamera()
        releaseRecordingPreviewSurface()
        releasePendingRecordingPreviewSurface()
        updateState(
            state.copy(
                status = RecorderStatus.WAITING_CAMERA,
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = message,
                message = "Camera taken by another app; waiting to resume this Session.",
                previewRequested = false,
                previewActive = false,
                previewFallbackUsed = false,
            ),
        )
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_RECOVERY_WAITING",
            payload = recoveryDiagnosticPayload() + mapOf("reason" to message),
        )
        applyCameraRecoveryAction(action)
    }

    private fun terminateCameraUnavailable(message: String) {
        recordingSessionIdentity.endSession()
        closeCamera()
        cancelCameraRecoveryTimer()
        cameraRecovery.terminateManualSession(manualSessionGeneration, message)
        stopCameraConflictDiagnostics()
        updateState(
            state.copy(
                status = RecorderStatus.CAMERA_UNAVAILABLE,
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = message,
                message = "Camera unavailable; session ended. Use manual Start for a new session.",
            ),
        )
        if (!releasing) onStopped()
    }

    private fun handleTargetCameraAvailability(
        callbackGeneration: Long,
        cameraId: String,
        available: Boolean,
    ) {
        if (!BuildConfig.CAMERA_INTERRUPTION_RECOVERY_ENABLED ||
            callbackGeneration != manualSessionGeneration
        ) {
            return
        }
        val action = cameraRecovery.onAvailability(
            generation = callbackGeneration,
            cameraId = cameraId,
            available = available,
            nowMs = SystemClock.elapsedRealtime(),
        )
        if (cameraId == cameraRecovery.snapshot.targetCameraId) {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_RECOVERY_AVAILABILITY_TRACKED",
                payload = recoveryDiagnosticPayload() + mapOf(
                    "available" to available.toString(),
                ),
            )
        }
        applyCameraRecoveryAction(action)
    }

    private fun applyCameraRecoveryAction(action: CameraRecoveryAction) {
        when (action) {
            CameraRecoveryAction.None -> scheduleCameraRecoveryTimer()
            is CameraRecoveryAction.Attempt -> beginCameraRecoveryAttempt(action)
            is CameraRecoveryAction.Abandon -> terminateCameraUnavailable(action.reason)
        }
    }

    private fun beginCameraRecoveryAttempt(action: CameraRecoveryAction.Attempt) {
        val cfg = config
        val expectedCameraId = cameraRecovery.snapshot.targetCameraId
        if (action.generation != manualSessionGeneration || stopping || releasing ||
            cfg == null || cfg.cameraId != expectedCameraId || recording || startInFlight ||
            cameraOpenInFlight || cameraRecovery.snapshot.phase != CameraRecoveryPhase.RESUMING
        ) {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_CAMERA_RECOVERY_ATTEMPT_REJECTED",
                severity = Severity.WARN,
                payload = recoveryDiagnosticPayload(),
            )
            if (action.generation == manualSessionGeneration && !stopping && !releasing) {
                terminateCameraUnavailable("RECOVERY_STATE_INVALID")
            }
            return
        }
        cancelCameraRecoveryTimer()
        closeCamera()
        updateState(
            state.copy(
                status = RecorderStatus.RESUMING,
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = null,
                message = "Reopening the same camera (${action.attemptNumber}/${cameraRecovery.maxAttempts})",
                previewRequested = false,
                previewActive = false,
            ),
        )
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_CAMERA_RECOVERY_ATTEMPT",
            payload = recoveryDiagnosticPayload() + mapOf(
                "attempt" to action.attemptNumber.toString(),
                "cameraId" to cfg.cameraId,
            ),
        )
        startInFlight = true
        openCamera(cfg.cameraId)
    }

    private fun scheduleCameraRecoveryTimer() {
        cancelCameraRecoveryTimer()
        val wakeAtMs = cameraRecovery.nextWakeAtMs() ?: return
        val token = manualSessionGeneration
        val delayMs = (wakeAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val runnable = Runnable {
            cameraRecoveryTimerRunnable = null
            if (token != manualSessionGeneration || stopping || releasing) return@Runnable
            val action = cameraRecovery.onTimer(token, SystemClock.elapsedRealtime())
            applyCameraRecoveryAction(action)
        }
        cameraRecoveryTimerRunnable = runnable
        cameraHandler?.postDelayed(runnable, delayMs)
    }

    private fun cancelCameraRecoveryTimer() {
        cameraRecoveryTimerRunnable?.let { cameraHandler?.removeCallbacks(it) }
        cameraRecoveryTimerRunnable = null
    }

    private fun recoveryDiagnosticPayload(): Map<String, String> {
        val recovery = cameraRecovery.snapshot
        return mapOf(
            "generation" to recovery.generation.toString(),
            "targetCameraId" to (recovery.targetCameraId ?: "-"),
            "phase" to recovery.phase.toString(),
            "availability" to recovery.availability.toString(),
            "attempts" to recovery.attemptsMade.toString(),
            "maxAttempts" to cameraRecovery.maxAttempts.toString(),
            "windowMs" to cameraRecovery.recoveryWindowMs.toString(),
            "deadlineAtElapsedMs" to (recovery.deadlineAtMs?.toString() ?: "-"),
            "nextAttemptAtElapsedMs" to (recovery.nextAttemptAtMs?.toString() ?: "-"),
            "sourceRole" to (config?.source?.sourceRole?.name ?: "-"),
            "resumeAllowed" to recovery.resumeAllowed.toString(),
        )
    }

    private fun setError(message: String) {
        stopCameraConflictDiagnostics()
        cancelCameraRecoveryTimer()
        cameraRecovery.terminateManualSession(manualSessionGeneration, message)
        updateState(
            state.copy(
                status = RecorderStatus.ERROR,
                currentFile = null,
                segmentStartedAtEpochMs = null,
                lastError = message,
            ),
        )
    }

    private fun storageBlocked(reason: String) {
        recordingSessionIdentity.endSession()
        cancelCameraRecoveryTimer()
        cameraRecovery.terminateManualSession(manualSessionGeneration, reason)
        closeCamera()
        stopCameraConflictDiagnostics()
        EventLogger.markError(Categories.SYSTEM, "RECORDER_STORAGE_BLOCKED", reason, null)
        updateState(
            state.copy(
                status = RecorderStatus.ERROR,
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
            val recent = segmentsDir.listFiles()
                ?.filter { SegmentNaming.isFinalMp4(it.name) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(count)
                ?.reversed()
                .orEmpty()
            var protectedCount = 0
            synchronized(RecorderStorageLock.lock) {
                recent.forEach { file ->
                    val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))
                        ?: return@forEach
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
        scheduleQuarantineRetention()
    }

    private fun quarantine(file: File): File? = try {
        val target = File(quarantineDir, file.name)
        if (file.renameTo(target)) {
            scheduleQuarantineRetention()
            target
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    private fun scheduleQuarantineRetention() {
        runIo { enforceQuarantineRetention() }
    }

    /** Keeps recent recorder-owned failure evidence while bounding disk use. */
    private fun enforceQuarantineRetention() {
        try {
            val now = System.currentTimeMillis()
            val listing = quarantineDir.listFiles().orEmpty()
            listing
                .filter {
                    it.name.endsWith(".tmp") &&
                        now - it.lastModified() > QuarantineRetentionPolicy.STALE_TMP_AGE_MS
                }
                .forEach { it.delete() }
            val primaries = listing.filter {
                SegmentNaming.isPartial(it.name) || SegmentNaming.isFinalMp4(it.name)
            }
            val pairedSidecarNames = primaries
                .map { it.name + SegmentNaming.SIDECAR_SUFFIX }
                .toSet()
            val orphanSidecars = listing.filter {
                it.name.endsWith(SegmentNaming.SIDECAR_SUFFIX) && it.name !in pairedSidecarNames
            }
            val units = primaries.map { primary ->
                val sidecar = File(quarantineDir, primary.name + SegmentNaming.SIDECAR_SUFFIX)
                QuarantinedEvidence(
                    path = primary.absolutePath,
                    totalBytes = primary.length() + (if (sidecar.exists()) sidecar.length() else 0L),
                    lastModifiedMs = primary.lastModified(),
                )
            } + orphanSidecars.map { orphan ->
                QuarantinedEvidence(
                    path = orphan.absolutePath,
                    totalBytes = orphan.length(),
                    lastModifiedMs = orphan.lastModified(),
                )
            }
            val evictions = QuarantineRetentionPolicy.selectEvictions(units, nowMs = now)
            if (evictions.isEmpty()) return
            var deletedUnits = 0
            var freedBytes = 0L
            evictions.forEach { path ->
                val primary = File(path)
                val sidecar = File(primary.absolutePath + SegmentNaming.SIDECAR_SUFFIX)
                val unitBytes = (if (primary.exists()) primary.length() else 0L) +
                    (if (sidecar.exists()) sidecar.length() else 0L)
                if (primary.delete()) {
                    sidecar.delete()
                    deletedUnits++
                    freedBytes += unitBytes
                }
            }
            if (deletedUnits > 0) {
                EventLogger.logEvent(
                    Categories.SYSTEM,
                    "RECORDER_QUARANTINE_EVICTED",
                    payload = mapOf(
                        "units" to deletedUnits.toString(),
                        "freedBytes" to freedBytes.toString(),
                        "remainingUnits" to (units.size - deletedUnits).toString(),
                    ),
                )
            }
        } catch (t: Throwable) {
            // Housekeeping retries at the next recorder start or quarantine.
        }
    }

    private fun buildSidecarFromSnapshot(
        s: SegmentSnapshot,
        profile: CameraFormatProfile,
        actualTrack: ActualTrackInfo?,
        health: FrameHealthReport?,
    ): SegmentSidecar {
        val realDurationMs = if (
            s.startedAtElapsedRealtimeMs != null &&
            s.stoppedAtElapsedRealtimeMs != null
        ) {
            (s.stoppedAtElapsedRealtimeMs - s.startedAtElapsedRealtimeMs).coerceAtLeast(0L)
        } else {
            null
        }
        val measurement = if (
            s.recordingMode == RecordingMode.TIME_LAPSE &&
            realDurationMs != null
        ) {
            TimeLapseMeasurementPolicy.measure(
                requestedMultiplier = s.timeLapseMultiplier,
                realDurationMs = realDurationMs,
                encodedDurationMs = actualTrack?.durationMs,
            )
        } else {
            null
        }
        return SegmentSidecar(
            file = s.file.absolutePath,
            cameraId = s.cameraId,
            profile = profile,
            sourceRole = s.sourceRole,
            layoutKind = s.layoutKind,
            mappingRevision = s.mappingRevision,
            segmentSeconds = s.segmentSeconds,
            segmentNumber = s.segmentNumber,
            processStartId = s.processStartId,
            recordingSessionId = s.recordingSessionId,
            recordingMode = s.recordingMode,
            timeLapseMultiplier = s.timeLapseMultiplier,
            requestedCaptureRateFps = s.requestedCaptureRateFps,
            captureSubmissionMode = TimeLapseCaptureCadencePolicy.plan(
                recordingMode = s.recordingMode,
                multiplier = s.timeLapseMultiplier,
            ).submissionMode,
            effectiveSegmentSeconds = s.effectiveSegmentSeconds,
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
            laneLayout = s.laneLayout,
            realDurationMs = realDurationMs,
            measuredMultiplier = measurement?.measuredMultiplier,
            timeLapseRelativeError = measurement?.relativeError,
            timeLapseAccuracy = measurement?.accuracy,
            finalizeReason = s.finalizeReason,
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
            cameraHandler?.post {
                val publish = LibraryPublicationPolicy.shouldPublish(
                    result = sidecar.result,
                    provisional = sidecar.provisional,
                )
                updateState(
                    state.copy(
                        lastSidecarPath = file.absolutePath,
                        libraryRevision = if (publish) {
                            state.libraryRevision + 1L
                        } else {
                            state.libraryRevision
                        },
                    ),
                )
            }
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
            val estimated = StoragePolicy.estimateSegmentBytes(
                cfg.profile.bitrateBps,
                cfg.estimatedEncodedSeconds(),
            )
            if (SettingsStore.get(context).autoCleanupEnabled) {
                enforceAutomaticCleanup(
                    limitBytes = cfg.storageLimitBytes,
                    reserveBytes = cfg.minFreeBytes,
                    estimatedNextSegmentBytes = estimated,
                )
            }
            val usage = segmentsDir.listFiles()
                ?.filter { SegmentNaming.isFinalMp4(it.name) }
                ?.sumOf { it.length() }
                ?: 0L
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
    ) {
        val usage = segmentsDir.listFiles()
            ?.filter { SegmentNaming.isFinalMp4(it.name) }
            ?.sumOf { it.length() }
            ?: 0L
        val quotaTarget = (limitBytes - estimatedNextSegmentBytes).coerceAtLeast(0L)
        val available = availableStorageBytes()
        val requiredFree = reserveBytes + estimatedNextSegmentBytes
        val freeSpaceTarget = if (available >= 0L && available < requiredFree) {
            (usage - (requiredFree - available)).coerceAtLeast(0L)
        } else {
            usage
        }
        val target = minOf(usage, quotaTarget, freeSpaceTarget)
        if (target < usage) enforceStorageLimit(limitBytes, target)
    }

    @SuppressLint("UsableSpace")
    private fun availableStorageBytes(): Long = try {
        File(context.filesDir, "recordings").usableSpace
    } catch (t: Throwable) {
        -1L
    }

    private fun enforceStorageLimit(limitBytes: Long, target: Long = limitBytes) {
        // Serialized with upload-pin writes so read/select/delete cannot race a
        // pin that was just applied.
        synchronized(RecorderStorageLock.lock) {
            val files = segmentsDir.listFiles()
                ?.filter { SegmentNaming.isFinalMp4(it.name) }
                ?.map { file ->
                    val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))
                    ManagedSegmentFile(
                        path = file.absolutePath,
                        bytes = file.length(),
                        lastModifiedMs = file.lastModified(),
                        isFinalMp4 = true,
                        hasSidecar = sidecar != null || file.name in pendingSidecarFiles,
                        protected = sidecar?.protected ?: false,
                        uploadPinned = sidecar?.uploadPinned ?: false,
                        usbExportPinned = UsbExportPinRegistry.isPinned(file),
                        analysisInFlight = file.name in pendingSidecarFiles,
                        playing = PlaybackPinRegistry.isPinned(file),
                    )
                }
                .orEmpty()
            val evictions = StoragePolicy.selectEvictionsToTarget(files, target)
            evictions.forEach { path ->
                val mp4 = File(path)
                val sidecar = SegmentSidecarIO.sidecarFileFor(mp4)
                if (mp4.delete()) {
                    sidecar.delete()
                    EventLogger.logEvent(
                        Categories.SYSTEM,
                        "RECORDER_STORAGE_EVICTED",
                        payload = mapOf(
                            "file" to mp4.name,
                            "limitBytes" to limitBytes.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun enforceTerminalRecordingResourceInvariant(reason: String) {
        if (closeTransaction != null) return // The process supervisor retains the exact handles.
        if (mediaRecorder != null || captureSession != null || cameraDevice != null || activeEncoderSurface != null) {
            CaptureCleanupRuntime.trace("invariant-" + System.identityHashCode(this), "OWNED_CLEANUP_REQUIRED", reason)
            closeCamera()
        }
    }

    private fun closeCamera() {
        cancelPreviewReplacementWatchdog()
        cancelPacedEncoderCaptures()
        cancelTimeLapseQuiesceWatchdog()
        if (closeTransaction != null) {
            closeTransaction?.merge(terminal = true)
            return
        }
        if (cameraDevice != null || captureSession != null || mediaRecorder != null || currentOutput != null) {
            beginResourceClose("CAMERA_CLOSE", null, outputLost = false)
            closeTransaction?.merge(terminal = true)
        }
    }

    private fun releaseRecordingPreviewSurface() {
        val surface = recordingPreviewSurface
        recordingPreviewSurface = null
        if (surface != null) retirePreviewSurface(surface)
    }

    private fun retirePreviewSurface(surface: Surface) {
        val producer = captureSession
        when {
            producer != null -> retiredPreviewSurfaces.getOrPut(producer) { mutableSetOf() }.add(surface)
            cameraDevice != null -> previewSurfacesAwaitingDevice.add(surface)
            else -> runCatching { surface.release() }
        }
    }

    private fun releasePendingRecordingPreviewSurface() {
        val surface = pendingRecordingPreviewSurface
        pendingRecordingPreviewSurface = null
        runCatching { surface?.release() }
    }

    private fun isRecoverableCameraAccessReason(reason: Int): Boolean = reason in setOf(
        CameraAccessException.CAMERA_IN_USE,
        CameraAccessException.MAX_CAMERAS_IN_USE,
        CameraAccessException.CAMERA_DISCONNECTED,
    )

    private fun isRecoverableCameraDeviceError(errorCode: Int): Boolean = errorCode in setOf(
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
    )

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
        const val PREVIEW_REPLACEMENT_TIMEOUT_MS = 6_000L
        const val POWER_RECONCILIATION_INTERVAL_MS = 5_000L
        const val POWER_RECONCILIATION_LOG_INTERVAL_MS = 60_000L
        const val PACED_CAPTURE_MIN_TIMEOUT_MS = 5_000L
        const val PACED_CAPTURE_EXTRA_TIMEOUT_MS = 3_000L
        const val USB_SEGMENT_SECONDS = 60
        const val USB_PRESENCE_WATCHDOG_INTERVAL_MS = 1_000L
        const val TIME_LAPSE_QUIESCE_DRAIN_TIMEOUT_MS = 5_000L
        const val USB_SEGMENT_METADATA_ALLOWANCE_BYTES = 4L * 1024L * 1024L
    }

    /** Immutable per-segment evidence captured on the camera thread before async work. */
    private data class SegmentSnapshot(

        val file: File,
        val finalPath: File?,
        val cameraId: String,
        val profile: CameraFormatProfile?,
        val sourceRole: RecordingSourceRole,
        val layoutKind: RecordingLayoutKind,
        val mappingRevision: Int,
        val laneLayout: SegmentLaneLayout?,
        val segmentSeconds: Int,
        val effectiveSegmentSeconds: Int,
        val recordingMode: RecordingMode,
        val timeLapseMultiplier: Int,
        val requestedCaptureRateFps: Double?,
        val finalizeReason: String,
        val segmentNumber: Int,
        val processStartId: String,
        val recordingSessionId: String,
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
    )
}
