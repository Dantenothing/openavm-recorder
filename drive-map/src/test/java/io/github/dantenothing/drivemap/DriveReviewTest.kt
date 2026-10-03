package io.github.dantenothing.drivemap

import org.junit.Assert.*
import org.junit.Test

class DriveReviewTest {
    @Test fun transitionsRequirePriorKnownStateAndKeepObservedTimes() {
        val result = DriveEventTimeline.build(listOf(DriveEventObservation(0, "off", false),
            DriveEventObservation(250, "left", true), DriveEventObservation(500, "left", true), DriveEventObservation(750, "off", false)))
        assertEquals(listOf(250L, 250L, 750L, 750L), result.map { it.sessionMs })
        assertEquals(listOf("left", "pressed", "off", "released"), result.map { it.value })
    }

    @Test fun unknownAndGapsNeverFabricateTurnOrBrakeTransitions() {
        val result = DriveEventTimeline.build(listOf(DriveEventObservation(0, "left", true),
            DriveEventObservation(250), DriveEventObservation(500, "off", false),
            DriveEventObservation(3_000, "right", true), DriveEventObservation(3_250, "off", false, breakBefore = true)))
        assertTrue(result.none { it.kind == DriveEventKind.TURN || it.kind == DriveEventKind.BRAKE })
        assertEquals(3, result.count { it.kind == DriveEventKind.VEHICLE_GAP })
    }

    @Test fun clockResetCannotCompareAgainstPriorControls() {
        val result = DriveEventTimeline.build(listOf(DriveEventObservation(1_000, "left", true),
            DriveEventObservation(900, "right", false), DriveEventObservation(1_500, "off", false)))
        assertTrue(result.isEmpty())
    }

    @Test fun manualEmergencyAndInterruptionMarkersAreBoundedAndSanitized() {
        val markers = listOf(DriveReviewEvent(600, DriveEventKind.EMERGENCY, "private label"),
            DriveReviewEvent(200, DriveEventKind.MANUAL), DriveReviewEvent(800, DriveEventKind.RECORDING_INTERRUPTED),
            DriveReviewEvent(-1, DriveEventKind.MANUAL), DriveReviewEvent(300, DriveEventKind.BRAKE, "pressed"))
        val result = DriveEventTimeline.build(emptyList(), markers)
        assertEquals(listOf(200L, 600L, 800L), result.map { it.sessionMs })
        assertTrue(result.all { it.value == null })
    }

    @Test fun eventStormIsCapped() {
        val values = List(10_000) { DriveEventObservation(it.toLong(), brakePressed = it % 2 == 0) }
        assertEquals(DriveEventTimeline.MAX_EVENTS, DriveEventTimeline.build(values).size)
    }

    @Test fun distanceAndFreshnessCoverageExcludeGaps() {
        val gps = listOf(DriveCoveragePoint(0, 0.0, 0.0), DriveCoveragePoint(1_000, 0.0, 0.0001),
            DriveCoveragePoint(4_000, 1.0, 1.0, breakBefore = true))
        val vehicle = listOf(DriveEventObservation(0, brakePressed = false), DriveEventObservation(1_000, brakePressed = true),
            DriveEventObservation(4_000, brakePressed = false, breakBefore = true))
        val result = DriveSessionSummary.summarize(5_000, gps, vehicle)
        assertEquals(11.12, result.distanceM, 0.05)
        assertEquals(2_000L, result.gpsCoveredMs); assertEquals(2_000L, result.vehicleCoveredMs)
        assertTrue(result.incomplete)
    }

    @Test fun invalidPointBreaksDistanceAndMissingObservationsStayUncovered() {
        val gps = listOf(DriveCoveragePoint(0, 0.0, 0.0), DriveCoveragePoint(500, Double.NaN, 0.0),
            DriveCoveragePoint(1_000, 0.0, 0.001))
        val result = DriveSessionSummary.summarize(2_000, gps, emptyList())
        assertEquals(0.0, result.distanceM, 0.0); assertEquals(0L, result.vehicleCoveredMs)
        assertEquals(2, result.gpsPointCount); assertTrue(result.incomplete)
    }

    @Test fun antimeridianDistanceIsShortAndHalfOpenWindowIsPreserved() {
        val gps = listOf(DriveCoveragePoint(0, 0.0, 179.999), DriveCoveragePoint(1_000, 0.0, -179.999),
            DriveCoveragePoint(2_000, 10.0, 10.0))
        val result = DriveSessionSummary.summarize(2_000, gps, emptyList())
        assertEquals(222.39, result.distanceM, 0.1)
        assertEquals(2, result.gpsPointCount)
    }

    @Test fun completeCoverageCanBeRecognizedWithoutInventingDistance() {
        val result = DriveSessionSummary.summarize(2_000,
            listOf(DriveCoveragePoint(0, 0.0, 0.0), DriveCoveragePoint(1_000, 0.0, 0.0)),
            listOf(DriveEventObservation(0, brakePressed = false), DriveEventObservation(1_000, brakePressed = false)))
        assertFalse(result.incomplete); assertEquals(0.0, result.distanceM, 0.0)
        assertEquals(2_000L, result.gpsCoveredMs); assertEquals(2_000L, result.vehicleCoveredMs)
    }
}
