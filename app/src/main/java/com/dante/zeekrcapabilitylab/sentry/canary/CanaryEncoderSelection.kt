package com.dante.zeekrcapabilitylab.sentry.canary

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class CanaryEncoderCandidate(
    val name: String,
    val hardware: Boolean,
    val surfaceInput: Boolean,
    val sizeSupported: Boolean? = null,
    val sizeAndRateSupported: Boolean? = null,
    val formatSupported: Boolean? = null,
    val widthRange: String? = null,
    val heightRange: String? = null,
    val queryError: String? = null,
)

@Serializable
data class CanaryEncoderAttempt(val name: String, val mode: String, val state: String, val error: String? = null)

@Serializable
data class CanaryStartupDiagnostics(
    val stage: String = "MEMORY_CHECK",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val cameraSurfaceDeclared: Boolean = false,
    val systemAvailableBytes: Long? = null,
    val systemTotalBytes: Long? = null,
    val systemLowMemory: Boolean? = null,
    val appHeapMaxBytes: Long = 0,
    val appHeapHeadroomBytes: Long = 0,
    val candidates: List<CanaryEncoderCandidate> = emptyList(),
    val omittedCandidates: Int = 0,
    val attempts: List<CanaryEncoderAttempt> = emptyList(),
)

class CanaryEncoderOpenFailure(val stage: String, val detail: String, val cleanupUnconfirmed: Boolean = false) :
    Exception("${stage}_$detail")

@Serializable
data class CanaryStartupReport(
    val schemaVersion: Int = 1,
    val format: String = "OPENAVM_SENTRY_STARTUP_REPORT",
    val capturedAtEpochMs: Long,
    val current: CombinedCanaryReport,
)

object CanaryStartupReportText {
    fun prepare(current: CombinedCanaryReport, capturedAtEpochMs: Long): CanaryPreparedTextReport {
        val json = CanaryEvidenceJson.copyFormat.encodeToString(CanaryStartupReport(capturedAtEpochMs = capturedAtEpochMs, current = current))
        check(json.toByteArray(Charsets.UTF_8).size <= CanaryTextReportWriter.MAX_COPY_BYTES) { "STARTUP_REPORT_TOO_LARGE" }
        return CanaryPreparedTextReport(json, listOf(json))
    }
}

object CanaryEncoderSelection {
    const val MAX_CANDIDATES = 16
    const val MAX_ATTEMPTS = 4

    fun <T> open(candidates: List<CanaryEncoderCandidate>, sourceDeclared: Boolean,
                 isCancelled: () -> Boolean = { false }, observe: (CanaryEncoderAttempt) -> Unit = {},
                 create: (CanaryEncoderCandidate) -> T): T {
        check(sourceDeclared) { "ENCODER_SURFACE_SIZE_UNDECLARED" }
        val eligible = candidates.take(MAX_CANDIDATES).filter { it.hardware && it.surfaceInput }.distinctBy { it.name }
            .sortedWith(compareByDescending<CanaryEncoderCandidate> { it.formatSupported == true }
                .thenByDescending { it.sizeAndRateSupported == true }.thenByDescending { it.sizeSupported == true })
        check(eligible.isNotEmpty()) { "NO_HARDWARE_AVC_SURFACE" }
        for (candidate in eligible.take(MAX_ATTEMPTS)) {
            check(!isCancelled()) { "ENCODER_SELECTION_CANCELLED" }
            val mode = if (candidate.formatSupported == true) "DECLARED_FORMAT" else "EXACT_SOURCE_PROBE"
            observe(CanaryEncoderAttempt(candidate.name, mode, "OPENING"))
            try {
                val opened = create(candidate)
                observe(CanaryEncoderAttempt(candidate.name, mode, "STARTED"))
                return opened
            } catch (error: CanaryEncoderOpenFailure) {
                observe(CanaryEncoderAttempt(candidate.name, mode, "FAILED", error.message?.take(240)))
                // The backend must confirm release before the next candidate can acquire hardware.
                if (error.cleanupUnconfirmed) throw error
            }
        }
        error("ENCODER_CONFIGURE_FAILED")
    }
}
