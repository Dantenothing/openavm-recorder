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
 * Sole owner of CameraDevice / CaptureSession / recording encoder for the segment
 * recorder. Camera transitions are serialized on its handler; codec/writer workers acknowledge ownership.
 *
 * Timing contract:
 *  - Legacy segments close/recreate their encoder; continuous segments retain the capture session
 *    across file rotation. Metadata and USB commits run on IO; first-USB verification
 *    can delay file admission while the continuous encoder uses a bounded sample queue.
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
    private val diagnosticScope: RecorderDiagnosticScope? = null,
) {
    /** App metadata returned with callbacks; this does not alter any HAL request controls. */
    private data class CaptureOutputTag(val includesPreview: Boolean)

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
    private var recordingEncoder: RecordingEncoder? = null
    private var continuousRotationPending = false
    private var continuousRotationToken = 0L
    private var continuousRotationWatchdog: Runnable? = null
    private var nativeRotationWatchdog: Runnable? = null
    private val nativeSidecars = linkedMapOf<UsbMediaStoreRecordingOutputHandle, SegmentSidecar>()
    private var completedEncodedSegment: EncodedSegmentSummary? = null
    private var captureSessionRevision = 0L
    private val pendingContinuousOutputs = java.util.concurrent.atomic.AtomicInteger()
    private val productFiles = java.util.IdentityHashMap<RecordingOutputHandle, SegmentSnapshot>()
    private var nextProductTimelineUs = 0L
    private var productNextPreparing = false
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
    private val previewCaptureTracker = PreviewCaptureTracker()
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
    private val cameraRecovery = CameraRecoveryStateMachine(CameraRecoveryPolicy(availabilityWaitMs = 30 * 60_000L))
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
    private val previewReleases = PreviewReleaseCallbacks<Surface> { it.release() }
    private val captureLedger = CaptureSessionLedger<CameraCaptureSession, Surface> { surface ->
        releasePreviewWrapper(surface)
    }
    private val previewSurfacesAwaitingDevice = mutableSetOf<Surface>()
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
    @Volatile private var continuousFailureCapture = ContinuousFailureAndroid.forSession("UNASSIGNED")
    private val statePublication = RecorderStatePublication(
        syncWakeLock = { status -> wakeLockHolder.sync(status); wakeLockHolder.isHeld },
        releaseWakeLock = { wakeLockHolder.releaseAll() },
        publish = publishState,
        onTerminated = { value, reason, source ->
            if (value.recordingSessionId != null) EventLogger.logEvent(Categories.SYSTEM, "RECORDER_SESSION_TERMINATED", payload =
                vehicleAwayDiagnosticPayload() + mapOf(
                    "recordingSessionId" to (value.recordingSessionId ?: "NONE"),
                    "reason" to reason, "source" to source, "status" to value.status,
                    "cleanupPending" to value.cleanupPending.toString(),
                    "cleanupUnconfirmed" to value.cleanupUnconfirmed.toString(),
                ))
        },
    )
    private val state get() = statePublication.state
    private var cameraDiagnosticsRegistered = false
    private var diagnosticsActiveCameraId: String? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null

    init {
        CaptureCleanupRuntime.initialize(context)
        updateState(state)
    }

    private fun updateState(s: RecorderState, expectedSessionId: String? = s.recordingSessionId) {
        if (!statePublication.isCurrent(expectedSessionId)) return
        if (diagnosticScope != null && (s.status != state.status || s.captureSessionRevision != state.captureSessionRevision || s.segmentNumber != state.segmentNumber)) {
            diagnosticScope.event("STATE_${s.status}", s.segmentNumber, s.cameraGeneration, captureSessionRevision)
        }
        if (modeSwitchHandoff && s.lastError != null && modeHandoffFailure == null) modeHandoffFailure = s.lastError
        statePublication.update(s.copy(
            recovery = cameraRecovery.snapshot, cleanupPending = closeTransaction != null,
            cleanupUnconfirmed = cleanupUnconfirmed,
            recordingBackend = recordingEncoder?.name ?: s.recordingBackend,
            captureSessionRevision = captureSessionRevision,
            activeStorageUuid = if (s.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE) activeUsbTarget?.storageUuid else null), expectedSessionId)
    }

    fun start(config: RecorderConfig, previewSurface: Surface? = null, requiredStorage: RecordingStorageIdentity? = null) {
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
            val selectedUsb = if (diagnosticScope != null) {
                UsbExportVolumeResolver.resolveExact(context, diagnosticScope.target)
            } else if (config.storagePreference == RecordingStoragePreference.USB_PREFERRED) {
                runCatching { UsbPendingRecordingRecovery.recoverMounted(context) }
                UsbExportVolumeResolver.mountedTargets(context).singleOrNull()
            } else null
            if (diagnosticScope != null && selectedUsb == null) {
                setError("PREFLIGHT_USB_REQUIRED"); onStopped(); return@postCamera
            }
            if (requiredStorage != null) {
                val power = VehiclePowerSnapshotReader.read(context)
                val selectedStorage = selectedUsb?.let { RecordingStorageIdentity(RecordingStorageKind.USB_MEDIASTORE, it.storageUuid) }
                    ?: RecordingStorageIdentity(RecordingStorageKind.INTERNAL)
                if (!power.interactive || power.mainDisplayState != "ON" ||
                    !RecordingModeSwitchGate.storageMatches(requiredStorage, selectedStorage)) {
                    runCatching { previewSurface?.release() }
                    setError("MODE_SWITCH_START_CONDITIONS_CHANGED")
                    onStopped()
                    return@postCamera
                }
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
            nextProductTimelineUs = 0
            this.previousSegmentStoppedElapsedMs = null
            val recordingSessionId = recordingSessionIdentity.beginNewSession()
            statePublication.begin(recordingSessionId)
            continuousFailureCapture = ContinuousFailureAndroid.forSession(recordingSessionId)
            val sessionStartedAtEpochMs = System.currentTimeMillis()
            manualSessionGeneration++
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
            if (diagnosticScope == null) quarantineLeftoverPartials()
            updateState(
                state.copy(
                    status = RecorderStatus.STARTING,
                    incidentMessage = null,
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
                    mirrorPreviewManaged = config.mirrorPreviewEnabled,
                    cameraGeneration = 0,
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
    fun setPreviewOutputEnabled(enabled: Boolean, expectedSessionId: String? = null, expectedCameraGeneration: Long? = null) {
        postCamera {
            if (!com.dante.zeekrcapabilitylab.mirror.MirrorPreviewPolicy.commandMatches(
                    expectedSessionId, expectedCameraGeneration, state)) return@postCamera
            if (enabled && !SegmentPreviewPolicy.canRememberEnable(stopping, releasing, releaseRequested, cleanupUnconfirmed)) return@postCamera
            previewOutputDesired = enabled
            if (!enabled) {
                updateState(state.copy(previewRequested = false, previewActive = false))
            }
            // No new repeating request (including encoder-only) may be installed
            // after the close transaction has captured its producer sequences.
            if (closeTransaction != null) {
                // Reattaching HOME/OVERLAY during a minute rollover is an intent
                // for the next segment, not permission to restart a closing producer.
                updateState(state.copy(previewRequested = enabled, previewActive = false))
                return@postCamera
            }
            (recordingEncoder as? ProductContinuousRecorder)?.let { shared ->
                shared.enablePreview(enabled)
                updateState(state.copy(previewRequested = enabled,
                    previewActive = enabled && recordingPreviewSurface?.isValid == true))
                return@postCamera
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
    fun replacePreviewSurface(replacement: Surface, expectedSessionId: String? = null,
                              expectedCameraGeneration: Long? = null, afterRelease: (() -> Unit)? = null) {
        val posted = postCamera {
            if (afterRelease != null) previewReleases.track(replacement, afterRelease)
            if (!replacement.isValid || stopping || releasing || releaseRequested || cleanupUnconfirmed ||
                !com.dante.zeekrcapabilitylab.mirror.MirrorPreviewPolicy.commandMatches(
                    expectedSessionId, expectedCameraGeneration, state)) {
                releasePreviewWrapper(replacement)
                return@postCamera
            }
            (recordingEncoder as? ProductContinuousRecorder)?.let { shared ->
                if (closeTransaction != null) { releasePreviewWrapper(replacement); return@postCamera }
                recordingPreviewSurface = replacement
                previewOutputDesired = true
                shared.setPreview(replacement, ::onProductPreviewReleased)
                shared.enablePreview(true)
                updateState(state.copy(previewRequested = true, previewActive = true, previewFallbackUsed = false))
                return@postCamera
            }
            if (captureLedger.retiredOutputCount + previewSurfacesAwaitingDevice.size >= 4) {
                releasePreviewWrapper(replacement)
                handleCameraLoss("PREVIEW_RELEASE_UNCONFIRMED")
                return@postCamera
            }
            val device = cameraDevice
            val encoder = activeEncoderSurface
            val canRebuild = ActivePreviewReplacementPolicy.canRebuild(
                replacementValid = replacement.isValid,
                recording = recording && !continuousRotationPending && closeTransaction == null && !cleanupUnconfirmed && !releaseRequested,
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
                    releasePreviewWrapper(replacement)
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
        if (!posted) runCatching { replacement.release(); afterRelease?.invoke() }
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
            captureSessionRevision++
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
        val stoppedSessionId = state.recordingSessionId
        statePublication.terminate("MANUAL_STOP", "STOP_REQUEST_RECEIVED", expectedSessionId = stoppedSessionId)
        postCamera {
            if (!statePublication.isCurrent(stoppedSessionId)) return@postCamera
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
        outputLost: Boolean = false,
    ) {
        if (outputLost) {
            recordingEncoder?.abandonOutput()
            closeTransaction?.merge(outputLost = true)
        }
        if (stopping) return
        val generation = manualSessionGeneration
        stopping = true
        startInFlight = false

        statePublication.terminate(authorityReason, "STOP_REQUEST", forcedError)

        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_STOP",
            payload = mapOf(
                "generation" to generation.toString(),
                "finalizeReason" to finalizeReason,
                "authorityReason" to authorityReason,
                "recordingSessionId" to (state.recordingSessionId ?: "NONE"),
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
            if (outputLost) beginResourceClose(finalizeReason, forcedError, outputLost = true)
            else finalizeCurrentSegment(finalizeReason, forcedError)
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
            if (stopping || releasing || !recording || config?.recordingMode != RecordingMode.NORMAL) return@postCamera
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
                    incidentMessage = Utils.t("Event marked; protection is being saved.", "事件已标记，正在保存保护信息。"),
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

    @Volatile private var modeSwitchHandoff = false
    @Volatile private var modeHandoffFailure: String? = null

    /** Product mode switching uses the existing device-close fence and final IO barrier. */
    fun releaseForRecordingModeSwitch(expectedSessionId: String, onComplete: (RecordingModeHandoffResult) -> Unit) {
        postCamera {
            if (!recording || stopping || releasing || releaseRequested || cleanupUnconfirmed ||
                state.recordingSessionId != expectedSessionId || !RecordingModeSwitchGate.eligible(state)) {
                onComplete(RecordingModeHandoffResult(false, "RECORDING_CHANGED"))
                return@postCamera
            }
            // Avoid a deliberate stop before even two capture results have arrived.
            // These callbacks are not encoded-file proof; normal finalize validation still applies.
            if (frameStats.count < 2) {
                onComplete(RecordingModeHandoffResult(false, "INSUFFICIENT_FRAMES"))
                return@postCamera
            }
            modeSwitchHandoff = true
            requestRelease {
                val failure = modeHandoffFailure ?: when {
                    cleanupUnconfirmed -> "CLEANUP_UNCONFIRMED"
                    pendingUsbCommits != 0 -> "USB_COMMIT_PENDING"
                    else -> null
                }
                onComplete(RecordingModeHandoffResult(true, failure))
            }
        }
    }

    private fun requestRelease(onComplete: (() -> Unit)?) {
        releaseRequested = true
        val releaseSessionId = state.recordingSessionId
        statePublication.terminate("SERVICE_RELEASE", "RELEASE_REQUEST", expectedSessionId = releaseSessionId)
        if (onComplete != null) releaseCompletion = onComplete
        // This deadline is independent of the camera worker, including a vendor call stuck in native code.
        CaptureCleanupRuntime.handler.postDelayed({
            if (!disposalFinished && statePublication.isCurrent(releaseSessionId)) {
                cleanupUnconfirmed = true
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CLOSE_UNCONFIRMED", payload = mapOf(
                    "recordingSessionId" to (releaseSessionId ?: "NONE"), "source" to "SERVICE_RELEASE",
                    "errorCode" to "SESSION_RELEASE_UNCONFIRMED"))
                statePublication.unconfirmed("SESSION_RELEASE_UNCONFIRMED", "Worker/IO cleanup deadline", releaseSessionId)
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
        if (!releaseRequested || disposalStarted || closeTransaction != null || cameraDevice != null || cameraOpenInFlight ||
            pendingContinuousOutputs.get() != 0) return
        disposalStarted = true
        // One IO barrier; late cleanup callbacks may reach here after the bounded wait has ended.
        runIo {
            cameraHandler?.post {
                stopCameraConflictDiagnostics()
                releaseRecordingPreviewSurface()
                releasePendingRecordingPreviewSurface()
                updateState(state.copy(status = RecorderStatus.STOPPED))
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

    private fun postCamera(block: () -> Unit): Boolean {
        ensureCameraThread()
        return cameraHandler?.post(block) == true
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
        if (!statePublication.allowsOpen(state.recordingSessionId)) return
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
        // Capability/permission queries above may take time. Recheck immediately before opening.
        if (reconcileVehiclePowerSnapshot(source = "CAMERA_OPEN_COMMIT")) return
        if (cameraRecovery.snapshot.phase == CameraRecoveryPhase.RESUMING &&
            applyVehicleAwayAction(vehicleAway.onCameraLoss(manualSessionGeneration))) return
        if (releaseRequested || cleanupUnconfirmed || closeTransaction != null || stopping || releasing ||
            !statePublication.allowsOpen(state.recordingSessionId)) return
        if (!cameraRecovery.mayOpen(manualSessionGeneration, SystemClock.elapsedRealtime())) {
            cameraUnavailable("RECOVERY_OPEN_AUTHORITY_INVALID",
                recoverableContention = cameraRecovery.snapshot.availability == CameraAvailabilityState.UNAVAILABLE)
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
        callbackSessionId: String? = state.recordingSessionId,
    ): CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            private fun evidence(type: String, code: Int? = null, error: String? = null, deviceOwnerId: Int? = null) {
                val enteredAt = SystemClock.elapsedRealtime()
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CAMERA_CALLBACK", payload = mapOf(
                    "callbackType" to type, "cameraErrorCode" to (code?.toString() ?: "NONE"),
                    "errorCode" to (error ?: "NONE"),
                    "generation" to sessionToken.toString(), "cameraGeneration" to generation.toString(),
                    "recordingSessionId" to (callbackSessionId ?: "NONE"),
                    "callbackEntryElapsedMs" to enteredAt.toString(),
                    "deviceOwnerId" to (deviceOwnerId?.toString() ?: "NONE"),
                    "staleCallback" to (generation != openGeneration || sessionToken != manualSessionGeneration).toString(),
                ))
            }
            override fun onOpened(camera: CameraDevice) {
                evidence("ON_OPENED")
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
                if (!statePublication.allowsOpen(callbackSessionId)) {
                    // Stop was received on the UI thread while onOpened was queued. Retain the
                    // returned device and use the normal fenced Stop, not an untracked close.
                    cameraDevice = camera
                    stopSessionOnCameraThread("STOP", "SESSION_AUTHORITY_REVOKED", null, false)
                    return
                }
                cameraDevice = camera
                updateState(state.copy(cameraGeneration = generation))
                startSegment()
            }

            override fun onDisconnected(camera: CameraDevice) {
                evidence("ON_DISCONNECTED", error = "CAMERA_DISCONNECTED")
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
                evidence("ON_ERROR", errorCode, cameraDeviceErrorMessage(errorCode))
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
                evidence("ON_CLOSED", deviceOwnerId = System.identityHashCode(camera))
                onCameraDeviceProducerClosed(camera)
            }
        }

    private fun startSegment() {
        val cfg = config ?: return
        if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return
        if (!statePublication.allowsOpen(state.recordingSessionId)) return
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
        val continuing = (recordingEncoder as? ContinuousVideoRecorder)?.takeIf { it.started }
        segmentGeneration++
        timeLapseQuiesced = false
        previewReplacementGeneration++
        cancelPreviewReplacementWatchdog()
        pendingRecordingPreviewSurface?.takeIf { continuing == null }?.let { pending ->
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
                releasePreviewWrapper(pending)
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
        previewCaptureTracker.reset(segmentGeneration)
        lastTimestampNs = null
        lastFrameReceivedAtElapsedMs = null
        RecorderCaptureEvidence.update(CaptureEvidence(recordingSessionIdentity.requireCurrentId(), segmentNumber))
        if (diagnosticScope == null && currentIncidentTag == null) {
            val pendingIncident = incidentStore.pending(nowEpoch)
            if (pendingIncident != null) {
                currentIncidentTag = pendingIncident
                currentConsumesPendingIncident = true
                protectedPending = true
            }
        }
        if (continuing != null) {
            prepareContinuousOutputAsync(cfg, generation, sessionToken, device, continuing, nowEpoch)
            return
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
                if (diagnosticScope != null) diagnosticScope.admit(estimated) else usbRetentionManager.admit(
                    target = target,
                    incomingBytes = estimated,
                    quotaBytes = cfg.usbQuotaBytes,
                    protectedBundleId = expectedBundleId,
                )
                requireNotNull(usbOutputSink).openSegment(segmentNumber, cfg.profile, nowEpoch).also {
                    if (diagnosticScope != null) {
                        try { diagnosticScope.opened(it as UsbMediaStoreRecordingOutputHandle) }
                        catch (failure: Throwable) { it.abort(); throw failure }
                    }
                }
            } catch (failure: Throwable) {
                if (!consumeUsbFallback("USB_OPEN_FAILED:${failure.message ?: failure.javaClass.simpleName}")) return
                internalOutputSink.openSegment(segmentNumber, cfg.profile, nowEpoch)
            }
        } else {
            internalOutputSink.openSegment(segmentNumber, cfg.profile, nowEpoch)
        }
        startPreparedOutput(output, cfg, generation, sessionToken, device, continuing)
    }

    private fun startPreparedOutput(output: RecordingOutputHandle, cfg: RecorderConfig, generation: Long,
                                    sessionToken: Long, device: CameraDevice, continuing: ContinuousVideoRecorder?) {
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
            val recorder = continuing ?: newRecordingEncoder(cfg)
            recordingEncoder = recorder // Own even partial prepare failures before allocating native resources.
            if (recorder is NativeFileRecordingEncoder && output is UsbMediaStoreRecordingOutputHandle) {
                val sidecar = nativeSidecar(output, cfg).copy(protected = protectedPending,
                    eventId = currentIncidentTag?.eventId, eventRequestedAtEpochMs = currentIncidentTag?.requestedAtEpochMs,
                    eventRole = currentIncidentTag?.role)
                output.checkpointNative(sidecar)
                nativeSidecars[output] = sidecar
            }
            if (recorder is ProductContinuousRecorder) {
                registerProductOutput(output, cfg, segmentNumber, segmentStartedAtEpochMs ?: System.currentTimeMillis())
            }
            recorder.prepare(cfg, output)
            if (recorder is ProductContinuousRecorder) {
                recorder.setPreview(recordingPreviewSurface, ::onProductPreviewReleased)
                recorder.enablePreview(previewOutputDesired)
            }
            val surface = recorder.surface
            activeEncoderSurface = surface
            if (continuing != null) {
                check(captureSession != null) { "CONTINUOUS_CAPTURE_SESSION_MISSING" }
                completeSegmentStart(recorder, output, cfg, generation, sessionToken,
                    recordingPreviewSurface?.takeIf { previewOutputDesired && it.isValid && !state.previewFallbackUsed },
                    TimeLapseCaptureCadencePolicy.plan(RecordingMode.NORMAL, 1))
                // A UI replacement requested during file rotation is an explicit topology change,
                // handled by the existing output-retirement owner after the writer is attached.
                val replacement = pendingRecordingPreviewSurface
                pendingRecordingPreviewSurface = null
                if (replacement != null) replacePreviewSurface(replacement)
                else setPreviewOutputEnabled(previewOutputDesired)
                return
            }
            cancelPacedEncoderCaptures()
            captureSession?.close()
            captureSession = null
            fun configureSession(includePreview: Boolean) {
                if (!ownsSetup(generation, partial, recorder, sessionToken)) return
                val preview = recordingPreviewSurface?.takeIf { recorder !is ProductContinuousRecorder && includePreview && it.isValid }
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
                                completeSegmentStart(recorder, output, cfg, generation, sessionToken,
                                    if (recorder is ProductContinuousRecorder) recordingPreviewSurface?.takeIf { previewOutputDesired } else previewTarget,
                                    cadencePlan)
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
                    captureSessionRevision++
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
                            originalError = t,
                        )
                    }
                }
            }
            configureSession(
                includePreview = recorder !is ProductContinuousRecorder && SegmentPreviewPolicy.includeInNewSession(
                    previewConfigured = recordingPreviewSurface != null,
                    previewDesired = previewOutputDesired,
                    retainedPreview = cfg.mirrorPreviewEnabled && !state.previewFallbackUsed,
                ),
            )
        } catch (t: Throwable) {
            failSegmentStart(generation, partial, t.message ?: "recorder prepare failed", originalError = t)
        }
    }

    private fun registerProductOutput(output: RecordingOutputHandle, cfg: RecorderConfig, number: Int, requestedEpoch: Long) {
        check(productFiles.size < 3 && !productFiles.containsKey(output)) { "PRODUCT_METADATA_OWNER_LIMIT" }
        productFiles[output] = SegmentSnapshot(
            file = requireNotNull(output.localWorkingFile), finalPath = null, cameraId = cfg.cameraId, profile = cfg.profile,
            sourceRole = cfg.source.sourceRole, layoutKind = cfg.source.layoutKind, mappingRevision = cfg.source.mappingRevision,
            laneLayout = cfg.source.laneLayout, segmentSeconds = cfg.segmentSeconds,
            effectiveSegmentSeconds = if (output is UsbMediaStoreRecordingOutputHandle) USB_SEGMENT_SECONDS else cfg.effectiveSegmentSeconds(),
            recordingMode = cfg.recordingMode, timeLapseMultiplier = cfg.timeLapseMultiplier, requestedCaptureRateFps = cfg.captureRateFpsOrNull(),
            finalizeReason = "CONTINUOUS_FILE", segmentNumber = number, processStartId = processStartId,
            recordingSessionId = recordingSessionIdentity.requireCurrentId(), requestedAtEpochMs = requestedEpoch,
            requestedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(), startedAtEpochMs = null, stoppedAtEpochMs = null,
            startedAtElapsedRealtimeMs = null, stoppedAtElapsedRealtimeMs = null, gapFromPreviousMs = null,
            result = SegmentSidecar.RESULT_SUCCESS, error = null, fileBytes = 0L,
            protected = false, eventId = null, eventRequestedAtEpochMs = null, eventRole = null,
            frameStats = SegmentFrameStats(), storageLimitBytes = cfg.storageLimitBytes)
    }

    private fun freezeProductProtection(output: RecordingOutputHandle?) {
        val seed = productFiles[output] ?: return
        productFiles[output] = seed.copy(protected = protectedPending, eventId = currentIncidentTag?.eventId,
            eventRequestedAtEpochMs = currentIncidentTag?.requestedAtEpochMs, eventRole = currentIncidentTag?.role,
            consumesPendingIncident = currentConsumesPendingIncident)
    }

    private fun onProductFileStarted(owner: ProductContinuousRecorder, number: Int, output: RecordingOutputHandle,
                                     epoch: Long, elapsed: Long) {
        cameraHandler?.post {
            if (recordingEncoder !== owner) return@post
            if (output !== currentOutput) {
                freezeProductProtection(currentOutput)
                protectedPending = false; currentIncidentTag = null; currentConsumesPendingIncident = false
                incidentStore.pending(epoch)?.let { currentIncidentTag = it; currentConsumesPendingIncident = true; protectedPending = true }
            }
            val seed = productFiles[output] ?: run { failContinuousRun("PRODUCT_FILE_SEED_MISSING", false); return@post }
            productFiles[output] = seed.copy(startedAtEpochMs = epoch, startedAtElapsedRealtimeMs = elapsed)
            if (stopping || releasing || releaseRequested || closeTransaction != null ||
                !statePublication.allowsOpen(state.recordingSessionId)) return@post
            currentOutput = output; currentPartial = output.localWorkingFile; segmentNumber = number
            segmentGeneration++; frameStats = SegmentFrameStats(); previewCaptureTracker.reset(segmentGeneration)
            segmentRecordingStartedAtEpochMs = epoch; segmentRecordingStartedAtElapsedMs = elapsed
            segmentStartedAtEpochMs = seed.requestedAtEpochMs; segmentStartedAtElapsedMs = seed.requestedAtElapsedRealtimeMs
            updateState(state.copy(status = RecorderStatus.RECORDING, segmentNumber = number, currentFile = output.displayName,
                segmentStartedAtEpochMs = epoch, message = null))
            EventLogger.logEvent(Categories.SYSTEM, "RECORDER_PRODUCT_FILE_STARTED", payload = mapOf(
                "segment" to number.toString(), "captureSessionRevision" to captureSessionRevision.toString()))
            scheduleTimeout(segmentGeneration, seed.effectiveSegmentSeconds * 1000L)
            if (output is UsbMediaStoreRecordingOutputHandle) scheduleUsbPresenceWatchdog(segmentGeneration, output)
            prepareProductNext(owner, requireNotNull(config), number + 1)
        }
    }

    private fun prepareProductNext(owner: ProductContinuousRecorder, cfg: RecorderConfig, number: Int) {
        if (productNextPreparing || stopping || releasing || closeTransaction != null) return
        if (!statePublication.allowsOpen(state.recordingSessionId)) return
        if (pendingUsbCommits >= 2 || usbFallbackPendingReason != null) {
            failContinuousRun("PRODUCT_PUBLICATION_BACKLOG", false); return
        }
        val target = activeUsbTarget?.takeIf { sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE }
        val sink = if (target != null) requireNotNull(usbOutputSink) else internalOutputSink
        val sessionId = recordingSessionIdentity.requireCurrentId()
        val sessionToken = manualSessionGeneration
        val epoch = System.currentTimeMillis() + (if (target != null) USB_SEGMENT_SECONDS else cfg.effectiveSegmentSeconds()) * 1000L
        productNextPreparing = true; pendingContinuousOutputs.incrementAndGet()
        runIo {
            val prepared = runCatching {
                if (target != null) usbRetentionManager.admit(target,
                    StoragePolicy.estimateSegmentBytes(cfg.profile.bitrateBps, USB_SEGMENT_SECONDS), cfg.usbQuotaBytes,
                    UsbExportPolicy.bundleId(sessionId, number, epoch))
                else check(prepareStorage(cfg).proceed) { "PRODUCT_INTERNAL_STORAGE_BLOCKED" }
                sink.openSegment(number, cfg.profile, epoch)
            }
            val openingFailure = prepared.exceptionOrNull()?.let {
                owner.captureFailure(it, ContinuousFailureStage.OUTPUT_OPEN)
            }
            val posted = cameraHandler?.post {
                if (recordingEncoder === owner) productNextPreparing = false
                if (recordingEncoder !== owner || manualSessionGeneration != sessionToken || stopping || releasing ||
                    releaseRequested || cleanupUnconfirmed || closeTransaction != null || !statePublication.allowsOpen(sessionId)) {
                    runIo {
                        prepared.getOrNull()?.let(::discardUnusedProductOutput)
                        pendingContinuousOutputs.decrementAndGet(); cameraHandler?.post { maybeFinishDisposal() }
                    }
                    return@post
                }
                pendingContinuousOutputs.decrementAndGet()
                prepared.onSuccess { output ->
                    registerProductOutput(output, cfg, number, System.currentTimeMillis())
                    try { owner.prepareNext(number, output) }
                    catch (error: Throwable) {
                        val preparationFailure = owner.captureFailure(error, ContinuousFailureStage.OUTPUT_PREPARE)
                        // The adapter reports whether ownership was admitted before a failure.
                        if (!owner.owns(output)) onProductOutputUnused(owner, output)
                        failContinuousRun(error.message ?: "PRODUCT_NEXT_PREPARE_FAILED", false,
                            failureEvidence = preparationFailure, originalError = error, failureStage = ContinuousFailureStage.OUTPUT_PREPARE)
                    }
                }.onFailure { failContinuousRun(it.message ?: "PRODUCT_NEXT_OPEN_FAILED", false,
                    failureEvidence = openingFailure, originalError = it, failureStage = ContinuousFailureStage.OUTPUT_OPEN) }
            } == true
            if (!posted) {
                prepared.getOrNull()?.let(::discardUnusedProductOutput)
                pendingContinuousOutputs.decrementAndGet()
            }
        }
    }

    private fun discardUnusedProductOutput(output: RecordingOutputHandle) {
        runCatching {
            output.close()
            check(output.nativeReleaseConfirmed)
            output.abort()
            if (output !is UsbMediaStoreRecordingOutputHandle) output.localWorkingFile?.let { if (it.exists()) check(it.delete()) }
        }.onFailure {
            EventLogger.markError(Categories.SYSTEM, "PRODUCT_UNUSED_OUTPUT_CLEANUP_FAILED", it.javaClass.simpleName, it)
            if (!output.nativeReleaseConfirmed) retainUnconfirmedProductOutput(output)
        }
    }

    private fun retainUnconfirmedProductOutput(output: RecordingOutputHandle) {
        val token = Any()
        // The failed close has returned, but its FD acknowledgement is missing. Retain the
        // exact handle in the process cleanup owner; do not retry or admit another recorder.
        val tx = CaptureCloseTransaction(CaptureCleanupRuntime.control, CaptureCleanupRuntime.control,
            CaptureCleanupRuntime.control, object : CaptureCloseResources {
                override fun stopRepeating() = Unit
                override fun abortCaptures() = Unit
                override fun closeSession() = Unit
                override fun closeDevice() = Unit
                override fun stopRecorder() = Unit
                override fun resetRecorder() = Unit
                override fun releaseRecorder() = Unit
                override fun closeOutput(lost: Boolean) { check(output.nativeReleaseConfirmed) { "PRODUCT_UNUSED_FD_UNCONFIRMED" } }
            }, hasSession = false, hasDevice = false, wasRecording = false, sequences = emptySet(), terminal = true, lost = true,
            trace = { step, detail -> CaptureCleanupRuntime.trace("product-unused-${System.identityHashCode(output)}", step, detail) },
            unconfirmed = { cleanupUnconfirmed = true },
            completed = { CaptureCleanupRuntime.settled(token, it.safeToContinue) })
        CaptureCleanupRuntime.retain(token, config?.cameraId ?: "?", null, tx)
        tx.begin()
    }

    private fun onProductOutputUnused(owner: ProductContinuousRecorder, output: RecordingOutputHandle) {
        pendingContinuousOutputs.incrementAndGet()
        cameraHandler?.post {
            productFiles.remove(output)
            runIo {
                discardUnusedProductOutput(output)
                pendingContinuousOutputs.decrementAndGet(); cameraHandler?.post { maybeFinishDisposal() }
            }
        }
    }

    private fun onProductFileClosed(owner: ProductContinuousRecorder, file: ProductEncodedFile) {
        val publicationSessionId = state.recordingSessionId
        pendingContinuousOutputs.incrementAndGet()
        cameraHandler?.post {
            nextProductTimelineUs = maxOf(nextProductTimelineUs, file.endPtsUs)
            if (currentOutput === file.output) freezeProductProtection(file.output)
            val seed = productFiles.remove(file.output)
            if (seed == null) {
                pendingContinuousOutputs.decrementAndGet()
                EventLogger.markError(Categories.SYSTEM, "PRODUCT_CLOSED_FILE_SEED_MISSING", file.number.toString(), null)
                return@post
            }
            val timeline = io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline(
                runId = seed.recordingSessionId, firstPtsUs = file.firstPtsUs, lastPtsUs = file.lastPtsUs,
                endExclusivePtsUs = file.endPtsUs, frames = file.frames, startsWithKeyFrame = true)
            val frozen = seed.copy(startedAtEpochMs = file.firstEpochMs, startedAtElapsedRealtimeMs = file.firstElapsedMs,
                stoppedAtEpochMs = file.endedEpochMs, stoppedAtElapsedRealtimeMs = file.endedElapsedMs,
                continuousTimeline = timeline,
                gapFromPreviousMs = SegmentGapPolicy.gapMs(previousSegmentStoppedElapsedMs, file.firstElapsedMs))
            previousSegmentStoppedElapsedMs = file.endedElapsedMs
            pendingUsbCommits++
            runIo {
                val outcome = runCatching {
                    val sidecar = buildSidecarFromSnapshot(frozen, requireNotNull(frozen.profile), null, null)
                    val document = SegmentSidecarIO.json.encodeToString(SegmentSidecar.serializer(), sidecar)
                    if (file.output is UsbMediaStoreRecordingOutputHandle) file.output.checkpointNative(sidecar)
                    file.output.appendFinalMetadata(document)
                    if (file.output is UsbMediaStoreRecordingOutputHandle) {
                        val usb = file.output
                        val canary = usbCapabilityStore.requiresCanary(usb.pendingVideo.target)
                        val committed = usbCommitEngine.commitDirect(usb.pendingVideo, sidecar,
                            canaryRequired = canary, preserveVideoOnFailure = true)
                        check(com.dante.zeekrcapabilitylab.usbexport.UsbCommittedBundleStore(context).mark(
                            usb.pendingVideo.target.storageUuid, committed.bundleId, committed.observedOwnerPackage)) { "PRODUCT_USB_OWNERSHIP_PENDING" }
                        usb.markCommitted()
                        if (canary) usbCapabilityStore.markPassed(usb.pendingVideo.target, committed.observedOwnerPackage)
                    } else {
                        val partial = requireNotNull(file.output.localWorkingFile)
                        val track = requireNotNull(CameraRuntime.readTrackMetadata(partial)) { "PRODUCT_TRACK_UNREADABLE" }
                        check(owner.raster.matchesTrack(track.width ?: 0, track.height ?: 0)) { "PRODUCT_TRACK_SIZE_MISMATCH" }
                        val final = SegmentNaming.finalFileFor(partial)
                        check(partial.renameTo(final)) { "PRODUCT_FILE_RENAME_FAILED" }
                        val snapshot = frozen.copy(file = final, finalPath = final, fileBytes = final.length())
                        pendingSidecarFiles += final.name
                        finishSegmentAsync(snapshot, true)
                    }
                    if (frozen.consumesPendingIncident && frozen.eventId != null) incidentStore.consume(frozen.eventId)
                }
                val publicationFailure = outcome.exceptionOrNull()?.let {
                    owner.captureFailure(it, ContinuousFailureStage.OUTPUT_PUBLISH, "PRODUCT_PUBLICATION_FAILED")
                }
                cameraHandler?.post {
                    pendingUsbCommits = (pendingUsbCommits - 1).coerceAtLeast(0)
                    pendingContinuousOutputs.decrementAndGet()
                    if (outcome.isSuccess) updateState(state.copy(libraryRevision = state.libraryRevision + 1), publicationSessionId)
                    else {
                        val error = "PRODUCT_PUBLICATION_FAILED:${outcome.exceptionOrNull()?.message}"
                        updateState(state.copy(lastError = error), publicationSessionId)
                        if (recordingEncoder === owner && !stopping && closeTransaction == null) failContinuousRun(error, false,
                            failureEvidence = publicationFailure, originalError = outcome.exceptionOrNull(),
                            failureStage = ContinuousFailureStage.OUTPUT_PUBLISH)
                    }
                    maybeFinishDisposal()
                }
            }
        }
    }

    private fun prepareContinuousOutputAsync(cfg: RecorderConfig, generation: Long, sessionToken: Long,
                                              device: CameraDevice, continuing: ContinuousVideoRecorder, epoch: Long) {
        val number = segmentNumber
        val failureCapture = continuousFailureCapture
        val target = activeUsbTarget?.takeIf { sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE }
        val sink = if (target != null) requireNotNull(usbOutputSink) else internalOutputSink
        val sessionId = recordingSessionIdentity.requireCurrentId()
        updateState(state.copy(segmentNumber = number, currentFile = null))
        pendingContinuousOutputs.incrementAndGet()
        // The previous USB commit can hold the volume lock while hashing. Waiting for that lock
        // must not hold the camera handler or prevent STOP / producer acknowledgements.
        runIo {
            val prepared = runCatching {
                if (target != null) usbRetentionManager.admit(target,
                    StoragePolicy.estimateSegmentBytes(cfg.profile.bitrateBps, USB_SEGMENT_SECONDS), cfg.usbQuotaBytes,
                    com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy.bundleId(sessionId, number, epoch))
                sink.openSegment(number, cfg.profile, epoch)
            }
            val failure = prepared.exceptionOrNull()?.let { error -> runCatching {
                failureCapture.capture(error, ContinuousFailureStage.OUTPUT_OPEN, signal = "CONTINUOUS_OUTPUT_OPEN_FAILED")
            }.getOrNull() }
            val posted = cameraHandler?.post {
                val valid = generation == segmentGeneration && sessionToken == manualSessionGeneration &&
                    recordingEncoder === continuing && cameraDevice === device && !stopping && !releasing &&
                    !releaseRequested && !cleanupUnconfirmed && closeTransaction == null
                if (!valid) {
                    runIo {
                        prepared.getOrNull()?.let { unused -> runCatching { unused.abort() } }
                        pendingContinuousOutputs.decrementAndGet()
                        cameraHandler?.post { maybeFinishDisposal() }
                    }
                    return@post
                }
                pendingContinuousOutputs.decrementAndGet()
                prepared.onSuccess { startPreparedOutput(it, cfg, generation, sessionToken, device, continuing) }
                    .onFailure { failContinuousRun("CONTINUOUS_OUTPUT_OPEN_FAILED:${it.message ?: it.javaClass.simpleName}",
                        outputLost = false, failureEvidence = failure, originalError = it,
                        failureStage = ContinuousFailureStage.OUTPUT_OPEN) }
            } == true
            if (!posted) {
                prepared.getOrNull()?.let { runCatching { it.abort() } }
                pendingContinuousOutputs.decrementAndGet()
            }
        }
    }

    private fun newRecordingEncoder(cfg: RecorderConfig): RecordingEncoder {
        if (diagnosticScope != null) {
            updateState(state.copy(encoderSelection = ContinuousEncoderSelection("PREFLIGHT_MEDIA_RECORDER_BASELINE")))
            return LegacyRecordingEncoder(::onEncoderError)
        }
        if (cfg.sharedInputRecordingEnabled) {
            val selection = ProductContinuousRecorder.inspect(manager, cfg)
            updateState(state.copy(encoderSelection = selection))
            val codec = requireNotNull(selection.codecName) { "SHARED_INPUT_REJECTED:${selection.reason}" }
            EventLogger.logEvent(Categories.SYSTEM, "RECORDER_ENCODER_SELECTED", payload = mapOf(
                "route" to "SHARED_INPUT_CONTINUOUS_CODEC", "reason" to selection.reason))
            return ProductContinuousRecorder(codec, segmentNumber, nextProductTimelineUs, ::onEncoderError, ::onProductFileStarted,
                ::onProductFileClosed, ::onProductOutputUnused, continuousFailureCapture) { owner, problem ->
                cameraHandler?.post {
                    if (recordingEncoder === owner) {
                        updateState(state.copy(previewActive = false, message = "Preview unavailable; recording continues"))
                        EventLogger.markError(Categories.SYSTEM, "PRODUCT_PREVIEW_WINDOW_FAILED", problem, null)
                    }
                }
            }
        }
        val eligible = com.dante.zeekrcapabilitylab.BuildConfig.CONTINUOUS_MIRROR_RECORDING_ENABLED &&
            ContinuousRecordingPolicy.eligible(cfg.recordingMode, cfg.mirrorPreviewEnabled, cfg.source.sourceRole)
        val selection = if (eligible) runCatching { ContinuousVideoRecorder.inspectEncoder(manager, cfg) }
            .getOrElse { ContinuousEncoderSelection("CODEC_INSPECTION_FAILED", queryError = it.javaClass.simpleName) }
            else ContinuousEncoderSelection("CONFIG_NOT_ELIGIBLE")
        val declared = selection.codecName
        val nativeFiles = BuildConfig.NATIVE_FILE_ROTATION_ENABLED && eligible && declared == null &&
            currentOutput is UsbMediaStoreRecordingOutputHandle && !currentSegmentCanary
        updateState(state.copy(encoderSelection = selection))
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_ENCODER_SELECTED", payload = mapOf(
            "route" to when { declared != null -> "CONTINUOUS_CODEC"; nativeFiles -> "CONTINUOUS_MEDIA_RECORDER"; else -> "MEDIA_RECORDER" },
            "reason" to selection.reason))
        return if (declared != null) ContinuousVideoRecorder(declared, ::onEncoderError, continuousFailureCapture)
            else if (nativeFiles) NativeFileRecordingEncoder(currentOutput as UsbMediaStoreRecordingOutputHandle,
                ::onEncoderError, ::onNativeRecorderInfo)
            else LegacyRecordingEncoder(::onEncoderError)
    }

    private fun diagnosticCall(name: String, call: () -> Unit) {
        diagnosticScope?.event("${name}_ENTER", segmentNumber, state.cameraGeneration, captureSessionRevision)
        call()
        diagnosticScope?.event("${name}_RETURN", segmentNumber, state.cameraGeneration, captureSessionRevision)
    }

    private fun nativeSidecar(output: UsbMediaStoreRecordingOutputHandle, cfg: RecorderConfig) = SegmentSidecar(
        file = output.displayName, cameraId = cfg.cameraId, profile = cfg.profile,
        sourceRole = cfg.source.sourceRole, layoutKind = cfg.source.layoutKind, mappingRevision = cfg.source.mappingRevision,
        laneLayout = cfg.source.laneLayout, segmentSeconds = USB_SEGMENT_SECONDS,
        segmentNumber = output.pendingVideo.segmentNumber, processStartId = processStartId,
        recordingSessionId = output.pendingVideo.recordingSessionId, recordingMode = RecordingMode.NORMAL,
        requestedAtEpochMs = output.pendingVideo.startedAtEpochMs, result = SegmentSidecar.RESULT_SUCCESS,
        provisional = true, finalizeReason = "NATIVE_FILE_PENDING")

    private fun scheduleNativeWatchdog(encoder: NativeFileRecordingEncoder, delayMs: Long) {
        nativeRotationWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        val watchdog = Runnable {
            if (recordingEncoder === encoder && !stopping && !releasing && closeTransaction == null) {
                failContinuousRun("NATIVE_FILE_HANDOFF_TIMEOUT", outputLost = false)
            }
        }
        nativeRotationWatchdog = watchdog
        cameraHandler?.postDelayed(watchdog, delayMs)
    }

    /** MediaRecorder delivers these callbacks on the recorder camera Looper. */
    private fun onNativeRecorderInfo(encoder: NativeFileRecordingEncoder, what: Int) {
        if (recordingEncoder !== encoder || stopping || releasing || releaseRequested ||
            cleanupUnconfirmed || closeTransaction != null || encoder.rotation.stopping) return
        when (what) {
            android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (reconcileVehiclePowerSnapshot(source = "NATIVE_FILE_BOUNDARY")) return
                if (!encoder.rotation.approaching()) {
                    if (encoder.rotation.owned.size >= NativeFileRotationPolicy.MAX_FILES && encoder.rotation.queued == null)
                        failContinuousRun("NATIVE_FILE_RUN_LIMIT", outputLost = false)
                    return
                }
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_NATIVE_FILE_APPROACHING",
                    payload = mapOf("segment" to segmentNumber.toString(), "captureSessionRevision" to captureSessionRevision.toString()))
                scheduleNativeWatchdog(encoder, NativeFileRotationPolicy.HANDOFF_WATCHDOG_MS)
                prepareNativeNextFile(encoder)
            }
            android.media.MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                val pair = encoder.rotation.switched() ?: run {
                    failContinuousRun("NATIVE_FILE_UNEXPECTED_HANDOFF", outputLost = false); return
                }
                val (old, next) = pair
                if (currentOutput !== old) {
                    failContinuousRun("NATIVE_FILE_OWNER_MISMATCH", outputLost = false); return
                }
                val epoch = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val tag = currentIncidentTag
                val completed = requireNotNull(nativeSidecars[old]).copy(stoppedAtEpochMs = epoch,
                    stoppedAtElapsedRealtimeMs = elapsed, frameStats = frameStats, protected = protectedPending,
                    eventId = tag?.eventId, eventRequestedAtEpochMs = tag?.requestedAtEpochMs, eventRole = tag?.role,
                    provisional = false, finalizeReason = "NATIVE_FILE_SWITCH")
                nativeSidecars[old] = completed
                val consumeIncident = currentConsumesPendingIncident
                runIo {
                    runCatching { old.checkpointNative(completed) }.onFailure {
                        cameraHandler?.post { if (recordingEncoder === encoder && !stopping)
                            failContinuousRun("NATIVE_CHECKPOINT_FAILED", outputLost = false) }
                    }.onSuccess { if (consumeIncident && tag != null) incidentStore.consume(tag.eventId) }
                }
                protectedPending = false; currentIncidentTag = null; currentConsumesPendingIncident = false
                // Consuming the completed NEXT tag is asynchronous. Do not assign it to a second NEXT file.
                incidentStore.pending(epoch)?.takeUnless { consumeIncident && it.eventId == tag?.eventId }?.let {
                    currentIncidentTag = it; currentConsumesPendingIncident = true; protectedPending = true
                }
                currentOutput = next; currentPartial = next.localWorkingFile
                segmentNumber = next.pendingVideo.segmentNumber
                segmentStartedAtEpochMs = next.pendingVideo.startedAtEpochMs
                segmentStartedAtElapsedMs = elapsed
                segmentRecordingStartedAtEpochMs = epoch; segmentRecordingStartedAtElapsedMs = elapsed
                val started = requireNotNull(nativeSidecars[next]).copy(startedAtEpochMs = epoch, startedAtElapsedRealtimeMs = elapsed,
                    protected = protectedPending, eventId = currentIncidentTag?.eventId,
                    eventRequestedAtEpochMs = currentIncidentTag?.requestedAtEpochMs, eventRole = currentIncidentTag?.role)
                nativeSidecars[next] = started
                runIo { runCatching { next.checkpointNative(started) }.onFailure {
                    cameraHandler?.post { if (recordingEncoder === encoder && !stopping)
                        failContinuousRun("NATIVE_CHECKPOINT_FAILED", outputLost = false) }
                } }
                // The producer/session and its generation are unchanged; only per-file observations reset.
                frameStats = SegmentFrameStats(); lastTimestampNs = null
                previewCaptureTracker.reset(segmentGeneration)
                publishCaptureEvidence()
                updateState(state.copy(segmentNumber = segmentNumber, currentFile = next.displayName,
                    segmentStartedAtEpochMs = epoch, nativeFileSwitches = encoder.rotation.switches,
                    nativePendingFiles = encoder.rotation.owned.size))
                com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordRun(context, state)
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_NATIVE_FILE_STARTED", payload = mapOf(
                    "segment" to segmentNumber.toString(), "switches" to encoder.rotation.switches.toString(),
                    "captureSessionRevision" to captureSessionRevision.toString(), "previewActive" to state.previewActive.toString()))
                scheduleUsbPresenceWatchdog(segmentGeneration, next)
                scheduleNativeWatchdog(encoder, NativeFileRotationPolicy.FILE_WATCHDOG_MS)
            }
            android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
            android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ->
                failContinuousRun("NATIVE_FILE_LIMIT_WITHOUT_HANDOFF", outputLost = false)
        }
    }

    private fun prepareNativeNextFile(encoder: NativeFileRecordingEncoder) {
        val cfg = config ?: return
        val sink = usbOutputSink ?: return
        val target = activeUsbTarget ?: return
        val sessionToken = manualSessionGeneration
        val nextNumber = segmentNumber + 1
        val epoch = System.currentTimeMillis()
        val sessionId = recordingSessionIdentity.requireCurrentId()
        // Include every deferred native file in the admission budget; they are not evictable mid-run.
        val reservation = NativeFileRotationPolicy.maxBytes(cfg.profile.bitrateBps, USB_SEGMENT_SECONDS) *
            (encoder.rotation.owned.size + 1L) + USB_SEGMENT_METADATA_ALLOWANCE_BYTES
        pendingContinuousOutputs.incrementAndGet()
        runIo {
            val prepared = runCatching {
                usbRetentionManager.admit(target, reservation, cfg.usbQuotaBytes, UsbExportPolicy.bundleId(sessionId, nextNumber, epoch))
                val output = sink.openSegment(nextNumber, cfg.profile, epoch) as UsbMediaStoreRecordingOutputHandle
                try { output.checkpointNative(nativeSidecar(output, cfg)) }
                catch (failure: Throwable) { output.clearNativeCheckpoint(); output.abort(); throw failure }
                output
            }
            val posted = cameraHandler?.post {
                val output = prepared.getOrNull()
                if (sessionToken != manualSessionGeneration || recordingEncoder !== encoder || stopping || releasing ||
                    releaseRequested || cleanupUnconfirmed || closeTransaction != null || encoder.rotation.stopping) {
                    runIo {
                        if (output != null) runCatching { output.clearNativeCheckpoint(); output.abort() }
                        pendingContinuousOutputs.decrementAndGet()
                        cameraHandler?.post { maybeFinishDisposal() }
                    }
                    return@post
                }
                pendingContinuousOutputs.decrementAndGet()
                if (output == null) {
                    failContinuousRun("NATIVE_NEXT_OUTPUT_OPEN_FAILED", outputLost = false)
                    return@post
                }
                nativeSidecars[output] = nativeSidecar(output, cfg)
                try {
                    check(encoder.queue(output)) { "NATIVE_NEXT_OUTPUT_REJECTED" }
                    updateState(state.copy(nativePendingFiles = encoder.rotation.owned.size))
                    EventLogger.logEvent(Categories.SYSTEM, "RECORDER_NATIVE_FILE_QUEUED", payload = mapOf("segment" to nextNumber.toString()))
                } catch (failure: Throwable) {
                    if (output !in encoder.rotation.owned) {
                        nativeSidecars.remove(output)
                        runIo { runCatching { output.clearNativeCheckpoint(); output.abort() } }
                    }
                    failContinuousRun("NATIVE_NEXT_OUTPUT_REJECTED:${failure.javaClass.simpleName}", outputLost = false)
                }
            } == true
            if (!posted) {
                prepared.getOrNull()?.let { runCatching { it.clearNativeCheckpoint(); it.abort() } }
                pendingContinuousOutputs.decrementAndGet()
            }
        }
    }

    /** No file inspection/publication before the existing close transaction has released the recorder. */
    private fun flushNativeFilesAfterStop(encoder: NativeFileRecordingEncoder, current: RecordingOutputHandle?) {
        encoder.rotation.owned.filter { it !== current }.forEach { output ->
            val sidecar = nativeSidecars.remove(output) ?: return@forEach
            NativeUsbPublication.submit(context, output, sidecar)
        }
    }

    private fun onEncoderError(encoder: RecordingEncoder, reason: String) {
        cameraHandler?.post {
            if (recordingEncoder !== encoder) return@post
            if (encoder is ContinuousVideoRecorder || encoder is NativeFileRecordingEncoder || encoder is ProductContinuousRecorder) {
                // A failed continuous writer stops this run; never manufacture a fallback mini-file.
                failContinuousRun(reason, encoder !is ProductContinuousRecorder && currentOutput is UsbMediaStoreRecordingOutputHandle)
            } else {
                val active = currentOutput as? UsbMediaStoreRecordingOutputHandle ?: return@post
                if ((stopping || releasing) && closeTransaction == null) return@post
                abandonUsbSegmentWithoutStop(active, reason, source = "RECORDER_CALLBACK")
            }
        }
    }

    private fun failContinuousRun(reason: String, outputLost: Boolean,
                                  failureEvidence: ContinuousFailureEvidence? = null,
                                  originalError: Throwable? = null,
                                  failureStage: ContinuousFailureStage = ContinuousFailureStage.SESSION_CALLBACK) {
        val evidence = failureEvidence?.takeIf { it.sessionId == state.recordingSessionId }
            ?: (recordingEncoder as? ProductContinuousRecorder)?.let {
                it.firstFailure ?: it.captureFailure(originalError, failureStage, reason)
            }
            ?: runCatching { continuousFailureCapture.capture(originalError, failureStage, signal = reason) }.getOrNull()
        if (recordingEncoder is ProductContinuousRecorder) publishCaptureEvidence()
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CONTINUOUS_FAILED", severity = com.dante.zeekrcapabilitylab.data.Severity.ERROR,
            payload = evidence?.facts() ?: mapOf("failureCategory" to "CONTINUOUS_PIPELINE_FAILURE",
                "failureStage" to failureStage.name, "exceptionType" to "UNKNOWN", "errorCode" to "EVIDENCE_UNAVAILABLE",
                "recordingSessionId" to (state.recordingSessionId ?: "UNKNOWN")),
            errorMessage = reason)
        updateState(state.copy(lastError = reason))
        com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordFault(context, state)
        stopSessionOnCameraThread("STOP", "CONTINUOUS_FAILED", reason, vehicleAwayConfirmed = false,
            outputLost = outputLost)
    }

    private fun completeSegmentStart(recorder: RecordingEncoder, output: RecordingOutputHandle,
                                     cfg: RecorderConfig, generation: Long, sessionToken: Long,
                                     previewTarget: Surface?, cadencePlan: CaptureCadencePlan) {
        if (!statePublication.allowsOpen(state.recordingSessionId)) return // Stop/release already queued its cleanup.
        if (!cameraRecovery.mayStartRecording(sessionToken, SystemClock.elapsedRealtime())) {
            cameraUnavailable("RECOVERY_START_AUTHORITY_EXPIRED")
            return
        }
        recorder.start()
        if (recorder is ContinuousVideoRecorder) continuousRotationPending = false
        cancelSetupWatchdog()
        recording = true
        segmentRecordingStartedAtEpochMs = System.currentTimeMillis()
        segmentRecordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val recoveryOutcome = cameraRecovery.markRecordingStarted(
            sessionToken,
            segmentRecordingStartedAtElapsedMs!!,
        )
        if (recoveryOutcome == CameraRecoveryStateMachine.RecordingStartOutcome.STALE) {
            beginResourceClose("RECOVERY_ATTEMPT_TERMINAL",
                cameraRecovery.snapshot.lastReason ?: "RECOVERY_AUTHORITY_EXPIRED", outputLost = false)
            return
        }
        updateState(
            state.copy(
                status = RecorderStatus.RECORDING,
                segmentNumber = segmentNumber,
                currentFile = output.displayName,
                segmentStartedAtEpochMs = segmentRecordingStartedAtEpochMs,
                nativeFileSwitches = (recorder as? NativeFileRecordingEncoder)?.rotation?.switches ?: 0,
                nativePendingFiles = (recorder as? NativeFileRecordingEncoder)?.rotation?.owned?.size ?: 0,
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
        com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordRun(context, state)
        if (recorder is NativeFileRecordingEncoder) {
            val nativeOutput = output as UsbMediaStoreRecordingOutputHandle
            nativeSidecars[nativeOutput]?.let { seed ->
                val started = seed.copy(startedAtEpochMs = segmentRecordingStartedAtEpochMs,
                    startedAtElapsedRealtimeMs = segmentRecordingStartedAtElapsedMs)
                nativeSidecars[nativeOutput] = started
                runIo { runCatching { nativeOutput.checkpointNative(started) }.onFailure {
                    cameraHandler?.post { if (recordingEncoder === recorder && !stopping)
                        failContinuousRun("NATIVE_CHECKPOINT_FAILED", outputLost = false) }
                } }
            }
            scheduleNativeWatchdog(recorder, NativeFileRotationPolicy.FILE_WATCHDOG_MS)
        } else scheduleTimeout(generation, segmentWallSeconds * 1000L)
        if (output is UsbMediaStoreRecordingOutputHandle) {
            scheduleUsbPresenceWatchdog(generation, output)
        }
    }

    private fun fallbackToRecorderOnly(
        generation: Long,
        partial: File,
        recorder: RecordingEncoder,
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
        localRecorder: RecordingEncoder,
        sessionToken: Long,
    ): Boolean = SegmentGuardPolicy.ownsSetup(generation, segmentGeneration, currentPartial, partial) &&
        sessionToken == manualSessionGeneration &&
        !stopping && !releasing && !releaseRequested && !cleanupUnconfirmed && closeTransaction == null &&
        recordingEncoder === localRecorder && statePublication.allowsOpen(state.recordingSessionId)

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
        setTag(CaptureOutputTag(targets.any { it === recordingPreviewSurface }))
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
        val plan = ContinuousVideoTiming.capturePlan(cfg)
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
                ).also { captureLedger.submitted(session, it) }
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
        val plan = ContinuousVideoTiming.capturePlan(cfg)
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
                ).also { captureLedger.submitted(session, it) }
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
            ).also { captureLedger.submitted(session, it) }
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
        captureLedger.submitted(session, submittedSequenceId)
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
        captureLedger.sequenceEnded(session, sequenceId)
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
                captureLedger.submitted(session, submittedSequenceId)
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
        captureLedger.sequenceEnded(session, sequenceId)
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
    private fun createFrameCaptureCallback(generation: Long?): CameraCaptureSession.CaptureCallback {
        val callbackGeneration = segmentGeneration
        val callbackEncoder = recordingEncoder
        fun currentCallbackGeneration(): Long = if ((callbackEncoder is ContinuousVideoRecorder || callbackEncoder is ProductContinuousRecorder) && recordingEncoder === callbackEncoder)
            segmentGeneration else callbackGeneration
        val preview = recordingPreviewSurface
        val encoder = activeEncoderSurface
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val activeGeneration = currentCallbackGeneration()
                if (activeGeneration != segmentGeneration || captureSession !== session || !recording) return
                (request.tag as? CaptureOutputTag)?.let { tag ->
                    previewCaptureTracker.completed(activeGeneration, tag.includesPreview,
                        result.get(CaptureResult.SENSOR_TIMESTAMP), SystemClock.elapsedRealtime())
                }
                if (generation != null) recordFrameResult(activeGeneration, result)
                else publishCaptureEvidence()
            }
            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                val activeGeneration = currentCallbackGeneration()
                if (activeGeneration != segmentGeneration || captureSession !== session || !recording) return
                val role = when (target) { preview -> CaptureOutputRole.PREVIEW; encoder -> CaptureOutputRole.ENCODER; else -> CaptureOutputRole.OTHER }
                previewCaptureTracker.bufferLost(activeGeneration, role, SystemClock.elapsedRealtime())
                publishCaptureEvidence()
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                val activeGeneration = currentCallbackGeneration()
                if (activeGeneration != segmentGeneration || captureSession !== session || !recording) return
                previewCaptureTracker.failed(activeGeneration, failure.reason)
                publishCaptureEvidence()
            }
            override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                captureLedger.sequenceEnded(session, sequenceId)
                if (pendingTimeLapseFinalize?.session === session) closeTransaction?.sequenceEnded(sequenceId)
            }
            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                captureLedger.sequenceEnded(session, sequenceId)
                if (pendingTimeLapseFinalize?.session === session) closeTransaction?.sequenceEnded(sequenceId)
            }
        }
    }

    private fun recordFrameResult(generation: Long, result: TotalCaptureResult) {
        if (generation != segmentGeneration || !recording) return
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: run { publishCaptureEvidence(); return }
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
        publishCaptureEvidence()
    }

    private fun publishCaptureEvidence() {
        RecorderCaptureEvidence.update(CaptureEvidence(recordingSessionIdentity.requireCurrentId(), segmentNumber,
            frameStats, lastFrameReceivedAtElapsedMs, previewCaptureTracker.stats,
            (recordingEncoder as? ProductContinuousRecorder)?.evidence() ?: (recordingEncoder as? ContinuousVideoRecorder)?.evidence()))
    }

    private fun failSegmentStart(generation: Long, partial: File, message: String, originalError: Throwable? = null) {
        if (generation != segmentGeneration || currentPartial !== partial) return
        if (recordingEncoder is NativeFileRecordingEncoder || recordingEncoder is ProductContinuousRecorder) {
            failContinuousRun("CONTINUOUS_START_FAILED:$message", outputLost = false,
                originalError = originalError, failureStage = ContinuousFailureStage.SESSION_SETUP)
            return
        }
        beginResourceClose("START_FAILED", message, outputLost = false)
    }

    /**
     * Camera-thread part only: cancel timeout, stop recorder, rename/quarantine,
     * capture an immutable snapshot, then schedule the next segment immediately.
     * The async metadata/health/sidecar work never gates the next segment.
     */
    private fun finalizeCurrentSegment(reason: String, forcedError: String?) {
        val product = recordingEncoder as? ProductContinuousRecorder
        if (reason == "TIMEOUT" && forcedError == null && product != null &&
            !stopping && !releasing && !releaseRequested && closeTransaction == null) {
            if (reconcileVehiclePowerSnapshot(source = "CONTINUOUS_FILE_BOUNDARY")) return
            cancelTimeout()
            runCatching { product.requestCut() }.onFailure {
                failContinuousRun(it.message ?: "PRODUCT_CUT_FAILED", outputLost = false,
                    originalError = it, failureStage = ContinuousFailureStage.CUT_REQUEST)
            }
            return
        }
        val continuous = recordingEncoder as? ContinuousVideoRecorder
        if (reason == "TIMEOUT" && forcedError == null && continuous != null &&
            !stopping && !releasing && !releaseRequested && closeTransaction == null) {
            if (previewReplacementWatchdogToken != null) { scheduleTimeout(segmentGeneration, 250); return }
            rotateContinuousFile(continuous)
        } else beginResourceClose(reason, forcedError, outputLost = false)
    }

    private fun rotateContinuousFile(encoder: ContinuousVideoRecorder) {
        if (continuousRotationPending) return
        val output = currentOutput ?: return
        continuousRotationPending = true
        val token = ++continuousRotationToken
        cancelTimeout()
        val watchdog = Runnable {
            if (continuousRotationPending && token == continuousRotationToken) {
                failContinuousRun("CONTINUOUS_CUT_TIMEOUT", outputLost = false)
            }
        }
        continuousRotationWatchdog = watchdog
        cameraHandler?.postDelayed(watchdog, 8_000)
        encoder.requestCut { summary -> cameraHandler?.post {
            if (!ContinuousRecordingPolicy.mayCommitRotation(token == continuousRotationToken,
                    recordingEncoder === encoder, currentOutput === output,
                    stopping || releasing || releaseRequested, closeTransaction != null || cleanupUnconfirmed)) return@post
            continuousRotationWatchdog?.let { cameraHandler?.removeCallbacks(it) }
            continuousRotationWatchdog = null
            completedEncodedSegment = summary
            EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CONTINUOUS_FILE_CLOSED", payload = mapOf(
                "segment" to segmentNumber.toString(), "frames" to summary.frames.toString(),
                "captureSessionRevision" to captureSessionRevision.toString()))
            // The writer acknowledged muxer release and descriptor close. Camera/codec remain owned.
            commitFinalizeCurrentSegment("TIMEOUT", null, emptyMap())
        } }
    }

    private fun beginResourceClose(reason: String, forcedError: String?, outputLost: Boolean) {
        (recordingEncoder as? ProductContinuousRecorder)?.let {
            freezeProductProtection(currentOutput); it.prepareForStop()
        }
        if (recordingEncoder != null)
            com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordRun(context, state)
        nativeRotationWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        nativeRotationWatchdog = null
        (recordingEncoder as? NativeFileRecordingEncoder)?.rotation?.stop()
        continuousRotationPending = false
        continuousRotationToken++
        continuousRotationWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        continuousRotationWatchdog = null
        if (outputLost) recordingEncoder?.abandonOutput()
        val terminal = stopping || releasing || releaseRequested || reason == "STOP"
        pendingTimeLapseFinalize?.let {
            it.reason = RecorderClosePolicy.mergeReason(it.reason, reason, terminal)
            if (forcedError != null) it.forcedError = forcedError
            closeTransaction?.merge(terminal = terminal, outputLost = outputLost,
                requireDeviceClose = RecorderClosePolicy.isInterruption(reason))
            return
        }
        val closeRequestedElapsedMs = SystemClock.elapsedRealtime()
        val closeSessionId = state.recordingSessionId
        val lastFrameAgeMs = lastFrameReceivedAtElapsedMs?.let { closeRequestedElapsedMs - it }
        cancelSetupWatchdog()
        cancelTimeout()
        cancelUsbPresenceWatchdog()
        cancelPreviewReplacementWatchdog()
        cancelTimeLapseQuiesceWatchdog()
        cancelPacedEncoderCaptures()
        timeLapseQuiesced = true
        val session = captureSession ?: pacedCaptureInFlightSession
        // A replaced preview session can still be using this encoder. If it has
        // not drained, require a device fence rather than releasing its input.
        val otherProducerInFlight = captureLedger.hasOtherInFlight(session)
        val camera = cameraDevice
        val recorder = recordingEncoder
        val output = currentOutput
        val encoder = activeEncoderSurface
        val hasPartial = currentPartial != null
        val pending = PendingTimeLapseFinalize(reason, forcedError, segmentGeneration,
            session, camera, ++timeLapseTeardownToken)
        pendingTimeLapseFinalize = pending
        val hold = Any()
        val traceId = processStartId + "-" + System.identityHashCode(this) + "-" + pending.token
        val closeFacts = mapOf("closeId" to traceId,
            "deviceOwnerId" to (camera?.let { System.identityHashCode(it).toString() } ?: "NONE"),
            "recordingSessionId" to (closeSessionId ?: "NONE"),
            "generation" to manualSessionGeneration.toString(), "cameraGeneration" to openGeneration.toString(),
            "closeRequestedElapsedMs" to closeRequestedElapsedMs.toString(), "finalizeReason" to reason)
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CLOSE_REQUESTED", payload = closeFacts)
        val tx = CaptureCloseTransaction(
            control = CaptureCleanupRuntime.control,
            native = HandlerCloseDispatcher(requireNotNull(cameraHandler)),
            output = ExecutorCloseDispatcher(ioExecutor),
            resources = object : CaptureCloseResources {
                override fun stopRepeating() { diagnosticCall("STOP_REPEATING") { session?.stopRepeating() } }
                override fun abortCaptures() { diagnosticCall("ABORT_CAPTURES") { session?.abortCaptures() } }
                override fun closeSession() { diagnosticCall("SESSION_CLOSE") { session?.close() } }
                override fun closeDevice() { diagnosticCall("DEVICE_CLOSE") { camera?.close() } }
                override fun stopRecorder() { diagnosticCall("RECORDER_STOP") { recorder?.stop() } }
                override fun resetRecorder() { diagnosticCall("RECORDER_RESET") { recorder?.reset() } }
                override fun releaseRecorder() {
                    recorder?.release()
                    if (recorder !is ContinuousVideoRecorder && recorder !is ProductContinuousRecorder) encoder?.release()
                }
                override fun closeOutput(lost: Boolean) {
                    if (recorder is ProductContinuousRecorder) Unit
                    else if (recorder is NativeFileRecordingEncoder) recorder.closeOutputs(lost)
                    else if (lost && output is UsbMediaStoreRecordingOutputHandle) output.abandonUnavailableTarget()
                    else output?.close()
                }
            },
            hasSession = session != null, hasDevice = camera != null, wasRecording = recording,
            sequences = captureLedger.pending(session),
            terminal = terminal || otherProducerInFlight, lost = outputLost,
            preferDeviceClose = RecorderClosePolicy.needsDeviceFence(reason, otherProducerInFlight),
            trace = { step, detail ->
                CaptureCleanupRuntime.trace(traceId, step, detail)
                val edge = when {
                    step == "STAGE" && detail == "CLOSING_DEVICE" -> "DEVICE_CLOSE_QUEUED"
                    step == "NATIVE_ENTER" && detail == "deviceClose" -> "DEVICE_CLOSE_CALL_ENTER"
                    step == "NATIVE_RETURN" && detail == "deviceClose" -> "DEVICE_CLOSE_CALL_RETURN"
                    step == "DEVICE_CLOSED" -> "DEVICE_CLOSE_ACK_PROCESSED"
                    else -> null
                }
                if (edge != null) EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CLOSE_PROGRESS",
                    payload = closeFacts + mapOf("closeStep" to edge, "stepElapsedMs" to SystemClock.elapsedRealtime().toString()))
            },
            unconfirmed = unconfirmed@{ failure ->
                if (!statePublication.isCurrent(closeSessionId)) return@unconfirmed
                // Revoke continuation on the control thread, even if native work is blocked.
                cleanupUnconfirmed = true
                stopping = true
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CLOSE_UNCONFIRMED",
                    payload = closeFacts + mapOf("errorCode" to failure))
                statePublication.unconfirmed(failure, Utils.t("Camera cleanup is unconfirmed. Automatic reopening stopped; copy diagnostics.", "相机收尾未确认；已停止自动重开，请复制诊断"), closeSessionId)
            },
            completed = { result ->
                cameraHandler?.post {
                    if (pendingTimeLapseFinalize !== pending) {
                        CaptureCleanupRuntime.settled(hold, result.safeToContinue)
                        return@post
                    }
                    if (result.terminal) stopping = true
                    if (!result.safeToContinue) return@post
                    CaptureCleanupRuntime.trace(traceId, "RECORDER_CLOSE_SETTLED",
                        "durationMs=" + (SystemClock.elapsedRealtime() - closeRequestedElapsedMs) +
                            " terminal=" + (result.terminal || stopping) + " outputLost=" + result.outputLost)
                    EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CLOSE_SETTLED", payload = closeFacts + mapOf(
                        "safeToContinue" to result.safeToContinue.toString(), "deviceClosed" to result.deviceClosed.toString(),
                        "terminal" to (result.terminal || stopping).toString(), "outputLost" to result.outputLost.toString(),
                        "closeDurationMs" to (SystemClock.elapsedRealtime() - closeRequestedElapsedMs).toString()))
                    completedEncodedSegment = (recorder as? ContinuousVideoRecorder)?.lastCompleted
                    recordingEncoder = null
                    if (captureSession === session) captureSession = null
                    if (result.deviceClosed && cameraDevice === camera) cameraDevice = null
                    if (activeEncoderSurface === encoder) activeEncoderSurface = null
                    pacedCaptureInFlightSequenceId = null
                    pacedCaptureInFlightSession = null
                    pendingTimeLapseFinalize = null
                    closeTransaction = null
                    cleanupUnconfirmed = false // Only after the real device/recorder/output acknowledgements.
                    CaptureCleanupRuntime.settled(hold, true)
                    statePublication.cleanupSettled(closeSessionId)
                    if (recorder is NativeFileRecordingEncoder) {
                        if (!result.outputLost) flushNativeFilesAfterStop(recorder, output)
                        else nativeSidecars.clear() // Durable checkpoints retain the disconnected USB files.
                    }
                    if (recorder is ProductContinuousRecorder) {
                        currentPartial = null; currentOutput = null; recording = false; currentSegmentCanary = false
                        productNextPreparing = false
                        segmentStartedAtEpochMs = null; segmentStartedAtElapsedMs = null
                        segmentRecordingStartedAtEpochMs = null; segmentRecordingStartedAtElapsedMs = null
                        productFiles.clear()
                        val nextReason = if (RecorderClosePolicy.isInterruption(pending.reason) && result.errors.isNotEmpty())
                            "RECOVERY_ATTEMPT_TERMINAL" else pending.reason
                        afterFinalize(nextReason, result.errors.isEmpty(), pending.forcedError ?: result.errors.values.firstOrNull())
                    } else if (result.outputLost) {
                        currentPartial = null; currentOutput = null; recording = false
                        currentSegmentCanary = false
                        segmentStartedAtEpochMs = null; segmentStartedAtElapsedMs = null
                        segmentRecordingStartedAtEpochMs = null; segmentRecordingStartedAtElapsedMs = null
                        updateState(state.copy(currentFile = null, segmentStartedAtEpochMs = null))
                        afterFinalize(pending.reason, true, pending.forcedError)
                    } else if (hasPartial) {
                        commitFinalizeCurrentSegment(pending.reason, pending.forcedError, result.errors)
                    } else if (RecorderClosePolicy.isInterruption(pending.reason)) {
                        // An onError may precede the first encoder segment. Its device
                        // still has to settle before recovery/termination can continue.
                        afterFinalize(pending.reason, true, pending.forcedError)
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
            captureLedger.sessionClosed(session)
        }
        if (pendingTimeLapseFinalize?.session === session) {
            if (evidence == "SESSION_CLOSED") closeTransaction?.sessionClosed()
            else closeTransaction?.sessionReady()
            return
        }
        if (evidence == "SESSION_CLOSED") {
            if (captureSession === session) captureSession = null
            // onClosed does not finish in-flight captures. Their sequence callbacks
            // (or the owning device close) still own the paced-capture lease.
        }
    }

    private fun onCameraDeviceProducerClosed(camera: CameraDevice) {
        CaptureCleanupRuntime.deviceClosed(camera)
        if (cameraDevice === camera || pendingTimeLapseFinalize?.camera === camera) {
            captureLedger.deviceClosed()
            previewSurfacesAwaitingDevice.forEach { releasePreviewWrapper(it) }
            previewSurfacesAwaitingDevice.clear()
        }
        if (pendingTimeLapseFinalize?.camera === camera) closeTransaction?.deviceClosed()
        if (cameraDevice === camera) cameraDevice = null
        if (releaseRequested) { cameraOpenInFlight = false; maybeFinishDisposal() }
    }

    private fun commitFinalizeCurrentSegment(reason: String, forcedError: String?, recorderErrors: Map<String, String>) {
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
        val encodedSummary = completedEncodedSegment?.takeIf { it.output === output }
        completedEncodedSegment = null
        val requestedAtEpoch = segmentStartedAtEpochMs
        val requestedAtElapsed = segmentStartedAtElapsedMs
        val actualStartedEpoch = encodedSummary?.firstEpochMs ?: segmentRecordingStartedAtEpochMs ?: requestedAtEpoch
        val actualStartedElapsed = encodedSummary?.firstElapsedMs ?: segmentRecordingStartedAtElapsedMs ?: requestedAtElapsed
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
        val captureContinues = recordingEncoder is ContinuousVideoRecorder
        recording = captureContinues
        segmentStartedAtEpochMs = null
        segmentStartedAtElapsedMs = null
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        updateState(state.copy(status = if (captureContinues) RecorderStatus.RECORDING else RecorderStatus.FINALIZING))

        val recorderStopError = recorderErrors["stop"] ?: recorderErrors["reset"] ?: recorderErrors["outputSync"]
        // Either the close transaction settled all resources, or the continuous writer
        // acknowledged this file's muxer/descriptor while retaining the camera and encoder.
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
                stoppedEpoch = encodedSummary?.endedEpochMs ?: System.currentTimeMillis(),
                stoppedElapsed = encodedSummary?.endedElapsedMs ?: SystemClock.elapsedRealtime(),
            )
            return
        }
        if (recordingEncoder !is ContinuousVideoRecorder) activeEncoderSurface = null

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
        val stoppedEpoch = encodedSummary?.endedEpochMs ?: System.currentTimeMillis()
        val stoppedElapsed = encodedSummary?.endedElapsedMs ?: SystemClock.elapsedRealtime()
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
                "finalizeDurationMs" to (SystemClock.elapsedRealtime() - finalizeEnteredElapsed).toString(),
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
                val health = if (snapshot.continuousTimeline == null) sampleAndAnalyzeFrames(finalFile) else null
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
        if (recordingEncoder is NativeFileRecordingEncoder) {
            failContinuousRun("NATIVE_USB_OUTPUT_LOST", outputLost = true)
            return
        }
        CaptureCleanupRuntime.trace("usb-" + output.pendingVideo.operationId, "OUTPUT_LOST",
            source + ":" + reason + "; pending URI retained")
        beginResourceClose("USB_OUTPUT_LOST", reason, outputLost = true)
        // Mark the lost descriptor FIRST. Vehicle-away arbitration below may
        // merge terminal intent, but must never enqueue stop/reset on a lost FD.
        consumeUsbFallback(reason)
    }

    private fun consumeUsbFallback(reason: String): Boolean {
        if (diagnosticScope != null) {
            stopSessionOnCameraThread("STOP", "PREFLIGHT_USB_STOP", "PREFLIGHT_USB_UNAVAILABLE", false)
            return false
        }
        if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return false
        if (reconcileVehiclePowerSnapshot(source = "USB_FALLBACK", usbFallbackReason = reason)) return false
        if (stopping || releasing || releaseRequested || cleanupUnconfirmed) return false
        if (sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE &&
            ProductContinuousPolicy.blocksUsbFallback(config?.sharedInputRecordingEnabled == true, cameraRecovery.snapshot.phase)) {
            stopSessionOnCameraThread("STOP", "USB_CONTINUATION_BLOCKED", "USB_TARGET_UNAVAILABLE:$reason", false)
            return false
        }
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
        stoppedEpoch: Long,
        stoppedElapsed: Long,
    ) {
        val gap = SegmentGapPolicy.gapMs(previousSegmentStoppedElapsedMs, actualStartedElapsed)
        previousSegmentStoppedElapsedMs = stoppedElapsed
        val preserveNative = nativeSidecars.remove(output) != null
        if (recorderStopError != null) {
            if (preserveNative) {
                // Retain this file's checkpoint. Recorder failure must never select an internal fallback.
                currentSegmentCanary = false
                updateState(state.copy(lastError = "NATIVE_RECORDER_FINISH_FAILED:$recorderStopError"))
                com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordFault(context, state)
                afterFinalize(reason, success = false, error = recorderStopError)
                return
            }
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
        if (preserveNative) {
            NativeUsbPublication.submit(context, output, sidecar) {
                if (consumesPendingIncident && incidentTag != null) incidentStore.consume(incidentTag.eventId)
            }
            currentSegmentCanary = false
            afterFinalize(reason, success = true, error = forcedError)
            return
        }
        val canary = currentSegmentCanary
        currentSegmentCanary = false
        pendingUsbCommits++
        if (canary) {
            updateState(state.copy(status = if (recordingEncoder is ContinuousVideoRecorder) RecorderStatus.RECORDING else RecorderStatus.FINALIZING,
                message = "Verifying first USB segment"))
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
                    runCatching { diagnosticScope?.committed(output) }.onFailure {
                        postCamera { stopSessionOnCameraThread("STOP", "PREFLIGHT_COMMIT_STOP", "PREFLIGHT_EVIDENCE_FAILED", false) }
                    }
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
                            lastError = if (stopping) state.lastError else null,
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
                "finalizeDurationMs" to (SystemClock.elapsedRealtime() - finalizeEnteredElapsed).toString(),
            ),
        )
    }

    private fun afterFinalize(reason: String, success: Boolean, error: String?) {
        if (modeSwitchHandoff && (!success || error != null) && modeHandoffFailure == null) {
            modeHandoffFailure = error ?: "FINALIZE_FAILED"
        }
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
            // A disconnect can arrive after a rotation transaction has already
            // completed on the control thread. Fence that remaining device in a
            // second nonterminal transaction; never enter WAITING ahead of it.
            if (hasOwnedCaptureResources()) beginResourceClose("CAMERA_LOSS", error, outputLost = false)
            else enterCameraRecoveryWaiting(error ?: "CAMERA_LOSS")
            return
        }
        if (reason == "RECOVERY_ATTEMPT_CONTENTION" || reason == "RECOVERY_ATTEMPT_TERMINAL") {
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
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CAMERA_LOSS_OBSERVED", payload = mapOf(
            "recordingSessionId" to (state.recordingSessionId ?: "NONE"),
            "generation" to manualSessionGeneration.toString(), "cameraGeneration" to openGeneration.toString(),
            "errorCode" to message, "recoverableContention" to recoverableContention.toString(),
        ))
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
        // A confirmed Camera2 reclaim follows the same fenced close/recovery path for both
        // encoders. A GL/codec timeout still uses failContinuousRun and never authorizes reopen.
        if (!recoverableContention) {
            cameraRecovery.terminateManualSession(token, message)
            statePublication.terminate(message, "CAMERA_LOSS_TERMINAL", message)
        }
        val wasResuming = cameraRecovery.snapshot.phase == CameraRecoveryPhase.RESUMING
        if (wasResuming) {
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_CAMERA_RECOVERY_ATTEMPT_LOST",
                message,
                null,
            )
            if (currentPartial != null || hasOwnedCaptureResources()) {
                finalizeCurrentSegment(
                    if (recoverableContention) {
                        "RECOVERY_ATTEMPT_CONTENTION"
                    } else {
                        "RECOVERY_ATTEMPT_TERMINAL"
                    },
                    message,
                )
            } else {
                cameraUnavailable(message, recoverableContention)
            }
            return
        }
        val recoveryArmed = diagnosticScope == null && BuildConfig.CAMERA_INTERRUPTION_RECOVERY_ENABLED &&
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
        if (currentPartial != null || hasOwnedCaptureResources()) {
            finalizeCurrentSegment("CAMERA_LOSS", message)
        } else {
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
        if (hasOwnedCaptureResources()) {
            beginResourceClose(
                if (recoverableContention && cameraRecovery.snapshot.phase == CameraRecoveryPhase.RESUMING)
                    "RECOVERY_ATTEMPT_CONTENTION" else "RECOVERY_ATTEMPT_TERMINAL",
                message, outputLost = false,
            )
            return
        }
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
        if (hasOwnedCaptureResources() || cleanupUnconfirmed) {
            terminateCameraUnavailable("RECOVERY_CLEANUP_UNCONFIRMED")
            return
        }
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
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CAMERA_RECOVERY_ENDED",
            payload = recoveryDiagnosticPayload() + mapOf("reason" to message))
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
            if (state.status == RecorderStatus.WAITING_CAMERA) updateState(state)
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
        if (action.generation != manualSessionGeneration) return
        if (reconcileVehiclePowerSnapshot(source = "RECOVERY_ATTEMPT")) return
        if (applyVehicleAwayAction(vehicleAway.onCameraLoss(action.generation))) return
        val cfg = config
        val expectedCameraId = cameraRecovery.snapshot.targetCameraId
        if (action.generation != manualSessionGeneration || stopping || releasing || releaseRequested ||
            cfg == null || cfg.cameraId != expectedCameraId || recording || startInFlight ||
            cameraOpenInFlight || cameraRecovery.snapshot.phase != CameraRecoveryPhase.RESUMING ||
            !statePublication.allowsOpen(state.recordingSessionId) || !cameraRecovery.snapshot.resumeAllowed
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
        if (hasOwnedCaptureResources() || cleanupUnconfirmed ||
            !com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock.cameraIdle(cfg.cameraId)) {
            terminateCameraUnavailable("RECOVERY_CLEANUP_UNCONFIRMED")
            return
        }
        if (sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE &&
            (usbFallbackPendingReason != null || activeUsbTarget?.let {
                UsbExportVolumeResolver.isRemovableVolumeMounted(context, it.storageUuid)
            } != true)) {
            terminateCameraUnavailable("RECOVERY_USB_TARGET_UNAVAILABLE")
            return
        }
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
        scheduleCameraRecoveryTimer()
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
            "waitingForAvailability" to recovery.waitingForAvailability.toString(),
            "occupancyDeadlineAtMs" to (recovery.occupancyDeadlineAtMs?.toString() ?: "-"),
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
        val ownerSession = recordingSessionIdentity.requireCurrentId()
        val currentSegment = segmentNumber
        val generation = manualSessionGeneration
        val target = activeUsbTarget.takeIf { sessionStoragePlan?.active?.kind == RecordingStorageKind.USB_MEDIASTORE }
        // These files are recorder-owned until Stop, so update only their local metadata checkpoints.
        val nativePrevious = nativeSidecars.entries.filter { it.key !== currentOutput &&
            it.value.finalizeReason == "NATIVE_FILE_SWITCH" && it.value.recordingSessionId == ownerSession &&
            it.value.segmentNumber < currentSegment && (it.value.eventId == null || it.value.eventId == eventId) }
            .sortedByDescending { it.value.segmentNumber }.take(count).map { (output, sidecar) ->
                output to sidecar.copy(protected = true, eventId = eventId, eventRequestedAtEpochMs = requestedAtEpochMs,
                    eventRole = IncidentProtectionStore.ROLE_PREVIOUS)
            }
        val nativeCurrent = (currentOutput as? UsbMediaStoreRecordingOutputHandle)?.let { output ->
            nativeSidecars[output]?.let { output to it.copy(protected = protectedPending,
                eventId = currentIncidentTag?.eventId, eventRequestedAtEpochMs = currentIncidentTag?.requestedAtEpochMs,
                eventRole = currentIncidentTag?.role) }
        }
        val checkpoints = nativePrevious + listOfNotNull(nativeCurrent)
        checkpoints.forEach { (output, sidecar) -> nativeSidecars[output] = sidecar }
        runIo {
            if (target != null) {
                val result = runCatching {
                    checkpoints.forEach { (output, sidecar) -> output.checkpointNative(sidecar) }
                    val committed = com.dante.zeekrcapabilitylab.usbexport.UsbIncidentMarkers(context)
                        .protectPrevious(target, ownerSession, currentSegment, eventId, requestedAtEpochMs,
                            (count - nativePrevious.size).coerceAtLeast(0))
                    committed.copy(protectedCount = committed.protectedCount + nativePrevious.size)
                }
                cameraHandler?.post {
                    if (generation != manualSessionGeneration) return@post
                    val message = result.fold({ value ->
                        Utils.t("Protected {0} earlier USB clips ({1} portable). Current/next clips save when complete.",
                            "已保护先前 {0} 段 USB 视频（{1} 段已同步标记）。当前及后一段将在完成时保存。", value.protectedCount, value.portableCount)
                    }, { Utils.t("Earlier USB clips could not be protected. Check the USB drive.", "先前 USB 片段保护失败，请检查 USB。") })
                    updateState(state.copy(incidentMessage = message, libraryRevision = state.libraryRevision + 1))
                }
                return@runIo
            }
            val recent = segmentsDir.listFiles()
                ?.filter { SegmentNaming.isFinalMp4(it.name) }
                .orEmpty()
            var protectedCount = 0
            synchronized(RecorderStorageLock.lock) {
                val candidates = recent.mapNotNull { file -> SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(file))?.let {
                    IncidentCandidate(file.absolutePath, it.recordingSessionId, it.segmentNumber, it.eventId)
                } }
                val selected = IncidentSelection.previous(candidates, ownerSession, currentSegment, eventId, count)
                recent.filter { it.absolutePath in selected }.forEach { file ->
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
                if (generation != manualSessionGeneration) return@post
                updateState(
                    state.copy(incidentMessage = Utils.t("Protected {0} earlier clips. Current/next clips save when complete.", "已保护先前 {0} 段视频。当前及后一段将在完成时保存。", protectedCount), libraryRevision = state.libraryRevision + 1),
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
                encodedDurationMs = actualTrack?.durationMs ?: s.continuousTimeline?.let { (it.endExclusivePtsUs - it.firstPtsUs) / 1000 },
            )
        } else {
            null
        }
        val sidecar = SegmentSidecar(
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
            captureSubmissionMode = if (s.continuousTimeline != null) CaptureSubmissionMode.REPEATING_ENCODER else TimeLapseCaptureCadencePolicy.plan(
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
        return s.continuousTimeline?.let { sidecar.withContinuousRaster(ProductContinuousRecorder.RASTER, it) } ?: sidecar
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
        if (recordingEncoder != null || captureSession != null || cameraDevice != null || activeEncoderSurface != null) {
            CaptureCleanupRuntime.trace("invariant-" + System.identityHashCode(this), "OWNED_CLEANUP_REQUIRED", reason)
            closeCamera()
        }
    }

    private fun hasOwnedCaptureResources(): Boolean = closeTransaction != null ||
        cameraDevice != null || captureSession != null || recordingEncoder != null || currentOutput != null

    private fun closeCamera() {
        cancelPreviewReplacementWatchdog()
        cancelPacedEncoderCaptures()
        cancelTimeLapseQuiesceWatchdog()
        if (closeTransaction != null) {
            closeTransaction?.merge(terminal = true)
            return
        }
        if (cameraDevice != null || captureSession != null || recordingEncoder != null || currentOutput != null) {
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
        (recordingEncoder as? ProductContinuousRecorder)?.let { shared ->
            shared.setPreview(null, ::onProductPreviewReleased)
            return
        }
        val producer = captureSession
        when {
            producer != null -> captureLedger.retire(producer, surface)
            cameraDevice != null -> previewSurfacesAwaitingDevice.add(surface)
            else -> releasePreviewWrapper(surface)
        }
    }

    private fun releasePendingRecordingPreviewSurface() {
        val surface = pendingRecordingPreviewSurface
        pendingRecordingPreviewSurface = null
        if (surface != null) releasePreviewWrapper(surface)
    }

    private fun releasePreviewWrapper(surface: Surface) {
        runCatching { previewReleases.release(surface) }.onFailure {
            CaptureCleanupRuntime.trace("preview-" + System.identityHashCode(this), "UNCONFIRMED", "Preview wrapper release failed")
        }
    }

    private fun onProductPreviewReleased(surface: Surface) {
        cameraHandler?.post {
            if (recordingPreviewSurface === surface) recordingPreviewSurface = null
            releasePreviewWrapper(surface)
        }
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
        val continuousTimeline: io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline? = null,
        val consumesPendingIncident: Boolean = false,
    )
}
