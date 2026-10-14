package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecordingGroupFailureEvidenceTest {
    private val id = "00000000-0000-0000-0000-000000000031"
    private val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.CABIN, "1",
        CameraFormatProfile(ProfileSize(1280, 720), 4_000_000), RecordingLayoutKind.SINGLE_V1),
        60, 5L * 1024 * 1024 * 1024)
    private val state = RecorderState(status = RecorderStatus.RECORDING, recordingSessionId = id,
        channelId = "FRONT_CABIN", cameraGeneration = 2, segmentNumber = 1, recordingBackend = "CONTINUOUS_CODEC")
    private val capture = CaptureEvidence(id, 1, SegmentFrameStats(count = 5), 99,
        preview = PreviewCaptureStats(completedWithoutPreviewTarget = 7, captureFailures = 3, lastFailureReason = 0),
        encoder = EncoderEvidence(frames = 0, writtenFrames = 0, formatNotifications = 0),
        channelId = "FRONT_CABIN", cameraGeneration = 2)
    private fun snapshot(c: CaptureEvidence = capture) = RecordingGroupFailureEvidence.capture(id,
        "AUXILIARY_NO_WRITTEN_FRAMES", 1_000, 200, "test", listOf(RecordingGroupFailureEvidence.Member(config, state, c)))

    @Test fun capturesCallbacksAndEncodingRemainSeparateAndImmutableAcrossStop() {
        val saved = snapshot()
        val stopped = capture.copy(encoder = null, frames = SegmentFrameStats())
        snapshot(stopped)
        val row = saved["channels"]!!.jsonArray.single().jsonObject
        assertEquals(5, row["capturedFrames"]!!.jsonPrimitive.int)
        assertEquals(7, row["completedCaptureCallbacks"]!!.jsonPrimitive.int)
        assertEquals(3, row["captureFailures"]!!.jsonPrimitive.int)
        assertEquals(0, row["encodedFrames"]!!.jsonPrimitive.int)
        assertEquals(0, row["writtenFrames"]!!.jsonPrimitive.int)
        assertEquals("RECORDING", row["status"]!!.jsonPrimitive.content)
        assertFalse(saved.toString().contains(id))
    }

    @Test fun otherSessionChannelSegmentOrCameraGenerationIsUnknownRatherThanZero() {
        listOf(capture.copy(sessionId = "old"), capture.copy(channelId = "SURROUND"),
            capture.copy(segment = 2), capture.copy(cameraGeneration = 1)).forEach { stale ->
            val row = snapshot(stale)["channels"]!!.jsonArray.single().jsonObject
            assertFalse(row["captureEvidenceMatches"]!!.jsonPrimitive.boolean)
            listOf("capturedFrames", "captureFailures", "encodedFrames", "writtenFrames").forEach {
                assertEquals("$it must be unknown for mismatched evidence", JsonNull, row[it])
            }
        }
    }

    @Test fun missingEncoderEvidenceDoesNotClaimZeroOutput() {
        val row = snapshot(capture.copy(encoder = null))["channels"]!!.jsonArray.single().jsonObject
        assertEquals(5, row["capturedFrames"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, row["encodedFrames"])
        assertEquals(JsonNull, row["writtenFrames"])
    }

    @Test fun savedInputsAreBoundedAndRebuiltWithoutUnexpectedFields() {
        val base = snapshot()
        val row = JsonObject(base["channels"]!!.jsonArray.single().jsonObject + mapOf(
            "account" to JsonPrimitive("PRIVATE_DATA"), "status" to JsonPrimitive("/private/path")))
        val input = JsonObject(base + mapOf("password" to JsonPrimitive("PRIVATE_DATA"),
            "channels" to JsonArray(List(12) { row })))
        val clean = RecordingGroupFailureEvidence.sanitize(input)
        assertEquals(3, clean["channels"]!!.jsonArray.size)
        assertFalse(clean.toString().contains("PRIVATE_DATA")); assertFalse(clean.toString().contains("private"))
        assertTrue(clean.toString().toByteArray().size < 8 * 1024)
    }
}
