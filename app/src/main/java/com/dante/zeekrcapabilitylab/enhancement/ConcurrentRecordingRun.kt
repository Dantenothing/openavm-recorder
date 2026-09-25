package com.dante.zeekrcapabilitylab.enhancement

import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.*
import android.os.*
import android.view.Surface
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.usbexport.*
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.Executor

/** One explicit 60 s experiment. No recovery/reopen, wake lock, grid encoding or internal fallback. */
@androidx.annotation.RequiresApi(30)
class ConcurrentRecordingRun(private val context: Context, private val id: String,
    private val plan: ConcurrentRecordingPlan, private val finished: () -> Unit) {
    private val control = CaptureCleanupRuntime.handler
    private val manager = context.getSystemService(CameraManager::class.java)
    private val barrier = ConcurrentStartBarrier(id, plan.channels.map { it.config.cameraId }.toSet(), plan.target.storageUuid)
    private val channels = plan.channels.map { Channel(it) }
    private val events = mutableListOf<JsonObject>()
    @Volatile private var stopping = false
    @Volatile private var usbLost = false
    private var admitted = false
    private var done = false
    private var beganAt = 0L
    private var recordingAt = 0L
    private var reason = ""
    private var queryInFlight = false
    private val preparation = HandlerThread("multi-preflight").apply { start() }
    private val preparationHandler = Handler(preparation.looper)

    fun start() { control.post {
        beganAt = SystemClock.elapsedRealtime()
        CaptureCleanupRuntime.awaitIdle(10_000) { idle ->
            if (stopping) { finishWithoutDevices("CANCELLED"); return@awaitIdle }
            if (!idle || !CanaryCameraInterlock.beginNormalGroup(channels.associate { it.token to it.cameraId })) {
                finishWithoutDevices("CAMERA_RELEASE_PENDING"); return@awaitIdle
            }
            admitted = true
            preparationHandler.post {
                val admission = runCatching { UsbSegmentRetentionManager(context).admit(plan.target, plan.estimatedBytes,
                    plan.channels.minOf { it.config.usbQuotaBytes }, "multi:$id") }
                control.post {
                    if (admission.isFailure || stopping) {
                        channels.forEach { it.cancelUnsubmittedOpen() }
                        stopInternal(admission.exceptionOrNull()?.message ?: "CANCELLED"); return@post
                    }
                    event("RESERVED", channels.joinToString { it.cameraId })
                    channels.forEach { it.open() }
                }
            }
            control.post(watchdog)
        }
    } }
    fun stop(reason: String = "USER") { control.post { stopInternal(reason) } }
    private fun stopInternal(value: String) {
        if (done) return
        if (reason.isBlank()) reason = value
        if (value == "USB_LOST") usbLost = true
        stopping = true
        barrier.stop(if (usbLost) ConcurrentStartBarrier.StopReason.USB_LOST else if(value == "USER" || value == "DURATION") ConcurrentStartBarrier.StopReason.USER else ConcurrentStartBarrier.StopReason.CHANNEL_FAILED)
        event("STOP", value)
        CameraWorkCoordinator.publish(id, Utils.t("Stopping both cameras…", "正在停止两路摄像头…"), value.takeUnless { it in setOf("USER", "DURATION") })
        if (admitted) channels.forEach { it.closeWhenReady() }
        maybeFinish()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (done) return
            val now = SystemClock.elapsedRealtime()
            if (!stopping) {
                if (recordingAt == 0L && now - beganAt > 20_000) stopInternal("START_TIMEOUT")
                else if (recordingAt > 0 && now - recordingAt >= ConcurrentRecordingPlan.DURATION_SECONDS * 1000L) stopInternal("DURATION")
                else if (recordingAt > 0 && channels.any { now - it.lastWrittenAt > 5_000 }) stopInternal("ENCODED_PROGRESS_STALLED")
                if (!queryInFlight) {
                    queryInFlight = true
                    preparationHandler.post {
                        val present = UsbExportVolumeResolver.isRemovableVolumeMounted(context, plan.target.storageUuid)
                        control.post { queryInFlight = false; if (!present && !done) stopInternal("USB_LOST") }
                    }
                }
                if (barrier.phase == ConcurrentStartBarrier.Phase.RECORDING && !stopping) CameraWorkCoordinator.publish(id,
                    Utils.t("Both cameras writing · {0} / 60 s", "两路正在写入 · {0} / 60 秒", ((now - recordingAt) / 1000).coerceAtLeast(0)))
            }
            control.postDelayed(this, 500)
        }
    }
    private fun event(step: String, detail: String = "") {
        if (events.size >= 100) return
        events += buildJsonObject { put("at", System.currentTimeMillis()); put("step", step); put("detail", detail.take(250)) }
        val report = reportJson()
        preparationHandler.post { writeReport(report) }
    }
    private fun finishWithoutDevices(error: String) {
        reason = error; stopping = true; done = true
        channels.forEach { it.shutdownUnused() }
        finishReport()
    }
    private fun maybeFinish() {
        if (!done && stopping && admitted && barrier.phase == ConcurrentStartBarrier.Phase.DEVICES_CLOSED && channels.all { it.settled }) {
            done = true; finishReport()
        }
    }
    private fun reportJson(): String = buildJsonObject {
            put("schemaVersion", 1); put("format", "OPENAVM_CONCURRENT_TEST"); put("run", id); put("reason", reason)
            put("active", !done); put("phase", barrier.phase.name)
            put("durationLimitSeconds", ConcurrentRecordingPlan.DURATION_SECONDS)
            put("channels", buildJsonArray { channels.forEach { add(it.diagnostics()) } })
            put("events", JsonArray(events.toList()))
        }.toString()
    private fun writeReport(report: String) {
        runCatching { android.util.AtomicFile(File(context.filesDir, "multi-camera-last.json")).let { file ->
            val stream = file.startWrite(); try { stream.write(report.toByteArray()); file.finishWrite(stream) } catch(t: Throwable) { file.failWrite(stream); throw t }
        } }
    }
    private fun finishReport() {
        control.removeCallbacks(watchdog)
        val report = reportJson()
        preparationHandler.post {
            writeReport(report)
            Handler(Looper.getMainLooper()).post {
                val ok = reason in setOf("USER", "DURATION") && channels.all { it.committed && it.settled }
                val message = if(ok) Utils.t("Two-camera test saved to USB. Check both videos in Library.", "两路测试已保存到 USB，请在记录中检查两段视频。")
                    else Utils.t("Two-camera test ended: {0}. Copy the test report for details.", "两路测试已结束：{0}。可复制测试报告查看详情。", reason)
                context.getSharedPreferences("multi_camera", 0).edit().putString("result", message).apply()
                CameraWorkCoordinator.finish(id)
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                finished()
            }
            preparation.quitSafely()
        }
    }

    private inner class Channel(val plan: ConcurrentChannelPlan) {
        val cameraId = plan.config.cameraId
        val token = Any()
        private val nativeThread = HandlerThread("multi-camera-$cameraId").apply { start() }
        private val native = Handler(nativeThread.looper)
        private val outputThread = HandlerThread("multi-writer-$cameraId").apply { start() }
        private val writer = Handler(outputThread.looper)
        @Volatile private var camera: CameraDevice? = null
        @Volatile private var session: CameraCaptureSession? = null
        private var submitted = false
        private var openPending = false
        private var preparing = false
        private var prepared = false
        private var key = ""
        private var transaction: CaptureCloseTransaction? = null
        @Volatile private var codec: MediaCodec? = null
        private var codecStarted = false
        @Volatile private var input: Surface? = null
        private var muxer: MediaMuxer? = null
        private var track = -1
        private var muxerStarted = false
        private var eos = false
        private var firstPts = -1L
        private var previousPts = -1L
        private var startedWall = 0L
        @Volatile var output: UsbMediaStoreRecordingOutputHandle? = null; private set
        @Volatile var samples = 0L; private set
        @Volatile var lastWrittenAt = 0L; private set
        @Volatile var committed = false; private set
        @Volatile var failure: String? = null; private set
        var settled = false; private set
        var configuration: SessionConfiguration? = null; private set

        fun diagnostics() = buildJsonObject {
            put("cameraId", cameraId); put("role", plan.config.source.sourceRole.name)
            put("openWorkSubmitted", submitted); put("openPending", openPending); put("deviceObserved", camera != null)
            put("samplesWritten", samples); put("committed", committed); put("released", settled)
            put("error", failure); put("file", output?.displayName)
        }

        fun open() {
            submitted = true; openPending = true
            native.post {
                if(stopping) {
                    control.post { openPending = false; CanaryCameraInterlock.normalClosed(token); barrier.failedWithoutDevice(id, cameraId); closeWhenReady() }
                    return@post
                }
                try {
                    CanaryCameraOpenAdapter.openReserved(manager, cameraId, token, object : CameraDevice.StateCallback() {
                        override fun onOpened(value: CameraDevice) { control.post { opened(value) } }
                        override fun onDisconnected(value: CameraDevice) { control.post { failedWithDevice(value, "DISCONNECTED") } }
                        override fun onError(value: CameraDevice, error: Int) { control.post { failedWithDevice(value, "CAMERA_ERROR_$error") } }
                        override fun onClosed(value: CameraDevice) { control.post {
                            if (camera === value) { barrier.deviceClosed(id, key); transaction?.deviceClosed(); if (!stopping) stopInternal("UNEXPECTED_CAMERA_CLOSE") }
                        } }
                    }, native)
                } catch (t: Throwable) { control.post { openPending = false; barrier.failedWithoutDevice(id, cameraId); stopInternal("OPEN_FAILED:${t.javaClass.simpleName}"); closeWhenReady() } }
            }
        }
        fun cancelUnsubmittedOpen() {
            if (!submitted) { CanaryCameraInterlock.normalClosed(token); barrier.failedWithoutDevice(id, cameraId); closeWhenReady() }
        }
        private fun opened(value: CameraDevice) {
            openPending = false; camera = value; key = "$cameraId:${System.identityHashCode(value)}"
            val action = barrier.opened(id, cameraId, key)
            event("DEVICE_OBSERVED", cameraId)
            if (stopping || action == ConcurrentStartBarrier.OpenAction.CLOSE_DEVICE) { closeWhenReady(); return }
            if (action == ConcurrentStartBarrier.OpenAction.CONFIGURE_ALL) {
                event("ALL_OPENED")
                channels.forEach { it.prepare() }
            }
        }
        private fun failedWithDevice(value: CameraDevice, error: String) {
            openPending = false
            if (camera == null) { camera = value; key = "$cameraId:${System.identityHashCode(value)}"; barrier.opened(id, cameraId, key) }
            failure = error; stopInternal(error)
        }
        private fun prepare() {
            if (stopping) { closeWhenReady(); return }
            preparing = true
            writer.post {
                val result = runCatching {
                    check(!stopping) { "CANCELLED" }
                    startedWall = System.currentTimeMillis()
                    output = UsbMediaStoreRecordingOutputSink(context, this@ConcurrentRecordingRun.plan.target, "$id-$cameraId", RecordingMode.NORMAL,
                        File(context.filesDir, "recordings/usb-tokens"), UsbRecordingRecoveryJournal(context))
                        .openSegment(1, plan.config.profile, startedWall) as UsbMediaStoreRecordingOutputHandle
                    check(!stopping) { "CANCELLED" }
                    muxer = output!!.createMuxer()
                    codec = MediaCodec.createByCodecName(plan.encoder)
                    check(!stopping) { "CANCELLED" }
                    codec!!.configure(ConcurrentRecordingPreflight.format(plan.config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    input = codec!!.createInputSurface(); codec!!.start(); codecStarted = true
                    writer.post(pump)
                }
                control.post {
                    preparing = false
                    if (result.isFailure) { failure = result.exceptionOrNull()?.javaClass?.simpleName; stopInternal("ENCODER_PREPARE_FAILED"); closeWhenReady(); return@post }
                    prepared = true
                    configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(requireNotNull(input))),
                        Executor { command -> native.post(command) }, sessionCallbacks)
                    if (stopping) closeWhenReady()
                    else if (channels.all { it.prepared }) checkAndConfigure()
                }
            }
        }
        private val sessionCallbacks = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                session = value
                control.post {
                    if (stopping) { native.post { value.close() }; return@post }
                    barrier.consumerReady(id, barrier.configurationEpoch, cameraId, key)
                    if (barrier.maySubmitCapture) channels.forEach { it.submitCapture() }
                }
            }
            override fun onConfigureFailed(value: CameraCaptureSession) { session = value; control.post { stopInternal("CONFIGURE_FAILED:$cameraId") } }
            override fun onClosed(value: CameraCaptureSession) { transaction?.sessionClosed() }
        }
        fun submitCapture() {
            native.post {
                if (stopping) return@post
                runCatching {
                    val request = requireNotNull(camera).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(requireNotNull(input)); set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    requireNotNull(session).setRepeatingRequest(request, null, native)
                }.onFailure { control.post { stopInternal("CAPTURE_FAILED:$cameraId") } }
            }
        }
        private val pump = object : Runnable {
            override fun run() {
                if (!codecStarted || stopping) return
                runCatching { repeat(8) { drain(0) } }.onFailure { failure = "WRITE:${it.javaClass.simpleName}"; control.post { stopInternal("ENCODE_WRITE_FAILED:$cameraId") } }
                if (!stopping) writer.postDelayed(this, 10)
            }
        }
        private fun drain(timeoutUs: Long) {
            val encoder = codec ?: return
            val info = MediaCodec.BufferInfo()
            val index = encoder.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(!muxerStarted); track = requireNotNull(muxer).addTrack(encoder.outputFormat); muxer!!.start(); muxerStarted = true
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && !usbLost) {
                        if (firstPts < 0 && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) firstPts = info.presentationTimeUs
                        if (firstPts >= 0) {
                            check(muxerStarted); val pts = info.presentationTimeUs - firstPts; check(pts > previousPts)
                            val data = requireNotNull(encoder.getOutputBuffer(index)); data.position(info.offset); data.limit(info.offset + info.size)
                            info.presentationTimeUs = pts; muxer!!.writeSampleData(track, data, info)
                            previousPts = pts; samples++; lastWrittenAt = SystemClock.elapsedRealtime()
                            if (samples == 1L) control.post {
                                barrier.writtenSample(id, barrier.configurationEpoch, cameraId, key)
                                if (barrier.phase == ConcurrentStartBarrier.Phase.RECORDING && recordingAt == 0L) {
                                    recordingAt = SystemClock.elapsedRealtime(); event("BOTH_WRITING")
                                }
                            }
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                } finally { encoder.releaseOutputBuffer(index, false) }
            }
        }
        fun closeWhenReady() {
            if (!stopping || openPending || preparing || settled || transaction != null) return
            val device = camera; val capture = session; val hold = Any()
            val tx = CaptureCloseTransaction(CaptureCleanupRuntime.control, HandlerCloseDispatcher(native), HandlerCloseDispatcher(writer),
                object : CaptureCloseResources {
                    override fun stopRepeating() { capture?.stopRepeating() }
                    override fun abortCaptures() { capture?.abortCaptures() }
                    override fun closeSession() { capture?.close() }
                    override fun closeDevice() { device?.close() }
                    override fun stopRecorder() = Unit
                    override fun resetRecorder() = Unit
                    override fun releaseRecorder() = Unit // MediaCodec belongs to the output worker below.
                    override fun closeOutput(lost: Boolean) { finishOutput() }
                }, hasSession = capture != null, hasDevice = device != null, wasRecording = false, sequences = emptySet(),
                terminal = true, lost = usbLost, preferDeviceClose = true,
                trace = { step, detail -> CaptureCleanupRuntime.trace("multi-$id-$cameraId", step, detail) },
                unconfirmed = { error -> event("CLEANUP_UNCONFIRMED", "$cameraId:$error"); CameraWorkCoordinator.publish(id, Utils.t("Camera release is unconfirmed", "相机释放尚未确认"), error) },
                completed = { result -> if (result.safeToContinue) {
                    settled = true; CaptureCleanupRuntime.settled(hold, true); nativeThread.quitSafely(); outputThread.quitSafely(); event("CHANNEL_RELEASED", cameraId); maybeFinish()
                } })
            transaction = tx; CaptureCleanupRuntime.retain(hold, cameraId, device, tx); tx.begin()
        }
        /** Called only after actual device close, on this channel's writer thread. */
        private fun finishOutput() {
            writer.removeCallbacks(pump)
            var writeError: Throwable? = null
            if (codecStarted && !usbLost) runCatching {
                codec!!.signalEndOfInputStream()
                val deadline = SystemClock.elapsedRealtime() + 2_000
                while (!eos && SystemClock.elapsedRealtime() < deadline) drain(10_000)
                check(eos) { "ENCODER_EOS_TIMEOUT" }
            }.onFailure { writeError = it }
            // Release errors retain the cleanup owner. A write error alone does not retain a closed camera.
            if (codecStarted) runCatching { codec!!.stop() }.onFailure { writeError = writeError ?: it }
            codec?.release(); codec = null; codecStarted = false
            input?.release(); input = null
            if (muxerStarted && !usbLost) runCatching { muxer!!.stop() }.onFailure { writeError = writeError ?: it }
            muxer?.release(); muxer = null
            val handle = output ?: return
            if (usbLost) { handle.abandonUnavailableTarget(); failure = "USB_LOST"; return }
            handle.close()
            if (samples < 2 || writeError != null) {
                failure = writeError?.message ?: "NO_DECODABLE_SAMPLES"; handle.abort(); return
            }
            runCatching {
                UsbSegmentCommitEngine(context).commitDirect(handle.pendingVideo, SegmentSidecar(
                    file = handle.pendingVideo.itemUri, cameraId = cameraId, profile = plan.config.profile,
                    sourceRole = plan.config.source.sourceRole, layoutKind = plan.config.source.layoutKind,
                    mappingRevision = plan.config.source.mappingRevision, laneLayout = plan.config.source.laneLayout,
                    segmentSeconds = 60, segmentNumber = 1, processStartId = ZeekrApp.processStartId,
                    recordingSessionId = "$id-$cameraId", startedAtEpochMs = startedWall, stoppedAtEpochMs = System.currentTimeMillis(),
                    result = SegmentSidecar.RESULT_SUCCESS, finalizeReason = "CONCURRENT_TEST:$reason",
                    realDurationMs = previousPts / 1000))
                handle.markCommitted(); committed = true
            }.onFailure { failure = "COMMIT:${it.javaClass.simpleName}"; handle.abort() }
        }
        fun configure() { native.post {
            if (!stopping) runCatching { requireNotNull(camera).createCaptureSession(requireNotNull(configuration)) }
                .onFailure { control.post { stopInternal("SESSION_CREATE_FAILED:$cameraId") } }
        } }
        fun shutdownUnused() { settled = true; nativeThread.quitSafely(); outputThread.quitSafely() }
    }
    private fun checkAndConfigure() {
        preparationHandler.post {
            val supported = try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("CAMERA_PERMISSION_REVOKED")
                }
                Result.success(manager.isConcurrentSessionConfigurationSupported(channels.associate { it.cameraId to requireNotNull(it.configuration) }))
            } catch (denied: SecurityException) { Result.failure(denied) }
              catch (failure: Exception) { Result.failure(failure) }
            control.post {
                if (stopping) channels.forEach { it.closeWhenReady() }
                else if (supported.getOrDefault(false)) { event("CONFIGURATION_DECLARED"); channels.forEach { it.configure() } }
                else stopInternal("CONCURRENT_CONFIGURATION_NOT_SUPPORTED")
            }
        }
    }
}
