package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.RecordingClockPolicy
import io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor
import org.junit.Assert.*
import org.junit.Test

class RecordingClockPolicyTest {
    @Test fun firstDeliveredFrameAnchorsLocalZeroWithUnknownPipelineLatency() {
        val anchor = requireNotNull(RecordingClockPolicy.observedAnchor(11_500, "CODEC_OUTPUT_OBSERVED"))
        assertEquals(11_500L, anchor.elapsedAtMediaMs(0))
        assertEquals(12_750L, anchor.elapsedAtMediaMs(1_250))
        assertNull(anchor.uncertaintyMs)
        assertEquals(0L, anchor.mediaPositionUs)
        assertTrue(anchor.valid())
    }

    @Test fun subsequentKeyframeSegmentDoesNotRestartAtSessionOrigin() {
        val first = requireNotNull(RecordingClockPolicy.observedAnchor(11_500, "CALLBACK_OBSERVED"))
        val next = requireNotNull(RecordingClockPolicy.observedAnchor(72_200, "CALLBACK_OBSERVED"))
        assertEquals(60_700L, next.elapsedAtMediaMs(0)!! - first.elapsedAtMediaMs(0)!!)
        assertEquals(72_700L, next.elapsedAtMediaMs(500))
    }

    @Test fun anchorRejectsMissingOverflowingOrUnprovedRealtimeSource() {
        assertNull(RecordingClockPolicy.observedAnchor(null, "CODEC_OUTPUT_OBSERVED"))
        assertNull(RecordingClockPolicy.observedAnchor(-1, "CODEC_OUTPUT_OBSERVED"))
        assertNull(RecordingClockPolicy.observedAnchor(VideoClockAnchor.MAX_TIME_MS + 1, "CALLBACK_OBSERVED"))
        assertNull(RecordingClockPolicy.observedAnchor(1_000, "CAMERA_REALTIME"))
    }
}
