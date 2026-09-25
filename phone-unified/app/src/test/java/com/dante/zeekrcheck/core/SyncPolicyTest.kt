package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class SyncPolicyTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun concurrentReadsShareTransportButLaterReadGetsNewData() = runTest {
        val reader = ReadCoalescer<String, Int>(); var calls = 0
        suspend fun fetch(): Int { calls++; delay(100); return calls }
        val first = async { reader.read("car/status", ::fetch) }
        val second = async { reader.read("car/status", ::fetch) }
        advanceUntilIdle()
        assertEquals(1, first.await()); assertEquals(1, second.await()); assertEquals(1, calls)
        assertEquals(2, reader.read("car/status", ::fetch))
    }
    @Test fun cancelledFollowerDoesNotCancelOwnerAndOwnerFailureDoesNotPoisonNextRead() = runTest {
        val reader = ReadCoalescer<String, Int>(); val ready = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val owner = async { reader.read("car") { ready.complete(Unit); finish.await(); 7 } }
        ready.await()
        val follower = launch { reader.read("car") { error("must coalesce") } }
        yield(); follower.cancelAndJoin(); finish.complete(Unit); assertEquals(7, owner.await())
        try { reader.read("car") { throw IllegalStateException("synthetic") }; fail() } catch (_: IllegalStateException) { }
        assertEquals(8, reader.read("car") { 8 })
    }
    @Test fun removedWidgetsStopTheirDemandButNotEnabledGuard() {
        assertTrue(SyncPolicy.needsPeriodic(true, true, true, false))
        assertFalse(SyncPolicy.needsPeriodic(true, false, true, false))
        assertTrue(SyncPolicy.needsPeriodic(true, false, false, true))
        assertFalse(SyncPolicy.needsPeriodic(false, true, true, true))
    }
    @Test fun limitsSurviveRestartAndManualRefreshCannotBypassServerCooldown() {
        val limited = SyncStatus().finished(100_000, ProbeOutcome.RATE_LIMITED, "服务限流")
        val restored = SyncStatus.parse(Json.parseToJsonElement(limited.json().toString()))
        assertFalse(restored.permits(100_001, true)); assertFalse(restored.permits(100_001, false))
        assertTrue(restored.permits(1_000_001, false))
    }
    @Test fun failedQueryDoesNotAdvanceSuccessfulQueryTime() {
        val ok = SyncStatus().finished(100_000, ProbeOutcome.SUCCESS, "完成")
        val failed = ok.started(120_000, SyncReason.PERIODIC).finished(125_000, ProbeOutcome.TIMEOUT, "超时")
        assertEquals(100_000L, failed.success); assertEquals(125_000L, failed.completed)
        assertFalse(failed.permits(126_000, false)); assertTrue(failed.permits(126_000, true))
        assertFalse(ok.permits(130_000, false)); assertTrue(ok.permits(130_000, true))
    }
}
