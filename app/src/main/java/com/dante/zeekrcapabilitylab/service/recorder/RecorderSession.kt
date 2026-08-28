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
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.CameraRuntime
import com.dante.zeekrcapabilitylab.product.SettingsStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
) {
    private val segmentsDir = File(context.filesDir, "recordings/segments").apply { mkdirs() }
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
    private var segmentNumber = 0
    private var recording = false
    private var stopping = false
    private var releasing = false
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
    private var segmentGeneration = 0L
    private var previewReplacementGeneration = 0L
    private var finalizeSequence = 0L
    private var openGeneration = 0L
    private var manualSessionGeneration = 0L
    private var timeoutRunnable: Runnable? = null
    private var openWatchdogRunnable: Runnable? = null
    private var setupWatchdogRunnable: Runnable? = null
    private var previewReplacementWatchdogRunnable: Runnable? = null
    private var previewReplacementWatchdogToken: Long? = null
    private val cameraRecovery = CameraRecoveryStateMachine()
    private var cameraRecoveryTimerRunnable: Runnable? = null
    private val vehicleAway = VehicleAwayStateMachine()
    private var vehicleAwayTimerRunnable: Runnable? = null
    /** Finalized mp4 names whose sidecar/health analysis is still in flight. */
    private val pendingSidecarFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var state = RecorderState()
    private var cameraDiagnosticsRegistered = false
    private var diagnosticsActiveCameraId: String? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null

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
            this.previewOutputDesired = true
            releaseRecordingPreviewSurface()
            releasePendingRecordingPreviewSurface()
            this.recordingPreviewSurface = previewSurface?.takeIf { it.isValid }
            if (this.recordingPreviewSurface == null) runCatching { previewSurface?.release() }
            this.stopping = false
            this.releasing = false
            this.protectedPending = false
            this.currentIncidentTag = null
            this.currentConsumesPendingIncident = false
            this.segmentNumber = 0
            this.previousSegmentStoppedElapsedMs = null
            val recordingSessionId = recordingSessionIdentity.beginNewSession()
            val sessionStartedAtEpochMs = System.currentTimeMillis()
            manualSessionGeneration++
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
            cancelCameraRecoveryTimer()
            cancelVehicleAwayTimer()
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
                    segmentNumber = 0,
                    currentFile = null,
                    segmentStartedAtEpochMs = null,
                    lastError = null,
                    lastSidecarPath = null,
                    message = "Starting",
                    previewRequested = this.recordingPreviewSurface != null,
                    previewActive = false,
                    previewFallbackUsed = false,
                    recordingSessionId = recordingSessionId,
                    sessionStartedAtEpochMs = sessionStartedAtEpochMs,
                    recordingMode = config.recordingMode,
                    timeLapseMultiplier = config.timeLapseMultiplier,
                    effectiveSegmentSeconds = config.effectiveSegmentSeconds(),
                ),
            )
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_START",
                payload = mapOf(
                    "cameraId" to config.cameraId,
                    "profile" to config.profile.key,
                    "segmentSeconds" to config.segmentSeconds.toString(),
                    "effectiveSegmentSeconds" to config.effectiveSegmentSeconds().toString(),
                    "recordingMode" to config.recordingMode.name,
                    "timeLapseMultiplier" to config.timeLapseMultiplier.toString(),
                    "captureRateFps" to (config.captureRateFpsOrNull()?.toString() ?: "-"),
                    "storageLimitBytes" to config.storageLimitBytes.toString(),
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
                recording = recording,
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
            runCatching { captureSession?.stopRepeating() }
            runCatching { captureSession?.close() }
            captureSession = null
            if (previous !== replacement) runCatching { previous?.release() }
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
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(encoder)
                        previewTarget?.let(::addTarget)
                    }.build()
                    session.setRepeatingRequest(
                        request,
                        createFrameCaptureCallback(replacementSegment),
                        cameraHandler,
                    )
                    captureSession = session
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
        recording = recording && !stopping && !releasing,
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
        cancelTimeout()
        cancelCameraRecoveryTimer()
        cancelVehicleAwayTimer()
        cancelPreviewReplacementWatchdog()
        if (currentPartial != null) {
            finalizeCurrentSegment(finalizeReason, forcedError)
        } else {
            recordingSessionIdentity.endSession()
            closeCamera()
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

    fun onAppForegroundChanged(foreground: Boolean) {
        postCamera {
            val action = vehicleAway.onAppForeground(
                manualSessionGeneration,
                foreground,
                SystemClock.elapsedRealtime(),
            )
            logVehicleAwaySignal("APP_FOREGROUND", foreground.toString(), action)
            applyVehicleAwayAction(action)
        }
    }

    fun onScreenPowerChanged(screenOn: Boolean) {
        postCamera {
            val action = vehicleAway.onScreenPower(
                manualSessionGeneration,
                screenOn,
                SystemClock.elapsedRealtime(),
            )
            logVehicleAwaySignal("SCREEN_ON", screenOn.toString(), action)
            applyVehicleAwayAction(action)
        }
    }

    fun onMainDisplayPowerChanged(displayOn: Boolean) {
        postCamera {
            val action = vehicleAway.onMainDisplayPower(
                manualSessionGeneration,
                displayOn,
                SystemClock.elapsedRealtime(),
            )
            logVehicleAwaySignal("MAIN_DISPLAY_ON", displayOn.toString(), action)
            applyVehicleAwayAction(action)
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

    /**
     * Deterministic, bounded teardown: the cleanup runs on the camera thread and
     * the finalize task is submitted BEFORE the latch releases, so the subsequent
     * ioExecutor.shutdown() can drain it. Never blocks on frame decoding; the
     * wait is capped so a wedged camera thread cannot hang onDestroy.
     */
    fun release() {
        val handler = cameraHandler
        val thread = cameraThread
        if (handler == null || thread == null) {
            // Never started; nothing to tear down.
            wakeLockHolder.releaseAll()
            ioExecutor.shutdown()
            return
        }
        if (thread.looper.thread === Thread.currentThread()) {
            teardownOnCameraThread()
            wakeLockHolder.releaseAll()
            ioExecutor.shutdown()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            teardownOnCameraThread()
            latch.countDown()
        }
        val teardownCompleted = try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!teardownCompleted) {
            // Explicit evidence: finalize commit is NOT guaranteed before shutdown.
            EventLogger.markError(
                Categories.SYSTEM,
                "RECORDER_TEARDOWN_TIMEOUT",
                "camera thread did not finish teardown within 3s; partial evidence may be retained",
                null,
            )
        }
        cameraThread?.quitSafely()
        wakeLockHolder.releaseAll()
        ioExecutor.shutdown()
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
        cancelCameraRecoveryTimer()
        cancelVehicleAwayTimer()
        if (currentPartial != null) {
            finalizeCurrentSegment("STOP", null)
        } else {
            recordingSessionIdentity.endSession()
        }
        closeCamera()
        stopCameraConflictDiagnostics()
        releaseRecordingPreviewSurface()
        releasePendingRecordingPreviewSurface()
        wakeLockHolder.releaseAll()
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
        val cfg = config ?: run {
            startInFlight = false
            return
        }
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
            manager.openCamera(cameraId, createStateCallback(generation, sessionToken), cameraHandler)
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
                if (stopping || releasing || config == null) {
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
                if (cameraDevice === camera) cameraDevice = null
                closeQuietly(camera)
                EventLogger.logEvent(Categories.SYSTEM, "RECORDER_CAMERA_DISCONNECTED")
                handleCameraLoss("CAMERA_DISCONNECTED", recoverableContention = true)
            }

            override fun onError(camera: CameraDevice, errorCode: Int) {
                if (generation != openGeneration || sessionToken != manualSessionGeneration) {
                    closeQuietly(camera)
                    return
                }
                if (cameraDevice === camera) cameraDevice = null
                closeQuietly(camera)
                val message = cameraDeviceErrorMessage(errorCode)
                EventLogger.markError(Categories.SYSTEM, "RECORDER_CAMERA_ERROR", message, null)
                handleCameraLoss(
                    message,
                    recoverableContention = isRecoverableCameraDeviceError(errorCode),
                )
            }
        }

    private fun startSegment() {
        val cfg = config ?: return
        if (stopping || releasing) return
        val device = cameraDevice ?: return
        val sessionToken = manualSessionGeneration
        val decision = prepareStorage(cfg)
        if (!decision.proceed) {
            storageBlocked(decision.reason ?: "STORAGE_BLOCKED")
            return
        }
        segmentGeneration++
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
        if (currentIncidentTag == null) {
            val pendingIncident = incidentStore.pending(nowEpoch)
            if (pendingIncident != null) {
                currentIncidentTag = pendingIncident
                currentConsumesPendingIncident = true
                protectedPending = true
            }
        }
        val partial = SegmentNaming.partialFile(segmentsDir, segmentNumber, cfg.profile, nowEpoch)
        currentPartial = partial
        scheduleSetupWatchdog(generation)
        try {
            partial.parentFile?.mkdirs()
            val recorder = MediaRecorder()
            // Assign before configuration so every prepare/configuration failure releases it.
            mediaRecorder = recorder
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
            recorder.setOutputFile(partial.absolutePath)
            recorder.prepare()
            val surface = recorder.surface
            activeEncoderSurface = surface
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
                                        recorder = recorder,
                                        sessionToken = sessionToken,
                                        reason = t.message ?: "PREVIEW_REPEATING_REQUEST_FAILED",
                                    ) { configureSession(includePreview = false) }
                                } else {
                                    failSegmentStart(generation, partial, t.message ?: "repeating request failed")
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
                                        currentFile = partial.name,
                                        segmentStartedAtEpochMs = segmentRecordingStartedAtEpochMs,
                                        lastError = null,
                                        message = if (
                                            recoveryOutcome == CameraRecoveryStateMachine.RecordingStartOutcome.RESUMED
                                        ) {
                                            "Recording resumed"
                                        } else {
                                            null
                                        },
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
                                        "recordingMode" to cfg.recordingMode.name,
                                        "timeLapseMultiplier" to cfg.timeLapseMultiplier.toString(),
                                        "captureRateFps" to (cfg.captureRateFpsOrNull()?.toString() ?: "-"),
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
                                scheduleTimeout(generation, cfg.effectiveSegmentSeconds() * 1000L)
                            } catch (t: Throwable) {
                                failSegmentStart(generation, partial, t.message ?: "recorder.start failed")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (!ownsSetup(generation, partial, recorder, sessionToken)) {
                                quarantine(partial)
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
        !stopping && !releasing &&
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
        if (generation != segmentGeneration || stopping || releasing || currentPartial !== partial) {
            // Stale setup failure: isolate the old partial only; never touch a newer segment.
            quarantine(partial)
            return
        }
        val cfg = config ?: return
        cancelTimeout()
        recording = false
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        mediaRecorder = null
        captureSession?.close()
        captureSession = null
        activeEncoderSurface = null
        val effectiveFile = quarantine(partial) ?: partial
        val snapshot = SegmentSnapshot(
            file = effectiveFile,
            finalPath = null,
            cameraId = cfg.cameraId,
            profile = cfg.profile,
            sourceRole = cfg.source.sourceRole,
            layoutKind = cfg.source.layoutKind,
            mappingRevision = cfg.source.mappingRevision,
            laneLayout = cfg.source.laneLayout,
            segmentSeconds = cfg.segmentSeconds,
            effectiveSegmentSeconds = cfg.effectiveSegmentSeconds(),
            recordingMode = cfg.recordingMode,
            timeLapseMultiplier = cfg.timeLapseMultiplier,
            requestedCaptureRateFps = cfg.captureRateFpsOrNull(),
            finalizeReason = "START_FAILED",
            segmentNumber = segmentNumber,
            processStartId = processStartId,
            recordingSessionId = recordingSessionIdentity.requireCurrentId(),
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
        )
        // Idempotent cleanup of every current-segment field so a later STOP cannot
        // finalize this same failed segment again, and a late callback cannot revive it.
        currentPartial = null
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
        runIo { writeSidecarAsync(buildSidecarFromSnapshot(snapshot, cfg.profile, null, null)) }
        recordingSessionIdentity.endSession()
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
        cancelTimeout()
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
        recording = false
        segmentStartedAtEpochMs = null
        segmentStartedAtElapsedMs = null
        segmentRecordingStartedAtEpochMs = null
        segmentRecordingStartedAtElapsedMs = null
        updateState(state.copy(status = RecorderStatus.FINALIZING))

        var stopError: String? = forcedError
        var stopExceptionType: String? = null
        val stopStartedElapsed = SystemClock.elapsedRealtime()
        if (RecorderTransitionPolicy.shouldInvokeStop(wasRecording)) {
            try {
                mediaRecorder?.stop()
            } catch (t: Throwable) {
                stopExceptionType = t.javaClass.name
                stopError = stopError ?: (t.message ?: "recorder.stop failed")
            }
        }
        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "RECORDER_MEDIA_RECORDER_STOP_RESULT",
            payload = mapOf(
                "finalizeId" to finalizeId.toString(),
                "invoked" to RecorderTransitionPolicy.shouldInvokeStop(wasRecording).toString(),
                "durationMs" to (SystemClock.elapsedRealtime() - stopStartedElapsed).toString(),
                "success" to (stopError == null).toString(),
                "exceptionType" to (stopExceptionType ?: "-"),
                "error" to (stopError ?: "-"),
                "bytesAfterStop" to runCatching { partial.length() }.getOrDefault(-1L).toString(),
            ),
            errorType = stopExceptionType,
            errorMessage = stopError,
        )
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        mediaRecorder = null
        captureSession?.close()
        captureSession = null
        activeEncoderSurface = null

        val finalFile = SegmentNaming.finalFileFor(partial)
        val partialExists = partial.exists()
        val partialBytes = if (partialExists) partial.length() else 0L
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

    private fun afterFinalize(reason: String, success: Boolean, error: String?) {
        if (reason == "STOP" || stopping) {
            recordingSessionIdentity.endSession()
            closeCamera()
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
    ): Boolean {
        when (action) {
            VehicleAwayAction.None -> return false
            is VehicleAwayAction.Schedule -> {
                cancelVehicleAwayTimer()
                val delayMs = (action.atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val runnable = Runnable {
                    vehicleAwayTimerRunnable = null
                    if (stopping || releasing) return@Runnable
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
                    finalizeReason = if (cameraLossError == null) {
                        "VEHICLE_AWAY"
                    } else {
                        "VEHICLE_AWAY_CAMERA_LOSS"
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

    private fun logVehicleAwaySignal(
        signal: String,
        value: String,
        action: VehicleAwayAction,
    ) {
        EventLogger.logEvent(
            Categories.SYSTEM,
            "RECORDER_VEHICLE_AWAY_SIGNAL",
            payload = vehicleAwayDiagnosticPayload() + mapOf(
                "signal" to signal,
                "value" to value,
                "decision" to vehicleAwayActionName(action),
            ),
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

    private fun closeCamera() {
        cancelPreviewReplacementWatchdog()
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
