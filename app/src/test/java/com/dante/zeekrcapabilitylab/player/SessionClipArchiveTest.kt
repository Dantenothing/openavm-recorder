package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionClipArchiveTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val timeline = SessionPlaybackTimeline(id, listOf(
        SessionVideoSegment("a", "SURROUND", id, 1_000, 31_000, 30_000)), 1_000)

    @Test fun manifestMapsEarlierKeyframeToSourceClockAndNamesOnlyIncludedData() {
        val value = Json.parseToJsonElement(SessionClipArchive.manifest(timeline, "SURROUND", 12_000, 20_000,
            2_000, 10_000, gps = true, vehicle = false)).jsonObject
        assertEquals(11_000L, value.getValue("videoZeroSourceElapsedMs").jsonPrimitive.long)
        assertEquals(12_000L, value.getValue("requestedFromSessionMs").jsonPrimitive.long)
        assertEquals("gps.jsonl", value.getValue("gpsFile").jsonPrimitive.content)
        assertFalse(value.containsKey("vehicleFile"))
        assertEquals("SOURCE_SESSION_APPROX_MILLISECONDS", value.getValue("alignment").jsonPrimitive.content)
    }

    @Test fun impossibleNegativeSourceOffsetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionClipArchive.manifest(timeline, "SURROUND", 0, 20_000, 2_000, 22_000, false, false)
        }
    }
}
