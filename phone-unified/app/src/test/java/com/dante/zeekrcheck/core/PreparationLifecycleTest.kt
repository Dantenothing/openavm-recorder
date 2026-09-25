package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PreparationLifecycleTest {
    private val start = Instant.parse("2026-09-20T06:00:00Z").toEpochMilli()
    private fun session() = PreparationSession("synthetic", start, start + 900_000, ComfortPreferences(),
        listOf(ClimateChannel.AC), phase = PreparationPhase.COOLING, initialTemperature = 32.0,
        lastTemperature = 29.0, lastSource = start + 5_000)
    private fun reading(on: Boolean?) = ClimateSnapshot(acOn = on, cabinTemperature = 28.0,
        sourceTime = Instant.ofEpochMilli(start + 20_000), fetchedAt = Instant.ofEpochMilli(start + 20_000))
    private fun status(mode: Int? = 13, source: Long? = start + 10_000, fetched: Long = start + 20_000,
                       speed: Int = 0, groupTime: String? = null) = Probe(Endpoint.STATUS,ProbeOutcome.SUCCESS,Instant.ofEpochMilli(fetched),buildJsonObject {
        source?.let { put("updateTime",it) }
        putJsonObject("basicVehicleStatus") { mode?.let { put("usageMode",it) }; put("speed",speed); groupTime?.let { put("updateTime",it) } }
        putJsonObject("additionalVehicleStatus") { putJsonObject("drivingSafetyStatus") { put("centralLockingStatus","0") } }
    })

    @Test fun completedRejectedAndNoOpSessionsReturnToOneTapPrepare() {
        for (phase in listOf(PreparationPhase.STOPPED, PreparationPhase.FAILED, PreparationPhase.EXPIRED, PreparationPhase.READY)) {
            val ended = session().copy(phase = phase, finished = true)
            assertTrue(phase.name, PreparationProgress.button(ended, start + 30_000).startsWith("一键备车\n"))
        }
    }
    @Test fun expiryReturnsTheButtonToIdleWithoutPretendingTheCarTurnedOff() {
        val s = session()
        assertTrue(PreparationProgress.button(s, s.deadline).startsWith("一键备车\n"))
        assertFalse(PreparationProgress.detail(s, s.deadline).contains("已关闭"))
    }
    @Test fun aFinishedTaskCannotBeRevivedByLaterClimateReadback() {
        val ended = session().copy(phase = PreparationPhase.STOPPED, finished = true)
        assertEquals(ended, PreparationProgress.observe(ended, reading(true), start + 20_000))
    }
    @Test fun remoteClimateOffEndsObservationEvenWhenPreparationIncludedSeats() {
        val s = session().copy(channels = listOf(ClimateChannel.AC, ClimateChannel.FRONT_LEFT))
        val next = PreparationProgress.observe(s, reading(false), start + 20_000)
        assertTrue(next.finished)
        assertFalse(PreparationProgress.detail(next, start + 20_000).contains("全部关闭"))
    }
    @Test fun unknownRequestAndMissingReadbackDoNotLookReadyForARetry() {
        val unknown = session().copy(phase = PreparationPhase.UNKNOWN, finished = true)
        assertFalse(PreparationProgress.button(unknown, start + 30_000).startsWith("一键备车"))
        assertFalse(PreparationProgress.observe(session(), reading(null), start + 20_000).finished)
    }
    @Test fun freshDrivingHandoverEndsSoftwareTaskWithoutRequestingClimateOff() {
        for(mode in listOf(13,33)) for(speed in listOf(0,40)) {
            val before=session().copy(channels=listOf(ClimateChannel.AC,ClimateChannel.FRONT_LEFT))
            val next=PreparationProgress.observeVehicle(before,status(mode=mode,speed=speed),start+20_000)
            assertEquals(PreparationPhase.HANDED_OVER,next.phase)
            assertTrue(next.finished); assertFalse(next.stopRequested)
            assertEquals(before.lastSource,next.lastSource)
            assertEquals(start+20_000,next.endedAt)
            assertEquals(next,PreparationProgress.observe(next,reading(true),start+20_000))
            assertFalse(PreparationProgress.resumable(next,start+20_000))
            assertEquals("一键备车\n目标 24°C",PreparationProgress.button(next,start+20_000,24))
            assertEquals(next,PreparationSession.parse(next.json()))
        }
    }
    @Test fun unlockAloneAndOldOrInvalidDrivingEvidenceCannotEndPreparation() {
        val before=session()
        listOf(status(mode=2),status(mode=null),status(source=null),status(source=start),status(source=start-1),
            status(source=start+21_000),status(fetched=start+21_000),status(source=start-300_000),
            status(groupTime="bad"),status(groupTime=(start-300_000).toString())).forEach { probe ->
            assertEquals(before,PreparationProgress.observeVehicle(before,probe,start+20_000))
        }
    }
    @Test fun drivingCannotEraseAnUnresolvedWriteOrReviveAnEndedSession() {
        for(phase in listOf(PreparationPhase.READING,PreparationPhase.SENDING,PreparationPhase.UNKNOWN,PreparationPhase.FAILED)) {
            val before=session().copy(phase=phase)
            assertEquals(before,PreparationProgress.observeVehicle(before,status(),start+20_000))
        }
        val ended=session().copy(finished=true,phase=PreparationPhase.STOPPED)
        assertEquals(ended,PreparationProgress.observeVehicle(ended,status(),start+20_000))
    }
    @Test fun readyWhileStillRunningIsDifferentFromAnAlreadyComfortableNoOp() {
        val ready=session().copy(phase=PreparationPhase.READY)
        assertTrue(PreparationProgress.inProgress(ready,start+20_000))
        assertFalse(PreparationProgress.idle(ready,start+20_000))
        assertTrue(PreparationProgress.idle(ready.copy(finished=true),start+20_000))
    }
    @Test fun anIncompleteClimateReadDoesNotForgetPreviouslyObservedRunning() {
        val incomplete=PreparationProgress.observe(session(),reading(null),start+20_000)
        assertFalse(incomplete.finished)
        val restored=PreparationSession.parse(incomplete.json())!!
        val off=reading(false).copy(sourceTime=Instant.ofEpochMilli(start+30_000))
        assertEquals(PreparationPhase.STOPPED,PreparationProgress.observe(restored,off,start+30_000).phase)
        val accepted=session().copy(phase=PreparationPhase.ACCEPTED)
        assertFalse(PreparationProgress.observe(accepted,reading(false),start+20_000).finished)
    }
    @Test fun resultFeedbackExpiresButUnrelatedFailuresAndAmbiguousCommandsRemainVisible() {
        val ended=session().copy(phase=PreparationPhase.HANDED_OVER,finished=true,endedAt=start+20_000)
        assertTrue(PreparationProgress.message(ended,"空调 · 已受理",start+21_000)!!.contains("行驶模式"))
        assertNull(PreparationProgress.message(ended,"备车进度会自动更新",start+66_000))
        assertEquals("车锁读取失败",PreparationProgress.message(ended,"车锁读取失败",start+21_000))
        assertEquals("发送结果待核实",PreparationProgress.message(ended.copy(phase=PreparationPhase.UNKNOWN),"发送结果待核实",start+66_000))
    }
    @Test fun idlePreparationHasANeutralButtonEvenWhenCabinClimateContinues() {
        val overview=VehicleOverview(acOn=true,climateSource=start+20_000,climateFetched=start+20_000,readings=mapOf("cabin_temperature" to OverviewReading("22.0 °C",start+20_000,start+20_000)))
        val time=Instant.ofEpochMilli(start+20_000)
        assertEquals(CardTone.ACTIVE,CardAppearance.tone("prepare",overview,time,session()))
        assertEquals(CardTone.NEUTRAL,CardAppearance.tone("prepare",overview,time,session().copy(phase=PreparationPhase.HANDED_OVER,finished=true)))
    }
}
