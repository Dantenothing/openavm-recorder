package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic AU 1.6.6 payload shape: root time, EPB, usage mode and no subgroup timestamps. */
class OfficialParkingGuardTest {
    private val now = Instant.parse("2026-09-20T06:00:00Z").toEpochMilli()
    private val home = CarLocation(-34.9, 138.6, now, verified = true)
    private fun status(at: Long = now, brake: String? = "1", speed: String? = "0", mode: String? = "2",
                       locked: String? = "1", lon: Double = 138.62, trusted: String? = "1",
                       rootTime: Long? = at, groupTime: Long? = null, positionTime: Long? = null) =
        Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), buildJsonObject {
            rootTime?.let { put("updateTime", it) }
            putJsonObject("basicVehicleStatus") {
                speed?.let { put("speed", it) }; mode?.let { put("usageMode", it) }
                groupTime?.let { put("updateTime", it) }
                putJsonObject("position") {
                    put("latitude", -34.9); put("longitude", lon)
                    trusted?.let { put("posCanBeTrusted", it) }; positionTime?.let { put("updateTime", it) }
                }
            }
            putJsonObject("additionalVehicleStatus") { putJsonObject("drivingSafetyStatus") {
                brake?.let { put("electricParkBrakeStatus", it) }; locked?.let { put("centralLockingStatus", it) }
                groupTime?.let { put("updateTime", it) }
            } }
        })
    private fun sentry(on: Boolean = false, at: Long = now, source: Long? = null) =
        Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), buildJsonObject {
            put("vstdModeState", if (on) "1" else "0"); source?.let { put("updateTime", it) }
        })
    private fun decision(p: Probe, time: Long = now, s: Probe = sentry(at = time), verified: Boolean = true): GuardDecision =
        ParkingGuard("car").observe(p, time).decision(p, s, home, 200, verified, time)

    @Test fun verifiedOfficialParkedSignalAllowsAwayCheckWithAggregateTime() {
        val p = status()
        val g = ParkingGuard("car").observe(p, now)
        assertEquals(ParkingPhase.PARKED, g.phase)
        assertTrue(g.decision(p, sentry(), home, 200, true, now).enable)
        assertNull(CarLocation.from(p)?.source) // Do not relabel global position data as independently sampled.
    }
    @Test fun stationaryGpsAndZeroSpeedWithoutParkedSignalCannotEnable() {
        val first = status(brake = null, groupTime = now, positionTime = now)
        val next = status(now + 60_000, brake = null, groupTime = now + 60_000, positionTime = now + 60_000)
        val g = ParkingGuard("car").observe(first, now).observe(next, now + 60_000)
        assertFalse(g.decision(next, sentry(at = now + 60_000), home, 200, true, now + 60_000).enable)
    }
    @Test fun parkedMustAgreeWithSpeedAndDrivingMode() {
        listOf(status(mode = "13"), status(mode = "33"), status(speed = "12"), status(speed = null),
            status(mode = null), status(mode = "unknown"), status(speed = "NaN"), status(speed = "-1"),
            status(brake = "0"), status(brake = "2"), status(brake = "3"), status(brake = "99")).forEach {
            assertFalse(decision(it).enable)
        }
    }
    @Test fun redLightInDrivingModeNeverCountsAsParked() {
        assertFalse(decision(status(brake = "0", speed = "0", mode = "13")).enable)
    }
    @Test fun freshFetchCannotRenewOldFutureOrMissingVehicleTime() {
        listOf(status(rootTime = now - 180_001), status(rootTime = now + 1), status(rootTime = null)).forEach {
            assertFalse(decision(it).enable)
        }
        assertFalse(decision(status(), time = now + 180_001).enable)
    }
    @Test fun aggregateTimeNeverOverridesAnExplicitStaleGroupOrPositionTime() {
        assertFalse(decision(status(groupTime = now - 180_001)).enable)
        assertFalse(decision(status(positionTime = now - 180_001)).enable)
        assertFalse(decision(status(groupTime = now + 1)).enable)
        assertFalse(decision(status(positionTime = now + 1)).enable)
    }
    @Test fun lockLocationTrustHomeAndSentryRemainRequired() {
        listOf(status(locked = "0"), status(locked = null), status(trusted = "0"), status(trusted = null),
            status(lon = 138.6), status(lon = 138.6025)).forEach { assertFalse(decision(it).enable) }
        assertFalse(decision(status(), verified = false).enable)
        assertFalse(decision(status(), s = sentry(true)).enable)
        assertFalse(decision(status(), s = sentry(at = now - 61_000)).enable)
        assertFalse(decision(status(), s = sentry(source = now - 180_001)).enable)
    }
    @Test fun pauseAndAttemptSurviveReloadUnlockRelockAndRepeatedSnapshots() {
        var g = ParkingGuard("car").observe(status(), now).copy(attemptedAt = now).pause(now + 1)
        g = ParkingGuard.parse(Json.parseToJsonElement(g.json().toString()))
            .observe(status(now + 30_000, locked = "0"), now + 30_000)
            .observe(status(now + 60_000, lon = 138.6201), now + 60_000)
        assertTrue(g.paused); assertEquals(now, g.attemptedAt)
        assertFalse(g.decision(status(now + 60_000), sentry(at = now + 60_000), home, 200, true, now + 60_000).enable)
    }
    @Test fun onlyNewConsistentDrivingEvidenceStartsNewTrip() {
        val g = ParkingGuard("car").observe(status(), now).pause(now + 1)
        assertTrue(g.observe(status(now + 20_000, mode = "13", speed = "0"), now + 20_000).paused)
        assertTrue(g.observe(status(now + 20_000, mode = "13", speed = "10"), now + 20_000).paused)
        assertTrue(g.observe(status(now + 20_000, brake = "0", mode = "13", speed = "10", rootTime = now), now + 20_000).paused)
        val moving = g.observe(status(now + 30_000, brake = "0", mode = "13", speed = "10"), now + 30_000)
        assertFalse(moving.paused); assertEquals(ParkingPhase.MOVING, moving.phase)
        val parked = moving.observe(status(now + 60_000), now + 60_000)
        assertEquals(ParkingPhase.PARKED, parked.phase)
        assertTrue(parked.decision(status(now + 60_000), sentry(at = now + 60_000), home, 200, true, now + 60_000).enable)
    }
    @Test fun duplicateOrOutOfOrderParkedSnapshotCannotEndNewDrive() {
        val moving = ParkingGuard("car").observe(status(now + 30_000, brake = "0", mode = "33", speed = "20"), now + 30_000)
        val oldPark = status(now + 31_000, rootTime = now)
        val duplicate = status(now + 31_000, rootTime = now + 30_000)
        assertEquals(ParkingPhase.MOVING, moving.observe(oldPark, now + 31_000).phase)
        assertFalse(moving.observe(duplicate, now + 31_000).decision(duplicate, sentry(at = now + 31_000), home, 200, true, now + 31_000).enable)
    }
    @Test fun parkedOnToOffPausesButPriorTripSentryCannotPauseNewTrip() {
        val parked = ParkingGuard("car").observe(status(), now).observe(sentry(true, now + 1), now + 1)
        assertTrue(parked.observe(sentry(false, now + 2), now + 2).paused)
        val moving = parked.observe(status(now + 30_000, brake = "0", speed = "20", mode = "13"), now + 30_000)
        val nextPark = moving.observe(status(now + 60_000), now + 60_000)
        assertFalse(nextPark.observe(sentry(true, now + 10_000), now + 60_000)
            .observe(sentry(false, now + 60_001), now + 60_001).paused)
    }
}
