package com.dante.zeekrcapabilitylab.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommands
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderNotification
import com.dante.zeekrcapabilitylab.service.recorder.RecorderSession
import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.RecordingModeChoice
import com.dante.zeekrcapabilitylab.service.recorder.RecordingModeSwitchGate
import com.dante.zeekrcapabilitylab.service.recorder.RecordingModeSwitchProgress
import com.dante.zeekrcapabilitylab.service.recorder.RecordingModeSwitchReadiness
import com.dante.zeekrcapabilitylab.service.recorder.RecordingModeHandoffResult
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStorageIdentity
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStorageKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStoragePreference
import com.dante.zeekrcapabilitylab.service.recorder.VehiclePowerSnapshotReader
import com.dante.zeekrcapabilitylab.sharing.BrowserShareManager
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.mirror.MirrorCameraKey
import com.dante.zeekrcapabilitylab.mirror.MirrorPreviewPolicy
import com.dante.zeekrcapabilitylab.product.RecordingModeSwitchConfig
import com.dante.zeekrcapabilitylab.product.RecordingQualityStore
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground camera segment recorder. The service (not the UI) owns
 * CameraDevice / CaptureSession / MediaRecorder; the UI only sends commands and
 * collects [state]. START_NOT_STICKY: a killed process is never restarted.
 */
class CameraRecordingService : Service() {

    private lateinit var session: RecorderSession
    private var foreground = false
    private var removableStorageMonitor: RemovableStorageMonitor? = null
    private var statusOverlay: RecordingStatusOverlay? = null
    private var mirrorPreview: com.dante.zeekrcapabilitylab.mirror.MirrorPreviewController? = null
    @Volatile private var mirrorConfig: RecorderConfig? = null
    private var mirrorAttemptedCamera: MirrorCameraKey? = null
    private var mirrorCamera: MirrorCameraKey? = null
    private var mirrorPendingKey: MirrorCameraKey? = null
    private var mirrorPendingUntil = 0L
    private val mirrorRefresh = Runnable { refreshMirror() }
    private val presentationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var sessionEpoch = 0L
    private var activeConfig: RecorderConfig? = null
    private val modeSwitchGate = RecordingModeSwitchGate()
    private var handoffSession: RecorderSession? = null
    private var requiredModeStorage: RecordingStorageIdentity? = null
    private val modeSwitchWatchdog = object : Runnable {
        override fun run() {
            val request = modeSwitchGate.pending ?: return
            if (SystemClock.elapsedRealtime() >= request.deadlineMs) {
                cancelModeSwitch("DEADLINE")
            } else {
                cancelModeSwitchIfUnsafe()
                if (modeSwitchGate.pending != null) presentationHandler.postDelayed(this, 250)
            }
        }
    }

    private fun createSession(): RecorderSession {
        val epoch = ++sessionEpoch
        return RecorderSession(this, { value ->
            presentationHandler.post { if (instance === this && sessionEpoch == epoch) publishState(value) }
        }, {
            presentationHandler.post { if (instance === this && sessionEpoch == epoch) onStopped() }
        })
    }

