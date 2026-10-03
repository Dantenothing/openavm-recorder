package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.avmtransfer.protocol.*
import org.junit.Assert.*
import org.junit.Test

class SessionReviewProjectionTest {
    private val id = "9e9ce0a3-c882-4181-a6b4-c90d5c788401"
    private fun segment(name: String, channel: String, start: Long, duration: Long, anchor: VideoClockAnchor? = null) =
        SessionVideoSegment(name, channel, id, start, start + duration, duration, anchor)
    private fun gps(vararg times: Long, session: String = id) = TelemetryTrack(TelemetryHeader(sessionId = session, startedElapsedMs = 1_000),
        times.map { TelemetryFix(it, 1_000_000 + it, -34.0, 138.0, 3f, "gps", 0f) })
    private fun vehicle(vararg times: Long, session: String = id) = VehicleTelemetryTrack(VehicleTelemetryHeader(sessionId = session, startedElapsedMs = 1_000),
        times.map { VehicleTelemetrySample(it, 1_000_000 + it, mapOf("speed" to VehicleTelemetryValue(it, number = 0.0),
            "gear" to VehicleTelemetryValue(it, text = "P"), "brake_pedal" to VehicleTelemetryValue(it, number = 0.0))) })

    @Test fun parallelChannelsDoNotTripleSessionClockOrTelemetry() {
        val timeline = SessionPlaybackTimeline(id, SessionPlaybackTimeline.CHANNELS.map { segment(it, it, 1_000, 4_000) })
        assertEquals(4_000L, timeline.durationMs)
        val points = SessionReviewProjection.gps(timeline, gps(1_000, 2_000, 3_000))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), points.map { it.elapsedMs })
    }

    @Test fun telemetryIsHalfOpenAndFromMatchingSessionOnly() {
        val timeline = SessionPlaybackTimeline(id, listOf(segment("a", "SURROUND", 1_000, 2_000)))
        assertEquals(listOf(1_000L, 2_999L), SessionReviewProjection.gps(timeline, gps(999, 1_000, 2_999, 3_000)).map { it.elapsedMs })
        assertTrue(SessionReviewProjection.gps(timeline, gps(1_000, session = "other")).isEmpty())
        assertTrue(SessionReviewProjection.vehicle(timeline, vehicle(1_000, session = "other")).isEmpty())
    }

    @Test fun recordingGapBreaksRouteAndVehicleEvenWhenNoObservationFallsInsideHole() {
        val timeline = SessionPlaybackTimeline(id, listOf(segment("a", "SURROUND", 1_000, 1_000), segment("b", "SURROUND", 2_500, 2_000)))
        assertTrue(SessionReviewProjection.gps(timeline, gps(1_900, 2_600))[1].breakBefore)
        assertTrue(SessionReviewProjection.vehicle(timeline, vehicle(1_900, 2_600))[1].breakBefore)
    }

    @Test fun overlappingOtherCameraPreservesExistingCoverage() {
        val timeline = SessionPlaybackTimeline(id, listOf(segment("a", "SURROUND", 1_000, 1_000),
            segment("b", "SURROUND", 2_500, 2_000), segment("c", "FRONT_CABIN", 1_800, 1_000)))
        assertFalse(SessionReviewProjection.gps(timeline, gps(1_900, 2_600))[1].breakBefore)
    }

    @Test fun invalidAndOutOfOrderDataCannotBridgeRoute() {
        val timeline = SessionPlaybackTimeline(id, listOf(segment("a", "SURROUND", 1_000, 5_000)))
        val track = gps(1_000, 900, 2_000, 3_000).let { it.copy(points = it.points.toMutableList().apply { this[3] = this[3].copy(latitude = Double.NaN) }) }
        val projected = SessionReviewProjection.gps(timeline, track)
        assertEquals(2, projected.size); assertTrue(projected[1].breakBefore)
    }

    @Test fun anchorDefinesClockAndMissingSpeedNeverBecomesZero() {
        val anchor = VideoClockAnchor(elapsedRealtimeNs = 1_500_000_000, source = "CAMERA_REALTIME")
        val timeline = SessionPlaybackTimeline(id, listOf(segment("a", "SURROUND", 1_000, 3_000, anchor)), 1_000)
        assertEquals(500L, timeline.mediaToSession("a", 0))
        assertEquals(listOf(1_500L, 2_000L), SessionReviewProjection.gps(timeline, gps(1_000, 1_500, 2_000)).map { it.elapsedMs })
        val observations = SessionReviewProjection.observations(timeline, listOf(VehicleTelemetrySample(1_500, 1,
            mapOf("speed" to VehicleTelemetryValue(1_500, status = "UNAVAILABLE")))))
        assertFalse(observations.single().available)
        assertTrue(SessionReviewProjection.observations(timeline, vehicle(1_500).samples).single().available)
    }

    @Test fun rawPedalObservationsCountForCoverageWithoutInventingBrakeOrTurnState() {
        val timeline = SessionPlaybackTimeline(id, listOf(segment("a", "SURROUND", 1_000, 5_000)))
        fun sample(at: Long, key: String = "brake_depth") = VehicleTelemetrySample(at, 1,
            mapOf(key to VehicleTelemetryValue(1_000, number = 0.0, status = "PROVISIONAL_RAW")))
        val fresh = SessionReviewProjection.observations(timeline, listOf(sample(1_000))).single()
        assertTrue(fresh.available)
        assertNull(fresh.brakePressed)
        assertNull(fresh.turn)
        assertFalse(SessionReviewProjection.observations(timeline, listOf(sample(3_001))).single().available)
        assertFalse(SessionReviewProjection.observations(timeline, listOf(sample(1_000, "untrusted_key"))).single().available)
    }
}
