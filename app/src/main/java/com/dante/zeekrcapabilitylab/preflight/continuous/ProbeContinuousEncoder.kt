package com.dante.zeekrcapabilitylab.preflight.continuous

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.service.recorder.BoundedEncodedQueue
import com.dante.zeekrcapabilitylab.service.recorder.RecordingOutputHandle
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Diagnostic-only core for P1; P2 can feed this same input from GL. No camera or UI dependencies.
 * Codec drain and USB writer run independently of eglSwapBuffers. Only finish() may send codec EOS,
 * and its caller must first destroy/acknowledge the GL producer. Each output owns a separate muxer.
 */
@androidx.annotation.RequiresApi(29)
internal class ProbeContinuousEncoder(
    private val codecName: String, private val requested: ProbeVideoEncoding,
    first: RecordingOutputHandle, val progress: ProbeSegmentProgress,
    private val encodedByteBudget: Long = 192L * 1024 * 1024,
    private val p2FileLifecycle: Boolean = false,
    private val metadata: (Int, Long) -> ByteArray,
) {
    data class Closed(val index: Int, val output: RecordingOutputHandle, val baseUs: Long,
        val lastUs: Long, val endUs: Long, val frames: Int)
    private sealed interface Item {
        data class Sample(val data: ByteArray, val runPts: Long, val filePts: Long, val flags: Int) : Item
        data class Cut(val boundary: ProbeCutPlan.Boundary) : Item
    }
    private class FileTarget(val index: Int, val output: RecordingOutputHandle) {
        var muxer: MediaMuxer? = null
        var started = false
        var video = -1; var layout = -1
        var base = -1L; var last = -1L; var frames = 0
        @Volatile var nativeReleased = false
    }
    private val ready = CountDownLatch(1)
    private data class Failure(val error: Throwable, val snapshot: ProbeSegmentProgress.Snapshot,
        val problem: ProbeSegmentProgress.Problem?, val finalizers: kotlinx.serialization.json.JsonObject?)
    private val failure = AtomicReference<Failure?>()
    private val cut = AtomicReference<Pair<Int, Long>?>()
    private val queue = BoundedEncodedQueue<Item>(32L * 1024 * 1024, 300)
    @Volatile private var format: MediaFormat? = null
    @Volatile var formatFingerprint: String? = null; private set
    @Volatile private var configuredInput: kotlinx.serialization.json.JsonObject? = null
    @Volatile private var configuredOutput: kotlinx.serialization.json.JsonObject? = null
    @Volatile private var emittedOutput: kotlinx.serialization.json.JsonObject? = null
    fun formatObservation()=com.dante.zeekrcapabilitylab.preflight.obj(
        "requested" to ProbeCodecFormat.evidence(requested),"inputAfterStart" to configuredInput,
        "outputAfterStart" to configuredOutput,"outputFormatChanged" to emittedOutput,
        "nullMeaning" to "NOT_OBSERVED","measuredBitrate" to "SEE_ENCODED_LOAD_EVIDENCE")
    private fun observeFormat(read:()->MediaFormat)=runCatching { ProbeCodecFormat.observe(read()) }
        .getOrElse { com.dante.zeekrcapabilitylab.preflight.obj("status" to "UNAVAILABLE","errorType" to it.javaClass.simpleName) }
    @Volatile private var input: Surface? = null
    val surface get() = requireNotNull(input)
    @Volatile private var next: FileTarget? = null
    @Volatile private var nextReady = false
    @Volatile private var endRequested = false
    @Volatile private var endPts = 0L
    @Volatile private var outputEos = false
    @Volatile private var drainDone = false
    @Volatile private var codecReleased = false
    @Volatile private var writerReleased = false
    @Volatile private var finalDuration = 0L
    @Volatile private var requireCompleteManifest = true
    @Volatile private var observerStopped = false
    @Volatile var encodedFrames = 0; private set
    @Volatile var encodedBytes = 0L; private set
    @Volatile var maximumQueueBytes = 0L; private set
    @Volatile var maximumQueueItems = 0; private set
    @Volatile var completedFiles = 0; private set
    @Volatile var eosSignals = 0; private set
    private var codec: MediaCodec? = null // native reference retained on release failure
    private var current: FileTarget? = FileTarget(0, first)
    private val closed = ArrayList<Closed>() // guarded; P2 closes on its independent finalizer
    private val closeOverlap = ArrayList<kotlinx.serialization.json.JsonObject>()
    private val encodedPts = ArrayList<Long>()
    private val finalizer = if(p2FileLifecycle)ProbeFileFinalizer(SystemClock::elapsedRealtime) else null
    private val finalizingTargets = ArrayList<FileTarget>() // writer owns admission; read after it joins
    private val codecThread = Thread(::encodeLoop, "p1-codec-drain")
    private val writerThread = Thread(::writeLoop, "p1-usb-writer")
    private val observerThread = Thread({
        while(!observerStopped) {
            observeProgress()
            Thread.sleep(20)
        }
    },"p1-stage-watchdog")
    private var launched = false

    fun start() {
        launched = true; observerThread.start(); writerThread.start(); codecThread.start()
        check(ready.await(8, TimeUnit.SECONDS)) { "CODEC_START_TIMEOUT" }; checkHealthy()
        check(input != null) { "CODEC_INPUT_UNAVAILABLE" }
    }
    private fun observeProgress() {
        if(failure.get()==null) {
            finalizer?.failure?.let {fail(it);return}
            finalizer?.problem()?.let {
                failure.compareAndSet(null,Failure(IllegalStateException(it.code),snapshot(),
                    ProbeSegmentProgress.Problem(it.code,it.stage,file=it.file),finalizationEvidence()))
                return
            }
            val (bytes,items)=queue.snapshot()
            progress.assess(SystemClock.elapsedRealtime(),bytes,items)?.let { (problem,snapshot) ->
                failure.compareAndSet(null,Failure(IllegalStateException(problem.code),snapshot,problem,finalizationEvidence()))
            }
        }
    }
    fun checkHealthy() { observeProgress(); failure.get()?.let { throw it.error } }
    fun inputSubmitted(ptsUs: Long) { progress.input(ptsUs,SystemClock.elapsedRealtime()) }
    fun snapshot() = queue.snapshot().let { (bytes,items) -> progress.snapshot(SystemClock.elapsedRealtime(),bytes,items) }
    fun faultSnapshot(): kotlinx.serialization.json.JsonObject? = failure.get()?.let {
        com.dante.zeekrcapabilitylab.preflight.obj("reason" to it.error.message?.takeIf { s -> s.matches(Regex("[A-Z0-9_]{1,100}")) },
            "stage" to it.problem?.stage,"cutId" to it.problem?.cutId,"file" to it.problem?.file,"state" to it.snapshot.json(),
            "finalizers" to it.finalizers)
    }
    fun recordFailure(t: Throwable) { fail(t) }
    fun prepareNext(index: Int, output: RecordingOutputHandle) {
        check(!endRequested && next == null && !nextReady); checkHealthy()
        next = FileTarget(index, output)
        // Output format often arrives only AFTER the first submitted frame. Never wait for it here.
    }
    fun preparedIndex(): Int? = if(nextReady) next?.index else null
    fun armCut(id: Int, targetPts: Long) {
        check(!endRequested && nextReady && next?.index == id && cut.compareAndSet(null, id to targetPts)) { "CUT_NOT_PREPARED" }
    }
    /** Producer acknowledgement precedes this method even on cancellation. */
    fun finish(actualEndPts: Long, completeManifest: Boolean = true): List<Closed> {
        endPts = actualEndPts; requireCompleteManifest=completeManifest; endRequested = true
        if (launched) {
            codecThread.join(8_000); writerThread.join(8_000)
            val finalizersStopped=finalizer?.finish() ?: true
            observerStopped=true; observerThread.join(1_000)
            check(!codecThread.isAlive && !writerThread.isAlive && finalizersStopped &&
                finalizingTargets.all {it.nativeReleased} && codecReleased && writerReleased) { "P1_ENCODER_CLEANUP_UNCONFIRMED" }
        } else { finalizer?.finish();codecReleased = true; writerReleased = true }
        input?.release(); input = null
        checkHealthy()
        check(outputEos && (!completeManifest || completedFiles == 4) && eosSignals == 1) { "P1_FILE_MANIFEST_INCOMPLETE" }
        return synchronized(closed) {closed.sortedBy {it.index}}
    }
    fun cleanupConfirmed() = !codecThread.isAlive && !writerThread.isAlive && !observerThread.isAlive &&
        (finalizer?.terminated() ?: true) && finalizingTargets.all {it.nativeReleased} && codecReleased && writerReleased && input == null
    fun codecPts()=synchronized(encodedPts) {encodedPts.toLongArray()}
    fun finalizationEvidence():kotlinx.serialization.json.JsonObject? = finalizer?.snapshot()?.let {s->
        com.dante.zeekrcapabilitylab.preflight.obj("mode" to "ONE_OLD_FILE_INDEPENDENT_OF_ACTIVE_WRITER",
            "maximumConcurrentClosingFiles" to 1,"observedAtMs" to s.observedAtMs,
            "muxerTimeoutMs" to ProbeFileFinalizer.MUXER_TIMEOUT_MS,"syncTimeoutMs" to ProbeFileFinalizer.SYNC_TIMEOUT_MS,
            "workerTerminated" to s.workerTerminated,"activeWriterOverlap" to synchronized(closeOverlap) {closeOverlap.toList()},
            "problem" to s.problem?.let {p->
                com.dante.zeekrcapabilitylab.preflight.obj("reason" to p.code,"stage" to p.stage,"file" to p.file,
                    "startedAtMs" to p.startedAtMs,"observedAtMs" to p.observedAtMs)},
            "work" to s.work.map {w->com.dante.zeekrcapabilitylab.preflight.obj("file" to w.file,
                "acceptedAtMs" to w.acceptedAtMs,"stage" to w.stage,"stageAtMs" to w.stageAtMs,
                "completedAtMs" to w.completedAtMs,"failureType" to w.failureType)})
    }
    fun failureEvidence(): kotlinx.serialization.json.JsonObject {
        val t=failure.get()?.error; val native=t as? MediaCodec.CodecException
        return com.dante.zeekrcapabilitylab.preflight.obj("type" to t?.javaClass?.simpleName,
            "codecDiagnostic" to native?.diagnosticInfo,"codecError" to native?.errorCode,
            "recoverable" to native?.isRecoverable,"transient" to native?.isTransient)
    }
    private fun fail(t: Throwable) { if(failure.get()==null)failure.compareAndSet(null, Failure(t,snapshot(),null,finalizationEvidence())) }

    private fun encodeLoop() {
        var started = false
        try {
            val active = MediaCodec.createByCodecName(codecName).also { codec = it }
            active.configure(ProbeCodecFormat.create(requested), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = active.createInputSurface(); active.start(); started = true
            configuredInput=observeFormat { active.inputFormat }
            configuredOutput=observeFormat { active.outputFormat }
            ready.countDown()
            val planner = ProbeCutPlan(); val info = MediaCodec.BufferInfo()
            var eosAt = 0L
            while (!outputEos) {
                if (endRequested && eosSignals == 0) {
                    active.signalEndOfInputStream(); eosSignals++; eosAt = SystemClock.elapsedRealtime()
                }
                if (eosSignals > 0) check(SystemClock.elapsedRealtime() - eosAt < 6_000) { "CODEC_EOS_TIMEOUT" }
                cut.getAndSet(null)?.let { (id, pts) ->
                    planner.arm(id, pts)
                    progress.armed(id,SystemClock.elapsedRealtime())
                }
                progress.syncDue()?.let { id ->
                    progress.syncRequested(id,SystemClock.elapsedRealtime())
                    active.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    progress.syncReturned(id,SystemClock.elapsedRealtime())
                }
                val index = active.dequeueOutputBuffer(info, 5_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val copy = ProbeCodecFormat.snapshot(active.outputFormat)
                    emittedOutput=ProbeCodecFormat.observe(copy)
                    val fingerprint = ProbeCodecFormat.fingerprint(copy)
                    check(formatFingerprint == null || formatFingerprint == fingerprint) { "FORMAT_CHANGED_MID_RUN" }
                    if (format == null) { formatFingerprint = fingerprint; format = copy }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && failure.get() == null) {
                            check(encodedBytes + info.size <= encodedByteBudget) { "ENCODED_BYTE_BUDGET_EXCEEDED" }
                            val boundary = planner.sample(info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            progress.output(info.presentationTimeUs,SystemClock.elapsedRealtime())
                            if (boundary != null) {
                                progress.accepted(boundary.cutId,info.presentationTimeUs,SystemClock.elapsedRealtime())
                                check(queue.offer(0) { Item.Cut(boundary) }) { "ENCODER_QUEUE_FULL" }
                                progress.enqueued(boundary.cutId,SystemClock.elapsedRealtime())
                            }
                            check(queue.offer(info.size) {
                                val src = requireNotNull(active.getOutputBuffer(index)).duplicate()
                                src.position(info.offset); src.limit(info.offset + info.size)
                                Item.Sample(ByteArray(info.size).also { src.get(it) }, info.presentationTimeUs,
                                    planner.filePts(info.presentationTimeUs), info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                            }) { "ENCODER_QUEUE_FULL" }
                            encodedFrames++
                            if(p2FileLifecycle)synchronized(encodedPts) {
                                check(encodedPts.size<25_000) {"CODEC_PTS_EVIDENCE_BUDGET"};encodedPts+=info.presentationTimeUs
                            }
                            encodedBytes += info.size
                            val (bytes, count) = queue.snapshot()
                            maximumQueueBytes = maxOf(maximumQueueBytes, bytes); maximumQueueItems = maxOf(maximumQueueItems, count)
                        }
                        // A buffer may contain the final real frame AND EOS. Process its data first.
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (failure.get() == null) finalDuration = planner.end(endPts,requireCompleteManifest)
                            outputEos = true
                        }
                    } finally { active.releaseOutputBuffer(index, false) }
                }
            }
        } catch (t: Throwable) { fail(t) }
        finally {
            ready.countDown()
            // A failure revokes further input. Do not release a Surface still being drawn by GL.
            while (!endRequested) Thread.sleep(2)
            try {
                if (started) runCatching { codec?.stop() }.onFailure(::fail)
                codec?.release(); codec = null; codecReleased = true
            } catch (t: Throwable) { fail(t) }
            drainDone = true
        }
    }
    private fun configure(target: FileTarget, format: MediaFormat) {
        if (target.started) return
        progress.writer("PREPARE_FILE",target.index,SystemClock.elapsedRealtime())
        val muxer = target.output.createMuxer().also { target.muxer = it }
        target.video = muxer.addTrack(ProbeCodecFormat.snapshot(format))
        target.layout = muxer.addTrack(MediaFormat().apply { setString(MediaFormat.KEY_MIME, METADATA_MIME) })
        muxer.start(); target.started = true
        progress.prepared(target.index,SystemClock.elapsedRealtime())
        progress.writer("IDLE",target.index,SystemClock.elapsedRealtime())
    }
    private fun writeLoop() {
        try {
            while (!drainDone || queue.peek() != null) {
                val f = format
                if (failure.get() != null) { queue.clear(); Thread.sleep(2); continue }
                if (f == null) { Thread.sleep(2); continue }
                current?.let { configure(it, f) }
                next?.let { configure(it, f); nextReady = true }
                val ticket = queue.poll()
                if (ticket == null) { Thread.sleep(2); continue }
                try {
                    when (val item = ticket.value) {
                        is Item.Cut -> {
                            check(nextReady && next?.index == item.boundary.cutId) { "CUT_TARGET_MISSING" }
                            progress.received(item.boundary.cutId,SystemClock.elapsedRealtime())
                            finishOrDispatch(requireNotNull(current), item.boundary.oldDurationUs)
                            current = next; next = null; nextReady = false
                            progress.writer("IDLE",current?.index,SystemClock.elapsedRealtime())
                        }
                        is Item.Sample -> {
                            val t = requireNotNull(current); val muxer = requireNotNull(t.muxer)
                            progress.writer("WRITE_SAMPLE",t.index,SystemClock.elapsedRealtime())
                            if (t.frames == 0) {
                                check(item.filePts == 0L && item.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                                t.base = item.runPts
                                val bytes = metadata(t.index, t.base)
                                muxer.writeSampleData(t.layout, ByteBuffer.wrap(bytes), MediaCodec.BufferInfo().apply { set(0, bytes.size, 0, 0) })
                            }
                            muxer.writeSampleData(t.video, ByteBuffer.wrap(item.data), MediaCodec.BufferInfo().apply {
                                set(0, item.data.size, item.filePts, item.flags) })
                            t.last = item.runPts; t.frames++
                            progress.written(item.runPts)
                            progress.writer("IDLE",t.index,SystemClock.elapsedRealtime())
                        }
                    }
                } finally { queue.release(ticket) }
            }
            if (failure.get() == null && outputEos) {
                finishOrDispatch(requireNotNull(current), finalDuration); current = null
            }
        } catch (t: Throwable) { fail(t) }
        finally {
            // Codec may still be draining after an I/O error. Never race its queue ownership.
            while (!drainDone) { queue.clear(); Thread.sleep(2) }
            queue.clear()
            var released = true
            for (t in listOfNotNull(current, next)) {
                if(p2FileLifecycle) {if(!releaseTarget(t))released=false;continue}
                try { t.muxer?.release(); t.muxer = null; t.started = false } catch (error: Throwable) { released = false; fail(error) }
                if (t.muxer == null) try { t.output.close() } catch (error: Throwable) { fail(error) }
            }
            writerReleased = released
            progress.writer("DONE",null,SystemClock.elapsedRealtime())
        }
    }
    private fun finishOrDispatch(t:FileTarget,durationUs:Long) {
        val worker=finalizer
        if(worker==null) {finishTarget(t,durationUs);return}
        finalizingTargets+=t
        worker.submit(t.index, releaseConfirmed = { t.nativeReleased }) {
            val before=snapshot()
            try {finishTarget(t,durationUs)}
            finally {
                releaseTarget(t)
                val after=snapshot()
                synchronized(closeOverlap) {closeOverlap+=com.dante.zeekrcapabilitylab.preflight.obj(
                    "closingFile" to t.index,"startedAtMs" to before.atElapsedMs,"finishedAtMs" to after.atElapsedMs,
                    "sourceFramesAdvanced" to after.sourceFrames-before.sourceFrames,
                    "codecOutputFramesAdvanced" to after.outputFrames-before.outputFrames,
                    "activeWriterFramesAdvanced" to after.writtenFrames-before.writtenFrames,
                    "queueBytesAtStart" to before.queueBytes,"queueBytesAtEnd" to after.queueBytes,
                    "nativeReleased" to t.nativeReleased)}
            }
        }
    }
    /** Called by the target's sole native owner, never by the watchdog. */
    private fun releaseTarget(t:FileTarget):Boolean {
        if(t.nativeReleased)return true
        try {t.muxer?.release();t.muxer=null;t.started=false} catch(error:Throwable) {fail(error);return false}
        try {t.output.close();t.nativeReleased=true} catch(error:Throwable) {fail(error);return false}
        return true
    }
    private fun closingStage(t:FileTarget,stage:String) {
        if(finalizer!=null)finalizer.stage(t.index,stage)
        else progress.writer(stage,t.index,SystemClock.elapsedRealtime())
    }
    private fun finishTarget(t: FileTarget, durationUs: Long) {
        check(t.frames > 0 && durationUs > t.last - t.base) { "FILE_TAIL_DURATION_INVALID" }
        val muxer = requireNotNull(t.muxer)
        progress.closeStarted(t.index,SystemClock.elapsedRealtime())
        closingStage(t,"MUXER_FINALIZE")
        // This empty muxer marker sets the last sample's duration. It is NOT codec input EOS.
        muxer.writeSampleData(t.video, ByteBuffer.allocate(0), MediaCodec.BufferInfo().apply {
            set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) })
        muxer.stop(); t.started = false; muxer.release(); t.muxer = null
        progress.muxerClosed(t.index,SystemClock.elapsedRealtime())
        progress.syncCloseStarted(t.index,SystemClock.elapsedRealtime())
        closingStage(t,"USB_SYNC_CLOSE")
        t.output.close()
        t.nativeReleased=true
        progress.closed(t.index,SystemClock.elapsedRealtime())
        if(finalizer==null)progress.writer("IDLE",t.index,SystemClock.elapsedRealtime())
        synchronized(closed) {
            closed += Closed(t.index, t.output, t.base, t.last, t.base + durationUs, t.frames)
            completedFiles=closed.size
        }
    }
    companion object { const val METADATA_MIME = "application/vnd.openavm.layout+json" }
}
