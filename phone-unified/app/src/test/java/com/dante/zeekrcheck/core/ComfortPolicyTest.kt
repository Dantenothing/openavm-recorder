package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ComfortPolicyTest {
    private val start=Instant.parse("2026-09-20T01:00:00Z").toEpochMilli()
    private val elapsed=100_000L
    private fun reading(seconds: Long=0,temp:Double=40.0,vent:Int?=0,heat:Int?=0,on:Boolean?=true)=ClimateSnapshot(
        frontLeft=vent,frontRight=0,heatLeft=heat,heatRight=0,acOn=on,cabinTemperature=temp,
        sourceTime=Instant.ofEpochMilli(start+seconds*1000),fetchedAt=Instant.ofEpochMilli(start+seconds*1000))
    private fun begin(temp:Double=40.0,p:ComfortPreferences=ComfortPreferences(seatVentilation=true),departure:Long?=null):PreparationSession {
        val plan=ComfortPolicy.start(p,reading(temp=temp),start,elapsed,departure,"synthetic-plan")
        return PreparationSession("synthetic",start,start+p.minutes*60_000L,p,plan.command?.targets?.map { it.channel }.orEmpty(),
            initialTemperature=temp,thermal=plan.thermal)
    }
    private fun observe(s:PreparationSession,seconds:Long,temp:Double=22.0,vent:Int?=3,heat:Int?=0,on:Boolean?=true)=
        PreparationProgress.observe(s,reading(seconds,temp,vent,heat,on),start+seconds*1000)
    private fun decide(s:PreparationSession,seconds:Long,temp:Double=22.0,vent:Int?=3,heat:Int?=0,parked:Boolean=true)=
        ComfortPolicy.evaluate(s,reading(seconds,temp,vent,heat),start+seconds*1000,elapsed+seconds*1000,parked)

    @Test fun hotStartKeepsUserTargetAndUsesRealCommandsOnly() {
        val plan=ComfortPolicy.start(ComfortPreferences(seatVentilation=true,seatHeat=true,steeringHeat=true),reading(),start,elapsed,start+900_000)
        assertEquals(listOf(ClimateTarget(ClimateChannel.AC,22,15),ClimateTarget(ClimateChannel.FRONT_LEFT,3,15)),plan.command!!.targets)
        assertEquals(300_000L,plan.thermal.tasks.single().requiredMs)
        assertEquals(28.0,plan.thermal.tasks.single().coolBelow!!,0.0)
        assertFalse(plan.command.body().contains("fan")); assertFalse(plan.command.body().contains("LO"))
    }
    @Test fun coldStartDefersLowHeatAndDoesNotInventWheelReadback() {
        val s=begin(4.0,ComfortPreferences(seatHeat=true,steeringHeat=true,seatVentilation=true),start+900_000)
        assertEquals(listOf(ClimateChannel.AC),s.channels)
        assertEquals(start+180_000,s.thermal!!.tasks.first().notBefore)
        assertEquals(SurfacePhase.SKIPPED,s.thermal.tasks.last().phase)
        val near=observe(s,180,10.0,vent=0,heat=0)
        val decision=decide(near,180,10.0,vent=0,heat=0)
        assertEquals(listOf(ClimateTarget(ClimateChannel.HEAT_LEFT,1,5)),decision.command!!.targets)
        assertEquals(s.deadline,decision.session.deadline)
        assertNull(decide(decision.session,180,10.0,vent=0,heat=0).command)
    }
    @Test fun airReachingTargetDoesNotEraseHotSurfaceTask() {
        var s=observe(begin(),10,28.0)
        s=observe(s,70); s=observe(s,130); s=observe(s,190)
        assertTrue(s.thermal!!.airReady)
        assertEquals(PreparationPhase.SURFACE_FINISH,s.phase)
        assertEquals(180_000L,s.thermal.tasks.single().confirmedMs)
        assertFalse(decide(s,190).end)
        s=observe(s,250); s=observe(s,310)
        val off=decide(s,310)
        assertEquals(listOf(ClimateTarget(ClimateChannel.FRONT_LEFT,0)),off.command!!.targets)
        assertFalse(off.end)
        val done=observe(off.session,320,vent=0)
        assertEquals(SurfacePhase.DONE,done.thermal!!.tasks.single().phase)
        val finish=decide(done,320,vent=0)
        assertTrue(finish.end)
        assertFalse(decide(finish.session,321,vent=0).end)
    }
    @Test fun appointmentHoldsAfterCompletionThenStopsAtDeparture() {
        var s=begin(p=ComfortPreferences(),departure=start+900_000)
        for(t in listOf(10L,70L,130L)) s=observe(s,t,vent=0)
        assertEquals(PreparationPhase.HOLD,s.phase)
        assertFalse(decide(s,130,vent=0).end)
        assertTrue(decide(observe(s,900,vent=0),900,vent=0).end)
        assertEquals(start+1_800_000,s.deadline)
        assertEquals(start+900_000,ComfortPolicy.endAt(s))
    }
    @Test fun duplicateSourceDoesNotAccumulateAirOrSurfaceProgressOrRepeatWrites() {
        val s=observe(begin(),10)
        val duplicate=PreparationProgress.observe(s,reading(10,temp=22.0,vent=3),start+100_000)
        assertEquals(s,duplicate)
        assertEquals(0L,duplicate.thermal!!.tasks.single().confirmedMs)
        assertFalse(duplicate.thermal.airReady)
        assertNull(decide(s,10).command)
    }
    @Test fun missingSeatStateAndLongGapsCannotCountAsConfirmedRuntime() {
        var s=observe(begin(),10,28.0)
        s=observe(s,70,vent=null)
        s=observe(s,130)
        assertEquals(0L,s.thermal!!.tasks.single().confirmedMs)
        s=observe(s,400)
        assertEquals(0L,s.thermal!!.tasks.single().confirmedMs)
        assertFalse(s.thermal.airReady)
    }
    @Test fun permissionAndSeatScopeAreRespectedAndExistingModesAreNotTakenOver() {
        val ac=ComfortPolicy.start(ComfortPreferences(),reading(),start,elapsed)
        assertEquals(listOf(ClimateChannel.AC),ac.command!!.targets.map { it.channel })
        val both=ComfortPolicy.start(ComfortPreferences(seatVentilation=true,bothSeats=true),reading(),start,elapsed)
        assertEquals(setOf(ClimateChannel.AC,ClimateChannel.FRONT_LEFT,ClimateChannel.FRONT_RIGHT),both.command!!.targets.map { it.channel }.toSet())
        val conflict=ComfortPolicy.start(ComfortPreferences(seatVentilation=true,seatHeat=true),reading(heat=2),start,elapsed)
        assertEquals(listOf(ClimateChannel.AC),conflict.command!!.targets.map { it.channel })
        assertEquals(SurfacePhase.SKIPPED,conflict.thermal.tasks.single().phase)
        val missing=ComfortPolicy.start(ComfortPreferences(seatVentilation=true),reading(heat=null),start,elapsed)
        assertEquals(SurfacePhase.SKIPPED,missing.thermal.tasks.single().phase)
    }
    @Test fun staleTemperatureOnlyRequestsNormalTargetAndCannotChooseAdjuncts() {
        val plan=ComfortPolicy.start(ComfortPreferences(seatHeat=true,seatVentilation=true),reading(),start+400_000,elapsed)
        assertEquals(listOf(ClimateChannel.AC),plan.command!!.targets.map { it.channel })
        assertTrue(plan.thermal.tasks.isEmpty())
        assertTrue(plan.thermal.note.isNotBlank())
    }
    @Test fun nativeDurationNeverRoundsUpAndQueueTimeConsumesRemainingBudget() {
        assertNull(ComfortPolicy.durationWithin(299_999))
        assertEquals(5,ComfortPolicy.durationWithin(480_000))
        assertEquals(10,ComfortPolicy.durationWithin(720_000))
        val s=begin(4.0,ComfortPreferences(seatHeat=true),start+900_000)
        val late=observe(s,661,10.0,vent=0)
        val decision=decide(late,661,10.0,vent=0)
        assertNull(decision.command)
        assertEquals(SurfacePhase.SKIPPED,decision.session.thermal!!.tasks.single().phase)
        assertEquals(s.deadline,decision.session.deadline)
    }
    @Test fun sourceConfirmationIsRequiredForEachOwnedChannelWhenStopping() {
        val s=observe(begin(),10).copy(stopRequested=true,stopSentAt=start+20_000)
        assertFalse(observe(s,15,vent=0,on=false).finished)
        assertFalse(observe(s,30,vent=3,on=false).finished)
        assertFalse(observe(s,30,vent=null,on=false).finished)
        assertEquals(PreparationPhase.STOPPED,observe(s,30,vent=0,on=false).phase)
    }
    @Test fun manualChangesAndRemoteInterruptionReleaseControlWithoutNewOffWrites() {
        val s=observe(begin(),10)
        val manual=observe(s,20,vent=1)
        assertTrue(manual.finished); assertEquals(PreparationPhase.HANDED_OVER,manual.phase)
        assertNull(decide(manual,20,vent=1).command); assertFalse(decide(manual,20,vent=1).end)
        assertEquals(PreparationPhase.HANDED_OVER,observe(s,20,on=false).phase)
    }
    @Test fun stoppedAndInFlightStopSessionsNeverGeneratePolicyCommands() {
        val s=observe(begin(),10).copy(stopRequested=true)
        val decision=decide(s,1900)
        assertFalse(decision.end); assertNull(decision.command)
        assertEquals(s,decision.session)
    }
    @Test fun deadlineNeverBecomesComfortSuccessAndRequiresFreshParkedEvidence() {
        val s=observe(begin(),10,38.0)
        assertFalse(decide(s,1800,38.0,parked=false).end)
        val stale=ComfortPolicy.evaluate(s,reading(10,38.0,3),start+1_800_000,elapsed+1_800_000,true)
        assertFalse(stale.end)
        val expired=decide(s,1800,38.0)
        assertTrue(expired.end); assertFalse(expired.session.thermal!!.airReady)
    }
    @Test fun processRecoveryAndClockChangeOnlyObserveAndNeverExtendBudget() {
        val s=observe(begin(),10)
        val recovered=PreparationProgress.interrupted(s)
        assertTrue(recovered.thermal!!.suspended)
        assertNull(decide(recovered,600).command); assertFalse(decide(recovered,1800).end)
        val shifted=ComfortPolicy.evaluate(s,reading(600,22.0,3),start+600_000,elapsed+300_000,true)
        assertTrue(shifted.session.thermal!!.suspended); assertNull(shifted.command)
        assertEquals(s.deadline,shifted.session.deadline)
    }
    @Test fun pendingSeatTimeoutDoesNotPretendTheRequestRanOrRetry() {
        val s=observe(begin(),130,22.0,vent=0)
        val d=decide(s,130,vent=0)
        assertTrue(d.session.thermal!!.suspended); assertNull(d.command)
        assertEquals(0L,d.session.thermal.tasks.single().confirmedMs)
    }
    @Test fun oldReminderIsNotSilentlyMigratedToAutomaticStop() {
        val old=JsonObject(ComfortPreferences().json().filterKeys { it!="finishWhenComfortable" })
        assertFalse(ComfortPreferences.parse(old).finishWhenComfortable)
        assertTrue(ComfortPreferences().finishWhenComfortable)
        var s=begin(p=ComfortPreferences(finishWhenComfortable=false))
        for(t in listOf(10L,70L,130L)) s=observe(s,t,vent=0)
        assertEquals(PreparationPhase.READY,s.phase); assertFalse(decide(s,130,vent=0).end)
    }
    @Test fun thermalStateRoundTripsAndKeepsDepartureAndSessionIdentity() {
        val state=AssistantState(activePreparation=observe(begin(departure=start+900_000),10))
        assertEquals(state,AssistantState.parse(state.encode()))
        val s=state.activePreparation!!
        val replanned=s.copy(preferences=s.preferences.copy(target=24))
        assertEquals(s.deadline,replanned.deadline); assertEquals(s.createdAt,replanned.createdAt)
        assertEquals(start+900_000,replanned.thermal!!.departureAt)
    }
    @Test fun tightTemperatureDifferenceNeverTriggersHotOrColdAdjuncts() {
        val p=ComfortPreferences(target=28,seatHeat=true,seatVentilation=true)
        val near=ComfortPolicy.start(p,reading(temp=28.5),start,elapsed)
        assertNull(near.command); assertTrue(near.thermal.tasks.isEmpty())
        val scheduled=ComfortPolicy.start(p,reading(temp=28.5),start,elapsed,start+900_000)
        assertEquals(listOf(ClimateChannel.AC),scheduled.command!!.targets.map { it.channel })
    }
    @Test fun rejectedOrUnknownStageWritesAreNeverAutomaticallyRepeated() {
        var s=observe(begin(),10)
        for(t in 70L..310L step 60) s=observe(s,t)
        val d=decide(s,310)
        for(result in listOf(CommandResult.REJECTED,CommandResult.UNKNOWN)) {
            val failed=ComfortPolicy.result(d.session,d.command!!,result)
            assertTrue(failed.thermal!!.suspended)
            assertNull(decide(failed,320).command); assertFalse(decide(failed,1800).end)
        }
    }
    @Test fun confirmedSeatStopReleasesOwnershipBeforeTheFinalCabinStop() {
        var s=observe(begin(),10)
        for(t in 70L..310L step 60) s=observe(s,t)
        val off=decide(s,310)
        val done=observe(off.session,320,vent=0)
        assertEquals(listOf(ClimateChannel.AC),done.channels)
        val userStartedSeat=observe(done,330,vent=2)
        assertEquals(listOf(ClimateTarget(ClimateChannel.AC,0,30)),PreparationControl.stopCommand(userStartedSeat).targets)
    }
    @Test fun initialAppointmentLeaseDoesNotEndFiveMinutesEarlyAfterAQuickStatusRead() {
        val p=ComfortPreferences()
        val plan=ComfortPolicy.start(p,reading(),start,elapsed,start+899_000)
        assertEquals(15,plan.command!!.targets.single().minutes)
        // The initial lease fits the 30-minute session budget; departure still triggers a bounded stop.
        assertTrue(plan.command.targets.single().minutes*60_000L<=p.minutes*60_000L)
        assertNull(ComfortPolicy.start(p,reading(),start,elapsed,start+299_000).command)
    }
    @Test fun airReadyDoesNotClaimThatADeferredOrUnconfirmedSeatIsRunning() {
        var waiting=begin(4.0,ComfortPreferences(seatHeat=true),start+900_000)
        for(t in listOf(10L,70L,130L)) waiting=observe(waiting,t,vent=0,heat=0)
        assertTrue(waiting.thermal!!.airReady)
        assertNotEquals(PreparationPhase.SURFACE_FINISH,waiting.phase)
        assertTrue(PreparationProgress.detail(waiting,start+130_000).contains("临近出发"))
        var missing=observe(begin(),10)
        missing=observe(missing,70,vent=null)
        missing=observe(missing,130,vent=null)
        assertTrue(missing.thermal!!.airReady)
        assertNotEquals(PreparationPhase.SURFACE_FINISH,missing.phase)
        assertTrue(PreparationProgress.detail(missing,start+130_000).contains("待核实"))
    }
}
