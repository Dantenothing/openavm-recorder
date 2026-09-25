package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeSentryGuardTest {
    private val now = Instant.parse("2026-09-20T06:00:00Z").toEpochMilli()
    private val home = CarLocation(-34.9, 138.6, now, verified = true)
    private fun status(at: Long = now, source: Long? = at, positionTime: Long? = null, groupTime: Long? = null,
                       lon: Double = 138.6, brake: String? = "1", speed: String? = "0", mode: String? = "2",
                       locked: String = "0", trusted: String? = "1") =
        Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), buildJsonObject {
            source?.let { put("updateTime", it) }
            putJsonObject("basicVehicleStatus") {
                speed?.let { put("speed", it) }; mode?.let { put("usageMode", it) }; groupTime?.let { put("updateTime", it) }
                putJsonObject("position") {
                    put("latitude", -34.9); put("longitude", lon)
                    trusted?.let { put("posCanBeTrusted", it) }; positionTime?.let { put("updateTime", it) }
                }
            }
            putJsonObject("additionalVehicleStatus") { putJsonObject("drivingSafetyStatus") {
                brake?.let { put("electricParkBrakeStatus", it) }; put("centralLockingStatus", locked)
                groupTime?.let { put("updateTime", it) }
            } }
        })
    private fun sentry(at: Long = now, on: Boolean = true, source: Long? = null) =
        Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), buildJsonObject {
            put("vstdModeState", if (on) "1" else "0"); source?.let { put("updateTime", it) }
        })
    private fun HomeSentryGuard.read(p: Probe) = observe(p, home, 100, true, p.fetchedAt.toEpochMilli())
    private fun HomeSentryGuard.decide(p: Probe, s: Probe = sentry(p.fetchedAt.toEpochMilli()), at: Long = p.fetchedAt.toEpochMilli()) =
        decision(p, s, home, 100, true, at)
    private fun confirmed() = HomeSentryGuard("car").read(status()).read(status(now + 70_000))

    @Test fun twoIndependentParkedHomeObservationsCloseEvenWhenCarUnlocked() {
        val first = HomeSentryGuard("car").read(status())
        assertFalse(first.decide(status()).disable); assertTrue(first.decide(status()).followUp)
        val last = status(now + 70_000)
        assertTrue(first.read(last).decide(last).disable)
        assertFalse(first.read(last).decide(last).enable)
    }
    @Test fun secondObservationBeforeOneMinuteDoesNotClose() {
        val p = status(now + 59_999)
        assertFalse(HomeSentryGuard("car").read(status()).read(p).decide(p).disable)
    }
    @Test fun repeatedCachedVehicleSnapshotIsNeverConfirmation() {
        val p = status(now + 70_000, source = now)
        val guard = HomeSentryGuard("car").read(status()).read(p)
        assertFalse(guard.decide(p).disable)
        assertEquals(now, guard.latestFetch)
    }
    @Test fun bothMotionAndPositionMustAdvance() {
        listOf(status(now + 70_000, groupTime = now), status(now + 70_000, positionTime = now)).forEach { p ->
            assertFalse(HomeSentryGuard("car").read(status()).read(p).decide(p).disable)
        }
    }
    @Test fun staleFutureMissingAndExplicitStaleSubgroupTimesCannotClose() {
        val at = now + 70_000
        listOf(status(at, source = at - 180_001), status(at, source = at + 1), status(at, source = null),
            status(at, positionTime = at - 180_001), status(at, groupTime = at - 180_001),
            status(at, groupTime = at + 1), status(at, positionTime = at + 1)).forEach { p ->
            val guard = HomeSentryGuard("car").read(status()).read(p)
            assertFalse(guard.decide(p).disable); assertEquals(0L, guard.firstFetch)
        }
    }
    @Test fun drivingMissingOrConflictingParkingEvidenceResetsConfirmation() {
        val at = now + 70_000
        listOf(status(at, brake = null), status(at, mode = "13"), status(at, mode = "33"),
            status(at, brake = "0"), status(at, brake = "2"), status(at, brake = "3"), status(at, speed = "1"),
            status(at, speed = null), status(at, mode = null)).forEach { p ->
            val guard = HomeSentryGuard("car").read(status()).read(p)
            assertFalse(guard.decide(p).disable); assertEquals(0L, guard.firstFetch)
        }
    }
    @Test fun outsideOrUntrustedPositionBreaksConsecutiveHomeEvidence() {
        listOf(status(now + 70_000, lon = 138.62), status(now + 70_000, trusted = null), status(now + 70_000, trusted = "0")).forEach { p ->
            val guard = HomeSentryGuard("car").read(status()).read(p).read(status(now + 90_000))
            assertFalse(guard.decide(status(now + 90_000)).disable)
        }
    }
    @Test fun sameHomeBufferAsAwayRuleIsUsed() {
        val p = status(now + 70_000, lon = 138.6018) // roughly 164m, inside 100m radius + 100m buffer
        assertTrue(HomeSentryGuard("car").read(status()).read(p).decide(p).disable)
    }
    @Test fun missingOrUnverifiedHomeAndLocationBlockOff() {
        val guard = confirmed(); val p = status(now + 70_000); val s = sentry(now + 70_000)
        assertFalse(guard.decision(p, s, null, 100, true, now + 70_000).disable)
        assertFalse(guard.decision(p, s, home.copy(verified = false), 100, true, now + 70_000).disable)
        assertFalse(guard.decision(p, s, home, 100, false, now + 70_000).disable)
    }
    @Test fun oldOrFailedOrAlreadyOffSentryNeverCloses() {
        val guard = confirmed(); val p = status(now + 70_000)
        listOf(sentry(), sentry(now + 70_000, source = now - 180_001), sentry(now + 70_000, on = false),
            Probe(Endpoint.SENTRY, ProbeOutcome.NETWORK, Instant.ofEpochMilli(now + 70_000))).forEach { s ->
            assertFalse(guard.decide(p, s).disable)
        }
    }
    @Test fun failedStatusReadRequiresAnewPairOfObservations() {
        val guard = confirmed().read(Probe(Endpoint.STATUS, ProbeOutcome.NETWORK, Instant.ofEpochMilli(now + 75_000))).read(status(now + 80_000))
        assertFalse(guard.decide(status(now + 80_000)).disable)
    }
    @Test fun confirmationExpiresAndOutOfOrderOldHomeDataCannotClose() {
        assertFalse(confirmed().decide(status(now + 70_000), sentry(now + 260_001), now + 260_001).disable)
        val next = confirmed().read(status(now + 320_000))
        assertEquals(now + 320_000, next.firstFetch)
        assertFalse(next.decide(status(now + 320_000)).disable)
        val older = status(now + 80_000, source = now - 1)
        assertFalse(confirmed().read(older).decide(older).disable)
    }
    @Test fun manualKeepOnSurvivesRestartUnlockRelockAndGpsDrift() {
        var guard = confirmed().hold(now + 70_001)
        guard = HomeSentryGuard.parse(Json.parseToJsonElement(guard.json().toString()))
            .read(status(now + 90_000, locked = "1")).read(status(now + 100_000, lon = 138.62))
            .read(status(now + 170_000))
        assertTrue(guard.held); assertFalse(guard.decide(status(now + 170_000)).disable)
    }
    @Test fun onlyFreshActualDrivingClearsManualHoldAndAttempt() {
        val guard = confirmed().copy(attemptedAt = now + 70_000).hold(now + 70_001)
        val redLight = guard.read(status(now + 80_000, brake = "0", mode = "13"))
        assertTrue(redLight.held); assertTrue(redLight.attemptedAt > 0)
        val moving = redLight.read(status(now + 90_000, brake = "0", mode = "13", speed = "15"))
        assertFalse(moving.held); assertEquals(0L, moving.attemptedAt); assertEquals(0L, moving.firstFetch)
        assertTrue(moving.read(status(now + 100_000)).read(status(now + 170_000)).decide(status(now + 170_000)).disable)
    }
    @Test fun externalOffToOnWhileHomeHonorsThatChoice() {
        val guard = HomeSentryGuard("car").read(status()).read(sentry(on = false))
            .read(status(now + 70_000)).read(sentry(now + 70_000))
        assertTrue(guard.held); assertFalse(guard.decide(status(now + 70_000)).disable)
    }
    @Test fun awayAutoOnBeforeArrivalIsNotMistakenForHomeManualOn() {
        val guard = HomeSentryGuard("car").read(status(lon = 138.62)).read(sentry(on = false))
            .read(sentry(now + 1)).read(status(now + 10_000)).read(status(now + 80_000)).read(sentry(now + 80_000))
        assertFalse(guard.held); assertTrue(guard.decide(status(now + 80_000)).disable)
    }
    @Test fun persistedAttemptIsNotReplayedAfterRestartOrNetworkUncertainty() {
        val guard = HomeSentryGuard.parse(confirmed().copy(attemptedAt = now + 70_000).json()).read(status(now + 150_000))
        assertFalse(guard.decide(status(now + 150_000)).disable); assertFalse(guard.needsFollowUp(now + 150_000))
    }
    @Test fun followUpIsBoundedAndRetainsCountAcrossRestart() {
        var guard = HomeSentryGuard("car").read(status())
        repeat(3) {
            assertTrue(guard.needsFollowUp(now + it * 70_000))
            guard = HomeSentryGuard.parse(guard.copy(followUps = guard.followUps + 1).json())
        }
        assertFalse(guard.needsFollowUp(now + 140_000))
        assertFalse(HomeSentryGuard("car").read(status()).needsFollowUp(now + 301_000))
    }
    @Test fun resumingManualChoiceRequiresNewConfirmation() {
        val guard = confirmed().hold(now + 70_001).resume()
        assertFalse(guard.held); assertEquals(0L, guard.firstFetch)
        assertFalse(guard.read(status(now + 80_000)).decide(status(now + 80_000)).disable)
    }
    @Test fun oldSavedStateDoesNotSilentlyEnableNewRuleAndNewStateRoundTrips() {
        val old = AssistantState.parse("""{"schema":1,"guardEnabled":true,"parkingGuard":{"vehicleKey":"car","pausedAt":12}}""")
        assertTrue(old.guardEnabled); assertTrue(old.parkingGuard.paused); assertFalse(old.homeGuardEnabled)
        val saved = old.copy(homeGuardEnabled = true, homeGuard = confirmed().hold(now + 70_001))
        assertEquals(saved, AssistantState.parse(saved.encode()))
    }
}
