package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test

class WidgetPinAttemptTest {
    private val requested = WidgetPinAttempt(setOf(17), 1_000, WidgetPinPhase.WAITING)

    @Test fun existingWidgetsDoNotProveThatAnotherWasAdded() {
        assertEquals(WidgetPinPhase.WAITING, requested.observe(setOf(17), 1_500).phase)
    }
    @Test fun acceptedButIgnoredRequestEventuallyOffersManualAddition() {
        assertEquals(WidgetPinPhase.NOT_CONFIRMED, requested.observe(setOf(17), 7_000).phase)
    }
    @Test fun aNewBoundWidgetConfirmsTheRequest() {
        assertEquals(WidgetPinPhase.ADDED, requested.observe(setOf(17, 23), 1_500).phase)
    }
    @Test fun aLateConfirmationOrManualAdditionCanStillBeRecognized() {
        val waitingForManual = requested.observe(setOf(17), 7_000)
        assertEquals(WidgetPinPhase.ADDED, waitingForManual.observe(setOf(17, 23), 20_000).phase)
        assertEquals(WidgetPinPhase.ADDED,
            requested.copy(phase = WidgetPinPhase.UNSUPPORTED).observe(setOf(17, 23), 20_000).phase)
    }
    @Test fun removalOfAnExistingWidgetIsNotAConfirmation() {
        assertEquals(WidgetPinPhase.NOT_CONFIRMED, requested.observe(emptySet(), 7_000).phase)
    }
    @Test fun missingBaselineCannotTurnExistingWidgetsIntoFalseSuccess() {
        val failed = WidgetPinAttempt(null, 1_000, WidgetPinPhase.FAILED)
        assertEquals(WidgetPinPhase.FAILED, failed.observe(setOf(17), 7_000).phase)
    }
}
