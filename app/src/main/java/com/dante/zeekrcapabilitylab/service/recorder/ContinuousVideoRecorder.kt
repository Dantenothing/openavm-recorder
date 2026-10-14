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
    val videoClockAnchor: io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor? = null,
    val continuousTimeline: io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline? = null,
)

data class EncoderEvidence(
    val frames: Long = 0, val firstPtsUs: Long? = null, val lastPtsUs: Long? = null,
    val lastOutputElapsedMs: Long? = null, val queueBytes: Long = 0, val queueItems: Int = 0,
    val completedFiles: Long = 0,
    val encodedBytes: Long? = null,
    val continuousStages: kotlinx.serialization.json.JsonObject? = null,
    val continuousFaultStages: kotlinx.serialization.json.JsonObject? = null,
    val writtenFrames: Long? = null,
    val lastWriteElapsedMs: Long? = null,
    val formatNotifications: Long? = null,
    val repeatedFormatNotifications: Long? = null,
    val formatDecision: String? = null,
    /** Final accepted samples with no destination. Null unless the terminal output-gap failure occurred. */
    val terminalUnwrittenFrames: Long? = null,
)

internal class ContinuousTailOutputMissingException : IllegalStateException("CONTINUOUS_TAIL_OUTPUT_MISSING")

/**
 * Direct camera -> one MediaCodec input Surface for the whole run. Only the MP4 writer rotates.
 * Codec output is copied into a bounded queue before its native buffer is returned. USB never runs
 * on the codec/camera worker. Caller MUST confirm the camera producer ended before stop/release.
 */
