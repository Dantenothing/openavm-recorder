package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MirrorSleepEvidenceTest {
    private val facts = mapOf("controlsSession" to "controls-1", "sleepCycle" to "sleep-1", "windowSession" to "window-1",
        "captureReleased" to "true", "overlayAttached" to "true", "screenUsable" to "false", "cleanupOwners" to "0",
        "recorderRunning" to "false", "previewRunning" to "false", "auxiliaryActive" to "false", "wakeLock" to "false",
        "nativeIdle" to "true", "glIdle" to "true")
    private fun row(event: String, elapsed: Long, uptime: Long = elapsed, process: String = "p1", extra: Map<String,String> = emptyMap()) =
        AwayRow(event, elapsed, elapsed, uptime, process, facts + extra)
    private val start = row("MIRROR_SLEEP_SUSPENDED", 100)
    private val ready = row("MIRROR_SLEEP_CAPTURE_RELEASED", 200)
    private val returned = row("MIRROR_SLEEP_RETURN_OBSERVED", 100_000, 500, extra = mapOf("screenUsable" to "true"))
    private fun result(vararg rows: AwayRow) = MirrorSleepEvidence.report(rows.toList())["classification"]!!.jsonPrimitive.content
    @Test fun requiresPositiveObservationsOnBothSidesOfSleep() {
        val report = MirrorSleepEvidence.report(listOf(start, ready, returned))
        assertEquals("SAME_PROCESS_AND_WINDOW_RETURNED_CAPTURE_RELEASED", report["classification"]!!.jsonPrimitive.content)
        assertEquals("99500", report["cpuSleepGapMs"]!!.jsonPrimitive.content)
        assertEquals("NOT_OBSERVABLE", report["whileCpuAsleep"]!!.jsonPrimitive.content)
        assertEquals("EXPLICIT_TAP_ONLY", report["resumePolicy"]!!.jsonPrimitive.content)
        assertFalse(report.containsKey("automaticCaptureOnReturn"))
    }
    @Test fun beforeReturnCannotPass() { assertEquals("AWAITING_RETURN", result(start, ready)) }
    @Test fun newProcessIsNotAResumedProcessEvenWithMatchingBooleans() {
        assertEquals("PROCESS_CHANGED_RETENTION_NOT_PROVEN", result(start, ready, returned.copy(process = "p2")))
    }
    @Test fun aRecreatedWindowIsNotTheRetainedWindow() {
        assertEquals("WINDOW_RETENTION_NOT_PROVEN", result(start, ready, returned.copy(facts = facts + mapOf("windowSession" to "new"))))
    }
    @Test fun windowDetachedAtReturnCannotPass() {
        assertEquals("WINDOW_RETENTION_NOT_PROVEN", result(start, ready, returned.copy(facts = facts + ("overlayAttached" to "false"))))
    }
    @Test fun releaseOnlyAfterReturnDoesNotProveReleaseDuringParking() {
        assertEquals("NO_RELEASED_WINDOW_OBSERVATION_BEFORE_RETURN", result(start, returned, ready))
    }
    @Test fun aReleasedLabelCannotHideAnOutstandingResource() {
        assertEquals("CAPTURE_RELEASE_UNCONFIRMED_AT_RETURN", result(start, ready,
            returned.copy(facts = facts + ("cleanupOwners" to "1"))))
    }
    @Test fun manualCloseCannotBeUndoneByLateReturnTelemetry() {
        assertEquals("CONTROL_SESSION_CLOSED", result(start, ready, row("MIRROR_SLEEP_CONTROLS_CLOSED", 300), returned))
    }
    @Test fun newCycleCannotBorrowOldEvidence() {
        assertEquals("AWAITING_RETURN", result(start, ready,
            start.copy(elapsed = 400, facts = facts + ("sleepCycle" to "second")), returned))
    }
    @Test fun cameraRestartWhilePausedInvalidatesTheResult() {
        assertEquals("CAPTURE_ACTIVITY_DURING_PAUSE", result(start, ready, row("RECORDER_START", 300), returned))
    }
    @Test fun noTrialIsNotAPass() { assertEquals("NOT_TESTED", result()) }
    @Test fun optedInCaptureAfterReturnDoesNotPretendItWasRunningDuringSleep() {
        val auto = row("MIRROR_SLEEP_AUTO_REQUESTED", 102_000, 2_500, extra = mapOf("reason" to "RECORD"))
        val report = MirrorSleepEvidence.report(listOf(start.copy(facts = facts + ("returnMode" to "RECORD")), ready, returned, auto))
        assertEquals("SAME_PROCESS_AND_WINDOW_RETURNED_CAPTURE_RELEASED", report["classification"]!!.jsonPrimitive.content)
        assertEquals("USER_OPTED_IN_RECORD", report["resumePolicy"]!!.jsonPrimitive.content)
        assertTrue(report["automaticReturn"].toString().contains("MIRROR_SLEEP_AUTO_REQUESTED"))
    }
    @Test fun anAutomaticRequestBeforeReturnInvalidatesTheParkingEvidence() {
        assertEquals("CAPTURE_ACTIVITY_DURING_PAUSE", result(start, ready, row("MIRROR_SLEEP_AUTO_REQUESTED", 300), returned))
    }
    @Test fun profileWaitEvidenceBelongsToThisReturnCycleOnly() {
        val waiting = row("MIRROR_SLEEP_PROFILE_TIMED_OUT", 117_000, extra = mapOf(
            "profileAttempt" to "20", "profileWaitMs" to "15000", "profileStatus" to "TIMED_OUT", "requestedRole" to "SURROUND"))
        val unrelated = row("MIRROR_SLEEP_PROFILE_READY", 118_000, extra = mapOf("sleepCycle" to "another"))
        val report = MirrorSleepEvidence.report(listOf(start, ready, returned, waiting, unrelated))
        val evidence = report["profilePreparation"]!!.jsonObject
        assertEquals("MIRROR_SLEEP_PROFILE_TIMED_OUT", evidence["event"]!!.jsonPrimitive.content)
        assertEquals("20", evidence["facts"]!!.jsonObject["profileAttempt"]!!.jsonPrimitive.content)
        assertEquals("15000", evidence["facts"]!!.jsonObject["profileWaitMs"]!!.jsonPrimitive.content)
    }
}
