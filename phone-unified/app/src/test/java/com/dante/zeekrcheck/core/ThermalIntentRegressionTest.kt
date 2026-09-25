package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ThermalIntentRegressionTest {
    private val now = Instant.parse("2026-09-21T10:00:00Z")
    private val t = now.toEpochMilli()
    private fun state(temp: Double) = VehicleOverview(vehicleKey="demo", acOn=true, blowerActive=true,
        climateSource=t, climateFetched=t, readings=mapOf("cabin_temperature" to OverviewReading("$temp °C",t,t)))
    private fun session(temp: Double) = PreparationSession("demo",t-60_000,t+840_000,ComfortPreferences(target=22),
        listOf(ClimateChannel.AC),phase=PreparationPhase.RUNNING,initialTemperature=temp,lastTemperature=temp,
        lastSource=t,remoteRunningObserved=true,thermal=ThermalSession(startElapsed=0))

    @Test fun eighteenDegreeStartShowsWarmIntentWithoutPretendingTemperatureAlreadyRose() {
        val s=session(18.0)
        val v=ThermalPresentation.from(state(18.0),s,now)
        assertEquals(Airflow.WARMING,v.airflow)
        assertEquals("预热至 22°C",v.label)
        assertEquals(v.label,v.preparationTitle(s,t))
        assertEquals("空调已运行 · 等待温度变化",v.preparationDetail(s,t))
        assertTrue(v.preparationButton(s,t,22).contains("再点停止"))
        assertEquals(PreparationPhase.RUNNING,s.phase)
        assertFalse(s.thermal!!.airReady)
    }
    @Test fun hotStartUsesCoolIntentAndFreshTrendKeepsMeasuredProgress() {
        val s=session(32.0)
        assertEquals("预冷至 22°C",ThermalPresentation.from(state(32.0),s,now).label)
        val progressing=s.copy(phase=PreparationPhase.COOLING,lastTemperature=29.0)
        val v=ThermalPresentation.from(state(29.0),progressing,now)
        assertEquals("正在降温",v.label)
        assertEquals("32.0 → 29.0°C",v.preparationDetail(progressing,t))
    }
    @Test fun anUnchangedTemperatureNeverBecomesReadyJustBecauseTimePassed() {
        val original=session(18.0)
        val later=t+600_000
        val reading=ClimateSnapshot(acOn=true,cabinTemperature=18.0,sourceTime=Instant.ofEpochMilli(later),fetchedAt=Instant.ofEpochMilli(later))
        val observed=ComfortPolicy.observe(original,reading,later)
        assertEquals(PreparationPhase.RUNNING,observed.phase)
        assertFalse(observed.thermal!!.airReady)
        val o=state(18.0).copy(climateSource=later,climateFetched=later,
            readings=mapOf("cabin_temperature" to OverviewReading("18.0 °C",later,later)))
        assertEquals("预热至 22°C",ThermalPresentation.from(o,observed,Instant.ofEpochMilli(later)).label)
    }
    @Test fun unconfirmedStaleForeignAndStoppedSessionsCannotShowWarmIntent() {
        val s=session(18.0)
        for(invalid in listOf(s.copy(phase=PreparationPhase.ACCEPTED),s.copy(phase=PreparationPhase.STOPPING),
            s.copy(phase=PreparationPhase.STOPPED,finished=true),s.copy(vehicleKey="other"),s.copy(remoteRunningObserved=false))) {
            assertNotEquals(Airflow.WARMING,ThermalPresentation.from(state(18.0),invalid,now).airflow)
        }
        assertNotEquals(Airflow.WARMING,ThermalPresentation.from(state(18.0).copy(climateSource=t-301_000),s,now).airflow)
        val mismatched=state(18.0).copy(readings=mapOf("cabin_temperature" to OverviewReading("18 °C",t-90_000,t)))
        assertNotEquals(Airflow.WARMING,ThermalPresentation.from(mismatched,s,now).airflow)
    }
}
