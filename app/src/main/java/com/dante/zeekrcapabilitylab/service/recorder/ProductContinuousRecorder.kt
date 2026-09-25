package com.dante.zeekrcapabilitylab.service.recorder

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.preflight.continuous.*
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

internal data class ProductEncodedFile(
    val number: Int, val output: RecordingOutputHandle, val frames: Long,
    val firstPtsUs: Long, val lastPtsUs: Long, val endPtsUs: Long,
    val maximumGapUs: Long, val encodedBytes: Long,
    val firstEpochMs: Long, val firstElapsedMs: Long,
    val endedEpochMs: Long, val endedElapsedMs: Long,
)

/** Product adapter for the real-camera P2 topology. A file cut changes only the writer's target.
 * At most one prepared output, one active writer and one independent native closer are owned.
 * Every output is identified before admission and remains owned until explicit release.
 */
@android.annotation.SuppressLint("NewApi") // inspect() rejects SDK < 29 before constructing this backend.
internal class ProductContinuousRecorder(
    private val codecName: String,
    private val firstNumber: Int,
    private val timelineOffsetUs: Long,
    private val onError: (RecordingEncoder, String) -> Unit,
    private val onStarted: (ProductContinuousRecorder, Int, RecordingOutputHandle, Long, Long) -> Unit,
    private val onClosed: (ProductContinuousRecorder, ProductEncodedFile) -> Unit,
    private val onUnused: (ProductContinuousRecorder, RecordingOutputHandle) -> Unit,
    private val failureCapture: ContinuousFailureCapture,
    private val onPreviewUnavailable: (ProductContinuousRecorder, String) -> Unit,
) : RecordingEncoder {
    override val name = "SHARED_INPUT_CONTINUOUS_CODEC"
    override val surface get() = requireNotNull(gl).surface
    val raster = RASTER
    @Volatile var started = false; private set
    private var config: RecorderConfig? = null
    private var timing = ContinuousVideoTiming(30, 1)
    private var codec: MediaCodec? = null
    private var codecInput: Surface? = null
    @Volatile private var gl: ContinuousCameraInput? = null
    private var codecLaunched = false
    private var writerLaunched = false
    private val ready = CountDownLatch(1)
    private val writerEnded = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    private val queue = BoundedEncodedQueue<Item>(32L * 1024 * 1024, 300)
    private val finalizer = RecordingFileFinalizer(SystemClock::elapsedRealtime)
    private val owned = ContinuousFileOwners<Target>()
    private val requestedNext = AtomicReference<Target?>()
    private val cutRequested = AtomicReference<Long?>()
    private var current: Target? = null
    @Volatile private var next: Target? = null
    @Volatile private var format: MediaFormat? = null
    @Volatile private var ending = false
    @Volatile private var continuationRevoked = false
    @Volatile private var outputLost = false
    @Volatile private var eos = false
    @Volatile private var drainEnded = false
    @Volatile private var codecReleased = false
    @Volatile private var glStopped = false
    @Volatile private var observerStopped = false
    private val cutProgress = ContinuousCutProgress { timing.keyframeTimeoutMs }
    @Volatile private var writtenFrames = 0L
    @Volatile private var writtenPtsUs: Long? = null
    @Volatile private var lastWrittenAt: Long? = null
    @Volatile private var stageEvidence: JsonObject? = null
    private val faultEvidence = AtomicReference<JsonObject?>()
    @Volatile private var cachedFailureSample: ContinuousFailureSample? = null
    @Volatile private var encodedFrames = 0L
    @Volatile private var firstEncodedPts: Long? = null
    @Volatile private var lastEncodedPts: Long? = null
    @Volatile private var encodedBytes = 0L
    @Volatile private var lastOutputAt = 0L
    @Volatile private var completedFiles = 0L
    private var endPtsUs = 0L
    private val codecThread = Thread(::drainLoop, "product-codec-drain")
    private val writerThread = Thread(::writeLoop, "product-file-writer")
    private val observerThread = Thread({
        var observed = 0L
        while (!observerStopped) {
            finalizer.failure?.let { fail(it, ContinuousFailureStage.FINALIZER_WATCHDOG) }
            finalizer.problem()?.let { fail(IllegalStateException(it.code), ContinuousFailureStage.FINALIZER_WATCHDOG) }
            if (!ending) {
                val now = SystemClock.elapsedRealtime()
                gl?.watchdogProblem(now)?.let { fail(IllegalStateException(it), ContinuousFailureStage.SOURCE_WATCHDOG) }
                cutProgress.problem(now, (gl?.sourceAgeMs(now) ?: 0) >= ContinuousSourceWaitPolicy.QUIET_AFTER_MS)
                    ?.let { fail(IllegalStateException(it), ContinuousFailureStage.CUT_WATCHDOG) }
            }
            if (SystemClock.elapsedRealtime() - observed >= 500) {
                stageEvidence = stageSnapshot(); cacheFailureSample(); observed = SystemClock.elapsedRealtime()
            }
            Thread.sleep(20)
        }
    }, "product-close-watchdog")
    private var observerLaunched = false

    private sealed interface Item {
        data class Frame(val bytes: ByteArray, val runPts: Long, val filePts: Long, val flags: Int) : Item
        data class Cut(val pts: Long) : Item
    }
    private class Target(val number: Int, val output: RecordingOutputHandle) {
        var muxer: MediaMuxer? = null
        var track = -1
        @Volatile var configured = false
        var frames = 0L; var bytes = 0L; var first = -1L; var last = -1L; var maximumGap = 0L
        var firstEpoch = 0L; var firstElapsed = 0L
        @Volatile var released = false
    }
    override fun prepare(config: RecorderConfig, output: RecordingOutputHandle) {
        check(this.config == null)
        require(eligible(config)) { "PRODUCT_CONTINUOUS_CONFIG_UNSUPPORTED" }
        this.config = config
        timing = ContinuousVideoTiming.forConfig(config)
        current = Target(firstNumber, output).also { owned.admit(it.number, it) }
        observerLaunched = true; observerThread.start()
        writerLaunched = true; writerThread.start()
        codecLaunched = true; codecThread.start()
        check(ready.await(4, TimeUnit.SECONDS)) { "PRODUCT_CODEC_START_TIMEOUT" }
        checkHealthy()
        val input = ContinuousCameraInput(requireNotNull(codecInput), ::fail, { onPreviewUnavailable(this, it) }, timing)
        gl = input // Retain partial native initialization before entering EGL.
        input.initialize()
    }
    override fun start() { checkHealthy(); check(!continuationRevoked); gl?.startCaptureWatchdog(); started = true }
    override fun reset() = Unit
    fun setPreview(surface: Surface?, released: (Surface) -> Unit) = requireNotNull(gl).setPreview(surface, released)
    fun enablePreview(enabled: Boolean) = requireNotNull(gl).enablePreview(enabled)

    /** Admission takes ownership even if writer-side native allocation subsequently fails. */
    fun prepareNext(number: Int, output: RecordingOutputHandle) {
        checkHealthy()
        check(!continuationRevoked && !ending && requestedNext.get() == null && next == null) { "PRODUCT_NEXT_ALREADY_OWNED" }
        val target = Target(number, output)
        owned.admit(number, target)
        check(requestedNext.compareAndSet(null, target))
    }
    fun owns(output: RecordingOutputHandle) = owned.snapshot().any { it.output === output }
    fun requestCut() {
        checkHealthy()
        check(!continuationRevoked && !ending && next?.configured == true) { "PRODUCT_NEXT_NOT_READY" }
        val now = SystemClock.elapsedRealtime()
        cutProgress.request(requireNotNull(next).number, now)
        check(cutRequested.compareAndSet(null, now)) { "PRODUCT_CUT_ALREADY_REQUESTED" }
    }
    private fun checkHealthy() { failure.get()?.let { throw it } }
    val firstFailure get() = failureCapture.value
    fun captureFailure(error: Throwable?, stage: ContinuousFailureStage, signal: String? = null): ContinuousFailureEvidence? =
        runCatching { failureCapture.capture(error, stage, cachedFailureSample, signal) }.getOrNull()
    private fun fail(error: Throwable, stage: ContinuousFailureStage) {
        if (failure.compareAndSet(null, error)) {
            captureFailure(error, stage)
            // A failure callback must not wait for queue/native-owner locks to collect diagnostics.
            faultEvidence.compareAndSet(null, stageEvidence)
            onError(this, error.message ?: "PRODUCT_CONTINUOUS_FAILURE")
        }
    }
    override fun abandonOutput() { outputLost = true }
    /** Cancels test deadlines/admission, not GPU input. Producer acknowledgement still precedes EOS. */
    fun prepareForStop() {
        continuationRevoked = true; cutProgress.cancel(); gl?.stopCaptureWatchdog()
    }

    private fun drainLoop() {
        var running = false
        try {
            val active = MediaCodec.createByCodecName(codecName).also { codec = it }
            active.configure(ProbeCodecFormat.create(spec(requireNotNull(config))), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecInput = active.createInputSurface(); active.start(); running = true
            ready.countDown()
            val planner = KeyframeSegmentPlanner(); val info = MediaCodec.BufferInfo()
            var eosAt: Long? = null
            while (!eos) {
                if (ending && eosAt == null) {
                    active.signalEndOfInputStream(); eosAt = SystemClock.elapsedRealtime()
                }
                check(eosAt == null || SystemClock.elapsedRealtime() - eosAt < 3_000) { "PRODUCT_CODEC_EOS_TIMEOUT" }
                cutRequested.getAndSet(null)?.let {
                    // Preparing an output never requests a keyframe. Exactly one request at the cut.
                    if (!ending && failure.get() == null) {
                        planner.requestCut()
                        active.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                }
                val index = active.dequeueOutputBuffer(info, 5_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(format == null) { "PRODUCT_CODEC_FORMAT_CHANGED_TWICE" }
                    val changed = ProbeCodecFormat.snapshot(active.outputFormat)
                    check(RASTER.matchesTrack(changed.getInteger(MediaFormat.KEY_WIDTH), changed.getInteger(MediaFormat.KEY_HEIGHT))) {
                        "PRODUCT_ENCODED_TRACK_MISMATCH"
                    }
                    format = changed
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && failure.get() == null && !outputLost) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val placement = planner.accept(info.presentationTimeUs, key)
                            if (placement != null) {
                                if (placement.cutBefore) {
                                    cutProgress.keyAccepted(SystemClock.elapsedRealtime())
                                    check(queue.offer(0) { Item.Cut(info.presentationTimeUs) }) { "PRODUCT_WRITER_QUEUE_FULL" }
                                }
                                val source = requireNotNull(active.getOutputBuffer(index)).duplicate().apply {
                                    position(info.offset); limit(info.offset + info.size)
                                }
                                check(queue.offer(info.size) {
                                    Item.Frame(ByteArray(info.size).also { source.get(it) }, info.presentationTimeUs,
                                        placement.ptsUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                                }) { "PRODUCT_WRITER_QUEUE_FULL" }
                                if (firstEncodedPts == null) firstEncodedPts = info.presentationTimeUs
                                lastEncodedPts = info.presentationTimeUs
                                encodedFrames++; encodedBytes += info.size; lastOutputAt = SystemClock.elapsedRealtime()
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    } finally { active.releaseOutputBuffer(index, false) }
                }
            }
        } catch (error: Throwable) { fail(error, ContinuousFailureStage.CODEC_DRAIN) }
        finally {
            ready.countDown()
            // Never destroy the codec input while its GL producer may still be submitting.
            while (!ending) Thread.sleep(2)
            try {
                if (running) runCatching { codec?.stop() }.onFailure { fail(it, ContinuousFailureStage.CODEC_CLEANUP) }
                codec?.release(); codec = null; codecReleased = true
            } catch (error: Throwable) { fail(error, ContinuousFailureStage.CODEC_CLEANUP) }
            drainEnded = true
        }
    }

    private fun configure(target: Target, format: MediaFormat) {
        if (target.muxer == null) target.muxer = target.output.createMuxer()
        if (!target.configured) {
            val muxer = requireNotNull(target.muxer)
            target.track = muxer.addTrack(ProbeCodecFormat.snapshot(format)); muxer.start(); target.configured = true
        }
    }
    private fun writeLoop() {
        try {
            while (true) {
                finalizer.failure?.let { throw it }
                finalizer.problem()?.let { error(it.code) }
                requestedNext.getAndSet(null)?.let { check(next == null); next = it }
                format?.let { f -> current?.let { configure(it, f) }; next?.let { configure(it, f) } }
                val ticket = queue.poll()
                if (ticket == null) {
                    if (ending && drainEnded) break
                    Thread.sleep(2); continue
                }
                try {
                    if (failure.get() != null || outputLost) continue
                    when (val item = ticket.value) {
                        is Item.Cut -> {
                            val old = requireNotNull(current)
                            val prepared = requireNotNull(next)
                            check(prepared.configured) { "PRODUCT_NEXT_NOT_CONFIGURED" }
                            current = prepared; next = null
                            dispatchClose(old, item.pts)
                            cutProgress.writerReceived(SystemClock.elapsedRealtime())
                        }
                        is Item.Frame -> {
                            val target = requireNotNull(current)
                            check(target.configured) { "PRODUCT_FILE_FORMAT_MISSING" }
                            if (target.frames == 0L) {
                                check(item.filePts == 0L && item.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) { "PRODUCT_FILE_NOT_KEY_START" }
                                target.first = item.runPts
                                target.firstEpoch = requireNotNull(gl?.firstFrameEpochMs) + timing.sourceUs(item.runPts) / 1000
                                target.firstElapsed = requireNotNull(gl?.firstFrameElapsedMs) + timing.sourceUs(item.runPts) / 1000
                                onStarted(this, target.number, target.output, target.firstEpoch, target.firstElapsed)
                            }
                            requireNotNull(target.muxer).writeSampleData(target.track, ByteBuffer.wrap(item.bytes),
                                MediaCodec.BufferInfo().apply { set(0, item.bytes.size, item.filePts, item.flags) })
                            if (target.last >= 0) target.maximumGap = maxOf(target.maximumGap, item.runPts - target.last)
                            target.last = item.runPts; target.frames++; target.bytes += item.bytes.size
                            writtenFrames++; writtenPtsUs = item.runPts; lastWrittenAt = SystemClock.elapsedRealtime()
                        }
                    }
                } finally { queue.release(ticket) }
            }
        } catch (error: Throwable) { fail(error, ContinuousFailureStage.FILE_WRITER) }
        finally {
            // Keep the ownership graph intact if native release is blocked or throws.
            while (!ending || !drainEnded) Thread.sleep(2)
            queue.clear()
            requestedNext.getAndSet(null)?.let { check(next == null); next = it }
            try {
                // No more writes/cuts exist now. Join the ONE old-file closer before terminal
                // cleanup on this writer. A failed file must not strand other already-owned FDs.
                check(finalizer.finish(10_000)) { "PRODUCT_FINALIZER_RELEASE_UNCONFIRMED" }
                val remaining = owned.snapshot()
                remaining.forEach { target ->
                    val end = if (failure.get() == null) maxOf(endPtsUs, target.last + 1)
                        else target.last + 1_000_000L / (config?.requestedFrameRate ?: 30)
                    runCatching { closeTarget(target, end, false) }.onFailure { fail(it, ContinuousFailureStage.FILE_CLEANUP) }
                }
                current = null; next = null
            } catch (error: Throwable) { fail(error, ContinuousFailureStage.FILE_CLEANUP) }
            writerEnded.countDown()
        }
    }
    private fun dispatchClose(target: Target, end: Long) {
        finalizer.submit(target.number, { target.released }) { closeTarget(target, end, true) }
    }
    private fun closeTarget(target: Target, end: Long, independent: Boolean) {
            var stopFailure: Throwable? = null
            try {
                if (target.configured && target.frames > 0 && !outputLost) {
                    try {
                        val duration = ContinuousFileTiming.durationUs(target.first, target.last, end)
                        requireNotNull(target.muxer).writeSampleData(target.track, ByteBuffer.allocate(0),
                            MediaCodec.BufferInfo().apply { set(0, 0, duration, MediaCodec.BUFFER_FLAG_END_OF_STREAM) })
                    } catch (error: Throwable) {
                        captureFailure(error, ContinuousFailureStage.FILE_FINALIZE)
                        stopFailure = error
                    }
                    try { target.muxer?.stop() } catch (error: Throwable) {
                        captureFailure(error, ContinuousFailureStage.FILE_FINALIZE)
                        if (stopFailure == null) stopFailure = error else stopFailure.addSuppressed(error)
                    }
                }
                target.muxer?.release(); target.muxer = null
                if (independent) finalizer.stage(target.number, "USB_SYNC_CLOSE")
                if (outputLost && target.output is UsbMediaStoreRecordingOutputHandle) target.output.abandonUnavailableTarget()
                else target.output.close()
                target.released = target.output.nativeReleaseConfirmed
                if (stopFailure != null) throw stopFailure
                if (target.frames == 0L) onUnused(this, target.output)
                else if (!outputLost) {
                    val sourceEnd = if (independent) timing.sourceUs(end)
                        else timing.sourceEndUs(end, gl?.lastSourcePtsUs ?: 0)
                    val durationMs = (sourceEnd - timing.sourceUs(target.first)).coerceAtLeast(1) / 1000
                    onClosed(this, ProductEncodedFile(target.number, target.output, target.frames, target.first + timelineOffsetUs, target.last + timelineOffsetUs,
                        end + timelineOffsetUs, target.maximumGap, target.bytes, target.firstEpoch, target.firstElapsed,
                        target.firstEpoch + durationMs, target.firstElapsed + durationMs))
                    completedFiles++
                }
            } catch (error: Throwable) {
                target.released = target.muxer == null && target.output.nativeReleaseConfirmed
                fail(error, ContinuousFailureStage.FILE_FINALIZE); throw error
            } finally { if (target.released) owned.release(target, true) }
    }

    /** Called only after Camera2's producer acknowledgement, also on partial startup failure. */
    override fun stop() {
        prepareForStop()
        if (!glStopped) { gl?.stopAfterCameraFence(); glStopped = true }
        endPtsUs = (gl?.lastSubmittedPtsUs ?: -1).coerceAtLeast(0) + 1_000_000L / (config?.requestedFrameRate ?: 30)
        ending = true
        if (codecLaunched) codecThread.join(4_000) else { codecReleased = true; drainEnded = true }
        if (writerLaunched) check(writerEnded.await(10, TimeUnit.SECONDS)) { "PRODUCT_WRITER_STOP_UNCONFIRMED" }
        checkHealthy()
    }
    override fun release() {
        // stop() can fail due to file damage after all native resources actually settled.
        runCatching { stop() }.onFailure { fail(it, ContinuousFailureStage.FILE_CLEANUP) }
        check(glStopped && (gl == null || gl!!.released()) && codecReleased && !codecThread.isAlive) { "PRODUCT_MEDIA_RELEASE_UNCONFIRMED" }
        if (writerLaunched) writerThread.join(500)
        check(!writerThread.isAlive && finalizer.finish(1_000) && owned.snapshot().isEmpty()) { "PRODUCT_OUTPUT_RELEASE_UNCONFIRMED" }
        observerStopped = true
        if (observerLaunched) observerThread.join(500)
        check(!observerThread.isAlive) { "PRODUCT_WATCHDOG_RELEASE_UNCONFIRMED" }
        codecInput?.release(); codecInput = null
    }
    fun evidence(): EncoderEvidence = queue.snapshot().let { (bytes, items) ->
        EncoderEvidence(encodedFrames, firstEncodedPts, lastEncodedPts, lastOutputAt.takeIf { it > 0 }, bytes, items, completedFiles,
            encodedBytes = encodedBytes, continuousStages = stageEvidence, continuousFaultStages = faultEvidence.get())
    }
    private fun cacheFailureSample() {
        val (bytes, items) = queue.snapshot()
        val now = SystemClock.elapsedRealtime()
        cachedFailureSample = ContinuousFailureSample(now, bytes, items, encodedFrames, encodedBytes,
            completedFiles, gl?.sourceAgeMs(now))
    }
    private fun stageSnapshot(): JsonObject = buildJsonObject {
        put("observedAtElapsedMs", SystemClock.elapsedRealtime())
        put("writtenFrames", writtenFrames); put("lastWrittenPtsUs", writtenPtsUs); put("lastWriteElapsedMs", lastWrittenAt)
        put("ownedNativeFiles", owned.snapshot().size); put("nextPreparedFile", next?.takeIf { it.configured }?.number)
        put("inputSubmissions", gl?.submittedFrames); put("ending", ending)
        put("sourceFrameAgeMs", gl?.sourceAgeMs(SystemClock.elapsedRealtime()))
        put("timelineOffsetUs", timelineOffsetUs)
        put("sourceLastPtsUs", gl?.lastSourcePtsUs); put("playbackLastPtsUs", gl?.lastSubmittedPtsUs)
        put("timeLapseMultiplier", timing.multiplier); put("keyframeTimeoutMs", timing.keyframeTimeoutMs)
        put("continuationRevoked", continuationRevoked)
        put("cut", cutProgress.snapshot()?.let { cut -> buildJsonObject {
            put("file", cut.file); put("requestedAtMs", cut.requestedAtMs); put("keyAcceptedAtMs", cut.keyAcceptedAtMs)
            put("writerReceivedAtMs", cut.writerReceivedAtMs)
            put("sourceWaitMs", cut.sourceWaitMs)
        } } ?: JsonNull)
        val close = finalizer.snapshot()
        put("closeProblem", close.problem?.code)
        put("recentClosers", buildJsonArray { close.work.takeLast(4).forEach { work -> add(buildJsonObject {
            put("file", work.file); put("stage", work.stage); put("stageAtMs", work.stageAtMs)
            put("acceptedAtMs", work.acceptedAtMs); put("completedAtMs", work.completedAtMs)
            put("nativeReleased", work.nativeReleased); put("failureType", work.failureType)
        }) } })
    }
    companion object {
        val RASTER = StripRepackContract(inputWidth = 1280, inputHeight = 5140, stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)
        fun eligible(config: RecorderConfig) = ProductContinuousPolicy.eligible(config)
        fun spec(config: RecorderConfig) = ContinuousProbeSpec.surroundRepack(ProbeAvcProfile.HIGH).encoding.copy(
            bitrateBps = config.profile.bitrateBps, frameRate = config.requestedFrameRate, bitrateMode = ProbeBitrateMode.VBR)
        fun inspect(manager: CameraManager, config: RecorderConfig): ContinuousEncoderSelection {
            if (Build.VERSION.SDK_INT < 29 || !eligible(config)) return ContinuousEncoderSelection("SHARED_INPUT_NOT_ELIGIBLE")
            val map = manager.getCameraCharacteristics(config.cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Camera consumes the tall OES input; only the encoder consumes 3840×1728.
            val cameraDeclared = map?.getOutputSizes(SurfaceTexture::class.java)?.any { it.width == 1280 && it.height == 5140 } == true
            val candidates = ProbeCodecFormat.declarations(spec(config)).map { declaration ->
                ContinuousCodecCandidate(declaration.codecName, declaration.surfaceInput, declaration.formatSupported == true && declaration.bitrateModeSupported == true,
                    declaration.sizeAndRateSupported, queryError = declaration.queryError)
            }
            return ContinuousEncoderSelectionPolicy.choose(cameraDeclared, candidates)
        }
    }
}
