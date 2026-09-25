package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManualRefreshTest {
    private val at = 1_800_000_000_000L

    @Test fun oldCombinedSettingIsDiscardedWithoutLosingCleanupOrPreferences() {
        val pending = TemperatureUpdate("car", at, 22, TemperaturePhase.NEEDS_STOP,
            startSent = at, ownsAc = true, combined = true)
        val original = AssistantState(temperatureUpdate = pending, homeRadius = 650,
            parkingNote = "demo", temperatureReceipt = TemperatureReceipt("car", at, 20.2))
        val json = Json.parseToJsonElement(original.encode()).jsonObject
        for (legacy in listOf(JsonPrimitive(true), JsonPrimitive(false))) {
            val restored = AssistantState.parse(JsonObject(json + ("refreshTemperature" to legacy)).toString())
            assertEquals(original, restored)
            assertFalse(Json.parseToJsonElement(restored.encode()).jsonObject.containsKey("refreshTemperature"))
            assertTrue(restored.temperatureUpdate!!.needsStop)
        }
    }
    @Test fun onlyCorrelatedRecentTemperatureAvoidsAnotherTemporaryStart() {
        assertNull(AssistantState().temperatureReadDelay("car", at))
        val receipt = TemperatureReceipt("car", at - 299_000, 29.5)
        val settings = AssistantState(temperatureReceipt = receipt)
        assertNotNull(settings.temperatureReadDelay("car", at))
        assertNull(settings.temperatureReadDelay("car", at + 2_000))
        assertEquals(receipt, settings.temperatureReceipt)
    }
    @Test fun anotherVehicleOrFutureClockCannotReuseReceipt() {
        for (receipt in listOf(TemperatureReceipt("other", at, 20.0), TemperatureReceipt("car", at + 1, 20.0)))
            assertNull(AssistantState(temperatureReceipt = receipt).temperatureReadDelay("car", at))
    }
    @Test fun failedTemperatureBackoffExpires() {
        val settings = AssistantState(temperatureRetryAfter = at + 60_000)
        assertNotNull(settings.temperatureReadDelay("car", at))
        assertNull(settings.temperatureReadDelay("car", at + 60_000))
    }
    @Test fun recentRunningPreparationAvoidsTemporaryAcButStaleOrFinishedDoesNot() {
        val session = PreparationSession("car", at - 60_000, at + 600_000, ComfortPreferences(), emptyList(),
            remoteRunningObserved = true, lastSource = at - 30_000, lastTemperature = 25.0)
        assertNotNull(AssistantState(activePreparation = session).temperatureReadDelay("car", at))
        for (other in listOf(session.copy(finished = true), session.copy(vehicleKey = "other"),
            session.copy(lastSource = at - 400_000), session.copy(remoteRunningObserved = false)))
            assertNull(AssistantState(activePreparation = other).temperatureReadDelay("car", at))
    }
    @Test fun temperatureRecoveryLabelNeverChangesOrdinaryRefreshState() {
        val task = TemperatureUpdate("car", at, 22, TemperaturePhase.STARTING, startSent = at, ownsAc = true, combined = true)
        val recovered = TemperatureUpdate.parse(task.json())!!.interrupted(at + 1_000)
        assertTrue(recovered.needsStop)
        assertEquals("停止临时空调", recovered.temperatureActionLabel(at + 1_000))
        assertEquals("核对空调", recovered.temperatureActionLabel(at + 500_000))
        assertEquals("更新车温", (null as TemperatureUpdate?).temperatureActionLabel(at))
        assertEquals("结束取温", task.temperatureActionLabel(at))
        assertEquals("结束中", task.copy(cancelRequested = true).temperatureActionLabel(at))
    }
}
