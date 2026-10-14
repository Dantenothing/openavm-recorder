package com.dante.zeekrcapabilitylab.service.recorder

/** A constant-size copy of existing capture callbacks. Reading it never probes the camera. */
data class CaptureEvidence(val sessionId: String? = null, val segment: Int = 0,
    val frames: SegmentFrameStats = SegmentFrameStats(), val lastReceivedElapsedMs: Long? = null,
    val preview: PreviewCaptureStats = PreviewCaptureStats(), val encoder: EncoderEvidence? = null,
    val channelId: String? = null, val cameraGeneration: Long = 0) {
    /** Concurrent cameras share session/segment numbers. Unknown ownership is not a match. */
    fun matches(state: RecorderState): Boolean = sessionId != null && sessionId == state.recordingSessionId &&
        segment == state.segmentNumber && channelId != null && channelId in RecordingChannelId.all && channelId == state.channelId
}

/** A start acknowledgement is not video. Old sessions/channels/camera opens cannot confirm a new start. */
internal object RecordingVideoReadiness {
    fun confirmed(state: RecorderState, capture: CaptureEvidence): Boolean {
        if (state.status != RecorderStatus.RECORDING || !capture.matches(state) ||
            state.cameraGeneration <= 0 || capture.cameraGeneration != state.cameraGeneration) return false
        return when (state.recordingBackend) {
            "SHARED_INPUT_CONTINUOUS_CODEC", "CONTINUOUS_CODEC" ->
                (capture.encoder?.frames ?: 0L) > 0 && capture.encoder?.lastOutputElapsedMs != null
            // MediaRecorder exposes no per-packet callback. Require an observed camera result;
            // file validity is verified separately during finalization.
            else -> capture.frames.count > 0 && capture.lastReceivedElapsedMs != null
        }
    }
}

object RecorderCaptureEvidence {
    @Volatile var latest = CaptureEvidence()
        private set
    fun update(value: CaptureEvidence) { latest = value }
}
