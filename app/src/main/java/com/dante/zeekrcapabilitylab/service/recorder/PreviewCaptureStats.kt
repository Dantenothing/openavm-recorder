package com.dante.zeekrcapabilitylab.service.recorder

/** Request/result evidence only: none of these counters proves delivery or encoding of an image. */
data class PreviewCaptureStats(
    val completedWithPreviewTarget: Long = 0,
    val completedWithoutPreviewTarget: Long = 0,
    val lastCompletedHadPreviewTarget: Boolean? = null,
    val lastPreviewResultElapsedMs: Long? = null,
    val lastPreviewSensorTimestampNs: Long? = null,
    val previewBuffersLost: Long = 0,
    val encoderBuffersLost: Long = 0,
    val otherBuffersLost: Long = 0,
    val lastBufferLostElapsedMs: Long? = null,
    val captureFailures: Long = 0,
    val lastFailureReason: Int? = null,
)

enum class CaptureOutputRole { PREVIEW, ENCODER, OTHER }

/** Camera-thread confined, constant-size telemetry. Stale segment callbacks cannot change it. */
class PreviewCaptureTracker {
    private var generation = -1L
    var stats = PreviewCaptureStats(); private set

    fun reset(generation: Long) {
        this.generation = generation
        stats = PreviewCaptureStats()
    }

    fun completed(generation: Long, hasPreviewTarget: Boolean, sensorTimestampNs: Long?, nowMs: Long) {
        if (generation != this.generation) return
        stats = if (hasPreviewTarget) stats.copy(
            completedWithPreviewTarget = stats.completedWithPreviewTarget + 1,
            lastCompletedHadPreviewTarget = true,
            lastPreviewResultElapsedMs = nowMs,
            lastPreviewSensorTimestampNs = sensorTimestampNs,
        ) else stats.copy(
            completedWithoutPreviewTarget = stats.completedWithoutPreviewTarget + 1,
            lastCompletedHadPreviewTarget = false,
        )
    }

    fun bufferLost(generation: Long, target: CaptureOutputRole, nowMs: Long) {
        if (generation != this.generation) return
        stats = stats.copy(
            previewBuffersLost = stats.previewBuffersLost + if (target == CaptureOutputRole.PREVIEW) 1 else 0,
            encoderBuffersLost = stats.encoderBuffersLost + if (target == CaptureOutputRole.ENCODER) 1 else 0,
            otherBuffersLost = stats.otherBuffersLost + if (target == CaptureOutputRole.OTHER) 1 else 0,
            lastBufferLostElapsedMs = nowMs,
        )
    }

    fun failed(generation: Long, reason: Int) {
        if (generation != this.generation) return
        stats = stats.copy(captureFailures = stats.captureFailures + 1, lastFailureReason = reason)
    }
}
