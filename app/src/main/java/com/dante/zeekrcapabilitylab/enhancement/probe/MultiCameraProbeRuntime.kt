package com.dante.zeekrcapabilitylab.enhancement.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.*
import android.util.AtomicFile
import android.view.Surface
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.RecordingSourcePolicy
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.Executor

data class ProbeSource(val slot: Int, val id: String, val size: ProfileSize)
data class ProbeCatalog(val sources: List<ProbeSource>, val declaredSets: Set<Set<String>>,
    val query: ProbeDeclaration, val warnings: List<String>)
data class ProbeUiState(val catalog: ProbeCatalog? = null, val reading: Boolean = false,
    val active: Boolean = false, val phase: String = "IDLE", val reason: String? = null,
    val frames: List<ProbeFrameSnapshot> = emptyList(), val observationMs: Long = 0,
    val report: String? = null, val reportSaved: Boolean = false)

class ProbePreviewTarget(val id: String, val texture: SurfaceTexture) {
    val ownership = ProbeTextureOwnership()
}

/** No background service, wake lock, auto start, recording, or retry. One foreground experiment at a time. */
object MultiCameraProbeRuntime {
    private val control get() = CaptureCleanupRuntime.handler
    private val metadata by lazy { Handler(HandlerThread("multi-preview-metadata").apply { start() }.looper) }
    private val journal by lazy { Handler(HandlerThread("multi-preview-journal").apply { start() }.looper) }
    private val mutable = MutableStateFlow(ProbeUiState())
    val state = mutable.asStateFlow()
    @Volatile private var visible = false
    private var inspectPending = false
    // Deliberately process-scoped until native cleanup; PreviewRun only retains applicationContext.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var run: PreviewRun? = null

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) stop("PAGE_HIDDEN")
    }

    fun inspect(context: Context) {
        val app = context.applicationContext
        control.post {
            if (!BuildConfig.CAMERA_CONCURRENCY_PROBE_ENABLED || run != null || inspectPending) return@post
            inspectPending = true
            mutable.value = mutable.value.copy(reading = true, reason = null)
            var timedOut = false
            val timeout = Runnable {
                timedOut = true
                mutable.value = mutable.value.copy(reading = false, reason = "METADATA_TIMEOUT")
            }
            control.postDelayed(timeout, 15_000)
            metadata.post {
                val result = runCatching { readCatalog(app) }
                control.post {
                    inspectPending = false
                    control.removeCallbacks(timeout)
                    if (!timedOut) mutable.value = mutable.value.copy(reading = false,
                        catalog = result.getOrNull(), reason = result.exceptionOrNull()?.let { "METADATA_${it.javaClass.simpleName}" })
                }
            }
        }
    }

    private fun readCatalog(context: Context): ProbeCatalog {
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList.take(8)
        val warnings = mutableListOf<String>()
        val sizes = ids.associateWith { id ->
            runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { ProfileSize(it.width, it.height) } }
                .getOrElse { warnings += "CAMERA_QUERY_FAILED:$id"; emptyList() }
        }
        val prefs = SettingsStore.get(context)
        val sources = listOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN, RecordingSourceRole.IR)
            .mapIndexedNotNull { index, role ->
                val mapping = prefs.cameraMapping(role)
                val id = if (index == 0 && mapping == RecordingSourcePolicy.AUTO) {
                    val composite = ids.filter { MultiCameraProbePolicy.size(true, sizes.getValue(it)) != null }
                    composite.singleOrNull() ?: composite.firstOrNull { it == RecordingSourcePolicy.DEFAULT_SURROUND_CAMERA_ID }
                } else mapping.takeIf { it in ids }
                val size = id?.let { MultiCameraProbePolicy.size(index == 0, sizes.getValue(it)) }
                if (id == null || size == null) { warnings += "SOURCE_UNAVAILABLE:$role"; null }
                else ProbeSource(index, id, size)
            }
        val sets = if (Build.VERSION.SDK_INT >= 30) runCatching { manager.concurrentCameraIds } else null
        return ProbeCatalog(sources, sets?.getOrNull().orEmpty(), when {
            sets == null -> ProbeDeclaration.API_UNAVAILABLE
            sets.isFailure -> ProbeDeclaration.QUERY_FAILED
            else -> ProbeDeclaration.DECLARED // Actual selection is classified separately.
        }, warnings)
    }

    fun start(context: Context, slots: List<Int>, targets: Map<String, ProbePreviewTarget>, parked: Boolean,
        allowUnlisted: Boolean) {
        val app = context.applicationContext
        control.post {
            if (run != null || mutable.value.active || inspectPending) return@post
            mutable.value = mutable.value.copy(phase = "IDLE", reason = null, frames = emptyList(),
                observationMs = 0, report = null, reportSaved = false)
            val catalog = mutable.value.catalog ?: return@post
            val selected = slots.mapNotNull { slot -> catalog.sources.find { it.slot == slot } }
            val ids = selected.map { it.id }
            val declaration = if (catalog.query == ProbeDeclaration.DECLARED)
                MultiCameraProbePolicy.declaration(ids, catalog.declaredSets) else catalog.query
            val rejection = MultiCameraProbePolicy.rejection(BuildConfig.CAMERA_CONCURRENCY_PROBE_ENABLED, visible,
                parked, app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                ids, declaration, allowUnlisted)
                ?: if (selected.size != slots.size || slots.distinct().size != slots.size || 0 !in slots) "INVALID_SELECTION" else null
                ?: if (CameraRecordingService.isRunning()) "STOP_RECORDING_FIRST" else null
                ?: if (ids.any { targets[it] == null }) "PREVIEW_SURFACE_NOT_READY" else null
            if (rejection != null) { mutable.value = mutable.value.copy(reason = rejection); return@post }
            if (Build.VERSION.SDK_INT < 30) return@post
            val owner = CameraWorkCoordinator.claim("MULTI_PREVIEW")
            if (owner == null) { mutable.value = mutable.value.copy(reason = "CAMERA_WORK_BUSY"); return@post }
            val claimed = mutableListOf<ProbePreviewTarget>()
            for (id in ids) {
                val target = targets.getValue(id)
                if (!target.ownership.claim()) {
                    claimed.forEach { if (it.ownership.release()) it.texture.release() }
                    CameraWorkCoordinator.finish(owner)
                    mutable.value = mutable.value.copy(reason = "PREVIEW_SURFACE_NOT_READY")
                    return@post
                }
                claimed += target
            }
            mutable.value = mutable.value.copy(active = true, phase = "WAITING_FOR_IDLE", reason = null,
                frames = emptyList(), observationMs = 0, report = null, reportSaved = false)
            run = PreviewRun(app, owner, selected, claimed, declaration, catalog.declaredSets).also { it.begin() }
        }
    }

    fun frame(target: ProbePreviewTarget, timestamp: Long, at: Long) {
        control.post { if (Build.VERSION.SDK_INT >= 30) run?.frame(target, timestamp, at) }
    }
    fun surfaceDestroyed(target: ProbePreviewTarget) {
        control.post { if (Build.VERSION.SDK_INT >= 30) run?.takeIf { it.hasTarget(target) }?.stop("SURFACE_DESTROYED") }
    }
    fun stop(reason: String = "USER_STOP") { control.post { if (Build.VERSION.SDK_INT >= 30) run?.stop(reason) } }

    @androidx.annotation.RequiresApi(30)
    private class PreviewRun(private val context: Context, private val owner: String,
        private val selected: List<ProbeSource>, targets: List<ProbePreviewTarget>,
        private val declaration: ProbeDeclaration, private val declaredSets: Set<Set<String>>) {
        private val manager = context.getSystemService(CameraManager::class.java)
        private val began = SystemClock.elapsedRealtime()
        private val beganWall = System.currentTimeMillis()
        private val evidence = MultiCameraFrameEvidence(selected.map { it.id }, began)
        private val channels = selected.zip(targets).map { (source, target) -> Channel(source, target) }
        @Volatile private var stopping = false
        private var admitted = false
        private var complete = false
        private var configuring = false
        private var reason = ""
        private var stopAt = 0L
        private var releaseUnconfirmed = false
        private var configurationQuery = "NOT_RUN"
        private val events = mutableListOf<JsonElement>()

        fun hasTarget(target: ProbePreviewTarget) = channels.any { it.target === target }
        fun frame(target: ProbePreviewTarget, timestamp: Long, at: Long) {
            if (!stopping && channels.any { it.target === target && it.capturing }) evidence.frame(target.id, timestamp, at)
        }
        fun begin() {
            event("REQUESTED")
            control.post(tick)
            CaptureCleanupRuntime.awaitIdle(8_000) { idle ->
                if (stopping) return@awaitIdle
                if (!idle || !visible) { stop(if (visible) "CAMERA_RELEASE_PENDING" else "PAGE_HIDDEN"); return@awaitIdle }
                if (!CanaryCameraInterlock.beginNormalGroup(channels.associate { it.token to it.source.id })) {
                    stop("CAMERA_WORK_BUSY"); return@awaitIdle
                }
                admitted = true
                mutable.value = mutable.value.copy(phase = "OPENING")
                channels.forEach { it.open() }
            }
        }

        private val tick = object : Runnable {
            override fun run() {
                if (complete) return
                val now = SystemClock.elapsedRealtime()
                if (!stopping) {
                    val interruption = when {
                        !visible -> "PAGE_HIDDEN"
                        !context.getSystemService(PowerManager::class.java).isInteractive -> "SCREEN_OFF"
                        context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> "PERMISSION_REVOKED"
                        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> "THERMAL_SEVERE"
                        else -> evidence.tick(now)
                    }
                    if (interruption != null) stop(interruption)
                } else if (now - stopAt >= 8_000 && !releaseUnconfirmed) {
                    releaseUnconfirmed = true
                    mutable.value = mutable.value.copy(phase = "RELEASE_UNCONFIRMED")
                    event("RELEASE_UNCONFIRMED")
                    saveReport(false)
                }
                mutable.value = mutable.value.copy(frames = evidence.snapshot(now),
                    observationMs = evidence.simultaneousSince?.let { ((if (stopping) stopAt else now) - it).coerceAtLeast(0) } ?: 0)
                if (!stopping && evidence.simultaneousSince != null) mutable.value = mutable.value.copy(phase = "OBSERVING")
                // No busy polling of an unconfirmed native owner; a late callback still settles it.
                if (!complete && !releaseUnconfirmed) control.postDelayed(this, 250)
            }
        }

        fun stop(value: String) {
            if (stopping || complete) return
            stopping = true; reason = value; stopAt = SystemClock.elapsedRealtime()
            evidence.stop(value)
            mutable.value = mutable.value.copy(phase = "STOPPING", reason = value)
            event("STOP_REQUESTED", value)
            channels.forEach { it.closeIfReady() }
        }

        private fun event(name: String, detail: String = "") {
            if (events.size < 100) events += buildJsonObject {
                put("ms", SystemClock.elapsedRealtime() - began); put("event", name); put("detail", detail.take(120))
            }
        }
        private fun finished() {
            if (!stopping || complete || channels.any { !it.settled }) return
            complete = true; control.removeCallbacks(tick)
            event("ALL_RELEASED")
            mutable.value = mutable.value.copy(active = false, phase = "FINISHED", reason = reason,
                frames = evidence.snapshot(stopAt))
            saveReport(true)
            CameraWorkCoordinator.finish(owner)
            run = null
        }
        private fun saveReport(released: Boolean) {
            val report = buildJsonObject {
                put("schemaVersion", 1); put("format", "OPENAVM_MULTI_PREVIEW_PROBE")
                put("run", owner); put("appVersion", BuildConfig.VERSION_NAME); put("source", BuildConfig.GIT_SHA)
                put("startedAtEpochMs", beganWall); put("model", Build.MODEL.take(120)); put("firmware", Build.DISPLAY.take(160))
                put("declaration", declaration.name); put("unlistedOptIn", declaration == ProbeDeclaration.NOT_LISTED)
                put("declaredSets", buildJsonArray { declaredSets.take(12).forEach { add(JsonArray(it.sorted().take(8).map(::JsonPrimitive))) } })
                put("configurationQuery", configurationQuery); put("reason", reason)
                put("previewObserved", reason == "PREVIEW_OBSERVED")
                put("cleanup", if (released) "CONFIRMED" else "UNCONFIRMED")
                put("recordingTested", false); put("sleepRecoveryTested", false); put("videoIncluded", false)
                put("observationMs", evidence.simultaneousSince?.let { (stopAt - it).coerceAtLeast(0) } ?: 0)
                put("channels", buildJsonArray { channels.forEach { c -> add(buildJsonObject {
                    val sample = evidence.snapshot(stopAt).first { it.id == c.source.id }
                    put("slot", c.source.slot); put("cameraId", c.source.id); put("requestedSurfaceSize", c.source.size.toString())
                    put("openRequested", c.submitted); put("opened", c.openObserved); put("captures", c.captures)
                    put("textureFrames", sample.frames); put("firstFrameAfterMs", sample.firstFrameAfterMs)
                    put("lastFrameAgeMs", sample.lastFrameAgeMs); put("displayedAverageFps", sample.averageFps)
                    put("released", c.settled); put("error", c.failure)
                }) } })
                put("events", JsonArray(events.toList()))
            }.toString()
            mutable.value = mutable.value.copy(report = report, reportSaved = false)
            journal.post {
                val saved = runCatching {
                    val file = AtomicFile(File(context.noBackupFilesDir, "multi-preview-last.json"))
                    val out = file.startWrite()
                    try { out.write(report.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
                    catch (error: Throwable) { file.failWrite(out); throw error }
                }.isSuccess
                control.post { if (mutable.value.report == report) mutable.value = mutable.value.copy(reportSaved = saved) }
            }
        }

        private inner class Channel(val source: ProbeSource, val target: ProbePreviewTarget) {
            val token = Any()
            private val worker = HandlerThread("multi-preview-${source.slot}").apply { start() }
            private val native = Handler(worker.looper)
            var submitted = false; private set
            private var openPending = false
            var openObserved = false; private set
            private var device: CameraDevice? = null
            private var session: CameraCaptureSession? = null
            @Volatile private var surface: Surface? = null
            var capturing = false; private set
            var captures = 0L; private set
            var settled = false; private set
            var failure: String? = null; private set
            private var tx: CaptureCloseTransaction? = null
            private var configReady = false

            fun open() {
                submitted = true; openPending = true
                native.post {
                    if (stopping) { control.post { noDevice("CANCELLED_BEFORE_OPEN") }; return@post }
                    try {
                        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "PERMISSION_REVOKED" }
                        target.texture.setDefaultBufferSize(source.size.width, source.size.height)
                        surface = Surface(target.texture)
                        check(surface!!.isValid) { "INVALID_SURFACE" }
                        CanaryCameraOpenAdapter.openReserved(manager, source.id, token, object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                openPending = false; device = camera; openObserved = true
                                event("OPENED", source.id)
                                if (stopping) closeIfReady()
                                else if (!configuring && channels.all { it.device != null }) configureAll()
                            }
                            override fun onDisconnected(camera: CameraDevice) = failedDevice(camera, "DISCONNECTED")
                            override fun onError(camera: CameraDevice, error: Int) = failedDevice(camera, "CAMERA_ERROR_$error")
                            override fun onClosed(camera: CameraDevice) {
                                if (device === camera) {
                                    tx?.deviceClosed()
                                    if (!stopping) stop("DEVICE_CLOSED:${source.id}")
                                }
                            }
                        }, control)
                    } catch (error: Exception) {
                        val code = if (error is CameraAccessException) "CAMERA_ACCESS_${error.reason}" else error.javaClass.simpleName
                        control.post { noDevice(code) }
                    }
                }
            }
            private fun noDevice(code: String) {
                openPending = false; failure = code
                CanaryCameraInterlock.normalClosed(token)
                if (!stopping) stop("OPEN_FAILED:${source.id}:$code")
                closeIfReady()
            }
            private fun failedDevice(camera: CameraDevice, code: String) {
                openPending = false; device = camera; failure = code
                stop("$code:${source.id}"); closeIfReady()
            }
            fun configuration(): SessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(requireNotNull(surface))), Executor { command -> control.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        session = value; configReady = true
                        if (stopping) { native.post { runCatching { value.close() } }; closeIfReady() }
                        else if (channels.all { it.configReady }) channels.forEach { it.capture() }
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        session = value; failure = "CONFIGURE_FAILED"
                        stop("CONFIGURE_FAILED:${source.id}"); closeIfReady()
                    }
                    override fun onClosed(value: CameraCaptureSession) { tx?.sessionClosed() }
                })

            fun configure() { native.post {
                if (!stopping) runCatching { requireNotNull(device).createCaptureSession(configuration()) }
                    .onFailure { control.post { failure = it.javaClass.simpleName; stop("SESSION_FAILED:${source.id}") } }
            } }
            private fun capture() {
                capturing = true
                native.post {
                    if (stopping) return@post
                    runCatching {
                        val request = requireNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply { addTarget(requireNotNull(surface)) }.build()
                        requireNotNull(session).setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                if (!stopping) captures++
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                if (!stopping) stop("CAPTURE_FAILED:${source.id}:${failure.reason}")
                            }
                            override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frame: Long) {
                                if (!stopping) stop("BUFFER_LOST:${source.id}")
                            }
                        }, control)
                    }.onFailure { control.post { failure = it.javaClass.simpleName; stop("CAPTURE_REQUEST_FAILED:${source.id}") } }
                }
            }

            fun closeIfReady() {
                if (!stopping || openPending || settled || tx != null) return
                if (admitted && !submitted) CanaryCameraInterlock.normalClosed(token)
                val camera = device; val capture = session; val hold = Any()
                val transaction = CaptureCloseTransaction(CaptureCleanupRuntime.control, HandlerCloseDispatcher(native),
                    HandlerCloseDispatcher(native), object : CaptureCloseResources {
                        override fun stopRepeating() { capture?.stopRepeating() }
                        override fun abortCaptures() { capture?.abortCaptures() }
                        override fun closeSession() { capture?.close() }
                        override fun closeDevice() { camera?.close() }
                        override fun stopRecorder() = Unit
                        override fun resetRecorder() = Unit
                        override fun releaseRecorder() = Unit
                        override fun closeOutput(lost: Boolean) {
                            surface?.release(); surface = null
                            if (target.ownership.release()) target.texture.release()
                        }
                    }, hasSession = capture != null, hasDevice = camera != null, wasRecording = false,
                    sequences = emptySet(), terminal = true, lost = false, preferDeviceClose = true,
                    trace = { name, detail -> CaptureCleanupRuntime.trace("multi-preview-$owner-${source.id}", name, detail) },
                    unconfirmed = { code -> failure = code; event("CLEANUP_WAIT", "${source.id}:$code") },
                    completed = { result -> if (result.safeToContinue) {
                        settled = true; CaptureCleanupRuntime.settled(hold, true)
                        worker.quitSafely(); event("RELEASED", source.id); finished()
                    } })
                tx = transaction
                CaptureCleanupRuntime.retain(hold, source.id, camera, transaction)
                transaction.begin()
            }
        }

        private fun configureAll() {
            configuring = true
            mutable.value = mutable.value.copy(phase = "CONFIGURING")
            if (declaration == ProbeDeclaration.NOT_LISTED) {
                configurationQuery = "SKIPPED_UNLISTED_EXPLICIT_PROBE"
                event("UNLISTED_RUNTIME_ATTEMPT")
                channels.forEach { it.configure() }
            } else {
                metadata.post {
                    val supported = runCatching {
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                            throw SecurityException("PERMISSION_REVOKED")
                        manager.isConcurrentSessionConfigurationSupported(channels.associate { it.source.id to it.configuration() })
                    }
                    control.post {
                        if (stopping) return@post
                        configurationQuery = supported.fold({ it.toString() }, { "QUERY_FAILED" })
                        if (supported.getOrDefault(false)) channels.forEach { it.configure() }
                        else stop("DECLARED_CONFIGURATION_REJECTED")
                    }
                }
            }
        }
    }
}
