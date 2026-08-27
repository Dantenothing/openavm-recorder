package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.PipelineRuntimeDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PipelineRuntimeDiagnosticsTest {

    @Test
    fun eventPayloadIncludesAvailableAggregateEvidence() {
        val payload = PipelineRuntimeDiagnostics(
            kind = "FRONT_CROP_CODEC",
            started = true,
            stopping = false,
            released = false,
            inputFramesReceived = 20,
            inputFramesRendered = 19,
            muxerStarted = true,
            drainThreadAlive = true,
            runtimeFailure = "egl failure",
        ).eventPayload()

        assertEquals("FRONT_CROP_CODEC", payload["pipelineKind"])
        assertEquals("20", payload["pipelineInputFrames"])
        assertEquals("19", payload["pipelineRenderedFrames"])
        assertEquals("true", payload["pipelineMuxerStarted"])
        assertEquals("egl failure", payload["pipelineFailure"])
    }

    @Test
    fun eventPayloadOmitsUnsupportedCounters() {
        val payload = PipelineRuntimeDiagnostics(
            kind = "MEDIA_RECORDER",
            started = true,
            stopping = false,
            released = false,
        ).eventPayload(prefix = "recorder")

        assertEquals("MEDIA_RECORDER", payload["recorderKind"])
        assertFalse(payload.containsKey("recorderInputFrames"))
        assertFalse(payload.containsKey("recorderMuxerStarted"))
    }
}
