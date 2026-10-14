package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** support131: a valid new SPS/colour contract can precede its independently decodable frame. */
class ContinuousFieldFormatTransitionTest {
    private fun spec(revision: Int): ContinuousTrackFormatSpec {
        val fields = mapOf<String, Any>("mime" to "video/avc", "width" to 1280, "height" to 720,
            "color-standard" to revision, "color-transfer" to revision,
            "csd-0" to ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, revision.toByte())),
            "csd-1" to ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x68, 1)))
        return ContinuousTrackFormatSpec.capture(object : ContinuousTrackFormatFields {
            override val keys = fields.keys
            override fun string(key: String) = fields[key] as String?
            override fun integer(key: String) = fields[key] as Int
            override fun buffer(key: String) = fields[key] as ByteBuffer?
        })
    }

    @Test fun formatNotificationBeforeKeyframeDoesNotTerminateRecording() {
        val state = ContinuousTrackFormatState()
        state.accept(spec(1))
        state.accept(spec(2), cutPending = true)
        // Do not write these dependent frames with the old SPS; await the requested IDR.
        assertEquals(false, state.beforeMediaSample(keyframe = false))
        assertEquals(true, state.beforeMediaSample(keyframe = true))
        assertTrue(state.commitBoundary(cutBefore = true))
        assertEquals(1L, state.snapshot().discardedTransitionFrames)
    }

    @Test fun formatAfterPreviousCutRequestsAnIndependentBoundaryAndWaitsForItsDestination() {
        val state = ContinuousTrackFormatState()
        state.accept(spec(1))
        assertEquals(ContinuousFormatDecision.STAGE_ROLLOVER,
            state.accept(spec(2), allowIndependentRollover = true))
        assertTrue(state.hasPendingBoundary)
        assertFalse(state.beforeMediaSample(keyframe = true, boundaryReady = false))
        assertFalse(state.beforeMediaSample(keyframe = false, boundaryReady = true))
        assertTrue(state.beforeMediaSample(keyframe = true, boundaryReady = true))
        assertTrue(state.commitBoundary(true))
        assertFalse(state.hasPendingBoundary)
        assertEquals(2L, state.snapshot().discardedTransitionFrames)
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(2)))
    }

    @Test fun repeatedNotificationWhileDestinationIsPreparingDoesNotResetTransition() {
        val state = ContinuousTrackFormatState()
        state.accept(spec(1)); state.accept(spec(2), allowIndependentRollover = true)
        assertFalse(state.beforeMediaSample(false, false))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(2)))
        assertEquals(1L, state.snapshot().discardedTransitionFrames)
        assertTrue(state.hasPendingBoundary)
    }

    @Test fun stopBeforeDestinationIsReadyKeepsTheOldTrackAndDoesNotOpenANewOne() {
        val state = ContinuousTrackFormatState()
        state.accept(spec(1)); state.accept(spec(2), allowIndependentRollover = true)
        state.beforeMediaSample(false, false)
        state.cancelPending()
        assertTrue(state.discardTerminalMedia)
        assertFalse(state.hasPendingBoundary)
        assertFalse(state.commitBoundary(true))
        assertEquals(1L, state.snapshot().discardedTransitionFrames)
    }
}
