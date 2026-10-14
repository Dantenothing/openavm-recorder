package com.dante.zeekrcapabilitylab.service.recorder

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Catch-site labels, not guesses about the device's underlying cause. */
internal enum class ContinuousFailureStage {
    CODEC_DRAIN, CODEC_CLEANUP, FILE_WRITER, FILE_FINALIZE, FILE_CLEANUP,
    INPUT, INPUT_CLEANUP, ENCODER_RENDER, ENCODER_RENDER_CLEANUP, DISPLAY_RENDER, DISPLAY_RENDER_CLEANUP,
    SOURCE_WATCHDOG, CUT_WATCHDOG, FINALIZER_WATCHDOG, OUTPUT_OPEN, OUTPUT_PREPARE, OUTPUT_PUBLISH,
    CUT_REQUEST, SESSION_SETUP, SESSION_CALLBACK, LEGACY_CONTINUOUS,
}

internal data class ContinuousFailureSample(
    val sampledAtElapsedMs: Long,
    val queueBytes: Long, val queueItems: Int, val encodedFrames: Long,
    val encodedBytes: Long, val completedFiles: Long, val sourceAgeMs: Long?,
)

/** Values read from typed exception properties only; never parsed from human-readable messages. */
internal data class ContinuousFailureNumbers(
    val codecErrorCode: Int? = null, val errno: Int? = null,
    val codecRecoverable: Boolean? = null, val codecTransient: Boolean? = null,
    val sourceType: String? = null, val causeDepth: Int? = null,
)

internal data class ContinuousFailureEvidence(
    val sessionId: String, val stage: ContinuousFailureStage, val errorCode: String,
    val exceptionType: String, val causeType: String,
    val atEpochMs: Long, val atElapsedMs: Long, val numbers: ContinuousFailureNumbers,
    val sample: ContinuousFailureSample?, val captureOrder: Long,
    val formatFacts: Map<String, String> = emptyMap(),
    val cut: DirectCutProgress.Snapshot? = null,
    val timestamp: EncodedTimestampException? = null,
) {
    fun facts(): Map<String, String> = linkedMapOf(
        "failureSchema" to "1", "failureCategory" to "CONTINUOUS_PIPELINE_FAILURE",
        "recordingSessionId" to sessionId, "failureStage" to stage.name, "errorCode" to errorCode,
        "exceptionType" to exceptionType, "causeType" to causeType,
        "faultEpochMs" to atEpochMs.toString(), "faultElapsedMs" to atElapsedMs.toString(),
        "faultCaptureOrder" to captureOrder.toString(),
        "codecErrorCode" to known(numbers.codecErrorCode), "errno" to known(numbers.errno),
        "codecRecoverable" to known(numbers.codecRecoverable), "codecTransient" to known(numbers.codecTransient),
        "numericSourceType" to safeType(numbers.sourceType), "numericCauseDepth" to known(numbers.causeDepth),
        "snapshotKind" to if (sample == null) "UNAVAILABLE" else "CACHED_COUNTERS_BEFORE_FAULT",
        "snapshotSampledAtElapsedMs" to known(sample?.sampledAtElapsedMs),
        "snapshotAgeMs" to known(sample?.let { atElapsedMs-it.sampledAtElapsedMs }),
        "faultQueueBytes" to known(sample?.queueBytes), "faultQueueItems" to known(sample?.queueItems),
        "faultEncodedFrames" to known(sample?.encodedFrames), "faultEncodedBytes" to known(sample?.encodedBytes),
        "faultCompletedFiles" to known(sample?.completedFiles), "faultSourceAgeAtSampleMs" to known(sample?.sourceAgeMs),
    ) + ContinuousFormatFacts.sanitize(formatFacts) + cut?.facts().orEmpty() + timestamp?.facts().orEmpty()

    companion object {
        private fun known(value: Any?): String = value?.toString() ?: "UNKNOWN"
        internal fun safeType(value: String?): String = value?.takeIf {
            it.length in 1..160 && it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*"))
        } ?: "UNKNOWN"
        val FACT_KEYS = setOf("failureSchema", "failureCategory", "failureStage", "exceptionType", "causeType",
            "faultEpochMs", "faultElapsedMs", "faultCaptureOrder", "codecErrorCode", "errno", "codecRecoverable", "codecTransient",
            "numericSourceType", "numericCauseDepth", "snapshotKind", "snapshotSampledAtElapsedMs", "snapshotAgeMs",
            "faultQueueBytes", "faultQueueItems", "faultEncodedFrames", "faultEncodedBytes", "faultCompletedFiles",
            "faultSourceAgeAtSampleMs") + ContinuousFormatFacts.keys + DirectCutProgress.FACT_KEYS + EncodedTimestampException.FACT_KEYS
    }
}

