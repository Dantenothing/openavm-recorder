package com.dante.zeekrcapabilitylab.preflight.continuous

/** Frame id must come from decoded image pixels, not from the extractor's sample counter. */
internal data class DecodedProbeFrame(val sourceFrameId: Long?, val filePtsUs: Long)
internal enum class DecodedPtsContract(val maximumToleranceUs:Long) {
    STRICT_TICK_ROUNDING(20), ANDROID_MP4_SUBFRAME_ADJUSTMENT(120)
}
internal data class ProbePtsError(val frame:Long,val file:Int,val expectedFilePtsUs:Long,val decodedFilePtsUs:Long,val errorUs:Long)
internal enum class SyntheticSequenceStatus { PASS, FAIL, INCOMPLETE }
internal enum class SyntheticSequenceIssue {
    FRAME_ID_UNREADABLE, FRAME_ID_MISMATCH, PTS_MISMATCH, INPUT_TIMESTAMP_GAP,
    FIRST_SAMPLE_NOT_KEY_FRAME, EMPTY_SEGMENT, EXTRA_DECODED_FRAME,
    DECODER_EOS_MISSING, INPUT_TAIL_NOT_DECODED, SEGMENT_STILL_OPEN, FILE_MANIFEST_INCOMPLETE,
    EXTRA_SEGMENT, RUN_NOT_FINISHED,
}

internal data class SyntheticSequenceResult(
    val status: SyntheticSequenceStatus,
    val completedSegments: Int,
    val decodedFrames: Long,
    val maximumInputGapUs: Long,
    val maximumBoundaryGapUs: Long,
    val issues: Set<SyntheticSequenceIssue>,
    val maximumPtsErrorUs:Long=0,
    val beyondStrictRoundingFrames:Int=0,
    val ptsErrors:List<ProbePtsError> = emptyList(),
) {
    // Does NOT certify GPU geometry, real-time freshness, cleanup, USB routing or camera continuity.
    val scope: String get() = "SYNTHETIC_DECODED_SEQUENCE_ONLY"
}

/**
 * Bounded verifier for the forthcoming synthetic probe's independently decoded files.
 * Input IDs are 0..N-1; ledger entries are the exact PTS submitted for those IDs.
 * A caller must finish collecting that ledger and snapshot the complete closed-file manifest
 * before decoding. expectedSegments is that manifest's count, not a minimum pass threshold.
 * finishRun seals evidence only after the caller has inspected the whole manifest.
 * No native calls or media buffers; freshness and media provenance require separate checks.
 */
