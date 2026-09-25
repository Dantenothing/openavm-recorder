package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.preflight.obj

/** Bounded software observations only. No native operation or I/O runs under this monitor. */
internal class ProbeSegmentProgress(targetsUs: List<Long>, private val frameRate: Int = 30) {
    data class Cut(val id: Int, val targetPtsUs: Long, val preparedAtMs: Long? = null,
        val armedAtMs: Long? = null, val syncRequestedAtMs: Long? = null, val syncReturnedAtMs: Long? = null,
        val acceptedAtMs: Long? = null, val acceptedPtsUs: Long? = null, val enqueuedAtMs: Long? = null,
        val writerReceivedAtMs: Long? = null)
    data class File(val index: Int, val openStartedAtMs: Long? = null, val openFinishedAtMs: Long? = null,
        val muxerPreparedAtMs: Long? = null, val closeStartedAtMs: Long? = null,
        val muxerClosedAtMs: Long? = null, val syncCloseStartedAtMs: Long? = null, val closedAtMs: Long? = null)
    data class Problem(val code: String, val stage: String, val cutId: Int? = null, val file: Int? = null)
    data class Overrun(val problem: Problem, val startedAtMs: Long, val returnedAtMs: Long)
    data class Snapshot(val atElapsedMs: Long, val sourceFrames: Int, val sourcePtsUs: Long?,
        val inputFrames: Int, val inputPtsUs: Long?, val inputAtMs: Long?,
        val outputFrames: Int, val outputPtsUs: Long?, val outputAtMs: Long?,
        val writerStage: String, val writerFile: Int?, val writerSinceMs: Long?, val writtenFrames: Int,
        val writtenPtsUs: Long?, val queueBytes: Long, val queueItems: Int,
        val cuts: List<Cut>, val files: List<File>, val overrun: Overrun?) {
        fun json() = obj("atElapsedMs" to atElapsedMs, "sourceFrames" to sourceFrames, "sourcePtsUs" to sourcePtsUs,
            "codecInputFrames" to inputFrames, "codecInputPtsUs" to inputPtsUs, "codecInputAtMs" to inputAtMs,
            "codecOutputFrames" to outputFrames, "codecOutputPtsUs" to outputPtsUs, "codecOutputAtMs" to outputAtMs,
            "writerStage" to writerStage, "writerFile" to writerFile, "writerSinceMs" to writerSinceMs,
            "writtenFrames" to writtenFrames, "writtenPtsUs" to writtenPtsUs,
            "queueBytesIncludingInFlight" to queueBytes, "queueItemsIncludingInFlight" to queueItems,
            "queueSampling" to "ADJACENT_SOFTWARE_SNAPSHOT_NOT_ATOMIC_WITH_CODEC",
            "completedStageOverrun" to overrun?.let { obj("reason" to it.problem.code,"stage" to it.problem.stage,
                "file" to it.problem.file,"startedAtMs" to it.startedAtMs,"returnedAtMs" to it.returnedAtMs) },
            "limits" to obj("nativeStageTimeoutMs" to NATIVE_TIMEOUT_MS, "queueTimeoutMs" to QUEUE_TIMEOUT_MS,
                "encoderStallMs" to ENCODER_STALL_MS, "keyframeGraceUs" to KEYFRAME_GRACE_US),
            "cuts" to cuts.map { c -> obj("id" to c.id, "targetPtsUs" to c.targetPtsUs,
                "preparedAtMs" to c.preparedAtMs, "armedAtMs" to c.armedAtMs,
                "syncRequestedAtMs" to c.syncRequestedAtMs, "syncReturnedAtMs" to c.syncReturnedAtMs,
                "acceptedAtMs" to c.acceptedAtMs, "acceptedPtsUs" to c.acceptedPtsUs,
                "enqueuedAtMs" to c.enqueuedAtMs, "writerReceivedAtMs" to c.writerReceivedAtMs) },
            "files" to files.map { f -> obj("index" to f.index, "openStartedAtMs" to f.openStartedAtMs,
                "openFinishedAtMs" to f.openFinishedAtMs, "muxerPreparedAtMs" to f.muxerPreparedAtMs,
                "closeStartedAtMs" to f.closeStartedAtMs, "muxerClosedAtMs" to f.muxerClosedAtMs,
                "syncCloseStartedAtMs" to f.syncCloseStartedAtMs, "closedAtMs" to f.closedAtMs) })
    }
    private val cuts = targetsUs.mapIndexed { i, t -> Cut(i + 1, t) }.toMutableList()
    private val files = (0..targetsUs.size).map { File(it) }.toMutableList()
    private var sourceFrames = 0; private var sourcePts: Long? = null
    private var inputFrames = 0; private var inputPts: Long? = null; private var inputAt: Long? = null
    private var firstInputAt: Long? = null
    private var outputFrames = 0; private var outputPts: Long? = null; private var outputAt: Long? = null
    private var writerStage = "WAIT_FORMAT"; private var writerFile: Int? = null; private var writerSince: Long? = null
    private var writtenFrames = 0; private var writtenPts: Long? = null
    private var overrun: Overrun? = null
    init { require(targetsUs.size == 3 && targetsUs.zipWithNext().all { (a,b) -> b>a } && frameRate>0) }
    @Synchronized fun source(pts: Long) { sourceFrames++; sourcePts=pts }
    @Synchronized fun input(pts: Long, at: Long) { inputFrames++; inputPts=pts; inputAt=at; if(firstInputAt==null)firstInputAt=at }
    @Synchronized fun output(pts: Long, at: Long) { outputFrames++; outputPts=pts; outputAt=at }
    @Synchronized fun openStarted(index: Int, at: Long) { files[index]=files[index].copy(openStartedAtMs=at) }
    @Synchronized fun openFinished(index: Int, at: Long) { files[index]=files[index].copy(openFinishedAtMs=at) }
    @Synchronized fun prepared(index: Int, at: Long) {
        files[index]=files[index].copy(muxerPreparedAtMs=at)
        if(index>0)cuts[index-1]=cuts[index-1].copy(preparedAtMs=at)
    }
    @Synchronized fun armed(id: Int, at: Long) { cuts[id-1]=cuts[id-1].copy(armedAtMs=at) }
    /** Called by the codec owner; preparation alone never schedules an immediate sync request. */
    @Synchronized fun syncDue(): Int? = cuts.firstOrNull {
        it.armedAtMs!=null && it.acceptedAtMs==null && it.syncRequestedAtMs==null &&
            (inputPts ?: -1) >= it.targetPtsUs - (1_000_000L/frameRate + 1)
    }?.id
    @Synchronized fun syncRequested(id: Int, at: Long) { cuts[id-1]=cuts[id-1].copy(syncRequestedAtMs=at) }
    @Synchronized fun syncReturned(id: Int, at: Long) { cuts[id-1]=cuts[id-1].copy(syncReturnedAtMs=at) }
    @Synchronized fun accepted(id: Int, pts: Long, at: Long) { cuts[id-1]=cuts[id-1].copy(acceptedAtMs=at,acceptedPtsUs=pts) }
    @Synchronized fun enqueued(id: Int, at: Long) { cuts[id-1]=cuts[id-1].copy(enqueuedAtMs=at) }
    @Synchronized fun received(id: Int, at: Long) { cuts[id-1]=cuts[id-1].copy(writerReceivedAtMs=at) }
    @Synchronized fun writer(stage: String, index: Int?, at: Long) {
        val since=writerSince; val code=nativeStageCode(writerStage)
        if(overrun==null && since!=null && code!=null && at-since>=NATIVE_TIMEOUT_MS)
            overrun=Overrun(Problem(code,writerStage,file=writerFile),since,at)
        writerStage=stage; writerFile=index; writerSince=at
    }
    @Synchronized fun written(pts: Long) { writtenFrames++; writtenPts=pts }
    @Synchronized fun closeStarted(index: Int, at: Long) { files[index]=files[index].copy(closeStartedAtMs=at) }
    @Synchronized fun muxerClosed(index: Int, at: Long) { files[index]=files[index].copy(muxerClosedAtMs=at) }
    @Synchronized fun syncCloseStarted(index: Int, at: Long) { files[index]=files[index].copy(syncCloseStartedAtMs=at) }
    @Synchronized fun closed(index: Int, at: Long) { files[index]=files[index].copy(closedAtMs=at) }
    @Synchronized fun endPtsUs(): Long = inputFrames * 1_000_000L / frameRate
    @Synchronized fun snapshot(now: Long, queueBytes: Long=0, queueItems: Int=0) = Snapshot(now,sourceFrames,sourcePts,
        inputFrames,inputPts,inputAt,outputFrames,outputPts,outputAt,writerStage,writerFile,writerSince,writtenFrames,writtenPts,
        queueBytes,queueItems,cuts.toList(),files.toList(),overrun)
    @Synchronized fun assess(now: Long, queueBytes: Long, queueItems: Int): Pair<Problem,Snapshot>? =
        problem(now)?.let { it to snapshot(now,queueBytes,queueItems) }