    override fun onCreate() {
        super.onCreate()
        RecorderNotification.ensureChannel(this)
        session = createSession()
        removableStorageMonitor = RemovableStorageMonitor(this) { action, path ->
            presentationHandler.post {
                if (RecordingModeSwitchGate.mediaRemovalApplies(requiredModeStorage, path)) cancelModeSwitch("USB_REMOVAL:$action")
                else cancelModeSwitchIfUnsafe()
            }
            session.onRemovableStorageUnavailable(
                action = action,
                directoryPath = path,
            )
        }.also { it.start() }
        instance = this
        statusOverlay = runCatching { RecordingStatusOverlay(this) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecorderCommands.ACTION_START -> {
                val previewSurface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(RecorderCommands.EXTRA_PREVIEW_SURFACE, Surface::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(RecorderCommands.EXTRA_PREVIEW_SURFACE) as? Surface
                }
                val configJson = intent.getStringExtra(RecorderCommands.EXTRA_CONFIG_JSON)
                // Each service lifetime accepts one manual START. Mode handoff is internal;
                // a delayed duplicate intent cannot overwrite the accepted camera/configuration.
                if (activeConfig != null || _modeSwitchProgress.value != null || handoffSession != null) {
                    runCatching { previewSurface?.release() }
                    return START_NOT_STICKY
                }
                val floatingToken = intent.getStringExtra(EXTRA_FLOATING_HANDOFF)
                if (floatingToken != null &&
                    !com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.admitsRecordingStart(floatingToken)) {
                    runCatching { previewSurface?.release() }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active) {
                    runCatching { previewSurface?.release() }
                    stopSelf()
                    return START_NOT_STICKY
                }
                val config = configJson?.let {
                    runCatching {
                        RecorderCommands.json.decodeFromString(RecorderConfig.serializer(), it)
                    }.getOrNull()
                }
                if (config == null) {
                    runCatching { previewSurface?.release() }
                    EventLogger.markError(
                        Categories.SYSTEM,
                        "RECORDER_INVALID_START_INTENT",
                        "missing or unparseable config",
                        null,
                    )
                    publishState(RecorderState(status = RecorderStatus.ERROR, lastError = "INVALID_CONFIG"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!BrowserShareManager.stopForRecording()) {
                    runCatching { previewSurface?.release() }
                    publishState(RecorderState(status = RecorderStatus.ERROR, lastError = "DOWNLOAD_READER_PENDING"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                try { goForeground() } catch (error: Exception) {
                    runCatching { previewSurface?.release() }
                    EventLogger.markError(Categories.SYSTEM, "RECORDER_FOREGROUND_REJECTED", "FOREGROUND_CAMERA_PERMISSION", error)
                    publishState(RecorderState(status = RecorderStatus.ERROR, lastError = "FOREGROUND_CAMERA_PERMISSION"))
                    com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.previewFailed("OPEN_APP_TO_CONTINUE")
                    stopSelf()
                    return START_NOT_STICKY
                }
                activeConfig = config
                mirrorConfig = config.takeIf { it.mirrorPreviewEnabled }
                session.start(config, previewSurface, requiredStorage = floatingToken?.let {
                    com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.recordingStartStorage(it)
                })
                replayVehicleEnvironment(session)
            }

            RecorderCommands.ACTION_STOP -> stopRecording()
            RecorderCommands.ACTION_BOOKMARK -> session.bookmark()
            RecorderCommands.ACTION_RETRY -> session.retry()
            RecorderCommands.ACTION_SET_PREVIEW_OUTPUT -> session.setPreviewOutputEnabled(
                intent.getBooleanExtra(RecorderCommands.EXTRA_PREVIEW_ENABLED, false),
            )

            else -> {
                // START_NOT_STICKY: a null intent (system restart) must not auto-start.
                if (intent == null) {
                    EventLogger.logEvent(
                        Categories.SYSTEM,
                        "RECORDER_RESTART_REJECTED",
                        payload = mapOf("startId" to startId.toString()),
                    )
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        if (foreground) {
            refreshNotification()
            return
        }
        val notification = RecorderNotification.build(this, state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                RecorderNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(RecorderNotification.NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun refreshNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                RecorderNotification.NOTIFICATION_ID,
                RecorderNotification.build(this, state.value),
            )
        } catch (t: Throwable) {
            // Notification updates must never crash the recorder.
        }
    }

    private fun publishState(s: RecorderState) {
        val publishedEpoch = sessionEpoch
        if (s.lastError != null && s.lastError != _state.value.lastError) {
            runCatching { com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordFault(applicationContext, s) }
        }
        _state.value = s
        presentationHandler.post {
            if (instance !== this || sessionEpoch != publishedEpoch) return@post
            val progress = _modeSwitchProgress.value ?: return@post
            val current = state.value
            if (current.lastError != null || current.cleanupUnconfirmed) {
                cancelModeSwitch(current.lastError ?: "CLEANUP_UNCONFIRMED")
            } else if (!progress.releasing && !progress.cancelled && current.status == RecorderStatus.RECORDING) {
                _modeSwitchProgress.value = null
                requiredModeStorage = null
                toast(Utils.t("Recording mode switched.", "录像模式已切换。"))
            }
        }
        presentationHandler.removeCallbacks(mirrorRefresh)
        presentationHandler.post(mirrorRefresh)
        if (foreground) refreshNotification()
    }

    private fun refreshMirror() {
        if (instance !== this) return
        val requested = mirrorConfig ?: return
        val current = state.value
        val key = MirrorPreviewPolicy.cameraKey(current)
        if (key != mirrorCamera) { mirrorPreview?.close(); mirrorPreview = null; mirrorCamera = null }
        if (key == null || current.status !in setOf(RecorderStatus.STARTING, RecorderStatus.RECORDING)) {
            mirrorPendingKey = null; return
        }
        if (mirrorPreview != null || mirrorAttemptedCamera == key) return
        if (!com.dante.zeekrcapabilitylab.mirror.MirrorGlPreview.isIdle()) {
            if (mirrorPendingKey != key) { mirrorPendingKey = key; mirrorPendingUntil = SystemClock.elapsedRealtime() + 5_000 }
            if (SystemClock.elapsedRealtime() < mirrorPendingUntil) {
                presentationHandler.removeCallbacks(mirrorRefresh); presentationHandler.postDelayed(mirrorRefresh, 100)
                return
            }
            mirrorAttemptedCamera = key
            EventLogger.markError(Categories.SYSTEM, "RECORDER_MIRROR_SETUP_FAILED", "PREVIOUS_GL_CLOSE_UNCONFIRMED", null)
            return
        }
        mirrorPendingKey = null; mirrorAttemptedCamera = key
        mirrorPreview = runCatching {
            com.dante.zeekrcapabilitylab.mirror.MirrorPreviewController(applicationContext, requested,
                com.dante.zeekrcapabilitylab.mirror.RecordingMirrorSource(applicationContext, session), key.sessionId, key.cameraGeneration)
        }.onFailure { EventLogger.markError(Categories.SYSTEM, "RECORDER_MIRROR_SETUP_FAILED", "SETUP_FAILED", it) }.getOrNull()
        mirrorCamera = key
    }

    private fun onStopped() {
        com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.recorderStopped(state.value)
        modeSwitchGate.cancel()
        presentationHandler.removeCallbacks(modeSwitchWatchdog)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun stopRecording(manual: Boolean = true) {
        if (manual) com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.onManualStop(state.value)
        cancelModeSwitch("MANUAL_STOP", notify = false)
        mirrorConfig = null
        presentationHandler.post { mirrorPreview?.close(); mirrorPreview = null }
        session.stop()
    }

    private fun requestModeSwitch(target: RecordingModeChoice, expectedSessionId: String) {
        val current = state.value
        val config = activeConfig ?: return
        if (_modeSwitchProgress.value != null || handoffSession != null ||
            current.recordingSessionId != expectedSessionId || !RecordingModeSwitchGate.eligible(current) ||
            com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active) {
            toast(Utils.t("Wait until recording is ready before switching modes.", "请等录像准备完成后再切换模式。")); return
        }
        if (target == RecordingModeChoice(current.recordingMode, current.timeLapseMultiplier)) return
        val power = VehiclePowerSnapshotReader.read(this)
        val storage = RecordingStorageIdentity(current.activeStorageKind, current.activeStorageUuid)
        if (!power.interactive || power.mainDisplayState != "ON" || !storageAvailable(storage)) {
            toast(Utils.t("Mode switch unavailable: check the screen and recording storage.", "暂时无法切换模式，请检查屏幕状态和录像存储。")); return
        }
        val settings = SettingsStore.get(this)
        val next = runCatching { RecordingModeSwitchConfig.build(config, target,
            RecordingQualityStore(this).get(config.source.sourceRole),
            settings.mirrorPreviewEnabled && android.provider.Settings.canDrawOverlays(this),
            normalSharedInput = settings.sharedInputRecordingEnabled)
            .copy(storagePreference = if (storage.kind == RecordingStorageKind.INTERNAL)
                RecordingStoragePreference.INTERNAL_ONLY else RecordingStoragePreference.USB_PREFERRED)
        }.getOrElse {
            toast(Utils.t("This recording mode is unavailable.", "此录像模式暂不可用。")); return
        }
        val request = modeSwitchGate.begin(current, target, SystemClock.elapsedRealtime()) ?: return
        val owner = session
        handoffSession = owner
        requiredModeStorage = storage
        keepFloatingControls = true
        _modeSwitchProgress.value = RecordingModeSwitchProgress(target)
        statusOverlay?.showForModeSwitch()
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_MODE_SWITCH_REQUESTED", payload = mapOf(
            "session" to expectedSessionId, "mode" to target.mode.name, "multiplier" to target.multiplier.toString()))
        presentationHandler.post(modeSwitchWatchdog)
        owner.releaseForRecordingModeSwitch(expectedSessionId) { result ->
            presentationHandler.post {
                if (instance === this && handoffSession === owner) finishModeSwitch(request, owner, next, storage, result)
            }
        }
    }

    private fun finishModeSwitch(request: RecordingModeSwitchGate.Request, owner: RecorderSession,
                                 next: RecorderConfig, storage: RecordingStorageIdentity,
                                 result: RecordingModeHandoffResult) {
        presentationHandler.removeCallbacks(modeSwitchWatchdog)
        handoffSession = null
        if (!result.released) {
            val cancelled = _modeSwitchProgress.value?.cancelled == true
            modeSwitchGate.cancel(); _modeSwitchProgress.value = null; requiredModeStorage = null
            if (cancelled) stopRecording(manual = false)
            else if (result.failure == "INSUFFICIENT_FRAMES") toast(Utils.t("Keep recording a little longer before switching modes.", "请再录一会儿，积累画面后再切换模式。"))
            else toast(Utils.t("Recording changed before the switch. Please try again when ready.", "切换前录像状态已变化，请等录像就绪后重试。"))
            return
        }
        val power = VehiclePowerSnapshotReader.read(this)
        val accepted = modeSwitchGate.take(request.token, SystemClock.elapsedRealtime(), RecordingModeSwitchReadiness(
            released = result.released, saved = result.failure == null,
            interactive = power.interactive, displayOn = power.mainDisplayState == "ON",
            storageMatches = storageAvailable(storage), ownerCurrent = session === owner &&
                !com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active,
        ))
        if (accepted == null) {
            if (_modeSwitchProgress.value?.cancelled != true) cancelModeSwitch(result.failure ?: "CONDITIONS_CHANGED")
            onStopped()
            return
        }
        mirrorConfig = null
        mirrorPreview?.close(); mirrorPreview = null; mirrorCamera = null; mirrorAttemptedCamera = null
        _modeSwitchProgress.value = RecordingModeSwitchProgress(request.target, releasing = false)
        activeConfig = next
        session = createSession()
        mirrorConfig = next.takeIf { it.mirrorPreviewEnabled }
        if (next.recordingMode == RecordingMode.TIME_LAPSE) SettingsStore.get(this).setTimeLapseMultiplier(next.timeLapseMultiplier)
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_MODE_SWITCH_RELEASE_CONFIRMED", payload = mapOf(
            "previousSession" to request.sessionId, "mode" to next.recordingMode.name))
        session.start(next, requiredStorage = storage)
        replayVehicleEnvironment(session)
    }

    private fun storageAvailable(storage: RecordingStorageIdentity): Boolean = when (storage.kind) {
        RecordingStorageKind.INTERNAL -> true
        RecordingStorageKind.USB_MEDIASTORE -> runCatching {
            !storage.storageUuid.isNullOrBlank() && UsbExportVolumeResolver.isRemovableVolumeMounted(this, storage.storageUuid)
        }.getOrDefault(false)
    }

    private fun cancelModeSwitchIfUnsafe() {
        if (_modeSwitchProgress.value == null) return
        val power = VehiclePowerSnapshotReader.read(this)
        val storage = requiredModeStorage
        if (!power.interactive || power.mainDisplayState != "ON" || storage == null || !storageAvailable(storage)) {
            cancelModeSwitch("POWER_OR_STORAGE_CHANGED")
        }
    }

    private fun cancelModeSwitch(reason: String, notify: Boolean = true) {
        val progress = _modeSwitchProgress.value ?: return
        modeSwitchGate.cancel()
        presentationHandler.removeCallbacks(modeSwitchWatchdog)
        if (progress.cancelled) return
        _modeSwitchProgress.value = progress.copy(cancelled = true)
        // After handoff, revoke the new Session's recovery authority as well as the UI intent.
        if (!progress.releasing) session.stop()
        EventLogger.logEvent(Categories.SYSTEM, "RECORDER_MODE_SWITCH_CANCELLED", payload = mapOf("reason" to reason))
        if (notify) toast(Utils.t("Mode switch cancelled. Check recording status before starting again.", "模式切换已取消，请检查录像状态后再手动开始。"))
    }

    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately do NOT stop here; the foreground service keeps segmenting
        // while the user drives away from the launcher/home screen.
    }

    override fun onDestroy() {
        sessionEpoch++
        modeSwitchGate.cancel()
        handoffSession = null
        _modeSwitchProgress.value = null
        keepFloatingControls = false
        mirrorConfig = null
        presentationHandler.removeCallbacksAndMessages(null)
        mirrorPreview?.close()
        mirrorPreview = null
        statusOverlay?.close()
        statusOverlay = null
        removableStorageMonitor?.stop()
        removableStorageMonitor = null
        instance = null
        session.release()
        _state.value = RecorderState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _modeSwitchProgress = MutableStateFlow<RecordingModeSwitchProgress?>(null)
        val modeSwitchProgress: StateFlow<RecordingModeSwitchProgress?> = _modeSwitchProgress.asStateFlow()
        @Volatile var keepFloatingControls: Boolean = false; private set
        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        @Volatile
        private var instance: CameraRecordingService? = null

        /** UI-visible Activity calls this after the user explicitly taps Start. */
        private const val EXTRA_FLOATING_HANDOFF = "openavm.record.FLOATING_HANDOFF"
        fun start(context: Context, config: RecorderConfig, previewSurface: Surface? = null, floatingHandoffToken: String? = null) {
            if (config.mirrorPreviewEnabled && com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.isRunning() &&
                com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.active()) {
                runCatching { previewSurface?.release() }
                com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.startRecording(config)
                return
            }
            if (com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active) {
                runCatching { previewSurface?.release() }; return
            }
            if (!BrowserShareManager.stopForRecording()) {
                runCatching { previewSurface?.release() }
                android.widget.Toast.makeText(context, Utils.t("Finishing active downloads…", "正在结束下载…"), android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (config.mirrorPreviewEnabled) com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.followRecording(context)
            val intent = Intent(context, CameraRecordingService::class.java)
                .setAction(RecorderCommands.ACTION_START)
                .putExtra(EXTRA_FLOATING_HANDOFF, floatingHandoffToken)
                .putExtra(
                    RecorderCommands.EXTRA_CONFIG_JSON,
                    RecorderCommands.json.encodeToString(RecorderConfig.serializer(), config),
                )
            if (previewSurface != null) {
                intent.putExtra(RecorderCommands.EXTRA_PREVIEW_SURFACE, previewSurface)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            deliverCommand(context, RecorderCommands.ACTION_STOP)
        }
        internal fun stopForMirrorSourceSwitch(expectedSession: String) {
            val owner = instance ?: return
            owner.presentationHandler.post {
                if (instance === owner && state.value.recordingSessionId == expectedSession &&
                    com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.awaitingRecordingRelease(expectedSession))
                    owner.stopRecording(manual = false)
            }
        }
        internal fun mirrorRecordingTransfer(): com.dante.zeekrcapabilitylab.mirror.MirrorRecordingTransfer? = instance?.let { owner ->
            owner.activeConfig?.takeIf { _modeSwitchProgress.value == null }?.let {
                com.dante.zeekrcapabilitylab.mirror.MirrorRecordingTransfer.capture(it, state.value)
            }
        }
        /** Snapshot for an explicit in-memory Cabin round trip; never a boot/resume permit. */
        internal fun mirrorCabinReturnPlan(): com.dante.zeekrcapabilitylab.mirror.MirrorCabinReturn? = instance?.let { owner ->
            owner.activeConfig?.takeIf { _modeSwitchProgress.value == null }?.let {
                com.dante.zeekrcapabilitylab.mirror.MirrorCabinReturn.capture(it, state.value)
            }
        }
        internal fun cancelFloatingStart() {
            instance?.let { active -> active.presentationHandler.post { if (instance === active) active.stopRecording(manual = false) } }
        }

        /** Called only by an explicit selection in a visible, app-owned floating menu. */
        fun switchMode(target: RecordingModeChoice, expectedSessionId: String) {
            val active = instance ?: return
            active.presentationHandler.post {
                if (instance === active) active.requestModeSwitch(target, expectedSessionId)
            }
        }

        fun bookmark(context: Context) {
            deliverCommand(context, RecorderCommands.ACTION_BOOKMARK)
        }

        fun retry(context: Context) {
            deliverCommand(context, RecorderCommands.ACTION_RETRY)
        }

        /** Changes only the repeating request targets; the encoder keeps running. */
        fun setPreviewOutputEnabled(context: Context, enabled: Boolean) {
            val active = instance ?: return
            val intent = Intent(context, CameraRecordingService::class.java)
                .setAction(RecorderCommands.ACTION_SET_PREVIEW_OUTPUT)
                .putExtra(RecorderCommands.EXTRA_PREVIEW_ENABLED, enabled)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                active.session.setPreviewOutputEnabled(enabled)
            }
        }

        /** Replaces a destroyed UI preview without restarting MediaRecorder. */
        fun replacePreviewSurface(surface: Surface) {
            val active = instance
            if (active == null) {
                runCatching { surface.release() }
                return
            }
            active.session.replacePreviewSurface(surface)
        }

        fun isRunning(): Boolean = instance != null
        internal fun isLegacyRunning(): Boolean = instance != null
        internal fun publishGuardState(state: RecorderState) { if (instance == null) _state.value = state }

        /** Broadcasts/listeners are hints; every report is a fresh atomic Android snapshot. */
        fun refreshVehiclePowerSnapshot(context: Context, source: String) {
            val snapshot = VehiclePowerSnapshotReader.read(context.applicationContext)
            instance?.session?.onVehiclePowerSnapshot(snapshot, source)
            instance?.let { active -> active.presentationHandler.post {
                if (instance === active) {
                    if (RecordingModeSwitchGate.powerRevoked(snapshot, source)) active.cancelModeSwitch("POWER_EDGE:$source")
                    else active.cancelModeSwitchIfUnsafe()
                }
            } }
        }

        private fun replayVehicleEnvironment(session: RecorderSession) {
            val snapshot = VehiclePowerSnapshotReader.read(ZeekrApp.appContext)
            session.onVehiclePowerSnapshot(snapshot, "SERVICE_START_REPLAY")
        }

        /**
         * Command delivery consistent with the notification's Stop action: send an
         * explicit service intent with the action. Guarded by the instance check so a
         * dead service is never accidentally started without going through START
         * (which is the only path that calls startForegroundService).
         */
        private fun deliverCommand(context: Context, action: String) {
            if (instance == null) return
            val intent = Intent(context, CameraRecordingService::class.java).setAction(action)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                // In-process fallback when the platform rejects background startService.
                instance?.let { active -> active.presentationHandler.post { if (instance === active) active.onCommand(action) } }
            }
        }
    }

    private fun onCommand(action: String) {
        when (action) {
            RecorderCommands.ACTION_STOP -> stopRecording()
            RecorderCommands.ACTION_BOOKMARK -> session.bookmark()
            RecorderCommands.ACTION_RETRY -> session.retry()
        }
    }
}
