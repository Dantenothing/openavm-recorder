package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FieldEncoderEvidenceExportTest {
    @Test fun shortAndAwayCopiesRetainActualRejectedPtsEvenWhenItIsNegative() {
        val capture = ContinuousFailureCapture("34695741-c1ed-4e36-bccf-a85e4015a775", { 50 }, { 100 })
        val facts = capture.capture(EncodedTimestampException(200, -1, false, true), ContinuousFailureStage.CODEC_DRAIN).facts() +
            mapOf("channelId" to "SURROUND", "recordingBackend" to "SHARED_INPUT_CONTINUOUS_CODEC")
        val event = ProbeEvent(1, "app", 100, 50, "SYSTEM", "RECORDER_CONTINUOUS_FIRST_FAILURE", "WARN", payload = facts)
        val groups = ProcessExitReport.continuousFailures(listOf(event))["groups"]!!.jsonArray
        val short = groups.single().jsonObject["firstAvailable"]!!.jsonObject
        assertEquals(200, short["timestampPreviousPtsUs"]!!.jsonPrimitive.long)
        assertEquals(-1, short["timestampRejectedPtsUs"]!!.jsonPrimitive.long)
        val away = AwayReportEvidence.recording(listOf(AwayRow(event.eventName, 100, 50, 50, "p", facts)))
        val exported = away["firstContinuousFailure"]!!.jsonObject["facts"]!!.jsonObject
        assertEquals("-1", exported["timestampRejectedPtsUs"]!!.jsonPrimitive.content)
        assertEquals("true", exported["timestampCutPending"]!!.jsonPrimitive.content)
    }

    @Test fun formatExportExplainsTransitionGapWithoutCodecBytes() {
        val observation = ContinuousFormatObservation(2, decision = "ROLLOVER_APPLIED", discardedTransitionFrames = 13)
        val exported = requireNotNull(CodecFormatReport.format(ContinuousFormatFacts.observation(observation, false, 100)))
        assertEquals(13, exported["discardedTransitionFrames"]!!.jsonPrimitive.int)
        assertTrue(exported["fields"]!!.jsonObject.isEmpty())
    }
}
