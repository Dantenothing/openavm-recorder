package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorReturnPolicyTest {
    private val idle = MirrorSleepResources()
    private val record = MirrorReturnChoice(MirrorReturnMode.RECORD, 1, true)
    private fun MirrorReturnGate.pollAt(now: Long, choice: MirrorReturnChoice = record,
        resources: MirrorSleepResources = idle, screen: Boolean = true, attached: Boolean = true) =
        poll(now, screen, attached, resources, choice)
    private fun armed(choice: MirrorReturnChoice = record) = MirrorReturnGate().apply { arm(choice, false); pollAt(100, choice) }

    @Test fun aFreshServiceWithoutAnObservedSleepDoesNotRecord() {
        assertEquals(MirrorReturnGate.Decision.NONE, MirrorReturnGate().pollAt(50_000))
    }
    @Test fun logoOffAndUnconfirmedChoicesNeverDispatchCapture() {
        listOf(record.copy(mode = MirrorReturnMode.LOGO), record.copy(mode = MirrorReturnMode.OFF), record.copy(confirmed = false)).forEach {
            assertEquals(MirrorReturnGate.Decision.NONE, armed(it).pollAt(5_000, it))
        }
    }
    @Test fun stableScreenIsRequiredBeforeTheSingleRecordingAction() {
        val gate = armed()
        assertEquals(MirrorReturnGate.Decision.WAIT, gate.pollAt(1_599))
        assertEquals(MirrorReturnGate.Decision.RECORD, gate.pollAt(1_600))
        repeat(10) { assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(1_601 + it.toLong())) }
    }
    @Test fun fullWindowWithoutAutoRecordingAsksForPreviewOnly() {
        val preview = record.copy(mode = MirrorReturnMode.PREVIEW)
        assertEquals(MirrorReturnGate.Decision.PREVIEW, armed(preview).pollAt(2_000, preview))
    }
    @Test fun aShortScreenFlashDoesNotStartAnything() {
        val gate = armed()
        assertEquals(MirrorReturnGate.Decision.WAIT, gate.pollAt(1_000, screen = false))
        assertEquals(MirrorReturnGate.Decision.WAIT, gate.pollAt(10_000))
        assertEquals(MirrorReturnGate.Decision.RECORD, gate.pollAt(11_500))
    }
    @Test fun everyOutstandingCaptureResourceBlocksAutomaticStart() {
        listOf(idle.copy(recorderRunning = true), idle.copy(previewRunning = true), idle.copy(auxiliaryActive = true),
            idle.copy(cleanupOwners = 1), idle.copy(nativeIdle = false), idle.copy(glIdle = false), idle.copy(wakeLockHeld = true)).forEach {
            val gate = armed()
            assertEquals(MirrorReturnGate.Decision.WAIT, gate.pollAt(2_000, resources = it))
            assertEquals(MirrorReturnGate.Decision.RECORD, gate.pollAt(3_000))
        }
    }
    @Test fun noAttachedWindowMeansNoAutomaticStart() {
        assertEquals(MirrorReturnGate.Decision.WAIT, armed().pollAt(2_000, attached = false))
    }
    @Test fun delayedCleanupTimesOutAndCannotStartMuchLater() {
        val gate = armed()
        assertEquals(MirrorReturnGate.Decision.TIMED_OUT, gate.pollAt(30_100, resources = idle.copy(cleanupOwners = 1)))
        assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(40_000))
    }
    @Test fun savingEvenTheSameSettingRevokesThePendingAction() {
        val gate = armed()
        assertEquals(MirrorReturnGate.Decision.SETTINGS_CHANGED, gate.pollAt(2_000, record.copy(revision = 2)))
        assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(4_000))
    }
    @Test fun closeOrManualStopCancelsUntilAnotherObservedSleep() {
        val gate = armed(); gate.cancel()
        assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(2_000))
        gate.arm(record, false)
        assertEquals(MirrorReturnGate.Decision.WAIT, gate.pollAt(3_000))
        assertEquals(MirrorReturnGate.Decision.RECORD, gate.pollAt(4_500))
    }
    @Test fun changedOrUnknownInstallationAlwaysNeedsReview() {
        assertTrue(MirrorReturnPromptPolicy.needsReview(true, "same-version:new-install-time", "same-version:old-install-time"))
        assertTrue(MirrorReturnPromptPolicy.needsReview(true, null, null))
        assertTrue(MirrorReturnPromptPolicy.needsReview(true, "new", null))
        assertFalse(MirrorReturnPromptPolicy.needsReview(true, "same", "same"))
        assertFalse(MirrorReturnPromptPolicy.needsReview(false, "new", null))
    }
    @Test fun upgradeCannotReuseAnOldAutomaticRecordingOptIn() {
        assertEquals(MirrorReturnMode.LOGO, MirrorReturnPromptPolicy.effectiveMode(true, MirrorReturnMode.RECORD, true))
        assertEquals(MirrorReturnMode.OFF, MirrorReturnPromptPolicy.effectiveMode(false, MirrorReturnMode.RECORD, false))
        assertEquals(MirrorReturnMode.RECORD, MirrorReturnPromptPolicy.effectiveMode(true, MirrorReturnMode.RECORD, false))
    }
    @Test fun anAutomaticGrantStillRechecksAllResourcesAtDispatch() {
        val state = MirrorSleepRetention().apply { suspend(); observe(idle) }
        assertFalse(state.resumeAutomatic(MirrorReturnGate.Decision.WAIT, true, true, idle))
        assertFalse(state.resumeAutomatic(MirrorReturnGate.Decision.RECORD, true, true, idle.copy(cleanupOwners = 1)))
        assertTrue(state.resumeAutomatic(MirrorReturnGate.Decision.RECORD, true, true, idle))
        assertFalse(state.resumeAutomatic(MirrorReturnGate.Decision.RECORD, true, true, idle))
    }
    @Test fun aShortScreenOffThatDidNotEndTheRecordingNeverStartsAnotherSession() {
        val gate = armed()
        assertEquals(MirrorReturnGate.Decision.FOLLOW_EXISTING, gate.poll(2_000, true, true,
            idle.copy(recorderRunning = true, nativeIdle = false), record, sameRecordingContinues = true))
        assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(4_000))
    }
    @Test fun aLateScreenOffBroadcastCannotRearmAnAlreadyConsumedReturn() {
        val gate = armed()
        assertEquals(MirrorReturnGate.Decision.RECORD, gate.pollAt(2_000))
        gate.arm(record, screenUsable = true)
        assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(3_000))
        assertEquals(MirrorReturnGate.Decision.NONE, gate.pollAt(5_000))
    }
    @Test fun onlyTheKnownHealthyRecordingCanBeReattached() {
        assertTrue(MirrorExistingRecording.canRestore("old", "old", true, true, true, false, false))
        assertFalse(MirrorExistingRecording.canRestore("old", "other", true, true, true, false, false))
        assertFalse(MirrorExistingRecording.canRestore(null, null, true, true, true, false, false))
        assertFalse(MirrorExistingRecording.canRestore("old", "old", true, false, true, false, false))
        assertFalse(MirrorExistingRecording.canRestore("old", "old", true, true, true, true, false))
        assertFalse(MirrorExistingRecording.canRestore("old", "old", true, true, true, false, true))
    }
    @Test fun reattachingDoesNotGrantAFutureCameraStart() {
        val state = MirrorSleepRetention().apply { suspend() }
        assertFalse(state.restoreExistingRecording(true, false, true, true))
        assertFalse(state.restoreExistingRecording(true, true, true, false))
        assertTrue(state.restoreExistingRecording(true, true, true, true))
        assertFalse(state.resumeAutomatic(MirrorReturnGate.Decision.RECORD, true, true, idle))
    }
}
