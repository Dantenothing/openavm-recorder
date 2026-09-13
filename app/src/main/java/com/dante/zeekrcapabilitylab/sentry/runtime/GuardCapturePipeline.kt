package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.canary.*
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import java.util.UUID

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.dante.zeekrcapabilitylab.sentry.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable

/** Integrated producer based on the vehicle-qualified Canary 5 path; lifecycle owned by GuardService. */
class GuardCapturePipeline(
    private val context: Context,
    private val runId: String,
    private val stamp: ModeStamp,
    private val configuredSource: SessionSourceSnapshot,
    private val configuredCamera: GuardCameraCapabilities,
    private val onAi: (GuardAiState) -> Unit,
    private val onEventActive: (String, Long) -> Unit,
    private val onEventComplete: (GuardEvent) -> Unit,
    private val publish: (CanarySnapshot) -> Unit,
    private val closed: (CanarySnapshot) -> Unit,
) {
    private val thread = HandlerThread("sentry-canary-camera-codec").apply { start() }
    private val handler = Handler(thread.looper)
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pool: EncodedBufferPool? = null
    private var ring: EncodedRingStore? = null
    private var codec: MediaCodec? = null
    private var codecStarted = false
    private var codecReleased = false
    private var outputEos = false
    private var input: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    @Volatile private var stopping = false
    private var finished = false
    private var writer: GuardEventWriter? = null
    private var eventId: String? = null
    private var source: SessionSourceSnapshot? = null
    private var analysisSurface: Surface? = null
    private val store = GuardEventStore(context)
    private val analysis = GuardAnalysis(context, stamp, onAi, { trigger(it) }, { handler.post { maybeComplete() } })
    private val clock = MediaClockMapper()
    private var firstPtsUs: Long? = null
    private var lastDrainUs = 0L
    private var lastSyncUs: Long? = null
    private var encodedCount = 0L
    private var encodedPayloadBytes = 0L
    private var snapshot = CanarySnapshot(runId = runId, phase = "STARTING", running = true,
        encodedBudgetBytes = EncodedBufferPool.CANARY_CAPACITY_BYTES.toLong(),
        historyTargetSeconds = CanaryRamPolicy.TARGET_SECONDS,
        historyStartupDeadlineSeconds = CanaryRamPolicy.STARTUP_DEADLINE_SECONDS,
        bufferStorage = "ANONYMOUS_SHARED_MEMORY")

    fun start() { handler.post { startOnHandler() } }
    fun trigger(signal: GuardTrigger? = null) { handler.post {
        publishCurrent()
        if (stopping || !snapshot.clipReady) return@post
        val now = nowUs()
        if (signal != null && !analysis.acceptsTrigger(signal, now)) return@post
        val pts = signal?.ptsUs ?: clock.toMediaPtsUs(now) ?: return@post
        val trigger = signal ?: GuardTrigger("MANUAL", pts, System.currentTimeMillis())
        writer?.let {
            if (it.extend(trigger)) onEventActive(eventId!!, it.targetEndPtsUs)
            return@post
        }
        val pinned = ring?.pinHistory().orEmpty()
        if (pinned.isEmpty()) return@post
        val id = UUID.randomUUID().toString()
        val event = GuardEvent(id = id, runId = runId, source = requireNotNull(source),
            createdAtEpochMs = trigger.epochMs, triggerPtsUs = pts, targetEndPtsUs = pts + 60_000_000,
            triggers = listOf(trigger), ai = analysis.snapshot)
        val saving = GuardEventWriter(store, event, writerExecutor) { result -> handler.post {
            writer = null; eventId = null
            GuardUsbAutoSave.schedule(context)
            onEventComplete(result)
            snapshot = snapshot.copy(saving = false)
            publishCurrent(); maybeComplete()
        } }
        writer = saving; eventId = id
        try { saving.begin(pinned) }
        catch (t: Throwable) { writer = null; eventId = null; fail("EVENT_WRITER_START_FAILED"); return@post }
        snapshot = snapshot.copy(saving = true)
        onEventActive(id, saving.targetEndPtsUs)
        publishCurrent()
    } }

    fun stop(reason: String = "MANUAL_STOP") {
        stopping = true // Revoke trigger admission before asynchronous teardown.
        handler.post { stopOnHandler(reason) }
    }

    @SuppressLint("MissingPermission")
    private fun startOnHandler() {
        if (stopping) { stopOnHandler("MANUAL_STOP"); return }
        try {
            val runtime = Runtime.getRuntime()
            val memory = runCatching { ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
            } }.getOrNull()
            val headroom = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            snapshot = snapshot.copy(startup = CanaryStartupDiagnostics(systemAvailableBytes = memory?.availMem,
                systemTotalBytes = memory?.totalMem, systemLowMemory = memory?.lowMemory,
                appHeapMaxBytes = runtime.maxMemory(), appHeapHeadroomBytes = headroom))
            publishCurrent()
            check(headroom >= 32L * 1024 * 1024) { "RAM_HEADROOM" }
            check(memory != null && !memory.lowMemory && memory.availMem >= EncodedBufferPool.CANARY_CAPACITY_BYTES + 256L * 1024 * 1024) {
                "SYSTEM_RAM_HEADROOM"
            }
            pool = CanaryNativeMemory.create()
            ring = EncodedRingStore(pool!!, CanaryRamPolicy.TARGET_US) { writer?.offer(it) }
            // Reuse metadata validated at explicit run start. The OEM can temporarily
            // reject metadata queries after NORMAL closes or the vehicle wakes up.
            val source = configuredSource
            this.source = source
            val width = source.profile.size.width
            val height = source.profile.size.height
            snapshot = snapshot.copy(width = width, height = height,
                startup = snapshot.startup!!.copy(stage = "PREPARED_CAMERA_CAPABILITIES", sourceWidth = width, sourceHeight = height))
            publishCurrent()
            val manager = context.getSystemService(CameraManager::class.java)
            configuredCamera.requireMatches(source)
            val declared = configuredCamera.surfaceDeclared
            snapshot = snapshot.copy(startup = snapshot.startup!!.copy(cameraSurfaceDeclared = declared))
            check(declared) { "ENCODER_SURFACE_SIZE_UNDECLARED" }
            val range = Range(configuredCamera.fpsLower, configuredCamera.fpsUpper)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            snapshot = snapshot.copy(cameraFpsRange = range.toString(), startup = snapshot.startup!!.copy(stage = "ENCODER_ENUMERATION"))
            publishCurrent()
            val candidates = encoderCandidates(format, width, height)
            CanaryEncoderSelection.open(candidates, declared, isCancelled = { stopping }, observe = { attempt ->
                val startup = snapshot.startup!!
                snapshot = snapshot.copy(startup = startup.copy(stage = "ENCODER_${attempt.state}",
                    attempts = startup.attempts.filterNot { it.name == attempt.name } + attempt))
                publishCurrent()
            }) { candidate -> startEncoder(candidate, format) }
            if (stopping) { stopOnHandler("MANUAL_STOP"); return }
            snapshot = snapshot.copy(startup = snapshot.startup!!.copy(stage = "CAMERA_OPEN"))
            publishCurrent()
            analysisSurface = analysis.configure(configuredCamera, source)
            opening = true
            try { manager.openCamera(source.cameraId, cameraCallback(range), handler) }
            catch (error: Throwable) { opening = false; throw error }
            handler.postDelayed({ if (!stopping && encodedCount == 0L) fail("CAMERA_OR_CODEC_START_TIMEOUT") }, 10_000)
            handler.postDelayed({
                if (!stopping) {
                    publishCurrent()
                    val readiness = CanaryRamPolicy.assess(ring!!.snapshot(), active = true, clock.healthy)
                    when (CanaryRamPolicy.deadline(readiness)) {
                        CanaryHistoryDeadline.TARGET_REACHED -> Unit
                        CanaryHistoryDeadline.CONTINUE_DIAGNOSTIC -> {
                            snapshot = snapshot.copy(historyTargetTimedOut = true)
                            publishCurrent()
                        }
                        CanaryHistoryDeadline.STOP_UNUSABLE -> {
                            snapshot = snapshot.copy(historyTargetTimedOut = true)
                            fail("RAM_HISTORY_TARGET_NOT_REACHED")
                        }
                    }
                }
            }, CanaryRamPolicy.STARTUP_DEADLINE_MS)
            handler.post(tick)
        } catch (error: Throwable) {
            if (stopping && error.message == "ENCODER_SELECTION_CANCELLED") stopOnHandler("MANUAL_STOP")
            else fail(safeReason(error), error)
        }
    }

    private fun encoderCandidates(format: MediaFormat, width: Int, height: Int): List<CanaryEncoderCandidate> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        }.distinctBy { if (Build.VERSION.SDK_INT >= 29) it.canonicalName else it.name }
        val candidates = infos.take(CanaryEncoderSelection.MAX_CANDIDATES).map { info ->
            val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else
                !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            var candidate = CanaryEncoderCandidate(info.name, hardware, false)
            try {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                candidate = candidate.copy(surfaceInput = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats)
                val video = caps.videoCapabilities
                candidate = candidate.copy(sizeSupported = video?.isSizeSupported(width, height),
                    sizeAndRateSupported = video?.areSizeAndRateSupported(width, height, 15.0),
                    widthRange = video?.supportedWidths?.toString(),
                    heightRange = video?.supportedHeights?.toString())
                candidate.copy(formatSupported = caps.isFormatSupported(format))
            } catch (error: Exception) { candidate.copy(queryError = error.javaClass.simpleName) }
        }
        snapshot = snapshot.copy(startup = snapshot.startup!!.copy(stage = "ENCODER_SELECTION", candidates = candidates,
            omittedCandidates = (infos.size - candidates.size).coerceAtLeast(0)))
        publishCurrent()
        return candidates
    }

    /** Uses the exact camera-declared source dimensions, including native tall four-lane composites. */
    private fun startEncoder(candidate: CanaryEncoderCandidate, format: MediaFormat): MediaCodec {
        var phase = "CREATE"
        snapshot = snapshot.copy(encoder = candidate.name)
        try {
            val encoder = MediaCodec.createByCodecName(candidate.name).also { codec = it }
            phase = "CALLBACK"
            encoder.setCallback(callback, handler)
            phase = "CONFIGURE"
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            phase = "INPUT_SURFACE"
            input = encoder.createInputSurface()
            phase = "START"
            encoder.start()
            codecStarted = true
            return encoder
        } catch (error: Exception) {
            val releaseOkay = runCatching { codec?.release() }.isSuccess
            if (releaseOkay) { codec = null; codecStarted = false }
            val surfaceOkay = runCatching { input?.release() }.isSuccess
            if (surfaceOkay) input = null
            val detail = if (error is MediaCodec.CodecException) error.diagnosticInfo else error.javaClass.simpleName
            throw CanaryEncoderOpenFailure(phase, detail.take(180), cleanupUnconfirmed = !releaseOkay || !surfaceOkay)
        }
    }

    private fun cameraCallback(range: Range<Int>) = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            opening = false
            camera = device
            if (stopping) { device.close(); return }
            configureSession(device, range, analysisSurface != null)
        }

        override fun onDisconnected(device: CameraDevice) {
            opening = false
            camera = device
            fail("CAMERA_DISCONNECTED")
        }
        override fun onError(device: CameraDevice, error: Int) {
            opening = false
            camera = device
            fail("CAMERA_ERROR_$error")
        }
        override fun onClosed(device: CameraDevice) {
            if (camera === device) camera = null
            session = null
            signalEndOrRelease()
            maybeComplete()
        }
    }

    private fun configureSession(device: CameraDevice, range: Range<Int>, withAnalysis: Boolean) {
        if (stopping) { device.close(); return }
        val targets = listOfNotNull(input, analysisSurface.takeIf { withAnalysis })
        try {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (stopping) { configured.close(); device.close(); return }
                    session = configured
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            targets.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        }.build()
                        configured.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { analysis.sensor(it) }
                            }
                        }, handler)
                        snapshot = snapshot.copy(phase = "RAM_LISTENING", startup = snapshot.startup?.copy(stage = "CAPTURING"))
                    } catch (t: Throwable) { fail(safeReason(t), t) }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    failed.close()
                    if (withAnalysis && !stopping) {
                        analysis.unavailable("相机拒绝录像与 AI 双输出；手动事件仍可使用")
                        configureSession(device, range, false)
                    } else fail("CAPTURE_SESSION_REJECTED")
                }
            }, handler)
        } catch (t: Throwable) {
            if (withAnalysis && !stopping) {
                analysis.unavailable("ANALYSIS_SESSION_${t.javaClass.simpleName}")
                configureSession(device, range, false)
            } else fail(safeReason(t), t)
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codecReleased || this@GuardCapturePipeline.codec !== codec) return
            try {
                val csd = format.getByteBuffer("csd-0") ?: error("AVC_CSD_MISSING")
                check(format.getInteger(MediaFormat.KEY_WIDTH) == snapshot.width && format.getInteger(MediaFormat.KEY_HEIGHT) == snapshot.height) {
                    "ENCODER_OUTPUT_SIZE_CHANGED"
                }
                updateAvcFormat(AvcParameterSets.read(csd, format.getByteBuffer("csd-1")))
            } catch (error: Throwable) { fail(safeReason(error)) }
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codecReleased || this@GuardCapturePipeline.codec !== codec) return
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            try {
                if (codecReleased) return
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val data = codec.getOutputBuffer(index) ?: error("CODEC_CONFIG_MISSING")
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    updateAvcFormat(AvcParameterSets.read(data.slice()))
                }
                // Config data belongs to the immutable format epoch, never to media samples.
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val now = nowUs()
                    analysis.encoded(info.presentationTimeUs)
                    if (lastDrainUs != 0L) snapshot = snapshot.copy(maxDrainGapMs = maxOf(snapshot.maxDrainGapMs, (now - lastDrainUs) / 1000))
                    lastDrainUs = now
                    if (firstPtsUs == null) {
                        firstPtsUs = info.presentationTimeUs
                        clock.anchor(now, info.presentationTimeUs, 2_000_000)
                    } else if (!clock.observe(now, info.presentationTimeUs)) {
                        fail("MEDIA_CLOCK_DISCONTINUITY")
                        return
                    }
                    val buffer = codec.getOutputBuffer(index) ?: error("CODEC_OUTPUT_MISSING")
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val sync = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (sync) {
                        lastSyncUs?.let { snapshot = snapshot.copy(largestSyncIntervalUs = maxOf(snapshot.largestSyncIntervalUs, info.presentationTimeUs - it)) }
                        lastSyncUs = info.presentationTimeUs
                    }
                    when (ring!!.append(buffer.slice(), info.presentationTimeUs, sync)) {
                        RingAppendResult.INVALID_PTS -> fail("REORDERED_OR_DUPLICATE_MEDIA_PTS")
                        RingAppendResult.GOP_DROPPED -> writer?.finishPartial("GOP_BUDGET_GAP")
                        RingAppendResult.MISSING_FORMAT -> fail("MEDIA_BEFORE_FORMAT")
                        else -> Unit
                    }
                    encodedCount++
                    encodedPayloadBytes += info.size
                }
            } catch (error: Throwable) { fail(safeReason(error)) }
            finally { runCatching { codec.releaseOutputBuffer(index, false) } }
            if (eos) {
                outputEos = true
                if (!stopping) fail("UNEXPECTED_CODEC_EOS")
                ring?.finishProducer()
                finishCodec()
            }
        }
        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (!codecReleased && this@GuardCapturePipeline.codec === codec) fail("CODEC_ERROR", error)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            if (!stopping && lastDrainUs != 0L && nowUs() - lastDrainUs > 5_000_000) fail("CODEC_DRAIN_STALLED")
            publishCurrent()
            maybeComplete()
            if (!finished) handler.postDelayed(this, 1_000)
        }
    }

    private fun updateAvcFormat(parameters: AvcParameterSets) {
        val previous = ring!!.snapshot().formatEpochId
        check(ring!!.setAvcFormat(snapshot.width, snapshot.height, parameters)) { "FORMAT_BUDGET_OR_CSD_INVALID" }
        if (previous != null && ring!!.snapshot().formatEpochId != previous) writer?.finishPartial("FORMAT_CHANGED")
    }

    private fun publishCurrent() {
        ring?.snapshot()?.let { value ->
            val elapsed = value.lastPtsUs?.minus(firstPtsUs ?: 0) ?: 0
            val readiness = CanaryRamPolicy.assess(value, !stopping, clock.healthy)
            snapshot = snapshot.copy(
                historySeconds = value.historyUs / 1_000_000.0, encodedLiveBytes = value.liveBytes,
                encodedHighWaterBytes = value.highWaterBytes, encodedCapacityBytes = value.capacityBytes,
                outputFrames = encodedCount, actualFps = if (elapsed > 0) (encodedCount - 1) * 1_000_000.0 / elapsed else 0.0,
                actualBitrateBps = if (elapsed > 0) (encodedPayloadBytes * 8_000_000.0 / elapsed).toLong() else 0,
                droppedGops = value.droppedGops,
                ramReady = readiness.targetReady, clipReady = readiness.clipReady, ramReadinessReason = readiness.reason,
                completedHistorySeconds = value.completedHistoryUs / 1_000_000.0,
                encodedPayloadLiveBytes = value.payloadLiveBytes, encodedPayloadHighWaterBytes = value.payloadHighWaterBytes,
                budgetEvictedGops = value.budgetEvictedGops, bufferAllocator = value.allocatorKind,
                saving = writer != null,
                clockMethod = if (analysis.snapshot.clockCalibrated) "AI_SAME_CAMERA_PTS_MATCHED_MANUAL_CALLBACK_ESTIMATE" else "CALLBACK_ARRIVAL_ESTIMATE_AI_MATCH_PENDING",
                clockUncertaintyUs = 2_000_000,
            )
        }
        publish(snapshot)
    }

    private fun fail(reason: String, error: Throwable? = null) {
        if (snapshot.stopReason == null && error != null) {
            snapshot = snapshot.copy(failureDetail = GuardExceptionSummary.describe(error))
        }
        stopping = true
        stopOnHandler(reason)
    }

    private fun stopOnHandler(reason: String) {
        if (finished) return
        if (snapshot.stopReason == null) snapshot = snapshot.copy(stopReason = reason)
        snapshot = snapshot.copy(phase = "STOPPING", ramReady = false, clipReady = false, ramReadinessReason = "INACTIVE")
        analysis.revoke()
        writer?.finishPartial(reason)
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { camera?.close() }
        if (!opening && camera == null) signalEndOrRelease()
        handler.postDelayed({
            if (!finished && camera == null && !opening && !codecReleased) {
                snapshot = snapshot.copy(stopReason = "CODEC_EOS_TIMEOUT")
                finishCodec()
            }
        }, 5_000)
        publishCurrent()
    }

    private fun signalEndOrRelease() {
        if (!stopping || codecReleased) return
        if (outputEos) finishCodec()
        else if (codecStarted) {
            runCatching { codec?.signalEndOfInputStream() }.onFailure { finishCodec() }
        } else finishCodec()
    }

    private fun finishCodec() {
        if (codecReleased || opening || camera != null) return
        runCatching { if (codecStarted) codec?.stop() }
        val released = runCatching { codec?.release() }.isSuccess
        if (!released) { snapshot = snapshot.copy(stopReason = "CODEC_RELEASE_UNCONFIRMED"); return }
        codec = null
        codecReleased = true
        analysis.cameraClosed()
        input?.release()
        input = null
        publishCurrent()
        ring?.close()
        writer?.finishPartial(snapshot.stopReason ?: "PRODUCER_ENDED")
        maybeComplete()
    }

    private fun maybeComplete() {
        if (finished || !stopping || opening || camera != null || !codecReleased || writer != null || !analysis.released) return
        if ((pool?.liveBytes ?: 0) != 0L) { snapshot = snapshot.copy(stopReason = "ENCODED_LEASES_REMAIN"); return }
        if (runCatching { pool?.close() }.isFailure) { snapshot = snapshot.copy(stopReason = "ENCODED_UNMAP_UNCONFIRMED"); return }
        finished = true
        snapshot = snapshot.copy(phase = "STOPPED", running = false, ramReady = false, clipReady = false,
            ramReadinessReason = "INACTIVE", saving = false, encodedLiveBytes = 0, encodedPayloadLiveBytes = 0,
            bufferStorageReleased = true)
        writerExecutor.shutdown()
        closed(snapshot)
        thread.quitSafely()
    }

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
    private fun safeReason(error: Throwable): String = if (error is CameraAccessException) "CAMERA_ACCESS_${error.reason}"
        else error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
            ?: "${error.javaClass.simpleName.uppercase()}_FAILURE"
}
