package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ThermalPresentationTest {
    private val now = Instant.parse("2026-09-21T10:00:00Z")
    private val time = now.toEpochMilli()
    private fun overview(temp: String = "29.0 °C", on: Boolean = true) = VehicleOverview(vehicleKey = "a".repeat(64),
        acOn = on, blowerActive = on, climateSource = time, climateFetched = time,
        readings = mapOf("cabin_temperature" to OverviewReading(temp, time, time)))
    private fun session(phase: PreparationPhase = PreparationPhase.COOLING) = PreparationSession("a".repeat(64), time-120_000, time+780_000,
        ComfortPreferences(), listOf(ClimateChannel.AC), phase=phase, lastSource=time, remoteRunningObserved=true,
        initialTemperature=34.0, lastTemperature=29.0)
    private fun show(o: VehicleOverview = overview(), s: PreparationSession? = session()) = ThermalPresentation.from(o,s,now)

    @Test fun hotAmbientAndCoolingAirAreIndependentAndColdCabinCanWarm() {
        assertEquals(ThermalPresentation("hot",Airflow.COOLING,"正在降温"),show())
        assertEquals(ThermalPresentation("cold",Airflow.WARMING,"正在升温"),show(overview("12.0 °C"),session(PreparationPhase.WARMING)))
        assertEquals(Airflow.NONE,show(overview(on=false),null).airflow)
    }
    @Test fun acceptedSendingAndStoppingNeverAnimateASuccess() {
        for (phase in listOf(PreparationPhase.ACCEPTED,PreparationPhase.SENDING,PreparationPhase.READING,PreparationPhase.STOPPING)) {
            val s=session(phase)
            assertEquals(phase.name,Airflow.NONE,show(s=s).airflow)
            assertEquals(CardTone.NEUTRAL,CardAppearance.tone("prepare",overview(),now,s))
        }
    }
    @Test fun holdingStaysActiveWhileAnEndedSessionReturnsToNeutral() {
        val o=overview("22.0 °C")
        for(phase in listOf(PreparationPhase.READY,PreparationPhase.HOLD,PreparationPhase.SURFACE_FINISH)) {
            assertEquals(Airflow.HOLD,show(o,session(phase)).airflow)
            assertEquals(CardTone.ACTIVE,CardAppearance.tone("prepare",o,now,session(phase)))
        }
        val ended=session(PreparationPhase.STOPPED).copy(finished=true,endedAt=time)
        assertEquals(Airflow.NONE,show(o,ended).airflow)
        assertEquals(CardTone.NEUTRAL,CardAppearance.tone("prepare",o,now,ended))
    }
    @Test fun staleTemperatureKeepsItsNumberAndTimeButLosesHaloAndFlow() {
        val old=overview().copy(readings=mapOf("cabin_temperature" to OverviewReading("29.0 °C",time-301_000,time)))
        val before=old.encode()
        assertEquals("neutral",show(old).ambient);assertEquals(Airflow.NONE,show(old).airflow)
        assertEquals("29.0°C",old.cabin(now));assertEquals(time-301_000,old.readings.getValue("cabin_temperature").source)
        assertEquals(before,old.encode())
        assertEquals("备车状态待核实",show(old).preparationTitle(session(),time))
        assertFalse(show(old).preparationButton(session(),time,22).contains("正在降温"))
        assertTrue(show(old).preparationButton(session(),time,22).contains("再点停止"))
        assertEquals("停止备车",PreparationControl.label(session(),time))
    }
    @Test fun recentTemperatureNeverRevivesStaleUnknownOrFutureClimateEvidence() {
        for(source in listOf(null,time-301_000,time+31_000,1L)) {
            val o=overview().copy(climateSource=source)
            assertEquals("hot",show(o).ambient);assertEquals(Airflow.NONE,show(o).airflow)
        }
        assertEquals(Airflow.NONE,show(overview().copy(climateFetched=time-301_000)).airflow)
    }
    @Test fun blowerAloneCannotClaimHeatingCoolingOrOwnARemoteSession() {
        val o=overview().copy(acOn=false)
        assertEquals(Airflow.AIR,show(o,null).airflow)
        assertEquals("空调送风中",show(o,null).label)
        assertEquals(CardTone.NEUTRAL,CardAppearance.tone("prepare",o,now,null))
        assertEquals(Airflow.AIR,show(s=session().copy(vehicleKey="another")).airflow)
    }
    @Test fun newerUnclassifiedSampleUsesTargetWithoutInheritingTheOldMeasuredTrend() {
        assertEquals(Airflow.COOLING,show(s=session().copy(lastSource=time-1)).airflow)
        assertEquals("预冷至 22°C",show(s=session().copy(lastSource=time-1)).label)
        assertFalse(show(s=session().copy(lastSource=time-1)).preparationTitle(session(),time).contains("正在降温"))
        assertEquals(Airflow.AIR,show(s=session().copy(remoteRunningObserved=false)).airflow)
        assertEquals(Airflow.NONE,show(s=session(PreparationPhase.UNKNOWN)).airflow)
        assertEquals(Airflow.NONE,show(s=session(PreparationPhase.DEGRADED)).airflow)
    }
    @Test fun drivingCannotKeepTheRemoteCoolingStory() {
        val o=overview().copy(motion=MotionReading("Driving",time,time,false))
        assertEquals(Airflow.AIR,show(o).airflow)
        assertEquals("空调以车机为准",show(o).label)
    }
    private fun report(p:Probe)=Report(false,now,now,"",emptyList(),listOf(p))
    @Test fun sourceComesFromClimateGroupAndNotRootOrFetchTime() {
        val p=Probe(Endpoint.STATUS,ProbeOutcome.SUCCESS,now,Json.parseToJsonElement("""{
            "updateTime":$time,"additionalVehicleStatus":{"climateStatus":{"updateTime":${time-600_000},"preClimateActive":true,"airBlowerActive":1}}
        }"""))
        val o=overview().updated(report(p))
        assertEquals(time-600_000,o.climateSource);assertFalse(o.climateFresh(now))
        assertEquals(o,VehicleOverview.decode(o.encode()))
    }
    @Test fun partialSentryRefreshCannotRenewClimateAndFailedReadInvalidatesIt() {
        val old=overview().copy(climateSource=time-600_000)
        val sentry=Probe(Endpoint.SENTRY,ProbeOutcome.SUCCESS,now,buildJsonObject { put("vstdModeState",1) })
        assertEquals(old.climateSource,old.updated(report(sentry)).climateSource)
        val failed=overview().updated(report(Probe(Endpoint.STATUS,ProbeOutcome.NETWORK,now)))
        assertNull(failed.climateSource);assertNull(failed.acOn);assertEquals(Airflow.NONE,show(failed).airflow)
        val missing=overview().updated(report(Probe(Endpoint.STATUS,ProbeOutcome.SUCCESS,now,buildJsonObject {})))
        assertNull(missing.climateSource);assertFalse(missing.climateFresh(now))
    }
    @Test fun oldCacheRestoresSettingsWithoutPromotingUntimedClimateFlags() {
        val saved=Json.parseToJsonElement(overview().copy(nickname="My car",target=24).encode()).jsonObject
        val restored=VehicleOverview.decode(JsonObject(saved-"climateSource"-"climateFetched").toString())
        assertEquals("My car",restored.nickname);assertEquals(24,restored.target)
        assertEquals(true,restored.acOn);assertNull(restored.climateSource)
        assertEquals(Airflow.NONE,show(restored).airflow)
    }
}
