package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ParkingJourneyRegressionTest {
    private val now = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli()
    private val home = CarLocation(-34.9, 138.6, now, verified = true)
    private fun status(at: Long, km: Double?, source: Long = at, lon: Double = 138.62,
                       mileageTime: Long? = null, brake: String = "1", speed: String = "0", mode: String = "2") =
        Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), buildJsonObject {
            put("updateTime", source)
            putJsonObject("basicVehicleStatus") {
                put("speed", speed); put("usageMode", mode)
                putJsonObject("position") { put("latitude", -34.9); put("longitude", lon); put("posCanBeTrusted", "1") }
            }
            putJsonObject("additionalVehicleStatus") {
                putJsonObject("drivingSafetyStatus") { put("electricParkBrakeStatus", brake); put("centralLockingStatus", "1") }
                putJsonObject("maintenanceStatus") { km?.let { put("odometer", it.toString()) }; mileageTime?.let { put("updateTime", it) } }
            }
        })
    private fun sentry(at: Long, on: Boolean) = Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at),
        buildJsonObject { put("vstdModeState", if (on) "1" else "0") })
    private fun ParkingGuard.read(p: Probe) = observe(p, p.fetchedAt.toEpochMilli())
    private val arrival get() = now + 1_200_000
    private fun oldStop() = ParkingGuard("car").read(status(now, 1000.0)).read(sentry(now + 1, true)).copy(attemptedAt = now + 1)
    @Test fun missedEntireDriveAndFactoryOffStillPermitNewAwayParking() {
        var guard = ParkingGuard("car").read(status(now, 1000.0)).read(sentry(now + 1, true)).copy(attemptedAt = now + 1)
        val arrival = now + 1_200_000
        guard = guard.read(status(arrival, 1008.0)).read(sentry(arrival + 1, false))
        val confirmed = status(arrival + 70_000, 1008.0)
        guard = guard.read(confirmed).read(sentry(arrival + 70_001, false))
        assertFalse("Factory OFF after a proven journey is not a manual pause for this new stop", guard.paused)
        assertTrue(guard.decision(confirmed, sentry(arrival + 70_001, false), home, 100, true, arrival + 70_001).enable)
    }
    @Test fun firstMileageIncreaseWaitsForAnotherGetAndCannotSend() {
        val p = status(arrival, 1008.0)
        val g = oldStop().read(p)
        val decision = g.decision(p, sentry(arrival + 1, false), home, 100, true, arrival + 1)
        assertFalse(decision.enable); assertTrue(decision.journeyFollowUp)
    }
    @Test fun oldManualPauseCanEndAfterProvenMissedJourney() {
        val g = oldStop().pause(now + 2).read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0))
        assertFalse(g.paused); assertEquals(0L, g.attemptedAt); assertTrue(g.journey.confirmedAt > 0)
    }
    @Test fun explicitManualOffDuringArrivalConfirmationRemainsInForce() {
        val g = oldStop().read(status(arrival, 1008.0)).pause(arrival + 2)
            .read(status(arrival + 70_000, 1008.0)).read(sentry(arrival + 70_001, false))
        assertTrue(g.paused); assertEquals(GuardPauseOrigin.MANUAL, g.pauseOrigin)
        assertEquals(0L, g.journey.confirmedAt)
    }
    @Test fun externalOnThenOffObservedAtNewStopAlsoKeepsThatChoice() {
        val g = oldStop().read(status(arrival, 1008.0)).read(sentry(arrival + 1, true)).read(sentry(arrival + 2, false))
            .read(status(arrival + 70_000, 1008.0))
        assertTrue(g.journey.confirmedAt > 0); assertTrue(g.paused)
        assertEquals(GuardPauseOrigin.OBSERVED_OFF, g.pauseOrigin)
    }
    @Test fun gpsJumpSameMileageAndOffNeverCreateANewStop() {
        val g = oldStop().pause(now + 2).read(status(arrival, 1000.0, lon = 138.65))
            .read(status(arrival + 70_000, 1000.0, lon = 138.6)).read(sentry(arrival + 70_001, false))
        assertTrue(g.paused); assertEquals(0L, g.journey.confirmedAt)
    }
    @Test fun repeatedCachedReportIsNotASecondGet() {
        val first = status(arrival, 1008.0)
        val g = oldStop().read(first).observe(first, arrival + 70_000)
        assertEquals(0L, g.journey.confirmedAt); assertTrue(g.journey.pending(arrival + 70_000))
    }
    @Test fun secondGetOfStillFreshArrivalSnapshotCanConfirmMileageJourney() {
        val g = oldStop().read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0, source = arrival))
        assertTrue(g.journey.confirmedAt > 0); assertEquals(0L, g.attemptedAt)
    }
    @Test fun dataWithoutMileageNeverInventsZeroOrClearsPause() {
        for (km in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 2_000_001.0)) {
            val g = oldStop().pause(now + 2).read(status(arrival, km)).read(status(arrival + 70_000, km))
            assertTrue(g.paused); assertEquals(0L, g.journey.confirmedAt)
        }
    }
    @Test fun explicitStaleOrFutureMileageTimeIsNotHiddenByRootTime() {
        for (t in listOf(arrival - 180_001, arrival + 1, arrival - 60_001)) {
            val g = oldStop().pause(now + 2).read(status(arrival, 1008.0, mileageTime = t))
            assertTrue(g.paused); assertNull(g.journey.candidate)
        }
    }
    @Test fun staleFetchCannotRenewOldVehicleData() {
        val g = oldStop().read(status(arrival, 1008.0)).read(status(arrival + 180_001, 1008.0, source = arrival))
        assertEquals(0L, g.journey.confirmedAt); assertNull(g.journey.candidate)
    }
    @Test fun rollbackSpikeAndInconsistentSameSourceAreRejected() {
        for (p in listOf(status(arrival, 990.0), status(arrival, 100000.0), status(now + 1, 1008.0, source = now))) {
            val g = oldStop().pause(now + 2).read(p)
            assertTrue(g.paused); assertEquals(0L, g.journey.confirmedAt); assertNull(g.journey.candidate)
        }
    }
    @Test fun mileageIncreaseWithoutParkedAndZeroSpeedIsNotEnough() {
        for (p in listOf(status(arrival, 1008.0, brake = "0"), status(arrival, 1008.0, speed = "15"), status(arrival, 1008.0, mode = "13"))) {
            val g = oldStop().pause(now + 2).read(p)
            assertTrue(g.paused); assertNull(g.journey.candidate)
        }
    }
    @Test fun changingMileageRequiresStableArrivalRecheck() {
        val first = oldStop().read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1009.0))
        assertEquals(0L, first.journey.confirmedAt)
        assertTrue(first.read(status(arrival + 140_000, 1009.0)).journey.confirmedAt > 0)
    }
    @Test fun confirmationAndManualOriginSurviveRestartWithoutReplay() {
        val pending = oldStop().read(status(arrival, 1008.0)).read(sentry(arrival + 1, false))
        val g = ParkingGuard.parse(Json.parseToJsonElement(pending.json().toString())).read(status(arrival + 70_000, 1008.0))
        assertFalse(g.paused)
        val claimed = ParkingGuard.parse(g.copy(attemptedAt = arrival + 70_001).json())
            .read(status(arrival + 90_000, 1008.0))
        assertFalse(claimed.decision(status(arrival + 90_000, 1008.0), sentry(arrival + 90_001, false), home, 100, true, arrival + 90_001).enable)
    }
    @Test fun aReturnToSameCoordinatesCanStillBeANewTrip() {
        val g = oldStop().pause(now + 2).read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0))
        assertTrue(g.journey.confirmedAt > 0); assertFalse(g.paused)
    }
    @Test fun newSessionDoesNotSkipCurrentHomeLockFreshnessAndSentryGates() {
        val g = oldStop().read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0))
        val p = status(arrival + 70_000, 1008.0, lon = 138.6)
        assertFalse(g.decision(p, sentry(arrival + 70_001, false), home, 100, true, arrival + 70_001).enable)
        assertFalse(g.decision(p, sentry(arrival + 70_001, false), home, 100, false, arrival + 70_001).enable)
        assertFalse(g.decision(status(arrival + 70_000, 1008.0), sentry(arrival + 70_001, true), home, 100, true, arrival + 70_001).enable)
    }
    @Test fun oldHomeOverrideAndCloseAttemptEndButNewChoicesArePreserved() {
        val journey = oldStop().read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0)).journey
        val old = HomeSentryGuard("car", manualHoldAt = now, attemptedAt = now + 1).afterMissedJourney(journey)
        assertFalse(old.held); assertEquals(0L, old.attemptedAt); assertEquals(0L, old.firstFetch)
        val new = HomeSentryGuard("car", manualHoldAt = arrival + 2, attemptedAt = arrival + 1).afterMissedJourney(journey)
        assertTrue(new.held); assertEquals(arrival + 1, new.attemptedAt)
    }
    @Test fun oldConfigurationMigrationPreservesCurrentPauseAndStartsWithoutBaseline() {
        val g = ParkingGuard.parse(Json.parseToJsonElement("""{"vehicleKey":"car","pausedAt":$now,"attemptedAt":$now,"phase":"PARKED"}"""))
        assertTrue(g.paused); assertNull(g.journey.baseline)
        assertTrue(g.read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0)).paused)
    }
    @Test fun missingManualBaselineCannotReuseAnOldCandidate() {
        val g = oldStop().read(status(arrival, 1008.0)).pause(arrival + 120_000)
            .read(status(arrival + 130_000, 1008.0)).read(status(arrival + 200_000, 1008.0))
        assertTrue(g.paused); assertEquals(0L, g.journey.confirmedAt)
    }
    @Test fun liveDrivingStillResetsWithoutMileageAndFollowupsAreBounded() {
        val moving = oldStop().pause(now + 2).read(status(arrival, null, brake = "0", speed = "15", mode = "13"))
        assertFalse(moving.paused); assertEquals(ParkingPhase.MOVING, moving.phase)
        val pending = oldStop().read(status(arrival, 1008.0)).journey
        assertTrue(pending.needsFollowUp(arrival)); assertFalse(pending.copy(followUps = 3).needsFollowUp(arrival + 70_000))
        assertFalse(pending.needsFollowUp(arrival + 180_001))
    }
    @Test fun unexpectedSecondsTimestampIsRejectedRatherThanAssumedFresh() {
        val e = ParkingEvidence.from(status(arrival, 1008.0, mileageTime = arrival / 1000))!!
        assertNull(e.odometerTime); assertNull(ParkedMileage.from(e, arrival))
    }
    @Test fun historicalParkedMileageCanSeedUpgradeWithoutClearingPause() {
        val legacy = ParkingGuard("car").copy(pausedAt = now + 1, attemptedAt = now + 1)
        val seeded = legacy.read(status(now + 600_000, 1000.0, source = now))
        assertNotNull(seeded.journey.baseline); assertNull(seeded.journey.latest)
        assertEquals(0L, seeded.journey.confirmedAt); assertTrue(seeded.paused)
        val restored = ParkingGuard.parse(seeded.json()).read(status(arrival, 1008.0)).read(status(arrival + 70_000, 1008.0))
        assertFalse(restored.paused); assertTrue(restored.journey.confirmedAt > 0)
    }
    @Test fun historicalBaselineCannotOverrideANewerManualChoice() {
        val paused = ParkingGuard("car").pause(now + 100_000).read(status(now + 600_000, 1000.0, source = now))
        assertNull(paused.journey.baseline); assertTrue(paused.paused)
        val future = ParkingGuard("car").read(status(now, 1000.0, source = now + 1))
        assertNull(future.journey.baseline)
        val ancient = ParkingGuard("car").read(status(now, 1000.0, source = now - 604_800_001))
        assertNull(ancient.journey.baseline)
    }
}
