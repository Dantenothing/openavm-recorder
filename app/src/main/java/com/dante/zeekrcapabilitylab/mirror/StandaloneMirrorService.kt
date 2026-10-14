package com.dante.zeekrcapabilitylab.mirror

import android.app.Service
import android.app.PendingIntent
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.os.*
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.util.Utils

/** Explicit, screen-on preview only. No encoder, MP4, wake lock, boot start or automatic restart. */
class StandaloneMirrorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var owner: String? = null
    private var handoffToken: String? = null
    private var producer: PreviewProducer? = null
    @Volatile private var mirror: MirrorPreviewController? = null
    @Volatile private var stopping = false
    private var startCommandReceived = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            if (!MirrorDisplayState.usable(this@StandaloneMirrorService)) stopPreview()
            else producer?.sleepCloseEvidence("STALE_POWER_HINT_IGNORED", mapOf("reason" to "SCREEN_OFF", "screenUsable" to "true"))
        }
    }
    override fun onCreate() {
        super.onCreate(); instance = this
        worker = HandlerThread("mirror-only-camera").apply { start() }; handler = Handler(worker.looper)
        CaptureCleanupRuntime.initialize(this); RecorderNotification.ensureChannel(this)
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCommandReceived = true
        val id = intent?.getStringExtra("owner")
        val ticket = intent?.getStringExtra("handoff")
        // A revoked handoff still arrived via startForegroundService. Android rejects
        // stopSelf before startForeground even when cancellation happens immediately.
        // Acknowledge that lifecycle contract first; this does not admit camera work.
        if (intent?.action == START) {
            try { goForeground() }
            catch (failure: Throwable) {
                if (owner == null) { id?.let(CameraWorkCoordinator::finish); handoffToken = ticket }
                failBeforeOpen("FOREGROUND_CAMERA_PERMISSION")
                return START_NOT_STICKY
            }
        }
        if (intent?.action == STOP) { FloatingMirrorService.close(); stopPreview(); return START_NOT_STICKY }
        val role = runCatching { RecordingSourceRole.valueOf(intent?.getStringExtra("role") ?: "SURROUND") }.getOrNull()
        val config = intent?.getStringExtra(RecorderCommands.EXTRA_CONFIG_JSON)?.let {
            runCatching { RecorderCommands.json.decodeFromString(RecorderConfig.serializer(), it) }.getOrNull()
        }
        if (intent?.action != START || stopping || id == null || CameraWorkCoordinator.state.value.owner != id || CameraRecordingService.isRunning() ||
            ticket == null || role !in setOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN) || !FloatingMirrorService.admitsPreviewStart(ticket, role!!) ||
            config == null || config.source.sourceRole != role || !config.mirrorPreviewEnabled || config.validate().isNotEmpty() || !MirrorPreviewPolicy.supportsPreview(config)) {
            if (owner == null) { id?.let(CameraWorkCoordinator::finish); stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) }
            return START_NOT_STICKY
        }
        if (owner != null) return START_NOT_STICKY
        owner = id
        handoffToken = ticket
        handler.post {
            if (!com.dante.zeekrcapabilitylab.sharing.BrowserShareManager.stopForRecording()) { failBeforeOpen("BROWSER_DOWNLOAD_ACTIVE"); return@post }
            // Use the just-validated profile from the controls handoff. A second catalog
            // read during wake could discard it even before the camera has been opened.
            val afterIdle: (Boolean) -> Unit = { idle -> handler.post {
                if (stopping) { finish(); return@post }
                if (!idle) { failBeforeOpen("CAMERA_RELEASE_PENDING"); return@post }
                val next = PreviewProducer(config, id, FloatingMirrorService.diagnosticPreviewOrder(ticket)); producer = next; next.begin()
            }; Unit }
            if (RetainedCameraConnection.ready(ticket, config.cameraId)) afterIdle(true)
            else CaptureCleanupRuntime.awaitIdle(10_000, done = afterIdle)
        }
        return START_NOT_STICKY
    }
    private fun goForeground() {
        val stopIntent = PendingIntent.getService(this, 83, Intent(this, StandaloneMirrorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 84, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, RecorderNotification.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("OpenAVM")
            .setContentText(Utils.t("Preview only", "仅预览")).setOngoing(true).setContentIntent(open)
            .addAction(0, Utils.t("Stop", "停止"), stopIntent).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(0x5E48, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(0x5E48, notification)
    }
    private fun failBeforeOpen(reason: String) {
        FloatingMirrorService.previewFailed(reason, handoffToken)
        main.post { android.widget.Toast.makeText(this, previewFailureMessage(reason), android.widget.Toast.LENGTH_LONG).show() }
        finish()
    }
    private fun previewFailureMessage(reason: String): String =
        if (com.dante.zeekrcapabilitylab.product.SettingsStore.get(this).developerModeEnabled)
            Utils.t("Preview unavailable: {0}", "预览不可用：{0}", reason)
        else Utils.t("Preview unavailable. Reopen the mirror manually.", "预览不可用，请手动重新开启后视镜。")
    private fun stopPreview() {
        stopping = true
        // onCreate and delivery of the START intent are separate main-thread messages.
        // Retain cancellation until that delivery acknowledges its foreground start.
        if (!startCommandReceived) return
        main.post { mirror?.close(); mirror = null }
        handler.post { producer?.close() ?: finish() }
    }
    private fun finish() {
        main.post {
            mirror?.close(); mirror = null
            owner?.let(CameraWorkCoordinator::finish)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() {
        instance = null; stopping = true
        runCatching { unregisterReceiver(screen) }
        mirror?.close(); mirror = null
        handler.post { producer?.close() ?: run { owner?.let(CameraWorkCoordinator::finish); worker.quitSafely() } }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class PreviewProducer(val config: RecorderConfig, val id: String, val trial: PreviewOrderTrial?) : MirrorCameraSource {
        @Volatile override var state = RecorderState(status = RecorderStatus.STARTING, cameraId = config.cameraId,
            profile = config.profile, sourceRole = config.source.sourceRole, layoutKind = config.source.layoutKind,
            mirrorPreviewManaged = true, recordingSessionId = id, cameraGeneration = 1)
            private set
        override val previewOnly = true
        override val acceptsPreviewOutputBeforeOpen = trial?.order == PreviewInitializationOrder.SURFACE_FIRST
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var surface: Surface? = null
        private var released: (() -> Unit)? = null
        @Volatile private var opening = false
        @Volatile private var closing = false
        @Volatile var displayedFrameAtMs: Long? = null
            private set
        private var closed = false
        private var enabled = true
        private var firstFrameRecovery: FirstFrameRecovery? = null
        @Volatile private var captureFrames = 0L
        @Volatile private var captureFailures = 0L
        @Volatile private var previewBuffersLost = 0L
        @Volatile private var captureFailureFacts: Map<String, String> = emptyMap()
        private var firstRequestLogged = false
        private val pendingSequences = mutableSetOf<Int>()
        private var transaction: CaptureCloseTransaction? = null
        fun begin() {
            if (acceptsPreviewOutputBeforeOpen) preparePresentation() else open()
        }
        private fun preparePresentation() {
            main.post {
                if (!closing && !stopping) {
                    mirror = runCatching { MirrorPreviewController(applicationContext, config, this@PreviewProducer, id, 1) }
                        .onFailure { stopPreview() }.getOrNull()
                }
            }
        }
        private fun open() {
            if (stopping || closing) { finish(); return }
            opening = true
            sleepCloseEvidence("OPEN_REQUESTED", emptyMap())
            CameraWorkCoordinator.publish(id, Utils.t("Preparing preview", "正在准备预览"))
            CaptureCleanupRuntime.handler.postDelayed({
                if (opening) {
                    closing = true
                    CameraWorkCoordinator.publish(id, Utils.t("Camera release is unconfirmed", "相机释放尚未确认"), "OPEN_TIMEOUT")
                }
            }, 10_000)
            try {
                val callback = object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false; device = camera
                        if (closing || stopping) { close(); return }
                        sleepCloseEvidence("OPENED", emptyMap())
                        state = state.copy(status = RecorderStatus.PREVIEWING)
                        if (acceptsPreviewOutputBeforeOpen) configure(camera, requireNotNull(surface))
                        else preparePresentation()
                    }
                    override fun onDisconnected(camera: CameraDevice) { opening = false; device = camera; error("CAMERA_DISCONNECTED") }
                    override fun onError(camera: CameraDevice, error: Int) { opening = false; device = camera; error("CAMERA_ERROR_$error") }
                    override fun onClosed(camera: CameraDevice) {
                        transaction?.deviceClosed()
                        sleepCloseEvidence("DEVICE_CLOSED", mapOf("deviceClosed" to "true"))
                    }
                }
                if (RetainedCameraConnection.enabled && trial == null) RetainedCameraConnection.open(
                    getSystemService(CameraManager::class.java), config.cameraId, requireNotNull(handoffToken), callback, handler)
                else CanaryCameraOpenAdapter.open(getSystemService(CameraManager::class.java), config.cameraId, callback, handler)
            } catch (t: Throwable) { opening = false; error(t.message ?: "OPEN_FAILED") }
        }
        override fun enable(enabled: Boolean, runId: String, generation: Long) {
            handler.post {
                if (runId != id || generation != 1L || stopping || closing || closed) return@post
                this.enabled = enabled
                val current = session ?: return@post
                runCatching { if (enabled) repeat(current) else current.stopRepeating() }.onFailure { error("PREVIEW_REQUEST_FAILED") }
            }
        }
        override fun displayedFrame(runId: String, generation: Long) {
            if (runId != id || generation != 1L || stopping || closing || closed) return
            val first = displayedFrameAtMs == null
            displayedFrameAtMs = SystemClock.elapsedRealtime()
            if (first) sleepCloseEvidence("FIRST_DISPLAYED_FRAME", mapOf("status" to state.status))
        }
        override fun replace(surface: Surface, runId: String, generation: Long, released: () -> Unit) {
            val accepted = handler.post {
                // Stop is latched on the main thread before native close can run.
                // Older output work already in this queue no longer has admission.
                if (runId != id || generation != 1L || stopping || closing || closed || this.surface != null) {
                    surface.release(); released(); return@post
                }
                val camera = device
                if (camera == null && !acceptsPreviewOutputBeforeOpen) { surface.release(); released(); error("DEVICE_MISSING"); return@post }
                this.surface = surface; this.released = released
                sleepCloseEvidence("SURFACE_READY", mapOf("surfaceValid" to surface.isValid.toString()))
                if (camera == null) open() else configure(camera, surface)
            }
            // The presentation may submit after this owner's worker has exited.
            // Rejected work never reached Camera2, so return ownership immediately.
            if (!accepted) { surface.release(); released() }
        }
        private fun configure(camera: CameraDevice, surface: Surface) {
                try {
                    sleepCloseEvidence("CONFIGURE_REQUESTED", emptyMap())
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (closing || stopping) { value.close(); return }
                            session = value
                            sleepCloseEvidence("CONFIGURED", emptyMap())
                            runCatching { if (enabled) repeat(value) }.onFailure { error("PREVIEW_REQUEST_FAILED") }
                            armFirstFrameRecovery(value)
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); error("PREVIEW_CONFIG_FAILED") }
                        override fun onReady(value: CameraCaptureSession) { if (session === value) transaction?.sessionReady() }
                        override fun onClosed(value: CameraCaptureSession) { transaction?.sessionClosed() }
                    }, handler)
                } catch (t: Throwable) { error(t.message ?: "PREVIEW_CONFIG_FAILED") }
        }
        private fun repeat(value: CameraCaptureSession) {
            val request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                // Match the successful three-camera probe: preserve this camera's template
                // defaults instead of forcing ordinary-camera AE/control values onto AVM.
                addTarget(requireNotNull(surface))
            }.build()
            val sequence = value.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    pendingSequences.remove(sequenceId); transaction?.sequenceEnded(sequenceId)
                }
                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    pendingSequences.remove(sequenceId); transaction?.sequenceEnded(sequenceId)
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (session !== this@PreviewProducer.session || closing || closed || stopping) return
                    captureFrames++
                    if (captureFrames == 1L) sleepCloseEvidence("FIRST_CAPTURE_CALLBACK", mapOf("captureFrames" to "1"))
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    if (session !== this@PreviewProducer.session || closing || closed || stopping) return
                    captureFailures++
                    captureFailureFacts = mapOf("captureFailureReason" to failure.reason.toString(),
                        "failureFrameNumber" to failure.frameNumber.toString(),
                        "failureSequenceId" to failure.sequenceId.toString(),
                        "failureImageCaptured" to failure.wasImageCaptured().toString())
                    if (captureFailures == 1L) sleepCloseEvidence("FIRST_CAPTURE_FAILED", captureFacts())
                    if (trial != null) error("PREVIEW_CAPTURE_FAILED")
                }
                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    if (session !== this@PreviewProducer.session || closing || closed || stopping || target !== surface) return
                    previewBuffersLost++
                    if (trial != null) error("PREVIEW_BUFFER_LOST")
                }
            }, handler)
            pendingSequences += sequence
            if (!firstRequestLogged) {
                firstRequestLogged = true
                sleepCloseEvidence("REQUEST_ACCEPTED", PreviewRequestFacts.read(request))
            }
            CameraWorkCoordinator.publish(id, Utils.t("Preview only", "仅预览"))
        }
        private fun armFirstFrameRecovery(value: CameraCaptureSession) {
            if (trial != null) return // The two diagnostic orders must not hide their result with a retry.
            val recovery = FirstFrameRecovery(SystemClock.elapsedRealtime())
            firstFrameRecovery?.cancel(); firstFrameRecovery = recovery
            handler.postDelayed({
                val frames = mirror?.sourceFrameCount() ?: 0L
                val allowed = firstFrameRecovery === recovery && session === value && enabled && !stopping && !closing && !closed &&
                    MirrorDisplayState.usable(this@StandaloneMirrorService)
                if (!recovery.request(SystemClock.elapsedRealtime(), frames, allowed)) return@postDelayed
                sleepCloseEvidence("REQUEST_REISSUED", mapOf("sourceFrames" to frames.toString(), "captureFrames" to captureFrames.toString()))
                runCatching { repeat(value) }.onFailure { error("PREVIEW_REQUEST_FAILED") }
                handler.postDelayed({
                    if (session === value && !closing && !closed && !stopping) sleepCloseEvidence("REQUEST_RESULT", mapOf(
                        "sourceFrames" to (mirror?.sourceFrameCount() ?: 0L).toString(), "captureFrames" to captureFrames.toString()))
                }, 2_000)
            }, FirstFrameRecovery.WAIT_MS)
        }
        private fun error(reason: String) {
            FloatingMirrorService.previewFailed(reason, handoffToken)
            state = state.copy(status = RecorderStatus.ERROR, lastError = reason)
            CameraWorkCoordinator.publish(id, previewFailureMessage(reason), reason)
            main.post { mirror?.close(); mirror = null
                android.widget.Toast.makeText(this@StandaloneMirrorService, previewFailureMessage(reason), android.widget.Toast.LENGTH_LONG).show() }
            close()
        }
        fun close() {
            closing = true
            firstFrameRecovery?.cancel()
            if (transaction != null || closed) return
            if (opening) return // A timeout never proves a native open has returned.
            val camera = device; val capture = session; val output = surface
            val transfer = if (trial == null && state.lastError == null && capture != null && camera != null)
                RetainedCameraConnection.prepare(camera) else null
            if (camera == null && output == null) { closed = true; finish(); worker.quitSafely(); return }
            val hold = Any()
            sleepCloseEvidence("CLOSE_REQUESTED", mapOf("deviceClosed" to (camera == null).toString()))
            val tx = CaptureCloseTransaction(CaptureCleanupRuntime.control, HandlerCloseDispatcher(handler), HandlerCloseDispatcher(handler),
                object : CaptureCloseResources {
                    override fun stopRepeating() { capture?.stopRepeating() }
                    override fun abortCaptures() { capture?.abortCaptures() }
                    override fun closeSession() { capture?.close() }
                    override fun closeDevice() { camera?.close() }
                    override fun stopRecorder() = Unit
                    override fun resetRecorder() = Unit
                    override fun releaseRecorder() = Unit
                    override fun closeOutput(lost: Boolean) { output?.release(); released?.invoke(); released = null }
                }, hasSession = capture != null, hasDevice = camera != null, wasRecording = false, sequences = pendingSequences.toSet(),
                terminal = transfer == null, lost = false, preferDeviceClose = transfer == null,
                requireSessionCloseAck = transfer != null,
                trace = { step, detail -> CaptureCleanupRuntime.trace("mirror-only-$id", step, detail) },
                unconfirmed = { CameraWorkCoordinator.publish(id, Utils.t("Camera release is unconfirmed", "相机释放尚未确认"), "CLEANUP_UNCONFIRMED") },
                completed = { result -> if (result.safeToContinue) {
                    sleepCloseEvidence("CLOSE_SETTLED", mapOf("safeToContinue" to result.safeToContinue.toString(),
                        "deviceClosed" to result.deviceClosed.toString()))
                    if (transfer != null && camera != null && !result.deviceClosed) {
                        RetainedCameraConnection.producerReleased(camera, transfer,
                            healthy = !result.terminal && result.errors.isEmpty())
                    }
                    device = null; session = null
                    closed = true; CaptureCleanupRuntime.settled(hold, true); finish(); worker.quitSafely()
                } })
            transaction = tx; CaptureCleanupRuntime.retain(hold, config.cameraId, camera, tx); tx.begin()
        }
        fun sleepCloseEvidence(stage: String, facts: Map<String, String>) {
            if (!com.dante.zeekrcapabilitylab.BuildConfig.MIRROR_RETURN_ENABLED) return
            com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(com.dante.zeekrcapabilitylab.data.Categories.SYSTEM,
                "MIRROR_PREVIEW_$stage", payload = facts + mapOf("previewOwner" to id, "requestTemplate" to "PREVIEW") +
                    (trial?.let { mapOf("trialId" to it.id, "initializationOrder" to it.order.name) } ?: emptyMap()))
        }
        override fun stop() { main.post { stopPreview() } }
        override fun captureFacts(): Map<String, String> = mapOf(
            "captureFrames" to captureFrames.toString(), "captureFailures" to captureFailures.toString(),
            "previewBuffersLost" to previewBuffersLost.toString()) + captureFailureFacts
        override fun bookmark() = Unit
    }
    companion object {
        private const val START = "openavm.preview.START"
        private const val STOP = "openavm.preview.STOP"
        @Volatile private var instance: StandaloneMirrorService? = null
        fun start(context: Context): Boolean = FloatingMirrorService.open(context)
        internal fun startOwned(context: Context, token: String, config: RecorderConfig): Boolean {
            val role = config.source.sourceRole
            // A visible control click is supported on the tested API 32 head unit. Newer
            // while-in-use restrictions require the App to be foreground before camera FGS creation.
            if (role !in setOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN) ||
                (!ZeekrApp.isForeground.value && (Build.VERSION.SDK_INT >= 34 || !FloatingMirrorService.visible())) ||
                !FloatingMirrorService.active() || instance != null || CameraRecordingService.isRunning() || !android.provider.Settings.canDrawOverlays(context)) return false
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                SettingsStore.get(context).mirrorRearLane !in 1..4) return false
            val id = CameraWorkCoordinator.claim("PREVIEW") ?: return false
            SettingsStore.get(context).setMirrorPreviewEnabled(true)
            return runCatching { ContextCompat.startForegroundService(context,
                Intent(context, StandaloneMirrorService::class.java).setAction(START).putExtra("owner", id).putExtra("handoff", token).putExtra("role", role.name)
                    .putExtra(RecorderCommands.EXTRA_CONFIG_JSON, RecorderCommands.json.encodeToString(RecorderConfig.serializer(), config))); true }
                .getOrElse { CameraWorkCoordinator.finish(id); false }
        }
        fun isRunning() = instance != null
        internal fun ownerId(): String? = instance?.owner
        internal fun isReleasingOwner(owner: String?): Boolean = instance?.let { it.owner == owner && it.stopping } == true
        internal fun previewFailed(owner: String, reason: String) {
            val service = instance?.takeIf { it.owner == owner } ?: return
            FloatingMirrorService.previewFailed(reason, service.handoffToken)
        }
        fun isPreviewing() = instance?.let { service ->
            val producer = service.producer
            !service.stopping && MirrorDisplayState.usable(service) && producer?.state?.status == RecorderStatus.PREVIEWING &&
                producer.displayedFrameAtMs?.let { SystemClock.elapsedRealtime() - it in 0..2_000 } == true
        } == true
        internal fun healthyCameraId(): String? = instance?.producer?.let { producer ->
            producer.config.cameraId.takeIf { isPreviewing() && producer.state.lastError == null }
        }
        fun stop() { FloatingMirrorService.close(); stopForHandoff() }
        internal fun stopForHandoff() { instance?.main?.post { instance?.stopPreview() } }
    }
}
