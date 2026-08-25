package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole

data class SurroundPreviewBackgroundDecision(
    val invalidateSurface: Boolean,
    val disableRecorderPreview: Boolean,
    val stopIdlePreview: Boolean,
    val nextGeneration: Int,
)

/** Pure policy seam for the alpha7 SURROUND-only fresh-Surface experiment. */
object SurroundPreviewLifecyclePolicy {
    fun onAppBackground(
        sourceRole: RecordingSourceRole,
        recordingActive: Boolean,
        currentGeneration: Int,
    ): SurroundPreviewBackgroundDecision = if (sourceRole == RecordingSourceRole.SURROUND) {
        SurroundPreviewBackgroundDecision(
            invalidateSurface = true,
            disableRecorderPreview = recordingActive,
            stopIdlePreview = !recordingActive,
            nextGeneration = currentGeneration + 1,
        )
    } else {
        SurroundPreviewBackgroundDecision(
            invalidateSurface = false,
            disableRecorderPreview = false,
            stopIdlePreview = false,
            nextGeneration = currentGeneration,
        )
    }
}
