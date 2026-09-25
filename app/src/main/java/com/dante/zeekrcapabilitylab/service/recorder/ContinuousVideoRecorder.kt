package com.dante.zeekrcapabilitylab.service.recorder

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class EncodedSegmentSummary(
    val output: RecordingOutputHandle, val frames: Long,
    val firstPtsUs: Long?, val lastPtsUs: Long?, val maximumGapUs: Long,
    val firstEpochMs: Long?, val firstElapsedMs: Long?, val endedEpochMs: Long, val endedElapsedMs: Long,
)

data class EncoderEvidence(
    val frames: Long = 0, val firstPtsUs: Long? = null, val lastPtsUs: Long? = null,
    val lastOutputElapsedMs: Long? = null, val queueBytes: Long = 0, val queueItems: Int = 0,
    val completedFiles: Long = 0,
    val encodedBytes: Long? = null,
    val continuousStages: kotlinx.serialization.json.JsonObject? = null,
    val continuousFaultStages: kotlinx.serialization.json.JsonObject? = null,
)

/**
 * Direct camera -> one MediaCodec input Surface for the whole run. Only the MP4 writer rotates.
 * Codec output is copied into a bounded queue before its native buffer is returned. USB never runs
 * on the codec/camera worker. Caller MUST confirm the camera producer ended before stop/release.
 */