@android.annotation.SuppressLint("NewApi") // inspectEncoder() rejects SDK < 29 before this backend is selected.
internal class ContinuousVideoRecorder(
    private val codecName: String,
    private val onError: (RecordingEncoder, String) -> Unit,
    private val failureCapture: ContinuousFailureCapture? = null,
    queueBytes: Long = 32L * 1024 * 1024,
    queueItems: Int = 300,
    private val onStarted: (ContinuousVideoRecorder, RecordingOutputHandle, Long, Long) -> Unit = { _, _, _, _ -> },
    private val onClosed: (ContinuousVideoRecorder, EncodedSegmentSummary) -> Unit = { _, _ -> },
    private val onUnused: (ContinuousVideoRecorder, RecordingOutputHandle) -> Unit = { _, _ -> },
    private val cabinPreviewSessionId: String? = null,
    private val onFormatBoundaryNeeded: (ContinuousVideoRecorder) -> Unit = {},
) : RecordingEncoder {
    override val name = "CONTINUOUS_CODEC"
    private val codecThread = HandlerThread("openavm-encoder").apply { start() }
    private val writerThread = HandlerThread("openavm-mp4").apply { start() }
    private val codecHandler = Handler(codecThread.looper)
    private val writerHandler = Handler(writerThread.looper)
    private var codec: MediaCodec? = null // codec worker only
    private var cabinPreview: com.dante.zeekrcapabilitylab.mirror.CabinPreviewBuffer? = null
    @Volatile private var input: Surface? = null
    override val surface: Surface get() = requireNotNull(input)
    @Volatile var started = false; private set
    @Volatile private var closing = false
    @Volatile private var ending = false
    @Volatile private var continuationRevoked = false
    @Volatile private var abandoned = false
    @Volatile private var eosSeen = false
    private val firstFailure = AtomicReference<Throwable?>()
    private val eos = CountDownLatch(1)
    private val writerEnded = CountDownLatch(1)
    private val pumpQueued = AtomicBoolean()
    private val queue = BoundedEncodedQueue<Item>(queueBytes, queueItems)
    private val planner = KeyframeSegmentPlanner()
    private val clock = ObservedCodecClock()
    private var cutCallback: ((EncodedSegmentSummary) -> Unit)? = null // codec worker; non-null also marks a requested cut
    private var writerFormat: MediaFormat? = null // writer worker
    private val trackFormatState = ContinuousTrackFormatState() // codec worker
    private var allowFormatRollover = false // frozen on first codec prepare
    private var pendingTrackFormat: ContinuousTrackFormatAndroid.Snapshot? = null // codec worker
    private var pendingFormatSinceUptimeMs: Long? = null // codec worker; independent of UI visibility
    val formatBoundaryNeeded: Boolean get() = trackFormatState.hasPendingBoundary
    @Volatile private var lastAppliedFormatNotification = 0L
    private var target: Target? = null // writer worker
    private var nextTarget: Target? = null // writer worker; admitted before requesting a keyframe
    private val ownedOutputs = java.util.concurrent.ConcurrentHashMap.newKeySet<RecordingOutputHandle>()
    @Volatile var lastCompleted: EncodedSegmentSummary? = null; private set
    @Volatile private var encodedFrames = 0L
    @Volatile private var encodedBytes = 0L
    @Volatile private var firstPtsUs: Long? = null
    @Volatile private var lastPtsUs: Long? = null
    @Volatile private var lastOutputElapsedMs: Long? = null
    @Volatile private var completedFiles = 0L
    @Volatile private var writtenFrames = 0L
    @Volatile private var lastWriteElapsedMs: Long? = null
    @Volatile private var cachedFailureSample: ContinuousFailureSample? = null
    @Volatile private var cutProgress: DirectCutProgress? = null
    val terminalUnwrittenFrames: Long? get() = if (firstFailure.get() is ContinuousTailOutputMissingException)
        (encodedFrames - writtenFrames).coerceAtLeast(0) else null

    private sealed interface Item {
        data class Frame(val bytes: ByteArray, val ptsUs: Long, val flags: Int,
                         val stamp: ObservedCodecClock.Stamp) : Item
        data class Boundary(val done: (EncodedSegmentSummary) -> Unit,
                            val nextKeyframe: ObservedCodecClock.Stamp,
                            val rollover: TrackRollover? = null,
                            val transitionGap: Boolean = false) : Item
    }
    private data class TrackRollover(val snapshot: ContinuousTrackFormatAndroid.Snapshot,
                                     val observation: ContinuousFormatObservation)

    override fun prepare(config: RecorderConfig, output: RecordingOutputHandle) {
        check(!closing && !ending && firstFailure.get() == null)
        check(ownedOutputs.isEmpty() && ownedOutputs.add(output)) { "CONTINUOUS_OUTPUT_STILL_OWNED" }
        observed(ContinuousFailureStage.SESSION_SETUP) { call(codecHandler) {
            if (codec == null) {
                allowFormatRollover = config.recordingMode == RecordingMode.NORMAL &&
                    config.source.sourceRole in setOf(RecordingSourceRole.CABIN, RecordingSourceRole.IR)
                codec = MediaCodec.createByCodecName(codecName)
                codec!!.configure(format(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = codec!!.createInputSurface()
                if (config.source.sourceRole == RecordingSourceRole.CABIN && config.recordingMode == RecordingMode.NORMAL)
                    cabinPreviewSessionId?.let { cabinPreview = com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.open(it) }
            }
        } }
        observed(ContinuousFailureStage.OUTPUT_PREPARE) { call(writerHandler) {
            check(!closing && target == null) { "CONTINUOUS_OUTPUT_STILL_OWNED" }
            target = Target(output)
            target!!.prepareMuxer()
            writerFormat?.let { target!!.configure(it) }
        } }
        schedulePump()
    }

    override fun start() {
        observed(ContinuousFailureStage.SESSION_SETUP) { call(codecHandler) {
            check(!closing && !ending && firstFailure.get() == null)
            if (!started) { codec!!.start(); started = true; codecHandler.post(drain) }
        } }
    }

    /** A cut is impossible until its destination is owned. Slow storage leaves the current file open. */
    fun beginCut(): DirectCutProgress = DirectCutProgress(SystemClock.uptimeMillis()).also { cutProgress = it }

    fun requestCut(output: RecordingOutputHandle, progress: DirectCutProgress = beginCut(), done: (EncodedSegmentSummary) -> Unit = {}) {
        check(started && !closing && !ending && !continuationRevoked && firstFailure.get() == null)
        check(ownedOutputs.size == 1 && ownedOutputs.add(output)) { "CONTINUOUS_NEXT_ALREADY_OWNED" }
        progress.advance(DirectCutProgress.Phase.PREPARING_MUXER, SystemClock.uptimeMillis())
        observed(ContinuousFailureStage.OUTPUT_PREPARE) { check(writerHandler.post {
            try {
                check(nextTarget == null)
                nextTarget = Target(output, cut = progress)
                // Own the handle before entering native code, including partial initialization failure.
                nextTarget!!.prepareMuxer()
                check(codecHandler.post {
                    cacheCounters()
                    if (continuationRevoked || closing || ending || firstFailure.get() != null) return@post
                    try {
                        check(cutCallback == null)
                        planner.requestCut(); cutCallback = done
                        progress.advance(DirectCutProgress.Phase.WAITING_KEYFRAME, SystemClock.uptimeMillis())
                        codec!!.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    } catch (t: Throwable) { fail(t, ContinuousFailureStage.CUT_REQUEST) }
                }) { "ENCODER_WORKER_REJECTED" }
            } catch (t: Throwable) { fail(t, ContinuousFailureStage.OUTPUT_PREPARE) }
        }) }
    }

    fun owns(output: RecordingOutputHandle) = output in ownedOutputs

    /** Revoke only uncommitted cuts. A queued boundary already owns both its keyframe and destination. */
    fun prepareForStop() {
        continuationRevoked = true
        cutProgress?.cancel(SystemClock.uptimeMillis())
        com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.close(cabinPreview)
    }

    fun evidence(): EncoderEvidence {
        val (bytes, count) = queue.snapshot()
        cacheCounters(bytes, count)
        val formats = trackFormatState.snapshot()
        return EncoderEvidence(encodedFrames, firstPtsUs, lastPtsUs, lastOutputElapsedMs, bytes, count, completedFiles,
            encodedBytes = encodedBytes, writtenFrames = writtenFrames, lastWriteElapsedMs = lastWriteElapsedMs,
            formatNotifications = formats.notificationCount, repeatedFormatNotifications = formats.compatibleRepeats,
            terminalUnwrittenFrames = terminalUnwrittenFrames, formatDecision = when {
                formats.decision != "ROLLOVER_PENDING" -> formats.decision
                formats.notificationCount <= lastAppliedFormatNotification -> "ROLLOVER_APPLIED"
                else -> formats.decision
            })
    }

    private val drain = object : Runnable {
        override fun run() {
            if (closing || eosSeen) return
            try {
                val active = codec ?: return
                if (!ending && !continuationRevoked && firstFailure.get() == null && trackFormatState.hasPendingBoundary) {
                    check(SystemClock.uptimeMillis() - requireNotNull(pendingFormatSinceUptimeMs) < FORMAT_BOUNDARY_WAIT_MS) {
                        "CONTINUOUS_FORMAT_BOUNDARY_TIMEOUT"
                    }
                }
                val info = MediaCodec.BufferInfo()
                for (attempt in 0 until 16) {
                    val index = active.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (firstFailure.get() != null || abandoned) continue
                        try {
                            // stop() publishes ending before its codec-worker cancellation runs. A
                            // format notification can win that race; never turn a revoked cut fatal.
                            if (ending || closing) cancelPendingFormatForEnd()
                            val snapshot = try { ContinuousTrackFormatAndroid.capture(active.outputFormat) }
                                catch (error: Exception) {
                                    val terminal = ending || closing
                                    trackFormatState.incomplete(error, terminal)
                                    if (terminal) {
                                        pendingTrackFormat = null; cutCallback = null
                                        continue
                                    }
                                    throw error
                                }
                            com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.format(cabinPreview, snapshot.format)
                            // Decide before dequeueing subsequent samples. An asynchronous writer-only
                            // check could let an already-running pump write changed data to the old track.
                            installOrStageFormat(snapshot)
                        } catch (error: Throwable) {
                            // ending can flip after accept read it. Preserve the rejection evidence,
                            // but revoke that uncommitted format instead of abandoning a valid old file.
                            if ((ending || closing) && error is ContinuousTrackFormatException) {
                                pendingTrackFormat = null; cutCallback = null
                                trackFormatState.discardAtEnd()
                                continue
                            }
                            // Publish the exact rejected observation before first-failure capture.
                            observeFormat(cutPending = cutCallback != null)
                            fail(error, ContinuousFailureStage.OUTPUT_PREPARE)
                        } finally { observeFormat(cutPending = cutCallback != null) }
                    } else if (index >= 0) {
                        try {
                            // A final EOS carrying bytes cannot create a fresh file for a pending format.
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) cancelPendingFormatForEnd()
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                firstFailure.get() == null && !abandoned) {
                                receive(active, index, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                cancelPendingFormatForEnd()
                                eosSeen = true; eos.countDown(); schedulePump()
                            }
                        } finally { active.releaseOutputBuffer(index, false) }
                    } else break
                    if (eosSeen) break
                }
            } catch (t: Throwable) { fail(t, ContinuousFailureStage.CODEC_DRAIN) }
            // After a writer failure, keep draining/discarding until the camera owner stops input.
            if (!closing && !eosSeen) codecHandler.postDelayed(this, 5)
        }
    }

    private fun installOrStageFormat(snapshot: ContinuousTrackFormatAndroid.Snapshot) {
        when (trackFormatState.accept(snapshot.spec,
            cutPending = allowFormatRollover && cutCallback != null && !ending && !closing,
            terminal = ending || closing, allowIndependentRollover = allowFormatRollover)) {
            ContinuousFormatDecision.INSTALL -> check(writerHandler.post {
                if (closing || abandoned || firstFailure.get() != null) return@post
                try {
                    writerFormat = snapshot.format
                    target?.configure(snapshot.format)
                    schedulePump()
                } catch (error: Throwable) { fail(error, ContinuousFailureStage.OUTPUT_PREPARE) }
            }) { "WRITER_QUEUE_REJECTED" }
            ContinuousFormatDecision.KEEP -> Unit
            ContinuousFormatDecision.STAGE_ROLLOVER -> {
                pendingTrackFormat = snapshot
                pendingFormatSinceUptimeMs = SystemClock.uptimeMillis()
                // File allocation belongs to RecorderSession. Reuse a cut already in progress,
                // otherwise ask it for a boundary now rather than waiting for the minute timer.
                if (cutCallback == null) onFormatBoundaryNeeded(this)
            }
            ContinuousFormatDecision.DISCARD_TERMINAL -> {
                pendingTrackFormat = null; cutCallback = null; pendingFormatSinceUptimeMs = null
            }
        }
    }

    private fun receive(active: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (ending || continuationRevoked) {
            planner.cancelCut()
            cutCallback = null
            cancelPendingFormatForEnd()
        }
        if (trackFormatState.discardTerminalMedia) return
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val placement: KeyframeSegmentPlanner.Placement
        val rollover: TrackRollover?
        try {
            // Check before planner.accept can drop a sample or consume the pending cut.
            if (!trackFormatState.beforeMediaSample(keyframe, boundaryReady = cutCallback != null)) return
            placement = planner.accept(info.presentationTimeUs, keyframe) ?: return
            rollover = if (trackFormatState.commitBoundary(placement.cutBefore)) {
                TrackRollover(requireNotNull(pendingTrackFormat), trackFormatState.snapshot()).also {
                    pendingTrackFormat = null; pendingFormatSinceUptimeMs = null
                }
            } else null
        } catch (error: ContinuousTrackFormatException) {
            if (ending || closing) {
                pendingTrackFormat = null; cutCallback = null
                trackFormatState.discardAtEnd()
                observeFormat(cutPending = false)
                return
            }
            observeFormat(cutPending = cutCallback != null)
            throw error
        }
        val epoch = System.currentTimeMillis()
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        val stamp = clock.observe(info.presentationTimeUs, elapsedNs, epoch)
        val boundary: (() -> Item)? = if (placement.cutBefore) {
            val done = requireNotNull(cutCallback); cutCallback = null
            cutProgress?.advance(DirectCutProgress.Phase.WRITING_BOUNDARY, SystemClock.uptimeMillis());
            { Item.Boundary(done, stamp, rollover, transitionGap = (rollover?.observation?.discardedTransitionFrames ?: 0) > 0) }
        } else null
        check(offerContinuousSample(queue, info.size, boundary) {
            val source = requireNotNull(active.getOutputBuffer(index)).duplicate()
            source.position(info.offset); source.limit(info.offset + info.size)
            Item.Frame(ByteArray(info.size).also { source.get(it) }, placement.ptsUs,
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(), stamp)
                .also { frame -> com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.frame(
                    cabinPreview, frame.bytes, frame.ptsUs, keyframe) }
        }) { "ENCODER_QUEUE_FULL" }
        encodedFrames++; encodedBytes += info.size
        if (firstPtsUs == null) firstPtsUs = info.presentationTimeUs
        lastPtsUs = info.presentationTimeUs; lastOutputElapsedMs = elapsedNs / 1_000_000L
        cacheCounters()
        schedulePump()
    }

    private fun schedulePump() {
        if (pumpQueued.compareAndSet(false, true) && !writerHandler.post {
                pumpQueued.set(false)
                pump()
            }) { pumpQueued.set(false); fail(IllegalStateException("WRITER_QUEUE_REJECTED"), ContinuousFailureStage.FILE_WRITER) }
    }

    private fun pump() {
        try {
            for (attempt in 0 until 32) {
                val next = queue.peek() ?: break
                if (next is Item.Frame && (target == null || writerFormat == null) && !ending) return
                val ticket = queue.poll() ?: break
                try {
                    when (val item = ticket.value) {
                        is Item.Frame -> if (!abandoned && firstFailure.get() == null) {
                            // Stop cannot convert accepted samples into successful, unwritten output.
                            // The normal running path above waits for a target; only terminal drain reaches this.
                            val destination = target ?: throw ContinuousTailOutputMissingException()
                            destination.write(item)
                        }
                        is Item.Boundary -> {
                            if (abandoned || firstFailure.get() != null) continue
                            val replacement = requireNotNull(nextTarget) { "CONTINUOUS_NEXT_NOT_OWNED" }
                            finishContinuousTrackBoundary(
                                finish = { finishTarget(item.nextKeyframe.epochMs, item.nextKeyframe.elapsedNs / 1_000_000L,
                                    nextKeyframePtsUs = item.nextKeyframe.ptsUs.takeUnless { item.transitionGap }) },
                                newFormat = item.rollover,
                                applyFormat = { transition ->
                                    writerFormat = transition.snapshot.format
                                    replacement.formatObservation = transition.observation
                                },
                                done = { summary ->
                                    target = replacement; nextTarget = null
                                    replacement.configure(requireNotNull(writerFormat))
                                    // Publication never prepares the writer; a committed cut survives Stop.
                                    item.done(summary)
                                },
                            )
                        }
                    }
                } finally { queue.release(ticket) }
            }
            if (queue.peek() != null) schedulePump()
            else if (ending && eosSeen) {
                finishTarget(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                discardNextTarget()
                writerEnded.countDown()
            }
        } catch (t: Throwable) {
            if (ending && t is ContinuousTailOutputMissingException) {
                // There is no live muxer here to abandon. Stop already owns teardown; avoid the
                // generic error callback, which marks a USB destination lost and could discard
                // the previous, successfully sealed file. Propagate the terminal error from stop().
                if (firstFailure.compareAndSet(null, t)) captureFailure(t, ContinuousFailureStage.FILE_WRITER)
                queue.clear()
                if (eosSeen) writerEnded.countDown()
            } else fail(t, ContinuousFailureStage.FILE_WRITER)
            // release() retries finishing the owned target after producer acknowledgement.
        }
    }

    private fun finishTarget(epoch: Long, elapsed: Long, nextKeyframePtsUs: Long? = null): EncodedSegmentSummary? {
        val current = target ?: return null
        observed(ContinuousFailureStage.FILE_FINALIZE) { current.finish(abandoned, nextKeyframePtsUs) }
        closeOwnedOutput(current.output)
        if (abandoned) {
            // A released descriptor is not a sealed MP4. Keep the lost file's recovery
            // journal; neither publish it nor classify its already-written samples as unused.
            target = null
            return null
        }
        val summary = observed(ContinuousFailureStage.FILE_FINALIZE) { current.summary(epoch, elapsed, nextKeyframePtsUs) }
        target = null
        if (summary.frames == 0L) onUnused(this, current.output)
        else {
            lastCompleted = summary; completedFiles++
            onClosed(this, summary)
        }
        return summary
    }

    private fun closeOwnedOutput(output: RecordingOutputHandle) {
        observed(ContinuousFailureStage.FILE_CLEANUP) {
            if (abandoned && output is UsbMediaStoreRecordingOutputHandle) output.abandonUnavailableTarget()
            else output.close()
            check(output.nativeReleaseConfirmed) { "CONTINUOUS_OUTPUT_CLOSE_UNCONFIRMED" }
            ownedOutputs.remove(output)
        }
    }

    private fun discardNextTarget() {
        val unused = nextTarget ?: return
        unused.finish(abandoned = true, nextKeyframePtsUs = null)
        closeOwnedOutput(unused.output)
        nextTarget = null
        onUnused(this, unused.output)
    }

    override fun abandonOutput() {
        abandoned = true; queue.clear()
        com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.close(cabinPreview)
    }

    override fun stop() {
        prepareForStop()
        ending = true
        if (started) {
            call(codecHandler) {
                cancelPendingFormatForEnd()
                if (!eosSeen) codec!!.signalEndOfInputStream()
            }
            check(eos.await(3, TimeUnit.SECONDS)) { "ENCODER_EOS_TIMEOUT" }
        } else { eosSeen = true; eos.countDown() }
        schedulePump()
        check(writerEnded.await(3, TimeUnit.SECONDS)) { "ENCODER_WRITER_STOP_TIMEOUT" }
        firstFailure.get()?.let {
            if (it is ContinuousTailOutputMissingException) throw it
            throw IllegalStateException("CONTINUOUS_ENCODER_FAILED", it)
        }
    }

    override fun reset() = Unit

    override fun release() {
        // Always called behind RecorderSession's existing camera-producer fence, also on startup failure.
        closing = true; ending = true
        com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.close(cabinPreview)
        call(codecHandler) {
            cancelPendingFormatForEnd()
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
            if (target != null) finishTarget(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            discardNextTarget()
            // Includes descriptors admitted before a native setup or handler-post failure.
            ownedOutputs.toList().forEach { closeOwnedOutput(it); onUnused(this, it) }
            queue.clear()
            writerEnded.countDown()
        }
        codecThread.quitSafely(); writerThread.quitSafely()
        codecThread.join(500); writerThread.join(500)
        check(!codecThread.isAlive && !writerThread.isAlive) { "ENCODER_WORKER_EXIT_UNCONFIRMED" }
        closeTrace("CODEC_INPUT_RELEASE_ENTER")
        input?.release(); input = null
        closeTrace("CODEC_INPUT_RELEASE_RETURN")
    }

    @Volatile internal var closeTrace: (String) -> Unit = {}

    /** Cache existing counters during ordinary work. Failure reporting never waits for this queue lock. */
    private fun cacheCounters() {
        val (bytes, count) = queue.snapshot()
        cacheCounters(bytes, count)
    }

    private fun cacheCounters(bytes: Long, count: Int) {
        cachedFailureSample = ContinuousFailureSample(SystemClock.elapsedRealtime(), bytes, count,
            encodedFrames, encodedBytes, completedFiles, sourceAgeMs = null)
    }

    private fun observeFormat(cutPending: Boolean) {
        runCatching { failureCapture?.observeFormat(trackFormatState.snapshot(), cutPending) }
    }

    /** No new track at terminal EOS: retain only samples already written under the old contract. */
    private fun cancelPendingFormatForEnd() {
        if (pendingTrackFormat == null) return
        pendingTrackFormat = null; cutCallback = null; pendingFormatSinceUptimeMs = null
        trackFormatState.cancelPending()
        observeFormat(cutPending = false)
    }

    fun captureFailure(error: Throwable?, stage: ContinuousFailureStage, signal: String? = null): ContinuousFailureEvidence? =
        runCatching { failureCapture?.capture(error, stage, cachedFailureSample, signal,
            cutProgress?.snapshot(SystemClock.uptimeMillis())) }.getOrNull()

    /** Adds evidence only; native calls, exception propagation and stop authority stay unchanged. */
    private inline fun <T> observed(stage: ContinuousFailureStage, action: () -> T): T = try {
        action()
    } catch (error: Throwable) {
        captureFailure(error, stage)
        throw error
    }

    private fun fail(error: Throwable, stage: ContinuousFailureStage) {
        if (firstFailure.compareAndSet(null, error)) {
            com.dante.zeekrcapabilitylab.mirror.RecordingCabinFeed.close(cabinPreview)
            captureFailure(error, stage)
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

    private inner class Target(val output: RecordingOutputHandle,
                               var formatObservation: ContinuousFormatObservation? = null,
                               val cut: DirectCutProgress? = null) {
        private var muxer: MediaMuxer? = null
        private var track = -1
        private var started = false
        private val fileClock = EncodedFileClock(clock.runId)

        fun prepareMuxer() { muxer = output.createMuxer() }

        fun configure(format: MediaFormat) {
            check(!started)
            // Each muxer gets its own CSD cursors; rotating must not reuse buffers consumed by addTrack.
            track = muxer!!.addTrack(ContinuousTrackFormatAndroid.copyForMuxer(format))
            muxer!!.start(); started = true
        }
        fun write(frame: Item.Frame) {
            check(started)
            if (fileClock.frames == 0L) check(frame.ptsUs == 0L && frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) { "SEGMENT_MUST_START_WITH_KEYFRAME" }
            val info = MediaCodec.BufferInfo().apply { set(0, frame.bytes.size, frame.ptsUs, frame.flags) }
            muxer!!.writeSampleData(track, ByteBuffer.wrap(frame.bytes), info)
            fileClock.written(frame.stamp)
            writtenFrames++; lastWriteElapsedMs = SystemClock.elapsedRealtime()
            if (fileClock.frames == 1L) {
                cut?.advance(DirectCutProgress.Phase.COMPLETE, SystemClock.uptimeMillis())
                onStarted(this@ContinuousVideoRecorder, output, frame.stamp.epochMs, frame.stamp.elapsedNs / 1_000_000L)
            }
            formatObservation?.let { observation ->
                formatObservation = null // Exactly once, and only after this track accepted a real frame.
                lastAppliedFormatNotification = maxOf(lastAppliedFormatNotification, observation.notificationCount)
                runCatching { failureCapture?.observeFormat(observation.copy(decision = "ROLLOVER_APPLIED"), cutPending = false) }
            }
        }
        fun finish(abandoned: Boolean, nextKeyframePtsUs: Long?) {
            val active = muxer ?: return
            var error: Throwable? = null
            if (started && !abandoned && fileClock.frames > 0) {
                try {
                    fileClock.coverage(nextKeyframePtsUs)?.let { coverage ->
                        active.writeSampleData(track, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
                            set(0, 0, coverage.durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        })
                    }
                } catch (t: Throwable) { captureFailure(t, ContinuousFailureStage.FILE_FINALIZE); error = t }
                try { active.stop() } catch (t: Throwable) {
                    captureFailure(t, ContinuousFailureStage.FILE_FINALIZE)
                    if (error == null) error = t else error.addSuppressed(t)
                }
            }
            observed(ContinuousFailureStage.FILE_CLEANUP) { active.release() }
            muxer = null; started = false
            error?.let { throw it }
        }
        fun summary(endEpoch: Long, endElapsed: Long, nextKeyframePtsUs: Long?): EncodedSegmentSummary {
            val coverage = fileClock.coverage(nextKeyframePtsUs)
            return EncodedSegmentSummary(output, fileClock.frames, coverage?.timeline?.firstPtsUs, coverage?.timeline?.lastPtsUs,
                fileClock.maximumGapUs, coverage?.first?.epochMs, coverage?.first?.elapsedNs?.div(1_000_000L),
                coverage?.endEpochMs ?: endEpoch, coverage?.endElapsedNs?.div(1_000_000L) ?: endElapsed,
                coverage?.anchor, coverage?.timeline)
        }
    }

    companion object {
        // Destination preparation has its own 30 s bound, followed by an 8 s keyframe bound.
        // This outer limit also catches a missed owner notification without unbounded buffering.
        const val FORMAT_BOUNDARY_WAIT_MS = 40_000L
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