/** One immutable first failure per run. Late owners keep their old instance, never the next run's. */
internal class ContinuousFailureCapture(
    private val sessionId: String,
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val numeric: (Throwable) -> ContinuousFailureNumbers = { ContinuousFailureNumbers() },
    private val onCaptured: (ContinuousFailureEvidence) -> Unit = {},
    private val onFormatObserved: (Map<String, String>) -> Unit = {},
) {
    private val first = AtomicReference<ContinuousFailureEvidence?>()
    private val sequence = AtomicLong()
    private val latestFormat = AtomicReference<Map<String, String>>(emptyMap())
    val value get() = first.get()

    /** Called only for codec-format notifications / decisions, never per frame or from an export. */
    fun observeFormat(observation: ContinuousFormatObservation, cutPending: Boolean? = null) {
        val signature = ContinuousFormatFacts.observation(observation, cutPending, 0) - "formatObservedElapsedMs"
        if ("formatDecision" !in signature) return
        while (true) {
            val previous = latestFormat.get()
            if (previous - "formatObservedElapsedMs" == signature) return
            val next = signature + ("formatObservedElapsedMs" to elapsed().toString())
            if (latestFormat.compareAndSet(previous, next)) {
                runCatching { onFormatObserved(next + ("recordingSessionId" to sessionId)) }
                return
            }
        }
    }

    private fun typedFormat(error: Throwable?): ContinuousTrackFormatException? {
        var current = error
        repeat(4) {
            if (current is ContinuousTrackFormatException) return current as ContinuousTrackFormatException
            current = runCatching { current?.cause }.getOrNull()
        }
        return null
    }

    private fun formatAtFailure(error: ContinuousTrackFormatException?): Map<String, String> {
        val latest = latestFormat.get()
        if (error == null) return latest
        val decision = when (error.errorCode) {
            ContinuousTrackFormatSpec.INCOMPATIBLE -> "INCOMPATIBLE"
            ContinuousTrackFormatSpec.INCOMPLETE -> "INCOMPLETE"
            else -> return emptyMap()
        }
        val fields = ContinuousFormatFacts.differences(error.fields)
        val matching = latest["formatDecision"] == decision &&
            latest.filterKeys { it.startsWith("formatField_") } == fields.filterKeys { it.startsWith("formatField_") }
        // Counts / cut state only belong to this failure when its exact observed field set matches.
        return (if (matching) latest else emptyMap()) + fields + ("formatDecision" to decision)
    }

    /** Only clocks, typed exception fields, and an ALREADY cached immutable sample. No I/O or native probes. */
    fun capture(error: Throwable?, stage: ContinuousFailureStage, sample: ContinuousFailureSample? = null,
                signal: String? = null, cut: DirectCutProgress.Snapshot? = null): ContinuousFailureEvidence {
        first.get()?.let { return it }
        val order = sequence.incrementAndGet()
        val at = elapsed(); val epoch = wall()
        val formatError = typedFormat(error)
        val timestampError = EncodedTimestampException.from(error)
        val format = formatAtFailure(formatError)
        val code = formatError?.errorCode?.takeIf { it in setOf(ContinuousTrackFormatSpec.INCOMPLETE, ContinuousTrackFormatSpec.INCOMPATIBLE) }
            ?: timestampError?.message ?: knownCode(signal) ?: knownCode(runCatching { error?.message }.getOrNull())
            ?: if (error == null) "UNCLASSIFIED_SIGNAL" else "UNCLASSIFIED_THROWABLE"
        val candidate = ContinuousFailureEvidence(sessionId, stage, code,
            ContinuousFailureEvidence.safeType(error?.javaClass?.name),
            ContinuousFailureEvidence.safeType(runCatching { error?.cause?.javaClass?.name }.getOrNull()), epoch, at,
            error?.let { runCatching { numeric(it) }.getOrNull() } ?: ContinuousFailureNumbers(),
            sample?.takeIf { it.sampledAtElapsedMs in 0..at }, order, format, cut, timestampError)
        while (true) {
            val existing = first.get()
            if (existing != null && existing.captureOrder < order) return existing
            // An earlier catch can finish diagnostic extraction later. It still owns first cause.
            if (first.compareAndSet(existing, candidate)) {
                // Passive journal submission stays independent of a later control callback accepting this owner.
                runCatching { onCaptured(candidate) }
                return candidate
            }
        }
    }

    companion object {
        /** Explicit application constants; an arbitrary uppercase message is not a safe diagnostic code. */
        internal fun knownCode(text: String?): String? = text?.takeWhile { it.isLetterOrDigit() || it == '_' }
            ?.takeIf { it in CODES }
        private val CODES = setOf(
            "PRODUCT_WRITER_QUEUE_FULL", "PRODUCT_ENCODER_BACKPRESSURE", "ENCODED_TIMESTAMP_NOT_INCREASING",
            "PRODUCT_CODEC_EOS_TIMEOUT", "PRODUCT_CODEC_FORMAT_CHANGED_TWICE", "PRODUCT_CODEC_START_TIMEOUT",
            "PRODUCT_CONTINUOUS_CONFIG_UNSUPPORTED", "PRODUCT_CONTINUOUS_FAILURE", "PRODUCT_ENCODED_TRACK_MISMATCH",
            "PRODUCT_NEXT_ALREADY_OWNED", "PRODUCT_NEXT_NOT_CONFIGURED", "PRODUCT_NEXT_NOT_READY",
            "PRODUCT_NEXT_OPEN_FAILED", "PRODUCT_NEXT_PREPARE_FAILED", "PRODUCT_FILE_FORMAT_MISSING",
            "PRODUCT_FILE_NOT_KEY_START", "PRODUCT_FILE_SEED_MISSING", "PRODUCT_FILE_RENAME_FAILED",
            "PRODUCT_FINALIZER_RELEASE_UNCONFIRMED", "PRODUCT_MEDIA_RELEASE_UNCONFIRMED",
            "PRODUCT_OUTPUT_RELEASE_UNCONFIRMED", "PRODUCT_WATCHDOG_RELEASE_UNCONFIRMED",
            "PRODUCT_WRITER_STOP_UNCONFIRMED", "PRODUCT_GL_RASTER_LIMIT", "PRODUCT_GL_READERS_UNCONFIRMED",
            "PRODUCT_GL_RELEASE_UNCONFIRMED", "PRODUCT_INPUT_START_TIMEOUT", "PRODUCT_INPUT_THREAD_UNCONFIRMED",
            "PRODUCT_INPUT_THREAD_STALLED", "PRODUCT_SOURCE_WAIT_TIMEOUT", "PRODUCT_READER_START_TIMEOUT",
            "PRODUCT_INTERNAL_STORAGE_BLOCKED", "PRODUCT_PUBLICATION_BACKLOG", "PRODUCT_PUBLICATION_FAILED",
            "PRODUCT_METADATA_OWNER_LIMIT", "PRODUCT_TRACK_UNREADABLE", "PRODUCT_TRACK_SIZE_MISMATCH",
            "PRODUCT_USB_OWNERSHIP_PENDING", "PRODUCT_CUT_ALREADY_REQUESTED", "PRODUCT_CUT_FAILED",
            "PRODUCT_CUT_AUTHORITY_ENDED", "PRODUCT_CUT_FILE_REUSED", "PRODUCT_KEYFRAME_CUT_TIMEOUT",
            "PRODUCT_WRITER_CUT_TIMEOUT", "PRODUCT_SAMPLE_TIME_REGRESSED", "PRODUCT_SOURCE_TIMESTAMP_REGRESSED",
            "CONTINUOUS_START_FAILED", "CONTINUOUS_OUTPUT_OPEN_FAILED", "CONTINUOUS_CUT_TIMEOUT",
            "CONTINUOUS_OUTPUT_PREPARATION_TIMEOUT", "CONTINUOUS_MUXER_PREPARATION_TIMEOUT",
            "CONTINUOUS_KEYFRAME_CUT_TIMEOUT", "CONTINUOUS_WRITER_CUT_TIMEOUT",
            "CONTINUOUS_FORMAT_BOUNDARY_TIMEOUT",
            "CONTINUOUS_CAPTURE_SESSION_MISSING", "CONTINUOUS_ENCODER_FAILED",
            "CONTINUOUS_TAIL_OUTPUT_MISSING",
            "CONTINUOUS_OUTPUT_STILL_OWNED", "ENCODER_FORMAT_CHANGED_MID_RUN", "ENCODER_QUEUE_FULL",
            "ENCODER_TRACK_FORMAT_INCOMPLETE", "ENCODER_TRACK_FORMAT_INCOMPATIBLE",
            "WRITER_QUEUE_REJECTED", "ENCODER_EOS_TIMEOUT", "ENCODER_WRITER_STOP_TIMEOUT",
            "ENCODER_WORKER_EXIT_UNCONFIRMED", "ENCODER_WORKER_REJECTED", "ENCODER_WORKER_TIMEOUT",
            "SEGMENT_MUST_START_WITH_KEYFRAME", "CODEC_CLOCK_REGRESSED", "INVALID_CODEC_FILE_BOUNDARY",
            "FINALIZER_ADMISSION_CLOSED", "FINALIZER_BUSY", "FINALIZER_FILE_REUSED", "FINALIZER_MUXER_TIMEOUT",
            "FINALIZER_NATIVE_RELEASE_UNCONFIRMED", "FINALIZER_PREVIOUS_TIMEOUT", "FINALIZER_STAGE_REGRESSED", "FINALIZER_SYNC_TIMEOUT",
            "SHARED_EGL_INITIALIZE_FAILED", "SHARED_ES3_CONTEXT_FAILED", "SHARED_ES3_RECORDABLE_CONFIG_UNAVAILABLE",
            "SHARED_FBO_INCOMPLETE", "SHARED_FENCE_CREATE_FAILED", "SHARED_FENCE_MISSING", "SHARED_FENCE_TIMEOUT",
            "SHARED_FENCE_WAIT_FAILED", "SHARED_GL_OPERATION_FAILED", "SHARED_GL_RASTER_LIMIT", "SHARED_MAKE_CURRENT_FAILED",
            "SHARED_PBUFFER_CURRENT_FAILED", "SHARED_PBUFFER_FAILED", "SHARED_PRESENTATION_FAILED", "SHARED_PROGRAM_LINK_FAILED",
            "SHARED_SHADER_COMPILE_FAILED", "SHARED_SWAP_FAILED", "SHARED_WINDOW_CLOSE_FAILED", "SHARED_WINDOW_FAILED",
        )
    }
}
