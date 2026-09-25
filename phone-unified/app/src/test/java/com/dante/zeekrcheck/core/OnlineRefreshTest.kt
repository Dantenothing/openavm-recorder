package com.dante.zeekrcheck.core

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OnlineRefreshTest {
    private val base = 1_800_000_000_000L
    private fun status(source: Long = base - 3_600_000, temperature: Double = 19.9) = Probe(
        Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(base), Json.parseToJsonElement(
            """{"additionalVehicleStatus":{"climateStatus":{"interiorTemp":$temperature,"updateTime":$source}}}"""))

    @Test fun newGroupTimeWithSameTemperatureEndsWaitAndClosesPresence() = runTest {
        val calls = mutableListOf<PresenceType>()
        val next = status(base + 3_000)
        var released = false
        val result = OnlineRefreshFlow(send = { calls += it; base + 3_000 }, read = { next },
            now = { Instant.ofEpochMilli(base + testScheduler.currentTime) }, afterExit = { released = true }).run(status())
        assertEquals(listOf(PresenceType.ENTER, PresenceType.POLL, PresenceType.POLL, PresenceType.EXIT), calls)
        assertSame(next, result.probe)
        assertEquals("已收到新车况上报 · 温度数值未变", result.message)
        assertTrue(released)
    }

    @Test fun newerHeartbeatWithoutNewClimateDoesNotRefreshTheTemperatureClock() = runTest {
        val original = status()
        val calls = mutableListOf<PresenceType>()
        var reads = 0
        val result = OnlineRefreshFlow(send = { calls += it; base + 10_000 },
            read = { reads++; original }, now = { Instant.ofEpochMilli(base + testScheduler.currentTime) }).run(original)
        assertSame(original, result.probe)
        assertEquals("已查询云端 · 车辆暂无新上报", result.message)
        assertEquals(2, reads)
        assertEquals(40_000L, testScheduler.currentTime)
        assertEquals(listOf(PresenceType.ENTER, PresenceType.POLL, PresenceType.POLL, PresenceType.POLL, PresenceType.EXIT), calls)
    }

    @Test fun cancellationStillPairsExitAndClearsItsLease() = runTest {
        val calls = mutableListOf<PresenceType>()
        var lease = false
        val work = launch { OnlineRefreshFlow(send = { calls += it; null }, read = { status() },
            beforeEnter = { lease = true }, afterExit = { lease = false }).run(status()) }
        runCurrent()
        assertTrue(lease)
        work.cancelAndJoin()
        assertEquals(PresenceType.EXIT, calls.last())
        assertFalse(lease)
    }

    @Test fun ambiguousEntryStillAttemptsExitWithoutRepeatingEntry() = runTest {
        val calls = mutableListOf<PresenceType>()
        val result = OnlineRefreshFlow(send = {
            calls += it
            if (it == PresenceType.ENTER) throw CheckFailure(ProbeOutcome.NETWORK)
            null
        }, read = { fail("No status reads after failed entry"); status() }).run(status())
        assertEquals(listOf(PresenceType.ENTER, PresenceType.EXIT), calls)
        assertEquals(ProbeOutcome.NETWORK, result.outcome)
        assertTrue(result.exitConfirmed)
    }

    @Test fun failedExitIsBoundedAndLeavesRecoveryMarker() = runTest {
        var lease = false
        var exits = 0
        val result = OnlineRefreshFlow(send = {
            if (it == PresenceType.EXIT) { exits++; throw CheckFailure(ProbeOutcome.NETWORK) }
            null
        }, read = { status(base + 3_000) }, now = { Instant.ofEpochMilli(base + testScheduler.currentTime) },
            beforeEnter = { lease = true }, afterExit = { lease = false }).run(status())
        assertFalse(result.exitConfirmed)
        assertTrue(lease)
        assertEquals(2, exits)
        assertTrue(result.message.endsWith("在线连接待收尾"))
    }

    @Test fun startingAnotherOperationEndsWaitingWithinOneSecond() = runTest {
        var active = true
        val calls = mutableListOf<PresenceType>()
        val work = async { OnlineRefreshFlow(send = { calls += it; null },
            read = { fail("Control started before next status read"); status() }, current = { active }).run(status()) }
        runCurrent(); advanceTimeBy(5_000); active = false; advanceUntilIdle()
        assertEquals(ProbeOutcome.CANCELLED, work.await().outcome)
        assertTrue(testScheduler.currentTime <= 6_000)
        assertEquals(listOf(PresenceType.ENTER, PresenceType.POLL, PresenceType.EXIT), calls)
    }

    @Test fun recentDataSkipsOnlineAndFutureOrMissingTimesDoNotBecomeRecent() {
        assertFalse(OnlineRefreshFlow.needed(status(base - 30_000), Instant.ofEpochMilli(base)))
        assertTrue(OnlineRefreshFlow.needed(status(), Instant.ofEpochMilli(base)))
        assertTrue(OnlineRefreshFlow.needed(status(base + 60_000), Instant.ofEpochMilli(base)))
        assertFalse(OnlineRefreshFlow.needed(Probe(Endpoint.STATUS, ProbeOutcome.NETWORK, Instant.ofEpochMilli(base)), Instant.ofEpochMilli(base)))
    }

    @Test fun ownDeadlineStillClosesAnAmbiguousOnlineEntry() = runTest {
        val calls = mutableListOf<PresenceType>()
        val result = OnlineRefreshFlow(send = {
            calls += it; if (it == PresenceType.ENTER) delay(60_000); null
        }, read = { fail("Entry did not complete"); status() }).run(status())
        assertTrue(result.exitConfirmed)
        assertEquals(45_000L, testScheduler.currentTime)
        assertEquals(listOf(PresenceType.ENTER, PresenceType.EXIT), calls)
    }

    @Test fun aRenderingFailureCannotPreventExit() = runTest {
        var exited = false
        OnlineRefreshFlow(send = { if (it == PresenceType.EXIT) exited = true; null }, read = { status(base + 3_000) },
            now = { Instant.ofEpochMilli(base + testScheduler.currentTime) },
            progress = { if (it == "正在结束本次刷新…") error("synthetic UI error") }).run(status())
        assertTrue(exited)
    }

    @Test fun futureClimateTimestampCannotProveNewVehicleReport() = runTest {
        val result = OnlineRefreshFlow(send = { null }, read = { status(base + 3_600_000) },
            now = { Instant.ofEpochMilli(base + testScheduler.currentTime) }).run(status())
        assertEquals("已查询云端 · 车辆暂无新上报", result.message)
    }

    @Test fun manualRefreshQueuedBehindBackgroundIsNotLostAndDuplicatesJoin() = runTest {
        val coordinator = OverviewReadCoordinator<String>()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val background = async { coordinator.read("account/vehicle", false) {
            order += "background"; started.complete(Unit); release.await(); "background-result"
        } }
        started.await()
        val manual = async { coordinator.read("account/vehicle", true) { order += "manual"; delay(100); "manual-result" } }
        val duplicate = async { coordinator.read("account/vehicle", true) { fail("Duplicate request"); "wrong" } }
        runCurrent(); release.complete(Unit)
        assertEquals("background-result", background.await())
        assertEquals("manual-result", manual.await())
        assertEquals("manual-result", duplicate.await())
        assertEquals(listOf("background", "manual"), order)
    }
}