internal class ContinuousVideoRecorder(
    private val codecName: String,
    private val onError: (RecordingEncoder, String) -> Unit,
    private val failureCapture: ContinuousFailureCapture? = null,
) : RecordingEncoder {
    override val name = "CONTINUOUS_CODEC"
    private val codecThread = HandlerThread("openavm-encoder").apply { start() }
    private val writerThread = HandlerThread("openavm-mp4").apply { start() }
    private val codecHandler = Handler(codecThread.looper)
    private val writerHandler = Handler(writerThread.looper)
    private var codec: MediaCodec? = null // codec worker only
    @Volatile private var input: Surface? = null
    override val surface: Surface get() = requireNotNull(input)
    @Volatile var started = false; private set
    @Volatile private var closing = false
    @Volatile private var ending = false
    @Volatile private var abandoned = false
    @Volatile private var eosSeen = false
    private val firstFailure = AtomicReference<Throwable?>()
    private val eos = CountDownLatch(1)
    private val writerEnded = CountDownLatch(1)
    private val pumpQueued = AtomicBoolean()
    private val queue = BoundedEncodedQueue<Item>(32L * 1024 * 1024, 300)
    private val planner = KeyframeSegmentPlanner()
    private var cutCallback: ((EncodedSegmentSummary) -> Unit)? = null // codec worker
    private var writerFormat: MediaFormat? = null // writer worker
    private var target: Target? = null // writer worker
    @Volatile var lastCompleted: EncodedSegmentSummary? = null; private set
    @Volatile private var encodedFrames = 0L
    @Volatile private var firstPtsUs: Long? = null
    @Volatile private var lastPtsUs: Long? = null
    @Volatile private var lastOutputElapsedMs: Long? = null
    @Volatile private var completedFiles = 0L

    private sealed interface Item {
        data class Frame(val bytes: ByteArray, val ptsUs: Long, val originalPtsUs: Long, val flags: Int,
                         val epochMs: Long, val elapsedMs: Long) : Item
        data class Boundary(val done: (EncodedSegmentSummary) -> Unit,
                            val epochMs: Long, val elapsedMs: Long) : Item
    }

    override fun prepare(config: RecorderConfig, output: RecordingOutputHandle) {
        check(!closing && !ending && firstFailure.get() == null)
        call(codecHandler) {
            if (codec == null) {
                codec = MediaCodec.createByCodecName(codecName)
                codec!!.configure(format(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = codec!!.createInputSurface()
            }
        }
        call(writerHandler) {
            check(!closing && target == null) { "CONTINUOUS_OUTPUT_STILL_OWNED" }
            target = Target(output)
            writerFormat?.let { target!!.configure(it) }
        }
        schedulePump()
    }

    override fun start() {
        call(codecHandler) {
            check(!closing && !ending && firstFailure.get() == null)
            if (!started) { codec!!.start(); started = true; codecHandler.post(drain) }
        }
    }

    fun requestCut(done: (EncodedSegmentSummary) -> Unit) {
        check(codecHandler.post {
            try {
                check(started && !closing && !ending && cutCallback == null)
                planner.requestCut(); cutCallback = done
                codec!!.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (t: Throwable) { fail(t) }
        })
    }

    fun evidence(): EncoderEvidence {
        val (bytes, count) = queue.snapshot()
        return EncoderEvidence(encodedFrames, firstPtsUs, lastPtsUs, lastOutputElapsedMs, bytes, count, completedFiles)
    }

    private val drain = object : Runnable {
        override fun run() {
            if (closing || eosSeen) return
            try {
                val active = codec ?: return
                val info = MediaCodec.BufferInfo()
                for (attempt in 0 until 16) {
                    val index = active.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outputFormat = active.outputFormat
                        check(writerHandler.post {
                            try {
                                check(writerFormat == null) { "ENCODER_FORMAT_CHANGED_MID_RUN" }
                                writerFormat = outputFormat
                                target?.configure(outputFormat)
                                schedulePump()
                            } catch (t: Throwable) { fail(t) }
                        })
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                firstFailure.get() == null && !abandoned) {
                                receive(active, index, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                eosSeen = true; eos.countDown(); schedulePump()
                            }
                        } finally { active.releaseOutputBuffer(index, false) }
                    } else break
                    if (eosSeen) break
                }
            } catch (t: Throwable) { fail(t) }
            // After a writer failure, keep draining/discarding until the camera owner stops input.
            if (!closing && !eosSeen) codecHandler.postDelayed(this, 5)
        }
    }

    private fun receive(active: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val placement = planner.accept(info.presentationTimeUs,
            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) ?: return
        val epoch = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        if (placement.cutBefore) {
            val done = requireNotNull(cutCallback); cutCallback = null
            check(queue.offer(0) { Item.Boundary(done, epoch, elapsed) }) { "ENCODER_QUEUE_FULL" }
        }
        check(queue.offer(info.size) {
            val source = requireNotNull(active.getOutputBuffer(index)).duplicate()
            source.position(info.offset); source.limit(info.offset + info.size)
            Item.Frame(ByteArray(info.size).also { source.get(it) }, placement.ptsUs, info.presentationTimeUs,
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(), epoch, elapsed)
        }) { "ENCODER_QUEUE_FULL" }
        encodedFrames++
        if (firstPtsUs == null) firstPtsUs = info.presentationTimeUs
        lastPtsUs = info.presentationTimeUs; lastOutputElapsedMs = elapsed
        schedulePump()
    }

    private fun schedulePump() {
        if (pumpQueued.compareAndSet(false, true) && !writerHandler.post {
                pumpQueued.set(false)
                pump()
            }) { pumpQueued.set(false); fail(IllegalStateException("WRITER_QUEUE_REJECTED")) }
    }

    private fun pump() {
        try {
            for (attempt in 0 until 32) {
                val next = queue.peek() ?: break
                if (next is Item.Frame && (target == null || writerFormat == null) && !ending) return
                val ticket = queue.poll() ?: break
                try {
                    when (val item = ticket.value) {
                        is Item.Frame -> if (!abandoned && firstFailure.get() == null) target?.write(item)
                        is Item.Boundary -> {
                            val summary = finishTarget(item.epochMs, item.elapsedMs, closeOutput = true)
                            if (!ending && !closing && firstFailure.get() == null && summary != null) item.done(summary)
                        }
                    }
                } finally { queue.release(ticket) }
            }
            if (queue.peek() != null) schedulePump()
            else if (ending && eosSeen) {
                finishTarget(System.currentTimeMillis(), SystemClock.elapsedRealtime(), closeOutput = false)
                writerEnded.countDown()
            }
        } catch (t: Throwable) {
            fail(t)
            // release() retries finishing the owned target after producer acknowledgement.
        }
    }

    private fun finishTarget(epoch: Long, elapsed: Long, closeOutput: Boolean): EncodedSegmentSummary? {
        val current = target ?: return null
        current.finish(abandoned)
        // Descriptor lifetime belongs to the close transaction on terminal stop.
        if (closeOutput) {
            if (abandoned && current.output is UsbMediaStoreRecordingOutputHandle) current.output.abandonUnavailableTarget()
            else current.output.close()
        }
        val summary = current.summary(epoch, elapsed)
        lastCompleted = summary; completedFiles++
        target = null
        return summary
    }

    override fun abandonOutput() { abandoned = true; queue.clear() }

    override fun stop() {
        ending = true
        if (started) {
            call(codecHandler) { if (!eosSeen) codec!!.signalEndOfInputStream() }
            check(eos.await(3, TimeUnit.SECONDS)) { "ENCODER_EOS_TIMEOUT" }
        } else { eosSeen = true; eos.countDown() }
        schedulePump()
        check(writerEnded.await(3, TimeUnit.SECONDS)) { "ENCODER_WRITER_STOP_TIMEOUT" }
        firstFailure.get()?.let { throw IllegalStateException("CONTINUOUS_ENCODER_FAILED", it) }
    }

    override fun reset() = Unit

    override fun release() {
        // Always called behind RecorderSession's existing camera-producer fence, also on startup failure.
        closing = true; ending = true
        call(codecHandler) {
            codecHandler.removeCallbacks(drain)
            val active = codec
            if (active != null) {
                if (started) runCatching { active.stop() }
                active.release() // If release fails, retain the native reference and report unconfirmed cleanup.
                codec = null; started = false
            }
        }
        eosSeen = true; eos.countDown()
        call(writerHandler) {
            pump()
            // A previous write/stop error may have left a target whose muxer still needs release.
            if (target != null) finishTarget(System.currentTimeMillis(), SystemClock.elapsedRealtime(), closeOutput = false)
            queue.clear()
            writerEnded.countDown()
        }
        codecThread.quitSafely(); writerThread.quitSafely()
        codecThread.join(500); writerThread.join(500)
        check(!codecThread.isAlive && !writerThread.isAlive) { "ENCODER_WORKER_EXIT_UNCONFIRMED" }
        input?.release(); input = null
    }

    private fun fail(error: Throwable) {
        if (firstFailure.compareAndSet(null, error)) {
            runCatching { failureCapture?.capture(error, ContinuousFailureStage.LEGACY_CONTINUOUS) }
            // Revoke writer use immediately, even if the camera worker is waiting for an EOS/close acknowledgement.
            abandoned = true
            queue.clear()
            onError(this, "CONTINUOUS_ENCODER_FAILED:${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun <T> call(handler: Handler, action: () -> T): T {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        check(handler.post { try { result.set(runCatching(action)) } finally { done.countDown() } }) { "ENCODER_WORKER_REJECTED" }
        check(done.await(4, TimeUnit.SECONDS)) { "ENCODER_WORKER_TIMEOUT" }
        return requireNotNull(result.get()).getOrThrow()
    }

    private class Target(val output: RecordingOutputHandle) {
        private var muxer: MediaMuxer? = output.createMuxer()
        private var track = -1
        private var started = false
        private var frames = 0L
        private var firstPts: Long? = null
        private var lastPts: Long? = null
        private var maxGap = 0L
        private var epoch: Long? = null
        private var elapsed: Long? = null

        fun configure(format: MediaFormat) {
            check(!started)
            track = muxer!!.addTrack(format)
            muxer!!.start(); started = true
        }
        fun write(frame: Item.Frame) {
            check(started)
            if (frames == 0L) check(frame.ptsUs == 0L && frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) { "SEGMENT_MUST_START_WITH_KEYFRAME" }
            val info = MediaCodec.BufferInfo().apply { set(0, frame.bytes.size, frame.ptsUs, frame.flags) }
            muxer!!.writeSampleData(track, ByteBuffer.wrap(frame.bytes), info)
            if (firstPts == null) { firstPts = frame.originalPtsUs; epoch = frame.epochMs; elapsed = frame.elapsedMs }
            lastPts?.let { maxGap = maxOf(maxGap, frame.originalPtsUs - it) }
            lastPts = frame.originalPtsUs; frames++
        }
        fun finish(abandoned: Boolean) {
            val active = muxer ?: return
            var error: Throwable? = null
            if (started && !abandoned) try { active.stop() } catch (t: Throwable) { error = t }
            active.release()
            muxer = null; started = false
            error?.let { throw it }
        }
        fun summary(endEpoch: Long, endElapsed: Long) = EncodedSegmentSummary(output, frames, firstPts, lastPts,
            maxGap, epoch, elapsed, endEpoch, endElapsed)
    }

    companion object {
        fun format(config: RecorderConfig): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
            config.profile.size.width, config.profile.size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.requestedFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        /** No allocations or camera opens. Unsupported declarations leave the legacy path available. */
        fun inspectEncoder(manager: CameraManager, config: RecorderConfig): ContinuousEncoderSelection {
            if (Build.VERSION.SDK_INT < 29) return ContinuousEncoderSelection("CODEC_API_UNAVAILABLE")
            val size = config.profile.size
            val sizes = runCatching { manager.getCameraCharacteristics(config.cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(MediaCodec::class.java).orEmpty() }
                .getOrElse { return ContinuousEncoderSelection("CAMERA_CODEC_QUERY_FAILED", queryError = it.javaClass.simpleName) }
            if (sizes.none { it.width == size.width && it.height == size.height }) {
                return ContinuousEncoderSelectionPolicy.choose(false, emptyList())
            }
            val hardware = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { type ->
                    type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
            } }.getOrElse { return ContinuousEncoderSelection("CODEC_ENUMERATION_FAILED", cameraSizeDeclared = true,
                queryError = it.javaClass.simpleName) }
            val candidates = hardware.map { candidate ->
                runCatching {
                    val caps = candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val video = caps.videoCapabilities
                    val acceptsFormat = runCatching { caps.isFormatSupported(format(config)) }
                    val acceptsSizeRate = runCatching { video?.areSizeAndRateSupported(
                        size.width, size.height, config.requestedFrameRate.toDouble()) == true }
                    ContinuousCodecCandidate(candidate.name,
                        surfaceInput = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats,
                        formatSupported = acceptsFormat.getOrNull(), sizeAndRateSupported = acceptsSizeRate.getOrNull(),
                        baselineAdvertised = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline },
                        maximumWidth = video?.supportedWidths?.upper, maximumHeight = video?.supportedHeights?.upper,
                        widthAlignment = video?.widthAlignment, heightAlignment = video?.heightAlignment,
                        queryError = (acceptsFormat.exceptionOrNull() ?: acceptsSizeRate.exceptionOrNull())?.javaClass?.simpleName)
                }.getOrElse { ContinuousCodecCandidate(candidate.name, queryError = it.javaClass.simpleName) }
            }
            return ContinuousEncoderSelectionPolicy.choose(true, candidates)
        }
    }
}
