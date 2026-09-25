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
    private var mirror: MirrorPreviewController? = null
    @Volatile private var stopping = false
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
        if (intent?.action == STOP) { FloatingMirrorService.close(); stopPreview(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("owner")
        val ticket = intent?.getStringExtra("handoff")
        val role = runCatching { RecordingSourceRole.valueOf(intent?.getStringExtra("role") ?: "SURROUND") }.getOrNull()
        val config = intent?.getStringExtra(RecorderCommands.EXTRA_CONFIG_JSON)?.let {
            runCatching { RecorderCommands.json.decodeFromString(RecorderConfig.serializer(), it) }.getOrNull()
        }
        if (intent?.action != START || id == null || CameraWorkCoordinator.state.value.owner != id || CameraRecordingService.isRunning() ||
            ticket == null || role !in setOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN) || !FloatingMirrorService.admitsPreviewStart(ticket, role!!) ||
            config == null || config.source.sourceRole != role || !config.mirrorPreviewEnabled || config.validate().isNotEmpty() || !MirrorPreviewPolicy.supportsPreview(config)) {
            if (owner == null) { id?.let(CameraWorkCoordinator::finish); stopSelf() }
            return START_NOT_STICKY
        }
        if (owner != null) return START_NOT_STICKY
        owner = id
        handoffToken = ticket
        val stopIntent = PendingIntent.getService(this, 83, Intent(this, StandaloneMirrorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 84, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, RecorderNotification.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("OpenAVM")
            .setContentText(Utils.t("Preview only", "仅预览")).setOngoing(true).setContentIntent(open)
            .addAction(0, Utils.t("Stop", "停止"), stopIntent).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(0x5E48, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(0x5E48, notification)
        } catch(t: Throwable) { failBeforeOpen("FOREGROUND_CAMERA_PERMISSION"); return START_NOT_STICKY }
        handler.post {
            if (!com.dante.zeekrcapabilitylab.sharing.BrowserShareManager.stopForRecording()) { failBeforeOpen("BROWSER_DOWNLOAD_ACTIVE"); return@post }
            // Use the just-validated profile from the controls handoff. A second catalog
            // read during wake could discard it even before the camera has been opened.
            CaptureCleanupRuntime.awaitIdle(10_000) { idle -> handler.post {
                if (stopping) { finish(); return@post }
                if (!idle) { failBeforeOpen("CAMERA_RELEASE_PENDING"); return@post }
                val next = PreviewProducer(config, id); producer = next; next.open()
            } }
        }
        return START_NOT_STICKY
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

    private inner class PreviewProducer(val config: RecorderConfig, val id: String) : MirrorCameraSource {
        @Volatile override var state = RecorderState(status = RecorderStatus.STARTING, cameraId = config.cameraId,
            profile = config.profile, sourceRole = config.source.sourceRole, layoutKind = config.source.layoutKind,
            mirrorPreviewManaged = true, recordingSessionId = id, cameraGeneration = 1)
            private set
        override val previewOnly = true
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
        private var transaction: CaptureCloseTransaction? = null
        fun open() {
            if (stopping || closing) { finish(); return }
            opening = true
            CameraWorkCoordinator.publish(id, Utils.t("Preparing preview", "正在准备预览"))
            CaptureCleanupRuntime.handler.postDelayed({
                if (opening) {
                    closing = true
                    CameraWorkCoordinator.publish(id, Utils.t("Camera release is unconfirmed", "相机释放尚未确认"), "OPEN_TIMEOUT")
                }
            }, 10_000)
            try {
                CanaryCameraOpenAdapter.open(getSystemService(CameraManager::class.java), config.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opening = false; device = camera
                        if (closing || stopping) { close(); return }
                        state = state.copy(status = RecorderStatus.PREVIEWING)
                        main.post {
                            if (!closing && !stopping) {
                                mirror = runCatching { MirrorPreviewController(applicationContext, config, this@PreviewProducer, id, 1) }
                                    .onFailure { stopPreview() }.getOrNull()
                            }
                        }
                    }
                    override fun onDisconnected(camera: CameraDevice) { opening = false; device = camera; error("CAMERA_DISCONNECTED") }
                    override fun onError(camera: CameraDevice, error: Int) { opening = false; device = camera; error("CAMERA_ERROR_$error") }
                    override fun onClosed(camera: CameraDevice) {
                        transaction?.deviceClosed()
                        sleepCloseEvidence("DEVICE_CLOSED", mapOf("deviceClosed" to "true"))
                    }
                }, handler)
            } catch (t: Throwable) { opening = false; error(t.message ?: "OPEN_FAILED") }
        }
        override fun enable(enabled: Boolean, runId: String, generation: Long) {
            handler.post {
                if (runId != id || generation != 1L || closing || closed) return@post
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
            handler.post {
                if (runId != id || generation != 1L || closing || closed || this.surface != null) {
                    surface.release(); released(); return@post
                }
                val camera = device
                if (camera == null) { surface.release(); released(); error("DEVICE_MISSING"); return@post }
                this.surface = surface; this.released = released
                try {
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (closing || stopping) { value.close(); return }
                            session = value
                            runCatching { if (enabled) repeat(value) }.onFailure { error("PREVIEW_REQUEST_FAILED") }
                        }
                        override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); error("PREVIEW_CONFIG_FAILED") }
                        override fun onClosed(value: CameraCaptureSession) { transaction?.sessionClosed() }
                    }, handler)
                } catch (t: Throwable) { error(t.message ?: "PREVIEW_CONFIG_FAILED") }
            }
        }
        private fun repeat(value: CameraCaptureSession) {
            val request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(requireNotNull(surface)); set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            value.setRepeatingRequest(request, null, handler)
            CameraWorkCoordinator.publish(id, Utils.t("Preview only", "仅预览"))
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
            if (transaction != null || closed) return
            if (opening) return // A timeout never proves a native open has returned.
            val camera = device; val capture = session; val output = surface
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
                }, hasSession = capture != null, hasDevice = camera != null, wasRecording = false, sequences = emptySet(),
                terminal = true, lost = false, preferDeviceClose = true,
                trace = { step, detail -> CaptureCleanupRuntime.trace("mirror-only-$id", step, detail) },
                unconfirmed = { CameraWorkCoordinator.publish(id, Utils.t("Camera release is unconfirmed", "相机释放尚未确认"), "CLEANUP_UNCONFIRMED") },
                completed = { result -> if (result.safeToContinue) {
                    sleepCloseEvidence("CLOSE_SETTLED", mapOf("safeToContinue" to result.safeToContinue.toString(),
                        "deviceClosed" to result.deviceClosed.toString()))
                    closed = true; CaptureCleanupRuntime.settled(hold, true); finish(); worker.quitSafely()
                } })
            transaction = tx; CaptureCleanupRuntime.retain(hold, config.cameraId, camera, tx); tx.begin()
        }
        fun sleepCloseEvidence(stage: String, facts: Map<String, String>) {
            if (!com.dante.zeekrcapabilitylab.BuildConfig.MIRROR_RETURN_ENABLED) return
            com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(com.dante.zeekrcapabilitylab.data.Categories.SYSTEM,
                "MIRROR_PREVIEW_$stage", payload = facts + ("previewOwner" to id))
        }
        override fun stop() { main.post { stopPreview() } }
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
        fun isPreviewing() = instance?.let { service ->
            val producer = service.producer
            !service.stopping && MirrorDisplayState.usable(service) && producer?.state?.status == RecorderStatus.PREVIEWING &&
                producer.displayedFrameAtMs?.let { SystemClock.elapsedRealtime() - it in 0..2_000 } == true
        } == true
        fun stop() { FloatingMirrorService.close(); stopForHandoff() }
        internal fun stopForHandoff() { instance?.main?.post { instance?.stopPreview() } }
    }
}
