package com.dante.zeekrcapabilitylab.transfer

import com.dante.zeekrcapabilitylab.telemetry.TelemetryFix
import com.dante.zeekrcapabilitylab.telemetry.TelemetryHeader
import com.dante.zeekrcapabilitylab.telemetry.TelemetryTrack
import com.dante.zeekrcapabilitylab.telemetry.VehicleTelemetryHeader
import com.dante.zeekrcapabilitylab.telemetry.VehicleTelemetrySample
import com.dante.zeekrcapabilitylab.telemetry.VehicleTelemetryTrack
import com.dante.zeekrcapabilitylab.telemetry.VehicleTelemetryValue
import io.github.dantenothing.avmtransfer.protocol.DriveDataCodec
import org.junit.Assert.*
import org.junit.Test

class DriveAttachmentSnapshotTest {
    @Test fun gpsCanBeExcludedWhileKeepingHudWithTheExistingReceiverContract() {
        val result = DriveAttachmentSnapshot.build(sidecar(), gps(fix(1_250)), vehicle(sample(1_250)), DriveDataSelection(gps = false, vehicle = true))
        assertTrue(result.gps.isEmpty()); assertEquals(1, result.vehicle.size)
        val payload = DriveDataCodec.encode(result)
        assertFalse(payload.toString(Charsets.UTF_8).contains("latitude"))
        assertNotNull(DriveDataCodec.verify(payload, DriveDataCodec.descriptor(payload)!!, sidecar()))
    }
    @Test fun hudCanBeExcludedWhileKeepingGps() {
        val result = DriveAttachmentSnapshot.build(sidecar(), gps(fix(1_250)), vehicle(sample(1_250)), DriveDataSelection(gps = true, vehicle = false))
        assertEquals(1, result.gps.size); assertTrue(result.vehicle.isEmpty()); assertNull(result.recorderReport)
    }
    @Test fun disjointConfirmedWindowsExcludeUnselectedReadingsEvenInsideOneOriginalFile() {
        val windows = listOf(DriveDataWindow(1_000, 1_300), DriveDataWindow(1_700, 1_900))
        val result = DriveAttachmentSnapshot.build(sidecar(), gps(fix(1_000), fix(1_300), fix(1_500), fix(1_700), fix(1_900)),
            vehicle(sample(1_000), sample(1_500), sample(1_700)), DriveDataSelection(true, true, windows))
        assertEquals(listOf(1_000L, 1_700L), result.gps.map { it.elapsedMs })
        assertEquals(listOf(1_000L, 1_700L), result.vehicle.map { it.elapsedMs })
        assertEquals(listOf(false, true), result.gps.map { it.breakBefore })
        assertEquals(listOf(false, true), result.vehicle.map { it.breakBefore })
        assertNotNull(DriveDataCodec.decode(DriveDataCodec.encode(result)))
    }
    @Test fun invalidUserWindowsFailBeforeMakingPayload() {
        assertThrows(IllegalArgumentException::class.java) { DriveAttachmentSnapshot.build(sidecar(), null, null,
            DriveDataSelection(true, true, listOf(DriveDataWindow(3_000, 1_000)))) }
    }
    private val session = "00000000-0000-4000-8000-000000000001"
    private val other = "00000000-0000-4000-8000-000000000002"
    private val epoch = 1_791_000_000_000L
    private fun sidecar(mode: String = "NORMAL", multiplier: Int = 1, start: Long = 1_000, end: Long = 2_000) =
        """{"recordingSessionId":"$session","recordingMode":"$mode","timeLapseMultiplier":$multiplier,"startedAtElapsedRealtimeMs":$start,"stoppedAtElapsedRealtimeMs":$end}"""
    private fun fix(at: Long) = TelemetryFix(at, epoch + at, -34.9, 138.6, 4f, "gps")
    private fun sample(at: Long) = VehicleTelemetrySample(at, epoch + at, mapOf("speed" to VehicleTelemetryValue(at, number = 0.0)))
    private fun gps(vararg points: TelemetryFix, sessionId: String = session) = TelemetryTrack(
        TelemetryHeader(sessionId = sessionId, startedElapsedMs = 0), points.toList(),
    )
    private fun vehicle(vararg samples: VehicleTelemetrySample, sessionId: String = session) = VehicleTelemetryTrack(
        VehicleTelemetryHeader(sessionId = sessionId, startedElapsedMs = 0), samples.toList(),
    )

    @Test fun newSnapshotKeepsMeasuredAnchorAndUnverifiedPedalStatus() {
        val metadata = sidecar().dropLast(1) + """, "videoClockAnchor":{"elapsedRealtimeNs":1100000000,"source":"CALLBACK_OBSERVED"}}"""
        val result = DriveAttachmentSnapshot.build(metadata, null, vehicle(sample(1_250).copy(values = mapOf(
            "brake_depth" to VehicleTelemetryValue(1_250, number = 8.0, status = "PROVISIONAL_RAW")))))
        assertEquals(2, result.schemaVersion)
        assertEquals(DriveDataCodec.ANCHORED_ALIGNMENT, result.alignment)
        assertEquals(1_100L, result.videoClockAnchor!!.elapsedAtMediaMs(0))
        val bytes = DriveDataCodec.encode(result)
        assertNotNull(DriveDataCodec.verify(bytes, DriveDataCodec.descriptor(bytes)!!, metadata))
        assertEquals("PROVISIONAL_RAW", result.vehicle.single().values["brake_depth"]!!.status)
    }