    @Synchronized fun problem(now: Long): Problem? {
        overrun?.let { return it.problem }
        // Native call latency has its own wall-clock budget; it does not imply a missing keyframe.
        val since=writerSince
        if(since!=null && now-since>=NATIVE_TIMEOUT_MS) {
            val code=nativeStageCode(writerStage)
            if(code!=null)return Problem(code,writerStage,file=writerFile)
        }
        files.firstOrNull { it.openStartedAtMs!=null &&
            (it.openFinishedAtMs ?: now)-it.openStartedAtMs>=NATIVE_TIMEOUT_MS }?.let { return Problem("USB_FILE_OPEN_TIMEOUT","FILE_OPEN",file=it.index) }
        cuts.firstOrNull { it.syncRequestedAtMs!=null &&
            (it.syncReturnedAtMs ?: now)-it.syncRequestedAtMs>=NATIVE_TIMEOUT_MS }?.let { return Problem("SYNC_REQUEST_TIMEOUT","SYNC_REQUEST",it.id) }
        cuts.firstOrNull { it.enqueuedAtMs!=null &&
            (it.writerReceivedAtMs ?: now)-it.enqueuedAtMs>=QUEUE_TIMEOUT_MS }?.let { return Problem("CUT_QUEUE_TIMEOUT","CUT_QUEUE",it.id) }
        cuts.firstOrNull { it.acceptedPtsUs!=null && it.acceptedPtsUs-it.targetPtsUs>KEYFRAME_GRACE_US }?.let {
            return Problem("CUT_KEYFRAME_LATE","KEYFRAME",it.id)
        }
        val inPts=inputPts
        val lastProgress=outputAt ?: firstInputAt
        if(inPts!=null && inPts>(outputPts ?: -1)+20 && lastProgress!=null && now-lastProgress>=ENCODER_STALL_MS)
            return Problem("ENCODER_OUTPUT_TIMEOUT","CODEC_OUTPUT")
        if(inPts!=null && inPts-(outputPts ?: 0)>KEYFRAME_GRACE_US)
            return Problem("ENCODER_OUTPUT_LAG","CODEC_OUTPUT")
        cuts.firstOrNull { it.acceptedAtMs==null && (outputPts ?: -1)>it.targetPtsUs+KEYFRAME_GRACE_US }?.let {
            return when {
                it.preparedAtMs==null -> Problem("NEXT_FILE_NOT_PREPARED","FILE_PREPARE",it.id)
                it.armedAtMs==null -> Problem("CUT_NOT_ARMED","ARM",it.id)
                it.syncRequestedAtMs==null -> Problem("SYNC_REQUEST_MISSING","SYNC_REQUEST",it.id)
                else -> Problem("CUT_KEYFRAME_MISSING","KEYFRAME",it.id)
            }
        }
        return null
    }
    private fun nativeStageCode(stage: String): String? = when(stage) {
        "PREPARE_FILE" -> "NEXT_FILE_PREPARE_TIMEOUT"
        "WRITE_SAMPLE" -> "USB_WRITE_TIMEOUT"
        "MUXER_FINALIZE" -> "MUXER_FINALIZE_TIMEOUT"
        "USB_SYNC_CLOSE" -> "USB_SYNC_CLOSE_TIMEOUT"
        else -> null
    }
    companion object {
        const val NATIVE_TIMEOUT_MS = 2_000L
        const val QUEUE_TIMEOUT_MS = 2_000L
        const val ENCODER_STALL_MS = 2_000L
        const val KEYFRAME_GRACE_US = 1_500_000L
    }
}
