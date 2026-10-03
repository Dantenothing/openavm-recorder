package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecordingSessionCompatibilityTest {
    @Test fun oldReceiverKeepsOldRecordingsButMustNotConcatenateParallelCameras() {
        val old = Json.decodeFromString<HealthResponse>("""{"deviceName":"Phone","phoneDeviceId":"device"}""")
        assertTrue(RecordingSessionCompatibility.supported("""{"recordingMode":"NORMAL"}""", old.recordingSessionFormats))
        for (channel in SessionPlaybackTimeline.CHANNELS) {
            val next = """{"recordingMode":"NORMAL","channelId":"$channel","sessionStartedElapsedMs":1000}"""
            assertFalse(RecordingSessionCompatibility.supported(next, old.recordingSessionFormats))
            assertTrue(RecordingSessionCompatibility.supported(next, listOf(RecordingSessionCompatibility.CAPABILITY)))
        }
    }
    @Test fun legacyOrTimelapseMetadataDoesNotAcquireParallelRequirement() {
        assertFalse(RecordingSessionCompatibility.required(null))
        assertFalse(RecordingSessionCompatibility.required("""{"recordingMode":"TIME_LAPSE","channelId":"SURROUND","sessionStartedElapsedMs":1000}"""))
        assertFalse(RecordingSessionCompatibility.required("""{"recordingMode":"NORMAL","channelId":null}"""))
    }
}
