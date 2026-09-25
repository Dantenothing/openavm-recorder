package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorControlPolicyTest {
    @Test fun cabinWaitsForEveryOwnerAndConfirmedRecordingStopJustLikeRecord() {
        for (held in 0..5) {
            val gate = MirrorHandoffGate(); val request = gate.begin(MirrorHandoffGate.Target.CABIN, 0, "surround")!!
            assertEquals(MirrorHandoffGate.Decision.WAIT, poll(gate, request.token, recorderGone = held != 0,
                previewGone = held != 1, auxiliaryGone = held != 2, nativeIdle = held != 3, glIdle = held != 4, stopConfirmed = held != 5))
            assertEquals(MirrorHandoffGate.Decision.START, poll(gate, request.token))
        }
    }
    @Test fun compactClockDoesNotResetAtMinuteOrHourBoundaries() {
        assertEquals("00:00", MirrorRecordingClock.text(-1))
        assertEquals("01:01", MirrorRecordingClock.text(61_999))
        assertEquals("1:01:01", MirrorRecordingClock.text(3_661_000))
    }
    private fun poll(gate: MirrorHandoffGate, token: Long, now: Long = 100,
                     allowed: Boolean = true, recorderGone: Boolean = true, previewGone: Boolean = true,
                     auxiliaryGone: Boolean = true, nativeIdle: Boolean = true, glIdle: Boolean = true,
                     stopConfirmed: Boolean = true) = gate.poll(token, now, allowed, recorderGone, previewGone,
        auxiliaryGone, nativeIdle, glIdle, stopConfirmed)

    @Test fun noUserCommandMeansNoCameraStart() {
        assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(MirrorHandoffGate(), 1))
    }
    @Test fun everyOwnerMustActuallyFinishBeforeRecordCanBegin() {
        for (held in 0..5) {
            val gate = MirrorHandoffGate(); val request = gate.begin(MirrorHandoffGate.Target.RECORD, 0)!!
            assertEquals(MirrorHandoffGate.Decision.WAIT, poll(gate, request.token, recorderGone = held != 0,
                previewGone = held != 1, auxiliaryGone = held != 2, nativeIdle = held != 3, glIdle = held != 4, stopConfirmed = held != 5))
            assertEquals(request, gate.pending)
            assertEquals(MirrorHandoffGate.Decision.START, poll(gate, request.token))
            assertNull(gate.pending)
            assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(gate, request.token))
        }
    }
    @Test fun doubleTapCannotReplaceOutstandingCommand() {
        val gate = MirrorHandoffGate(); val first = gate.begin(MirrorHandoffGate.Target.RECORD, 0)
        assertNull(gate.begin(MirrorHandoffGate.Target.PREVIEW, 1)); assertEquals(first, gate.pending)
    }
    @Test fun powerClosePermissionOrErrorRevokesCommandEvenIfReleaseFinished() {
        val gate = MirrorHandoffGate(); val request = gate.begin(MirrorHandoffGate.Target.PREVIEW, 0)!!
        assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(gate, request.token, allowed = false))
        assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(gate, request.token))
    }
    @Test fun timeoutDoesNotPretendUnconfirmedNativeCleanupSucceeded() {
        val gate = MirrorHandoffGate(100); val request = gate.begin(MirrorHandoffGate.Target.RECORD, 0)!!
        assertEquals(MirrorHandoffGate.Decision.WAIT, poll(gate, request.token, 99, nativeIdle = false))
        assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(gate, request.token, 100, nativeIdle = false))
        assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(gate, request.token, 101))
    }
    @Test fun staleCallbacksCannotConsumeOrCancelNewUserRequest() {
        val gate = MirrorHandoffGate(); val old = gate.begin(MirrorHandoffGate.Target.RECORD, 0)!!
        gate.cancel(); val fresh = gate.begin(MirrorHandoffGate.Target.PREVIEW, 50)!!
        assertEquals(MirrorHandoffGate.Decision.CANCEL, poll(gate, old.token))
        assertEquals(fresh, gate.pending)
        assertEquals(MirrorHandoffGate.Decision.START, poll(gate, fresh.token))
    }
    @Test fun stopMustBeAcknowledgedBeforePreviewResumes() {
        val gate = MirrorHandoffGate(); val request = gate.begin(MirrorHandoffGate.Target.PREVIEW, 0, "manual-session")!!
        assertEquals("manual-session", request.stoppedSession)
        assertEquals(MirrorHandoffGate.Decision.WAIT, poll(gate, request.token, stopConfirmed = false))
        assertEquals(MirrorHandoffGate.Decision.START, poll(gate, request.token))
    }
    @Test fun newProcessHasNoPendingResume() {
        MirrorHandoffGate().begin(MirrorHandoffGate.Target.PREVIEW, 0, "old-process")
        assertNull(MirrorHandoffGate().pending)
    }
    @Test fun uncalibratedDirectionsAreNeverInvented() {
        assertEquals(listOf(0, 4, 0, 0), MirrorDirectionMapping.resolve(listOf(1, 2, 3, 4), false, 0, 4, 0, 0))
    }
    @Test fun confirmedRearWinsOverConflictingOldSideCalibration() {
        assertEquals(listOf(1, 4, 3, 0), MirrorDirectionMapping.resolve(listOf(1, 2, 3, 4), true, 0, 4, 0, 0))
    }
    @Test fun explicitDirectionsSurviveDifferentLegacyOrder() {
        assertEquals(listOf(3, 4, 2, 1), MirrorDirectionMapping.resolve(listOf(1, 2, 3, 4), true, 3, 4, 2, 1))
    }
    @Test fun duplicateExplicitDirectionsDoNotProduceTwoRearButtons() {
        assertEquals(listOf(0, 4, 0, 1), MirrorDirectionMapping.resolve(listOf(1, 2, 3, 4), false, 4, 4, 4, 1))
    }
    @Test fun resizeKeepsTopLeftUnlessScreenEdgeRequiresClamp() {
        val a = MirrorWindowGeometry.fit(450, 20, 30, 1920, 1080, 270, true, 300, 800)
        val b = MirrorWindowGeometry.fit(650, 20, 30, 1920, 1080, 270, true, 300, 800)
        assertEquals(a.x, b.x); assertEquals(a.y, b.y); assertTrue(b.imageHeight > a.imageHeight)
        assertEquals(650, b.width)
    }
    @Test fun largeAndSmallScreensKeepTheWholeWindowWithinBounds() {
        for (screen in listOf(1920 to 1080, 1280 to 720, 320 to 600)) {
            val b = MirrorWindowGeometry.fit(9999, 9999, 9999, screen.first, screen.second, 270, true, 300, 800)
            assertTrue(b.x >= 0 && b.y >= 0 && b.x + b.width <= screen.first)
            assertTrue(b.y + b.imageHeight + 270 <= screen.second)
        }
    }
    @Test fun hidingVideoPreservesWidthAndRemovesOnlyImageHeight() {
        val open = MirrorWindowGeometry.fit(450, 20, 20, 1920, 1080, 270, true, 300, 800)
        val closed = MirrorWindowGeometry.fit(450, 20, 20, 1920, 1080, 170, false, 300, 800)
        assertEquals(open.width, closed.width); assertEquals(0, closed.imageHeight)
    }
}
