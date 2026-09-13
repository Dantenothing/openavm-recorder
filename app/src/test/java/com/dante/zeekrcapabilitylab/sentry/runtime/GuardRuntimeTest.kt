package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.*
import com.dante.zeekrcapabilitylab.sentry.ai.*
import com.dante.zeekrcapabilitylab.sentry.canary.CanaryTextReportPart
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class GuardRuntimeTest {
    @Test fun eventDeadlineArithmeticDoesNotWrap() {
        val window = GuardEventWindow(Long.MAX_VALUE - 120_000_000)
        assertTrue(window.extend(Long.MAX_VALUE - 1))
        assertEquals(Long.MAX_VALUE, window.endUs)
    }
    @Test fun repeatedTriggersExtendOneEventButCannotExceedFiveMinuteTotalTarget() {
        val window = GuardEventWindow(180_000_000)
        assertEquals(240_000_000, window.endUs)
        assertTrue(window.extend(220_000_000)); assertEquals(280_000_000, window.endUs)
        assertTrue(window.extend(260_000_000)); assertEquals(300_000_000, window.endUs)
        assertFalse(window.extend(250_000_000)); assertFalse(window.extend(300_000_000))
        assertEquals(300_000_000, window.endUs)
    }
    @Test fun clockRequiresRealtimeMatchingAndExpiresAfterMissingCapture() {
        val clock = GuardCaptureClock(true)
        for (i in 0..6) { clock.sensor(1_000_000L + i * 33_333); clock.encoded(1_000_100L + i * 33_333) }
        assertFalse(clock.calibrated(1_233_400))
        clock.sensor(1_233_331); clock.encoded(1_233_431)
        assertTrue(clock.calibrated(1_250_000))
        assertNotNull(clock.imagePts(1_233_331, 1_250_000))
        assertNull(clock.imagePts(1_300_000, 1_250_000))
        assertNull(clock.imagePts(1_000_000, 3_300_000))
    }
    @Test fun offsetAndUnknownClocksNeverBecomeAutomaticTriggerProof() {
        for (realtime in listOf(false, true)) {
            val clock = GuardCaptureClock(realtime)
            for (i in 0..100) { clock.sensor(2_000_000L + i * 100_000); clock.encoded(2_050_000L + i * 100_000) }
            assertFalse(clock.calibrated(12_100_000))
        }
        val unknown = GuardCaptureClock(false)
        for (i in 0..10) { unknown.sensor(i * 10_000L); unknown.encoded(i * 10_000L) }
        assertFalse(unknown.calibrated(100_000))
    }
    @Test fun delayedEncoderCallbacksCanMatchPreviouslyCapturedFrames() {
        val clock = GuardCaptureClock(true)
        repeat(40) { clock.sensor(1_000_000L + it * 33_333) }
        repeat(40) { clock.encoded(1_000_000L + it * 33_333) }
        assertTrue(clock.calibrated(2_350_000))
    }
    @Test fun stopDuringHandoffRejectsLateAcquireAndWaitsForExactRelease() {
        val coordinator = RecorderModeCoordinator(true)
        coordinator.setPolicy(GuardPolicy.AUTO)
        val normal = (coordinator.explicitStart().single() as ModeEffect.Acquire).ticket
        coordinator.acquired(normal)
        coordinator.presence(normal.stamp.run, VehiclePresencePhase.AWAY_CONFIRMED)
        coordinator.stop()
        assertTrue(coordinator.released(normal).isEmpty())
        assertNull(coordinator.lease.snapshot)
        assertTrue(coordinator.acquired(normal).single() is ModeEffect.CloseStaleResource)
        assertEquals(RecorderModePhase.STOPPED, coordinator.phase)
        assertTrue(coordinator.presence(normal.stamp.run, VehiclePresencePhase.AWAY_CONFIRMED).isEmpty())
    }
    @Test fun allRotationsAndMirrorsMapBackInsideTheFrozenVehicleCrops() {
        val source = SegmentLaneLayoutFactory.forProfile(1280, 5140, listOf("a", "b", "c", "d"), listOf(3, 1, 4, 2), listOf(0, 90, 180, 270))!!
        for (mirror in listOf(emptySet(), setOf(1, 2, 3, 4))) {
            val layout = FrozenLaneLayoutAdapter.freeze(source, "test", mirror)
            for (lane in layout.lanes) for (u in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) for (v in listOf(0.0, 0.3, 1.0)) {
                val point = NanoDetRuntime.sourcePoint(lane, u, v)
                val inverse = lane.sourcePointToLane(point.first, point.second)!!
                assertEquals(u, inverse.x, 1e-9); assertEquals(v, inverse.y, 1e-9)
            }
        }
    }
    @Test fun letterboxDropsPaddingOnlyObjectsAndRestoresNonSquareCrop() {
        val mapping = NanoDetRuntime.letterbox(1280, 640)
        assertEquals(104, mapping.top); assertEquals(208, mapping.height)
        assertNull(mapping.box(10.0, 0.0, 30.0, 100.0))
        assertEquals(DetectionBox(0.0, 0.0, 1.0, 1.0), mapping.box(0.0, 104.0, 416.0, 312.0))
    }
    @Test fun nanoDetPostprocessIsStableForLargeLogitsAndRejectsBadShapes() {
        val outputs = listOf(8, 16, 32).flatMap { stride ->
            val cells = 416 / stride
            listOf(FloatArray(cells * cells * 80), FloatArray(cells * cells * 32) { 1000f })
        }
        outputs[0][(10 * 52 + 10) * 80] = 0.9f
        val detections = NanoDetRuntime.decode(outputs, NanoDetRuntime.letterbox(416, 416))
        assertEquals(1, detections.size); assertEquals(ObjectKind.PERSON, detections.single().kind)
        assertEquals(55.5 / 416, detections.single().box.left, 1e-6)
        try { NanoDetRuntime.decode(outputs.dropLast(1), NanoDetRuntime.letterbox(416, 416)); fail("Bad shape accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun nativeOutputClassNmsRetainsOverlappingPersonAndVehicle() {
        val outputs = listOf(8, 16, 32).flatMap { stride ->
            val cells = 416 / stride
            listOf(FloatArray(cells * cells * 80), FloatArray(cells * cells * 32))
        }
        outputs[0][(10 * 52 + 10) * 80] = 0.9f
        outputs[0][(10 * 52 + 11) * 80] = 0.8f
        outputs[0][(11 * 52 + 10) * 80 + 2] = 0.85f
        val detections = NanoDetRuntime.decode(outputs, NanoDetRuntime.letterbox(416, 416))
        assertEquals(listOf(ObjectKind.PERSON, ObjectKind.VEHICLE), detections.map { it.kind })
    }
    @Test fun jsonMailPartsRoundTripEscapesChineseAndSurrogatePairsWithHash() {
        val json = buildJsonObject {
            put("format", "OPENAVM_SENTRY_INTEGRATED_REPORT")
            put("payload", "测试🙂\n\"\\".repeat(15_000))
        }.toString()
        val parts = GuardDiagnostics.parts(json)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray().size <= 128 * 1024 })
        val decoded = parts.map { GuardEventStore.json.decodeFromString<CanaryTextReportPart>(it) }
        assertEquals(json, decoded.joinToString("") { it.jsonText })
        val hash = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(decoded.all { it.reportSha256 == hash && it.partCount == decoded.size })
        assertEquals(decoded.indices.map { it + 1 }, decoded.map { it.partNumber })
    }
    @Test fun unrelatedReportCannotBePackagedAsGuardEvidence() {
        try { GuardDiagnostics.parts("{\"format\":\"OTHER\"}"); fail("Wrong report accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun guardStateKeepsTheMainStopActionEnabled() {
        assertTrue(RecorderCommandPolicy.isActive(RecorderStatus.SENTRY_LISTENING))
        assertTrue(RecorderCommandPolicy.canStop(RecorderStatus.SENTRY_LISTENING, true))
        assertFalse(RecorderCommandPolicy.canStart(RecorderStatus.SENTRY_LISTENING, true))
    }
}
