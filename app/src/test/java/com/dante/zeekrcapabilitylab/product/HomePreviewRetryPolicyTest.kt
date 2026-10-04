package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import org.junit.Assert.*
import org.junit.Test

class HomePreviewRetryPolicyTest {
    private val failedShell = HomePreviewRetryPolicy.State(
        source = RecordingSourceRole.SURROUND, floatingActive = true, floatingFailed = true, floatingBusy = false,
        foreground = true, permission = true, recording = false, recorderRunning = false, previewRunning = false,
        otherOwnerActive = false, otherOwnerFailed = false, transitionPending = false, cleanupPending = false,
        cleanupUnconfirmed = false, cleanupOwners = 0, nativeIdle = true, glIdle = true,
    )

    @Test fun failedActiveShellAllowsAnExplicitSameSourceRetryAfterActualRelease() {
        assertTrue(HomePreviewRetryPolicy.visible(failedShell))
        assertTrue(HomePreviewRetryPolicy.allowed(failedShell))
        assertTrue(HomePreviewRetryPolicy.allowed(failedShell.copy(source = RecordingSourceRole.CABIN)))
        assertFalse(HomePreviewRetryPolicy.allowed(failedShell.copy(source = RecordingSourceRole.IR)))
    }

    @Test fun healthyPreviewAndClosedControlsDoNotOfferRetry() {
        assertFalse(HomePreviewRetryPolicy.visible(failedShell.copy(floatingFailed = false)))
        assertFalse(HomePreviewRetryPolicy.visible(failedShell.copy(floatingActive = false)))
    }

    @Test fun allBusyAndUnconfirmedOwnersKeepTheVisibleControlDisabled() {
        listOf(failedShell.copy(recording = true), failedShell.copy(recorderRunning = true),
            failedShell.copy(previewRunning = true), failedShell.copy(floatingBusy = true),
            failedShell.copy(otherOwnerActive = true), failedShell.copy(otherOwnerFailed = true),
            failedShell.copy(transitionPending = true), failedShell.copy(cleanupPending = true),
            failedShell.copy(cleanupUnconfirmed = true), failedShell.copy(cleanupOwners = 1),
            failedShell.copy(nativeIdle = false), failedShell.copy(glIdle = false)).forEach {
            assertTrue(HomePreviewRetryPolicy.visible(it))
            assertFalse(HomePreviewRetryPolicy.allowed(it))
        }
    }

    @Test fun revokedPermissionOrForegroundAtClickTimeCannotConsumeStaleEligibility() {
        assertTrue(HomePreviewRetryPolicy.allowed(failedShell))
        assertFalse(HomePreviewRetryPolicy.allowed(failedShell.copy(permission = false)))
        assertFalse(HomePreviewRetryPolicy.allowed(failedShell.copy(foreground = false)))
        assertFalse(HomePreviewRetryPolicy.allowed(failedShell.copy(floatingBusy = true)))
    }
}
