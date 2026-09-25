package com.dante.zeekrcapabilitylab.service.recorder

/** Declaration evidence only. A positive result is not a successful encode or camera session. */
data class ContinuousCodecCandidate(
    val name: String,
    val surfaceInput: Boolean? = null,
    val formatSupported: Boolean? = null,
    val sizeAndRateSupported: Boolean? = null,
    val baselineAdvertised: Boolean? = null,
    val maximumWidth: Int? = null,
    val maximumHeight: Int? = null,
    val widthAlignment: Int? = null,
    val heightAlignment: Int? = null,
    val queryError: String? = null,
) {
    val accepted: Boolean get() = queryError == null && surfaceInput == true &&
        formatSupported == true && sizeAndRateSupported == true
}

data class ContinuousEncoderSelection(
    val reason: String,
    val codecName: String? = null,
    val cameraSizeDeclared: Boolean? = null,
    val candidates: List<ContinuousCodecCandidate> = emptyList(),
    val omittedCandidates: Int = 0,
    val queryError: String? = null,
)

object ContinuousEncoderSelectionPolicy {
    /** Same strict conditions as Beta9; diagnostics never bypass a rejected declaration. */
    fun choose(cameraSizeDeclared: Boolean, candidates: List<ContinuousCodecCandidate>): ContinuousEncoderSelection {
        val selected = candidates.firstOrNull { it.accepted }?.takeIf { cameraSizeDeclared }
        val reason = when {
            !cameraSizeDeclared -> "CAMERA_CODEC_SIZE_NOT_DECLARED"
            selected != null -> "SELECTED"
            candidates.isEmpty() -> "NO_HARDWARE_AVC_ENCODER"
            candidates.all { it.queryError != null } -> "CODEC_QUERY_FAILED"
            candidates.none { it.surfaceInput == true } -> "CODEC_SURFACE_INPUT_NOT_DECLARED"
            candidates.none { it.surfaceInput == true && it.formatSupported == true } -> "CODEC_FORMAT_NOT_DECLARED"
            else -> "CODEC_SIZE_OR_RATE_NOT_DECLARED"
        }
        return ContinuousEncoderSelection(reason, selected?.name, cameraSizeDeclared,
            candidates.take(8), (candidates.size - 8).coerceAtLeast(0))
    }
}
