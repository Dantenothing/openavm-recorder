package com.dante.zeekrcapabilitylab.service.recorder

/** A constant-size copy of existing capture callbacks. Reading it never probes the camera. */
data class CaptureEvidence(val sessionId: String? = null, val segment: Int = 0,
    val frames: SegmentFrameStats = SegmentFrameStats(), val lastReceivedElapsedMs: Long? = null,
    val preview: PreviewCaptureStats = PreviewCaptureStats(), val encoder: EncoderEvidence? = null,
    val channelId: String? = null) {
    /** Concurrent cameras share session/segment numbers. Unknown ownership is not a match. */
    fun matches(state: RecorderState): Boolean = sessionId != null && sessionId == state.recordingSessionId &&
        segment == state.segmentNumber && channelId != null && channelId in RecordingChannelId.all && channelId == state.channelId
}

object RecorderCaptureEvidence {
    @Volatile var latest = CaptureEvidence()
        private set
    fun update(value: CaptureEvidence) { latest = value }
}
