package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.product.HomePreviewRecoveryPolicy.Decision.*
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreviewRecoveryPolicyTest {
    private val ready = HomePreviewRecoveryPolicy.State(foreground = true, permission = true, sourceReady = true)

    @Test fun aClosePastEightSecondsCanRestoreAtTwentyFiveSeconds() {
        val closing = ready.copy(cleanupOwners = 3, nativeIdle = false)
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(closing, 8_001))
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(closing.copy(cleanupOwners = 1), 24_999))
        assertEquals(RESTORE, HomePreviewRecoveryPolicy.decide(ready, 25_000))
    }

    @Test fun primaryStopAndServiceResetNeverBorrowAnAuxiliaryCamera() {
        assertEquals(STOP_OWN_PREVIEW, HomePreviewRecoveryPolicy.decide(ready.copy(recorderServiceRunning = true)))
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(ready.copy(cleanupOwners = 1, nativeIdle = false)))
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(ready.copy(nativeIdle = false)))
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(ready.copy(cleanupOwners = 1)))
    }

    @Test fun timeoutIsNotEvidenceAndANewRealReleaseCanRestore() {
        val blocked = ready.copy(cleanupOwners = 1, nativeIdle = false)
        assertEquals(TIMED_OUT, HomePreviewRecoveryPolicy.decide(blocked, 50_000))
        assertEquals(TIMED_OUT, HomePreviewRecoveryPolicy.decide(blocked, 90_000))
        assertEquals(RESTORE, HomePreviewRecoveryPolicy.decide(ready, 90_001))
    }

    @Test fun revokedVisibilityPermissionSourceOrIntentCannotRestoreAfterLateClose() {
        listOf(ready.copy(foreground = false), ready.copy(permission = false), ready.copy(sourceReady = false),
            ready.copy(recorderActive = true), ready.copy(otherOwnerActive = true), ready.copy(handoffPending = true))
            .forEach { assertEquals(STOP_OWN_PREVIEW, HomePreviewRecoveryPolicy.decide(it, 25_000)) }
    }

    @Test fun unconfirmedFlagsNeedARealSettlementEvenIfLedgerIsEmpty() {
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(ready.copy(cleanupPending = true)))
        assertEquals(WAIT_RELEASE, HomePreviewRecoveryPolicy.decide(ready.copy(cleanupUnconfirmed = true)))
        assertEquals(RESTORE, HomePreviewRecoveryPolicy.decide(ready))
    }

    @Test fun delayedCleanupNotificationDoesNotCloseOrRetryThePreviewJustRequested() {
        val own = ready.copy(ownPreviewRequested = true, nativeIdle = false)
        assertEquals(KEEP_OWN_PREVIEW, HomePreviewRecoveryPolicy.decide(own.copy(cleanupOwners = 1)))
        assertEquals(KEEP_OWN_PREVIEW, HomePreviewRecoveryPolicy.decide(own))
        assertEquals(STOP_OWN_PREVIEW, HomePreviewRecoveryPolicy.decide(own.copy(foreground = false)))
        assertEquals(STOP_OWN_PREVIEW, HomePreviewRecoveryPolicy.decide(own.copy(otherOwnerActive = true)))
    }
}
