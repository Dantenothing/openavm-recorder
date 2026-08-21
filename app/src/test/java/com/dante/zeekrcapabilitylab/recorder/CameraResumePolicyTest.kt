package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.CameraResumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraResumePolicyTest {

    private val armedAt = 1_000_000L

    private fun attempt(
        availableId: String = "2",
        targetId: String = "2",
        armed: Long? = armedAt,
        lastAttempt: Long? = null,
        attempts: Int = 0,
        now: Long = armedAt + 5_000L,
    ) = CameraResumePolicy.shouldAttempt(
        availableCameraId = availableId,
        targetCameraId = targetId,
        armedAtMs = armed,
        lastAttemptAtMs = lastAttempt,
        attemptsMade = attempts,
        nowMs = now,
    )

    @Test
    fun firstAttemptFiresImmediatelyForTheSessionCamera() {
        assertTrue(attempt())
    }

    @Test
    fun otherCameraIdsNeverTriggerAnAttempt() {
        assertFalse(attempt(availableId = "5"))
    }

    @Test
    fun nothingFiresWhileNotArmed() {
        assertFalse(attempt(armed = null))
    }

    @Test
    fun theWaitingWindowExpires() {
        assertTrue(attempt(now = armedAt + CameraResumePolicy.RESUME_WINDOW_MS))
        assertFalse(attempt(now = armedAt + CameraResumePolicy.RESUME_WINDOW_MS + 1L))
        assertFalse(CameraResumePolicy.windowExpired(null, armedAt))
        assertTrue(
            CameraResumePolicy.windowExpired(armedAt, armedAt + CameraResumePolicy.RESUME_WINDOW_MS + 1L),
        )
    }

    @Test
    fun theAttemptCapIsEnforced() {
        assertFalse(attempt(attempts = CameraResumePolicy.MAX_ATTEMPTS))
        assertTrue(attempt(attempts = CameraResumePolicy.MAX_ATTEMPTS - 1, lastAttempt = armedAt - 120_000L))
    }

    @Test
    fun repeatAttemptsRequireEscalatingSpacing() {
        // After one attempt, a second needs 10 s of spacing.
        assertFalse(attempt(attempts = 1, lastAttempt = armedAt, now = armedAt + 9_999L))
        assertTrue(attempt(attempts = 1, lastAttempt = armedAt, now = armedAt + 10_000L))
        // Deep into the ladder the spacing clamps to the last interval (60 s).
        assertFalse(attempt(attempts = 7, lastAttempt = armedAt, now = armedAt + 59_999L))
        assertTrue(attempt(attempts = 7, lastAttempt = armedAt, now = armedAt + 60_000L))
    }

    @Test
    fun spacingLadderClampsInsteadOfCrashing() {
        assertEquals(0L, CameraResumePolicy.minIntervalBeforeAttempt(0))
        assertEquals(10_000L, CameraResumePolicy.minIntervalBeforeAttempt(1))
        assertEquals(60_000L, CameraResumePolicy.minIntervalBeforeAttempt(50))
        assertEquals(0L, CameraResumePolicy.minIntervalBeforeAttempt(-3))
    }
}
