package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorSleepRetentionTest {
    private val idle = MirrorSleepResources()
    private fun parked() = MirrorSleepRetention().apply { suspend(); observe(idle) }

    @Test fun screenOffKeepsAControlSessionButRevokesCaptureAdmission() {
        val state = MirrorSleepRetention()
        assertTrue(state.suspend())
        assertTrue(state.suspended)
        assertEquals(MirrorSleepRetention.Phase.RELEASING, state.phase)
    }
    @Test fun screenOnAndResourceReleaseAloneNeverResumePreview() {
        val state = parked()
        repeat(20) { state.observe(idle) }
        assertEquals(MirrorSleepRetention.Phase.PAUSED, state.phase)
        assertFalse(state.resume(false, true, true, idle))
        assertTrue(state.suspended)
    }
    @Test fun everyUnreleasedOwnerBlocksTheReleasedLabelAndResume() {
        val blockers = listOf(idle.copy(recorderRunning = true), idle.copy(previewRunning = true),
            idle.copy(auxiliaryActive = true), idle.copy(cleanupOwners = 1),
            idle.copy(nativeIdle = false), idle.copy(glIdle = false), idle.copy(wakeLockHeld = true))
        blockers.forEach { resources ->
            val state = parked()
            state.observe(resources)
            assertEquals(resources.toString(), MirrorSleepRetention.Phase.RELEASING, state.phase)
            assertFalse(state.resume(true, true, true, resources))
        }
    }
    @Test fun unknownOwnerCountCannotBeTreatedAsIdle() { assertFalse(idle.copy(cleanupOwners = -1).released) }
    @Test fun onlyAnExplicitTapOnAnAttachedWindowWithUsableScreenCanResume() {
        val state = parked()
        assertFalse(state.resume(true, false, true, idle))
        assertFalse(state.resume(true, true, false, idle))
        assertTrue(state.resume(true, true, true, idle))
        assertEquals(MirrorSleepRetention.Phase.ACTIVE, state.phase)
        assertFalse(state.resume(true, true, true, idle))
    }
    @Test fun releaseIsRecheckedAtTapEvenIfEarlierObservationWasIdle() {
        val state = parked()
        assertFalse(state.resume(true, true, true, idle.copy(cleanupOwners = 1)))
        assertTrue(state.suspended)
    }
    @Test fun duplicateSleepEventsDoNotResetASettledPause() {
        val state = parked()
        assertFalse(state.suspend())
        assertEquals(MirrorSleepRetention.Phase.PAUSED, state.phase)
    }
    @Test fun manualCloseIsTerminalDespiteLateReleaseOrWake() {
        val state = parked()
        state.close(); state.observe(idle)
        assertFalse(state.suspend())
        assertFalse(state.resume(true, true, true, idle))
        assertEquals(MirrorSleepRetention.Phase.CLOSED, state.phase)
    }
    @Test fun secondTripRequiresANewTap() {
        val state = parked()
        assertTrue(state.resume(true, true, true, idle))
        assertTrue(state.suspend()); state.observe(idle)
        assertFalse(state.resume(false, true, true, idle))
    }
}