    @Test fun onlyTheSelectedHalfOpenSegmentIsIncludedForBothSources() {
        val result = DriveAttachmentSnapshot.build(sidecar(),
            gps(fix(999), fix(1_000), fix(1_999), fix(2_000), fix(2_001)),
            vehicle(sample(999), sample(1_000), sample(1_999), sample(2_000), sample(2_001)))
        assertEquals(listOf(1_000L, 1_999L), result.gps.map { it.elapsedMs })
        assertEquals(listOf(1_000L, 1_999L), result.vehicle.map { it.elapsedMs })
        assertEquals(1_000L, result.segmentStartedElapsedMs)
        assertEquals(2_000L, result.segmentEndedElapsedMs)
        val bytes = DriveDataCodec.encode(result)
        assertNotNull(DriveDataCodec.verify(bytes, DriveDataCodec.descriptor(bytes)!!, sidecar()))
    }

    @Test fun aBatchInsideSegmentCannotLeakNeighboringSegmentGetterReadings() {
        val batch = sample(1_500).copy(values = mapOf(
            "speed" to VehicleTelemetryValue(999, number = 20.0),
            "gear" to VehicleTelemetryValue(1_000, text = "D"),
            "indicator" to VehicleTelemetryValue(1_500, text = "left"),
            "turn_left" to VehicleTelemetryValue(2_000, number = 1.0),
        ))
        val result = DriveAttachmentSnapshot.build(sidecar(), null, vehicle(batch))
        val values = result.vehicle.single().values
        assertEquals(setOf("gear", "indicator"), values.keys)
        assertEquals(1_000L, values.getValue("gear").readElapsedMs)
        assertEquals(1_500L, values.getValue("indicator").readElapsedMs)
        assertNotNull(DriveDataCodec.decode(DriveDataCodec.encode(result)))
    }

    @Test fun mismatchedSessionDoesNotContributePointsEvenWithMatchingTimes() {
        val result = DriveAttachmentSnapshot.build(sidecar(), gps(fix(1_000), sessionId = other),
            vehicle(sample(1_000), sessionId = other))
        assertEquals(session, result.recordingSessionId)
        assertTrue(result.gps.isEmpty())
        assertTrue(result.vehicle.isEmpty())
        val independent = DriveAttachmentSnapshot.build(sidecar(), gps(fix(1_000)), vehicle(sample(1_000), sessionId = other))
        assertEquals(1, independent.gps.size)
        assertTrue(independent.vehicle.isEmpty())
    }

    @Test fun absentOrOutOfWindowHistoryRemainsAnEmptyAttachment() {
        for (result in listOf(DriveAttachmentSnapshot.build(sidecar(), null, null),
            DriveAttachmentSnapshot.build(sidecar(), gps(fix(999), fix(2_000)), vehicle(sample(999), sample(2_000))))) {
            assertTrue(result.gps.isEmpty())
            assertTrue(result.vehicle.isEmpty())
            assertNotNull(DriveDataCodec.decode(DriveDataCodec.encode(result)))
        }
    }

    @Test fun timeLapseAndMissingModeCannotGetAnApproximateNormalVideoAttachment() {
        for (metadata in listOf(sidecar(mode = "TIME_LAPSE", multiplier = 2), sidecar(mode = "TIME_LAPSE"),
            sidecar(multiplier = 2), sidecar().replace("\"recordingMode\":\"NORMAL\",", ""))) {
            assertThrows(IllegalArgumentException::class.java) { DriveAttachmentSnapshot.build(metadata, gps(fix(1_000)), null) }
        }
    }

    @Test fun invalidWindowsAreRejectedBeforeClipping() {
        for (metadata in listOf(sidecar(start = -1), sidecar(end = 1_000), sidecar(start = 2_000, end = 1_000))) {
            assertThrows(IllegalArgumentException::class.java) { DriveAttachmentSnapshot.build(metadata, null, null) }
        }
    }

    @Test fun clippingPreservesSourceGapsWithoutInventingARotationInterruption() {
        val result = DriveAttachmentSnapshot.build(sidecar(), gps(fix(999), fix(1_000), fix(1_250), fix(1_500).copy(breakBefore = true)),
            vehicle(sample(999), sample(1_000), sample(1_250), sample(1_500).copy(breakBefore = true)))
        assertEquals(listOf(false, false, true), result.gps.map { it.breakBefore })
        assertEquals(listOf(false, false, true), result.vehicle.map { it.breakBefore })
    }

    @Test fun selectedSourcesKeepZeroMissingAndDifferentSpeedUnits() {
        val result = DriveAttachmentSnapshot.build(sidecar(), gps(fix(1_000).copy(speedMps = 0f), fix(1_500)),
            vehicle(sample(1_000).copy(values = mapOf("speed" to VehicleTelemetryValue(1_000, number = 4.0))),
                sample(1_500).copy(values = mapOf("speed" to VehicleTelemetryValue(1_500, status = "UNAVAILABLE")))))
        assertEquals(0f, result.gps.first().speedMps!!, 0f)
        assertNull(result.gps.last().speedMps)
        assertEquals(4.0, result.vehicle.first().values.getValue("speed").number!!, 0.0)
        assertNull(result.vehicle.last().values.getValue("speed").number)
        assertEquals("UNAVAILABLE", result.vehicle.last().values.getValue("speed").status)
    }
}
