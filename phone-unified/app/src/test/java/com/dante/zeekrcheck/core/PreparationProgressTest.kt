package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PreparationProgressTest {
    private val start=Instant.parse("2026-09-20T01:00:00Z").toEpochMilli()
    private fun session()=PreparationSession("synthetic",start,start+1_800_000,ComfortPreferences(),listOf(ClimateChannel.AC),initialTemperature=32.0)
    private fun reading(seconds:Long,temp:Double?=29.0,on:Boolean?=true)=ClimateSnapshot(acOn=on,cabinTemperature=temp,
        sourceTime=Instant.ofEpochMilli(start+seconds*1_000),fetchedAt=Instant.ofEpochMilli(start+seconds*1_000))

    @Test fun acceptedIsNotProofOfRunningAndPreStartOffReportDoesNotFinishIt() {
        val s=session()
        assertEquals(PreparationPhase.ACCEPTED,PreparationProgress.phase(s,start+1_000))
        assertFalse(PreparationProgress.button(s,start+1_000).contains("正在降温"))
        val next=PreparationProgress.observe(s,reading(5,on=false),start+5_000)
        assertEquals(PreparationPhase.ACCEPTED,next.phase); assertFalse(next.finished)
        assertTrue(PreparationProgress.resumable(next,start+5_000))
    }
    @Test fun coolingRequiresFreshPostStartPowerAndMeasuredChange() {
        val s=session()
        val running=PreparationProgress.observe(s,reading(5,temp=32.0),start+5_000)
        assertEquals(PreparationPhase.RUNNING,running.phase)
        val cooling=PreparationProgress.observe(running,reading(20),start+20_000)
        assertEquals(PreparationPhase.COOLING,cooling.phase)
        assertEquals("32.0 → 29.0°C",PreparationProgress.detail(cooling,start+20_000))
        val noPower=PreparationProgress.observe(s,reading(5,on=null),start+5_000)
        assertEquals(PreparationPhase.ACCEPTED,noPower.phase)
        assertEquals(PreparationPhase.ACCEPTED,PreparationProgress.observe(cooling,reading(30,on=null),start+30_000).phase)
        val withSeat=cooling.copy(channels=listOf(ClimateChannel.AC,ClimateChannel.FRONT_LEFT))
        assertEquals(PreparationPhase.STOPPED,PreparationProgress.observe(withSeat,reading(30,on=false),start+30_000).phase)
        val warm=PreparationProgress.observe(s.copy(initialTemperature=10.0),reading(5,temp=12.0),start+5_000)
        assertEquals(PreparationPhase.WARMING,warm.phase)
    }
    @Test fun missingOldFutureAndDuplicateSourcesCannotAdvanceProgress() {
        val s=session()
        for(snapshot in listOf(reading(-5),reading(100),reading(5).copy(sourceTime=null)))
            assertEquals(s,PreparationProgress.observe(s,snapshot,start+5_000))
        assertEquals(s,PreparationProgress.observe(s,reading(5),start+400_000))
        val next=PreparationProgress.observe(s,reading(5),start+5_000)
        assertEquals(next,PreparationProgress.observe(next,reading(5,temp=22.0),start+15_000))
        assertEquals(PreparationPhase.ACCEPTED,PreparationProgress.phase(next,start+306_000))
    }
    @Test fun readyRequiresTwoRecentInRangeObservationsSpacedByAMinute() {
        val first=PreparationProgress.observe(session(),reading(5,temp=22.2),start+5_000)
        assertEquals(PreparationPhase.RUNNING,first.phase)
        val quick=PreparationProgress.observe(first,reading(35,temp=22.1),start+35_000)
        assertNotEquals(PreparationPhase.READY,quick.phase)
        val ready=PreparationProgress.observe(quick,reading(65,temp=22.0),start+65_000)
        assertEquals(PreparationPhase.READY,ready.phase); assertFalse(ready.finished)
        val afterGap=PreparationProgress.observe(first,reading(405,temp=22.1),start+405_000)
        assertNotEquals(PreparationPhase.READY,afterGap.phase)
        assertTrue(PreparationProgress.inProgress(ready,start+65_000)) // no automatic stop command
    }
    @Test fun expiryIsNotAnOffConfirmationButNewOffAfterRunningIs() {
        val running=PreparationProgress.observe(session(),reading(5),start+5_000)
        assertEquals(PreparationPhase.EXPIRED,PreparationProgress.phase(running,running.deadline))
        assertFalse(PreparationProgress.detail(running,running.deadline).contains("已关闭"))
        val off=PreparationProgress.observe(running,reading(20,on=false),start+20_000)
        assertTrue(off.finished); assertEquals(PreparationPhase.STOPPED,off.phase)
        assertFalse(PreparationProgress.resumable(off,start+20_000))
    }
    @Test fun progressSurvivesSerializationAndOnlyObservationCanResume() {
        val cooling=PreparationProgress.observe(session(),reading(5),start+5_000)
        val state=AssistantState(activePreparation=cooling)
        assertEquals(state,AssistantState.parse(state.encode()))
        for(phase in listOf(PreparationPhase.READING,PreparationPhase.SENDING,PreparationPhase.UNKNOWN,PreparationPhase.FAILED))
            assertFalse(PreparationProgress.resumable(session().copy(phase=phase),start+1_000))
        assertTrue(PreparationProgress.resumable(cooling,start+10_000))
        val legacy=JsonObject(session().json().filterKeys { it !in setOf("phase","initialTemperature","lastTemperature","lastSource","stableSince") })
        assertEquals(PreparationPhase.ACCEPTED,PreparationSession.parse(legacy)!!.phase)
        val legacyFinished=JsonObject(legacy+mapOf("finished" to JsonPrimitive(true),"status" to JsonPrimitive("已到时限，停止结果待核实")))
        assertEquals(PreparationPhase.UNKNOWN,PreparationSession.parse(legacyFinished)!!.phase)
        assertNotEquals(PreparationPhase.STOPPED,PreparationProgress.phase(session().copy(phase=PreparationPhase.STOPPED,finished=true),start+2_000))
    }
    @Test fun interruptedReadsAndSendsAreDistinctAndAcceptedWorkIsNotReplayed() {
        assertEquals(PreparationPhase.FAILED,PreparationProgress.interrupted(session().copy(phase=PreparationPhase.READING)).phase)
        assertEquals(PreparationPhase.UNKNOWN,PreparationProgress.interrupted(session().copy(phase=PreparationPhase.SENDING)).phase)
        assertEquals(session(),PreparationProgress.interrupted(session()))
        val ready=session().copy(phase=PreparationPhase.READY,finished=true)
        assertEquals(PreparationPhase.READY,PreparationProgress.phase(ready,ready.deadline+1))
        assertTrue(PreparationProgress.detail(ready,start).contains("无需启动"))
    }
    @Test fun upgradeOnlyReleasesKnownAcceptedPreparationNotAmbiguousOrOtherCommands() {
        val accepted=AssistantState(operationPending=true,activePreparation=session(),history=listOf(OperationEntry(start+5_000,"空调 · 22°C · 30 分钟",CommandResult.ACCEPTED.label)))
        assertTrue(PreparationProgress.legacyAcceptedGate(accepted))
        for(entry in listOf(OperationEntry(start+5_000,"空调 · 22°C",CommandResult.UNKNOWN.label),
            OperationEntry(start+5_000,"解锁车辆",CommandResult.ACCEPTED.label),
            OperationEntry(start+200_000,"空调 · 22°C",CommandResult.ACCEPTED.label)))
            assertFalse(PreparationProgress.legacyAcceptedGate(accepted.copy(history=listOf(entry))))
        assertFalse(PreparationProgress.legacyAcceptedGate(accepted.copy(activePreparation=session().copy(stopRequested=true))))
        assertFalse(PreparationProgress.legacyAcceptedGate(accepted.copy(operationMessage="正在发送：解锁车辆")))
    }
}
