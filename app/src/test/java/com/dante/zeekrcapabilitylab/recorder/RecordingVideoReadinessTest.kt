package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class RecordingVideoReadinessTest {
    private val state = RecorderState(status = RecorderStatus.RECORDING, recordingSessionId = "run",
        segmentNumber = 1, cameraGeneration = 2, channelId = "SURROUND", recordingBackend = "SHARED_INPUT_CONTINUOUS_CODEC")
    private val evidence = CaptureEvidence(sessionId = "run", segment = 1, channelId = "SURROUND", cameraGeneration = 2,
        frames = SegmentFrameStats(count = 12), lastReceivedElapsedMs = 200,
        encoder = EncoderEvidence(frames = 3, lastOutputElapsedMs = 199))

    @Test fun acceptedStartAndCameraReceiptsCannotConfirmAnEmptyCodec() {
        assertFalse(RecordingVideoReadiness.confirmed(state, evidence.copy(encoder = null)))
        assertFalse(RecordingVideoReadiness.confirmed(state, evidence.copy(encoder = EncoderEvidence())))
        assertFalse(RecordingVideoReadiness.confirmed(state, evidence.copy(encoder = EncoderEvidence(frames = 3))))
        assertTrue(RecordingVideoReadiness.confirmed(state, evidence))
    }

    @Test fun oldCameraSessionFileOrOtherChannelCannotConfirmThisStart() {
        for (other in listOf(evidence.copy(cameraGeneration = 1), evidence.copy(sessionId = "old"),
            evidence.copy(segment = 2), evidence.copy(channelId = "FRONT_CABIN"))) {
            assertFalse(RecordingVideoReadiness.confirmed(state, other))
        }
        for (status in listOf(RecorderStatus.STARTING, RecorderStatus.FINALIZING, RecorderStatus.STOPPED, RecorderStatus.ERROR))
            assertFalse(RecordingVideoReadiness.confirmed(state.copy(status = status), evidence))
    }

    @Test fun mediaRecorderRequiresCameraFramesAndDoesNotClaimEncodedFileValidation() {
        val native = state.copy(recordingBackend = "MEDIA_RECORDER")
        assertTrue(RecordingVideoReadiness.confirmed(native, evidence.copy(encoder = null)))
        assertFalse(RecordingVideoReadiness.confirmed(native, evidence.copy(frames = SegmentFrameStats(), encoder = null)))
        assertFalse(RecordingVideoReadiness.confirmed(native, evidence.copy(lastReceivedElapsedMs = null, encoder = null)))
    }
}