internal class SyntheticSegmentVerifier(
    inputPtsUs: LongArray,
    private val expectedSegments: Int,
    maximumInputGapUs: Long,
    private val ptsToleranceUs: Long = 0,
    private val ptsContract:DecodedPtsContract=DecodedPtsContract.STRICT_TICK_ROUNDING,
) {
    private val input: LongArray
    private val issues = mutableSetOf<SyntheticSequenceIssue>()
    private var failed = false
    private var open = false
    private var finished = false
    private var basePtsUs = 0L
    private var segmentFrames = 0L
    private var decodedFrames = 0L
    private var completedSegments = 0
    private var maxInputGap = 0L
    private var maxBoundaryGap = 0L
    private var lastFilePts:Long?=null
    private var maxPtsError=0L
    private var adjustedFrames=0
    private val ptsErrors=ArrayList<ProbePtsError>()

    init {
        require(inputPtsUs.size in 1..MAX_INPUT_FRAMES)
        require(expectedSegments in 1..MAX_SEGMENTS && maximumInputGapUs > 0)
        require(ptsToleranceUs in 0..ptsContract.maximumToleranceUs)
        input = inputPtsUs.copyOf()
        for (index in input.indices) {
            require(input[index] >= 0 && (index == 0 || input[index] > input[index - 1]))
            if (index > 0) maxInputGap = maxOf(maxInputGap, input[index] - input[index - 1])
        }
        if (maxInputGap > maximumInputGapUs) fail(SyntheticSequenceIssue.INPUT_TIMESTAMP_GAP)
    }

    fun beginSegment(runBasePtsUs: Long, firstSampleIsKeyFrame: Boolean) {
        check(!finished) { "RUN_ALREADY_FINISHED" }
        check(!open) { "PREVIOUS_SEGMENT_NOT_FINISHED" }
        check(completedSegments < MAX_SEGMENTS) { "SEGMENT_EVIDENCE_BUDGET_EXCEEDED" }
        open = true
        segmentFrames = 0
        lastFilePts=null
        basePtsUs = runBasePtsUs
        if (completedSegments >= expectedSegments) fail(SyntheticSequenceIssue.EXTRA_SEGMENT)
        if (runBasePtsUs < 0) fail(SyntheticSequenceIssue.PTS_MISMATCH)
        if (!firstSampleIsKeyFrame) fail(SyntheticSequenceIssue.FIRST_SAMPLE_NOT_KEY_FRAME)
    }

    fun decoded(frame: DecodedProbeFrame) {
        check(!finished) { "RUN_ALREADY_FINISHED" }
        check(open) { "NO_SEGMENT_TO_VERIFY" }
        if (frame.sourceFrameId == null) fail(SyntheticSequenceIssue.FRAME_ID_UNREADABLE)
        else if (frame.sourceFrameId != decodedFrames) fail(SyntheticSequenceIssue.FRAME_ID_MISMATCH)
        if (frame.filePtsUs < 0 || (segmentFrames == 0L && frame.filePtsUs != 0L) ||
            lastFilePts?.let {frame.filePtsUs<=it}==true) {
            fail(SyntheticSequenceIssue.PTS_MISMATCH)
        }
        lastFilePts=frame.filePtsUs
        if (decodedFrames >= input.size) {
            fail(SyntheticSequenceIssue.EXTRA_DECODED_FRAME)
        } else {
            val expectedPts = input[decodedFrames.toInt()]
            if(basePtsUs>=0 && basePtsUs<=expectedPts && frame.filePtsUs>=0) {
                val expectedFile=expectedPts-basePtsUs
                val error=frame.filePtsUs-expectedFile
                maxPtsError=maxOf(maxPtsError,kotlin.math.abs(error))
                if(kotlin.math.abs(error)>20) {
                    adjustedFrames++
                    if(ptsErrors.size<24)ptsErrors+=ProbePtsError(decodedFrames,completedSegments,expectedFile,frame.filePtsUs,error)
                }
            }
            // Subtract from the known nonnegative ledger value; never add unchecked native PTS.
            if (basePtsUs < 0 || basePtsUs > expectedPts || frame.filePtsUs < 0 ||
                kotlin.math.abs(frame.filePtsUs - (expectedPts - basePtsUs)) > ptsToleranceUs) {
                fail(SyntheticSequenceIssue.PTS_MISMATCH)
            }
            if (segmentFrames == 0L && decodedFrames > 0) {
                maxBoundaryGap = maxOf(maxBoundaryGap, expectedPts - input[decodedFrames.toInt() - 1])
            }
        }
        segmentFrames++
        decodedFrames++
    }

    fun endSegment(decoderReachedEos: Boolean) {
        check(!finished) { "RUN_ALREADY_FINISHED" }
        check(open) { "NO_SEGMENT_TO_FINISH" }
        if (segmentFrames == 0L) fail(SyntheticSequenceIssue.EMPTY_SEGMENT)
        if (!decoderReachedEos) issues += SyntheticSequenceIssue.DECODER_EOS_MISSING
        open = false
        completedSegments++
    }

    fun finishRun() {
        check(!finished) { "RUN_ALREADY_FINISHED" }
        finished = true
        // A fully decoded manifest with a missing submitted tail is a real loss, not missing evidence.
        if (!open && completedSegments == expectedSegments && decodedFrames < input.size &&
            SyntheticSequenceIssue.DECODER_EOS_MISSING !in issues) {
            fail(SyntheticSequenceIssue.INPUT_TAIL_NOT_DECODED)
        }
    }

    fun result(): SyntheticSequenceResult {
        val observed = issues.toMutableSet()
        if (!finished) observed += SyntheticSequenceIssue.RUN_NOT_FINISHED
        if (open) observed += SyntheticSequenceIssue.SEGMENT_STILL_OPEN
        if (decodedFrames < input.size) observed += SyntheticSequenceIssue.INPUT_TAIL_NOT_DECODED
        if (completedSegments < expectedSegments) observed += SyntheticSequenceIssue.FILE_MANIFEST_INCOMPLETE
        val status = when {
            failed -> SyntheticSequenceStatus.FAIL
            observed.isNotEmpty() -> SyntheticSequenceStatus.INCOMPLETE
            else -> SyntheticSequenceStatus.PASS
        }
        return SyntheticSequenceResult(status, completedSegments, decodedFrames, maxInputGap, maxBoundaryGap, observed,
            maxPtsError,adjustedFrames,ptsErrors.toList())
    }

    private fun fail(issue: SyntheticSequenceIssue) { issues += issue; failed = true }

    companion object {
        const val MAX_INPUT_FRAMES = 36_000
        const val MAX_SEGMENTS = 128
    }
}
