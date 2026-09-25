package com.dante.zeekrcheck.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CardControlTest {
    private val now = Instant.parse("2026-09-19T12:00:00Z")
    private fun status(lock: String = "1", trunk: String = "0", port: String = "0", source: Long? = null) = Probe(
        Endpoint.STATUS, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("""{"additionalVehicleStatus":{
            "drivingSafetyStatus":{"centralLockingStatus":"$lock","trunkOpenStatus":"$trunk","trunkLockStatus":"0","updateTime":$source},
            "electricVehicleStatus":{"chargeLidDcAcStatus":"$port"}}}"""))

    @Test fun lockTapReadsThenSendsExactlyOneFixedUnlock() = runTest {
        val order = mutableListOf<String>()
        CardControl.run("lock", "已锁", { order += "read"; status() }, { order += "publish" }, { order += it.name }, { fail(it) })
        assertEquals(listOf("read", "publish", "UNLOCK"), order)
    }
    @Test fun changedOrMissingStateDoesNotReverseIntentOrSendAnything() = runTest {
        for (probe in listOf(status(lock="0"), status(lock="255"), Probe(Endpoint.STATUS,ProbeOutcome.NETWORK,now))) {
            var message = ""
            CardControl.run("lock", "已锁", { probe }, {}, { fail("must not send") }, { message = it })
            assertTrue(message.isNotBlank())
        }
    }
    @Test fun unknownStateOnlyReadsOnFirstTap() = runTest {
        CardControl.run("lock", null, { status() }, {}, { fail("unknown must not choose an action") }, { assertTrue(it.contains("再点一次")) })
    }
    @Test fun explicitOpenPortWorksWithUnavailableStateButNeverInfersClosed() = runTest {
        assertEquals(BodyAction.PORT_OPEN,CardControl.target("port",null))
        for(probe in listOf(status(port="9"),status(port="0"))) {
            val sent=mutableListOf<BodyAction>()
            CardControl.run("port",null,{probe},{},{sent+=it},{fail(it)})
            assertEquals(listOf(BodyAction.PORT_OPEN),sent)
        }
        for(probe in listOf(status(port="1"),Probe(Endpoint.STATUS,ProbeOutcome.NETWORK,now))) {
            CardControl.run("port",null,{probe},{},{fail("must not send")},{assertTrue(it.isNotBlank())})
        }
        assertEquals(ReadEvidence.MISSING,Capabilities.parse(listOf(status(port="9"))).first { it.id=="port" }.read)
    }
    @Test fun aRepeatedFixedIntentAfterStateChangedDoesNotSendTwice() = runTest {
        var state = status(); var count = 0
        repeat(2) { CardControl.run("lock", "已锁", { state }, {}, { count++; state = status(lock="0") }, {}) }
        assertEquals(1, count)
    }
    @Test fun latchUnlockIsNeverTreatedAsPoweredTailgateClose() = runTest {
        assertEquals(BodyAction.TRUNK_UNLOCK, CardControl.target("trunk", "关闭"))
        CardControl.run("trunk", "打开", { status(trunk="1") }, {}, { fail("unverified powered-close command") }, { assertTrue(it.contains("尚未接入")) })
        assertEquals("关闭", Capabilities.parse(listOf(status())).first { it.id == "trunk" }.value)
    }
    @Test fun openUnknownValuesNeverBecomeClosedFromFallback() {
        val values = Capabilities.parse(listOf(status(trunk="255",port="9")))
        assertEquals(ReadEvidence.MISSING, values.first { it.id == "trunk" }.read)
        assertEquals(ReadEvidence.MISSING, values.first { it.id == "port" }.read)
    }
    @Test fun acceptanceOrUntimestampedUnchangedCacheCannotConfirmOperation() {
        val pending = PendingBody(BodyAction.UNLOCK, now.minusSeconds(2).toEpochMilli(), "已锁")
        assertFalse(pending.matches(status()))
        assertTrue(pending.matches(status(lock="0")))
        assertFalse(pending.copy(before="未锁").matches(status(lock="0")))
        assertFalse(pending.matches(status(lock="0",source=now.minusSeconds(600).toEpochMilli())))
        assertFalse(pending.matches(status(lock="0",source=now.plusSeconds(600).toEpochMilli())))
        assertFalse(pending.matches(Probe(Endpoint.STATUS,ProbeOutcome.TIMEOUT,now,status(lock="0").data)))
    }
    @Test fun pendingBodySurvivesRestartAndClearsOnlyOnAChangedReadback() {
        val before = VehicleOverview(pendingBody=PendingBody(BodyAction.UNLOCK,now.minusSeconds(2).toEpochMilli(),"已锁"))
        val restored = VehicleOverview.decode(before.encode())
        assertEquals(before,restored)
        fun report(probe: Probe) = Report(false,now,now,"synthetic",emptyList(),listOf(probe))
        assertNotNull(restored.updated(report(status())).pendingBody)
        assertNull(restored.updated(report(status(lock="0"))).pendingBody)
    }
    @Test fun refreshReportsUnchangedOldTemperatureWithoutChangingItsAge() {
        val temp = OverviewReading("25.4 °C",now.minusSeconds(3600).toEpochMilli(),now.toEpochMilli())
        val overview = VehicleOverview(readings=mapOf("cabin_temperature" to temp))
        assertEquals("25.4°C",overview.cabin(now))
        assertEquals("上次车温",overview.cabinCaption(now))
        assertEquals("已查询云端 · 车温暂无新上报",overview.refreshResult(temp,now))
        assertFalse(temp.fresh(now))
        assertEquals("neutral",overview.thermalState(now))
    }
}
