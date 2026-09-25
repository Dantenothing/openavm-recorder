package com.dante.zeekrcapabilitylab.service.recorder

import org.junit.Assert.*
import org.junit.Test

class NativeFileRotationTest {
    @Test fun currentProcessNativeFilesAreNeverRecoveredOrDeleted() {
        listOf(null, 0L, 1L, 210_000_000L).forEach { bytes ->
            assertEquals(NativePendingRecoveryPolicy.Action.RETAIN,
                NativePendingRecoveryPolicy.decide(true, bytes, true))
        }
    }

    @Test fun recoveryRemovesOnlyConfirmedEmptyVideoOnlyEntries() {
        assertEquals(NativePendingRecoveryPolicy.Action.REMOVE_EMPTY,
            NativePendingRecoveryPolicy.decide(false, 0, true))
        assertEquals(NativePendingRecoveryPolicy.Action.RETAIN,
            NativePendingRecoveryPolicy.decide(false, null, true))
        assertEquals(NativePendingRecoveryPolicy.Action.RETAIN,
            NativePendingRecoveryPolicy.decide(false, 0, false))
    }

    @Test fun previousProcessNonemptyVideoCanBeRecoveredButPartialPublicationIsRetained() {
        assertEquals(NativePendingRecoveryPolicy.Action.RECOVER,
            NativePendingRecoveryPolicy.decide(false, 1, true))
        assertEquals(NativePendingRecoveryPolicy.Action.RETAIN,
            NativePendingRecoveryPolicy.decide(false, 1, false))
    }
    @Test fun outputIsOnlyPromotedAfterNativeAcknowledgement() {
        val state = NativeFileRotation("first")
        assertTrue(state.approaching())
        assertTrue(state.offer("second"))
        assertEquals("first", state.current)
        assertEquals("second", state.queued)
        assertEquals("first" to "second", state.switched())
        assertEquals("second", state.current)
        assertEquals(1, state.switches)
        assertNull(state.queued)
    }

    @Test fun duplicateApproachingCannotAllocateTwoNextFiles() {
        val state = NativeFileRotation("first")
        assertTrue(state.approaching())
        assertFalse(state.approaching())
        assertTrue(state.offer("second"))
        assertFalse(state.approaching())
        assertFalse(state.offer("unexpected"))
        assertEquals(listOf("first", "second"), state.owned)
    }

    @Test fun stoppingDuringPreparationRejectsTheLateUnusedOutput() {
        val state = NativeFileRotation("first")
        assertTrue(state.approaching())
        state.stop()
        assertFalse(state.offer("late"))
        assertEquals(listOf("first"), state.owned)
    }

    @Test fun stoppingBeforeSwitchCallbackRetainsBothPotentiallyWrittenFiles() {
        val state = NativeFileRotation("first")
        assertTrue(state.approaching())
        assertTrue(state.offer("second"))
        state.stop()
        assertNull(state.switched())
        assertEquals(listOf("first", "second"), state.owned)
        assertEquals("second", state.queued)
    }

    @Test fun noCallbackCanCreateOrReuseAnUnownedFile() {
        val state = NativeFileRotation("first")
        assertNull(state.switched())
        assertFalse(state.offer("unsolicited"))
        assertEquals(0, state.switches)
        assertEquals(listOf("first"), state.owned)
    }

    @Test fun boundAppliesToAllRetainedFilesNotJustThePendingOne() {
        val state = NativeFileRotation("first", limit = 2)
        assertTrue(state.approaching())
        assertTrue(state.offer("second"))
        state.switched()
        assertFalse(state.approaching())
        assertEquals(2, state.owned.size)
    }

    @Test fun byteLimitUsesBitsToBytesWithoutIntegerOverflow() {
        assertEquals(210_000_000L, NativeFileRotationPolicy.maxBytes(28_000_000, 60))
        assertEquals(630_000_000L, NativeFileRotationPolicy.maxBytes(28_000_000, 180))
    }
}
