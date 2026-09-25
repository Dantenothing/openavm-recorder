package com.dante.zeekrcheck.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ParkingGuardTest {
    private val now = Instant.parse("2026-09-20T01:00:00Z").toEpochMilli()
    private val home = CarLocation(-34.9, 138.6, now, verified = true)
    private fun status(at: Long, speed: Int = 0, locked: Boolean = true, lon: Double = 138.62, time: Boolean = true): Probe {
        val t = if (time) ",\"updateTime\":$at" else ""
        return Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), Json.parseToJsonElement("""{
          "updateTime":${if (time) at else "null"},"basicVehicleStatus":{"speed":$speed,"usageMode":${if (speed > 0) 13 else 2}$t,"position":{"latitude":-34.9,"longitude":$lon,"posCanBeTrusted":true$t}},
          "additionalVehicleStatus":{"drivingSafetyStatus":{"electricParkBrakeStatus":${if (speed > 0) 0 else 1},"centralLockingStatus":${if (locked) 1 else 0},"updateTime":$at}}}
        """))
    }
    private fun sentry(on: Boolean, at: Long) = Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at),
        Json.parseToJsonElement("""{"vstdModeState":${if (on) 1 else 0}}"""))
    private fun parked() = ParkingGuard("car").observe(status(now), now).observe(status(now + 60_000), now + 60_000)
    @Test fun repeatedOfficialParkedObservationsPermitOnlyOneAttemptAwayFromHome() {
        val guard = parked()
        assertEquals(ParkingPhase.PARKED, guard.phase)
        assertTrue(guard.decision(status(now + 60_000), sentry(false, now + 60_000), home, 200, true, now + 60_000).enable)
        assertFalse(guard.copy(attemptedAt = now + 60_000).decision(status(now + 60_000), sentry(false, now + 60_000), home, 200, true, now + 60_000).enable)
    }
    @Test fun manualPauseSurvivesPersistenceUnlockRelockAndGpsDrift() {
        val pause = parked().pause(now + 65_000)
        var restored = ParkingGuard.parse(Json.parseToJsonElement(pause.json().toString()))
        restored = restored.observe(status(now + 70_000, locked = false), now + 70_000)
            .observe(status(now + 150_000, lon = 138.6201), now + 150_000)
        assertTrue(restored.paused)
        assertFalse(restored.decision(status(now + 150_000), sentry(false, now + 150_000), home, 200, true, now + 150_000).enable)
    }
    @Test fun onlyNewFreshMotionClearsPauseThenAnotherParkingMustBeConfirmed() {
        val pause = parked().pause(now + 65_000)
        assertTrue(pause.observe(status(now, speed = 30), now + 66_000).paused)
        val moving = pause.observe(status(now + 90_000, speed = 30), now + 90_000)
        assertFalse(moving.paused); assertEquals(ParkingPhase.MOVING, moving.phase)
        val newPark = moving.observe(status(now + 120_000), now + 120_000)
        assertEquals(ParkingPhase.PARKED, newPark.phase)
        assertEquals(ParkingPhase.PARKED, newPark.observe(status(now + 180_000), now + 180_000).phase)
    }
    @Test fun fetchTimeCannotReplaceMissingVehicleTime() {
        val state = ParkingGuard("car").observe(status(now, time = false), now)
        assertEquals(ParkingPhase.UNKNOWN, state.phase)
        assertFalse(state.decision(status(now, time = false), sentry(false, now), home, 200, true, now).enable)
    }
    @Test fun observedExternalOffPausesWithoutAssumingWhoClosedIt() {
        val guard = parked().observe(sentry(true, now + 65_000), now + 65_000)
            .observe(sentry(false, now + 70_000), now + 70_000)
        assertTrue(guard.paused)
        assertTrue(guard.observe(sentry(true, now + 66_000), now + 71_000).paused)
        assertFalse(guard.resume().paused)
    }
    @Test fun homeAndStaleDataNeverPermitEnable() {
        val guard = parked()
        assertFalse(guard.decision(status(now + 60_000, lon = 138.6), sentry(false, now + 60_000), home, 200, true, now + 60_000).enable)
        assertFalse(guard.decision(status(now + 60_000), sentry(false, now + 60_000), home, 200, true, now + 600_000).enable)
    }
    @Test fun assistantRecordPreservesPauseAndDefaultsOldRecordsToWidgetSync() {
        val before = AssistantState(parkingGuard = parked().pause(now + 65_000), widgetSyncEnabled = false)
        val after = AssistantState.parse(before.encode())
        assertEquals(before.parkingGuard, after.parkingGuard); assertFalse(after.widgetSyncEnabled)
        val legacy = Json.parseToJsonElement(AssistantState().encode()).let { it as kotlinx.serialization.json.JsonObject }
        val withoutNewFields = kotlinx.serialization.json.JsonObject(legacy.filterKeys { it !in setOf("parkingGuard", "widgetSyncEnabled") })
        assertTrue(AssistantState.parse(withoutNewFields.toString()).widgetSyncEnabled)
        assertFalse(AssistantState.parse(withoutNewFields.toString()).parkingGuard.paused)
    }
}
