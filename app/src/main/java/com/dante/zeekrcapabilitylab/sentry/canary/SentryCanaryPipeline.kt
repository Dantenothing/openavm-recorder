package com.dante.zeekrcapabilitylab.sentry.canary

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
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.sentry.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable

@Serializable
data class CanarySnapshot(
    val runId: String = "",
    val phase: String = "STOPPED",
    val running: Boolean = false,
    val ramReady: Boolean = false,
    val saving: Boolean = false,
    val historySeconds: Double = 0.0,
    val encodedLiveBytes: Long = 0,
    val encodedHighWaterBytes: Long = 0,
    val encodedCapacityBytes: Long = 0,
    val outputFrames: Long = 0,
    val actualFps: Double = 0.0,
    val actualBitrateBps: Long = 0,
    val largestSyncIntervalUs: Long = 0,
    val maxDrainGapMs: Long = 0,
    val droppedGops: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val requestedFps: Int = 15,
    val requestedBitrate: Int = 4_000_000,
    val encoder: String = "",
    val cameraFpsRange: String = "",
    val clockMethod: String = "CALLBACK_ARRIVAL_ESTIMATE_NOT_YET_CALIBRATED",
    val clockUncertaintyUs: Long = 2_000_000,
    val processPssKiB: Int = 0,
    val thermalStatus: Int? = null,
    val reportReady: Boolean = false,
    val stopReason: String? = null,
    val failureDetail: String? = null,
    val clip: CanaryClipResult? = null,
    val clips: List<CanaryClipResult> = emptyList(),
    val omittedClipResults: Long = 0,
    val telemetry: CanaryTelemetry? = null,
    val evidenceArchiveError: String? = null,
    val startup: CanaryStartupDiagnostics? = null,
    val clipReady: Boolean = false,
    val ramReadinessReason: String = "INACTIVE",
    val historyTargetTimedOut: Boolean = false,
    val completedHistorySeconds: Double = 0.0,
    val encodedPayloadLiveBytes: Long = 0,
    val encodedPayloadHighWaterBytes: Long = 0,
    val budgetEvictedGops: Long = 0,
    val bufferAllocator: String = "UNKNOWN",
    // Old archived runs used 64 MiB / 30 s; new runs explicitly record their own budget.
    val encodedBudgetBytes: Long = 64L * 1024 * 1024,
    val historyTargetSeconds: Int = 30,
    val historyStartupDeadlineSeconds: Int = 60,
    val bufferStorage: String = "JAVA_HEAP",
    val bufferStorageReleased: Boolean = false,
)

/** Isolated S1A/B producer. Never requests NORMAL or touches removable storage. */
class SentryCanaryPipeline(
    private val context: Context,
    private val runId: String,
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
    private var writer: CanaryEventWriter? = null
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
    fun trigger() { handler.post {
        publishCurrent() // Recheck current coverage, not the previous UI tick.
        if (stopping || !snapshot.clipReady || writer != null) return@post
        val pts = clock.toMediaPtsUs(nowUs()) ?: return@post
        val pinned = ring?.pinHistory().orEmpty()
        if (pinned.isEmpty()) return@post
        val event = CanaryEventWriter(File(context.filesDir, "sentry-canary/clips"), pool!!, pts, writerExecutor) { result ->
            handler.post {
                // Admission and shutdown wait for this result acknowledgement, not just
                // the worker's finished flag, so the final report cannot lose the clip.
                writer = null
                val history = CanaryClipHistory(snapshot.clips, snapshot.omittedClipResults).append(result)
                snapshot = snapshot.copy(clip = result, clips = history.retained, omittedClipResults = history.omitted, saving = false)
                publishCurrent()
                maybeComplete()
            }
        }
        writer = event
        event.begin(pinned)
        snapshot = snapshot.copy(saving = true)
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
            val source = ProductRecorderConfigFactory.resolveSource(context, RecordingSourceRole.SURROUND)
                ?: error("NO_DECLARED_SURROUND_PROFILE")
            val width = source.profile.size.width
            val height = source.profile.size.height
            snapshot = snapshot.copy(width = width, height = height,
                startup = snapshot.startup!!.copy(stage = "CAMERA_CAPABILITIES", sourceWidth = width, sourceHeight = height))
            publishCurrent()
            val manager = context.getSystemService(CameraManager::class.java)
            val characteristics = manager.getCameraCharacteristics(source.cameraId)
            val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaCodec::class.java).orEmpty()
            val declared = sizes.any { it.width == width && it.height == height }
            snapshot = snapshot.copy(startup = snapshot.startup!!.copy(cameraSurfaceDeclared = declared))
            check(declared) { "ENCODER_SURFACE_SIZE_UNDECLARED" }
            val range = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty().filter { it.contains(15) }.minByOrNull { it.upper - it.lower }
                ?: error("NO_15_FPS_CAMERA_RANGE")
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
            else fail(safeReason(error))
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
            try {
                device.createCaptureSession(listOf(input!!), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (stopping) { configured.close(); device.close(); return }
                        session = configured
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(input!!)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                            }.build()
                            configured.setRepeatingRequest(request, null, handler)
                            snapshot = snapshot.copy(phase = "RAM_LISTENING", startup = snapshot.startup?.copy(stage = "CAPTURING"))
                        } catch (error: Throwable) { fail(safeReason(error)) }
                    }
                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                        fail("CAPTURE_SESSION_REJECTED")
                    }
                }, handler)
            } catch (error: Throwable) { fail(safeReason(error)) }
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

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (codecReleased || this@SentryCanaryPipeline.codec !== codec) return
            try {
                val csd = format.getByteBuffer("csd-0") ?: error("AVC_CSD_MISSING")
                check(format.getInteger(MediaFormat.KEY_WIDTH) == snapshot.width && format.getInteger(MediaFormat.KEY_HEIGHT) == snapshot.height) {
                    "ENCODER_OUTPUT_SIZE_CHANGED"
                }
                updateAvcFormat(AvcParameterSets.read(csd, format.getByteBuffer("csd-1")))
            } catch (error: Throwable) { fail(safeReason(error)) }
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codecReleased || this@SentryCanaryPipeline.codec !== codec) return
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
            if (!codecReleased && this@SentryCanaryPipeline.codec === codec) fail("CODEC_ERROR")
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
            )
        }
        publish(snapshot)
    }

    private fun fail(reason: String) {
        stopping = true
        stopOnHandler(reason)
    }

    private fun stopOnHandler(reason: String) {
        if (finished) return
        if (snapshot.stopReason == null) snapshot = snapshot.copy(stopReason = reason)
        snapshot = snapshot.copy(phase = "STOPPING", ramReady = false, clipReady = false, ramReadinessReason = "INACTIVE")
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
        input?.release()
        input = null
        publishCurrent()
        ring?.close()
        writer?.finishPartial(snapshot.stopReason ?: "PRODUCER_ENDED")
        maybeComplete()
    }

    private fun maybeComplete() {
        if (finished || !stopping || opening || camera != null || !codecReleased || writer != null) return
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
    private fun safeReason(error: Throwable): String = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
        ?: "${error.javaClass.simpleName.uppercase()}_FAILURE"
}
