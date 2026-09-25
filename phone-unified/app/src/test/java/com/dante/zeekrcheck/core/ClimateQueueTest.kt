package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClimateQueueTest {
    private fun left(value: Int) = ClimateTarget(ClimateChannel.FRONT_LEFT, value)
    @Test fun rapidTapsSendOnlyTheFinalAbsoluteLevel() = runTest {
        val sent = mutableListOf<ClimateTarget>()
        val queue = ClimateQueue(this) { target, _ -> sent.add(target); ClimateResult.MATCHED }
        queue.submit(left(1)); queue.submit(left(2)); queue.submit(left(3))
        runCurrent(); advanceTimeBy(599); runCurrent()
        assertTrue(sent.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(left(3)), sent)
        assertEquals(ClimatePhase.MATCHED, queue.state.value.phase)
    }
    @Test fun changingTargetsDuringLoadingNeverOverlapsAndReplacesIntermediateTargets() = runTest {
        val gate = CompletableDeferred<ClimateResult>()
        val sent = mutableListOf<ClimateTarget>()
        var active = 0; var maximum = 0
        val queue = ClimateQueue(this) { target, accepted ->
            sent.add(target); active++; maximum = maxOf(maximum, active); accepted()
            val result = if (sent.size == 1) gate.await() else ClimateResult.MATCHED
            active--; result
        }
        queue.submit(left(1)); runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(ClimatePhase.WAITING, queue.state.value.phase)
        queue.submit(left(2)); queue.submit(left(3))
        val right = ClimateTarget(ClimateChannel.FRONT_RIGHT, 2)
        queue.submit(right)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(left(1)), sent)
        gate.complete(ClimateResult.MATCHED); advanceUntilIdle()
        assertEquals(listOf(left(1), left(3), right), sent)
        assertEquals(1, maximum)
    }
    @Test fun duplicateTargetWhileInFlightIsNotSentTwice() = runTest {
        val gate = CompletableDeferred<ClimateResult>(); var calls = 0
        val queue = ClimateQueue(this) { _, _ -> calls++; gate.await() }
        queue.submit(left(3)); runCurrent(); advanceTimeBy(600); runCurrent()
        queue.submit(left(3)); gate.complete(ClimateResult.MATCHED); advanceUntilIdle()
        assertEquals(1, calls)
    }
    @Test fun ambiguousTimeoutHaltsAllPendingWritesAndNeverRetries() = runTest {
        var calls = 0
        val queue = ClimateQueue(this) { _, _ -> calls++; withTimeout(1_000) { awaitCancellation() } }
        queue.submit(left(1)); runCurrent(); advanceTimeBy(600); runCurrent()
        queue.submit(left(3)); advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(queue.state.value.halted)
        assertEquals(ClimatePhase.UNKNOWN, queue.state.value.phase)
        assertFalse(queue.submit(left(2)))
        queue.acknowledgeOutcome(); advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(queue.state.value.pending.isEmpty())
    }
    @Test fun droppingPendingDoesNotClaimToCancelSentAction() = runTest {
        val gate = CompletableDeferred<ClimateResult>(); val sent = mutableListOf<ClimateTarget>()
        val queue = ClimateQueue(this) { target, _ -> sent.add(target); gate.await() }
        queue.submit(left(1)); runCurrent(); advanceTimeBy(600); runCurrent()
        queue.submit(left(3)); queue.discardPending()
        assertEquals(left(1), queue.state.value.inFlight)
        assertTrue(queue.state.value.pending.isEmpty())
        gate.complete(ClimateResult.MATCHED); advanceUntilIdle()
        assertEquals(listOf(left(1)), sent)
    }
    @Test fun closeDiscardsUnsentWorkAndCannotBeReactivated() = runTest {
        var calls = 0
        val queue = ClimateQueue(this) { _, _ -> calls++; ClimateResult.MATCHED }
        queue.submit(left(2)); queue.close(); advanceUntilIdle()
        assertEquals(0, calls); assertFalse(queue.submit(left(3)))
    }
    @Test fun acceptedTemperatureCanContinueWithoutManualSetpointConfirmation() = runTest {
        val sent = mutableListOf<ClimateTarget>()
        val queue = ClimateQueue(this) { target, _ -> sent.add(target); ClimateResult.NEEDS_CHECK }
        val temp = ClimateTarget(ClimateChannel.AC, 22)
        queue.submit(temp); queue.submit(left(3)); advanceUntilIdle()
        assertEquals(listOf(temp, left(3)), sent)
        assertEquals(ClimatePhase.NEEDS_CHECK, queue.state.value.phase)
        assertFalse(queue.state.value.halted)
    }
}
