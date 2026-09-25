package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleHomePolicyTest {

    @Test fun recentAuthenticatedHttpActivityIsVisibleWithoutAWebSocket() {
        val result = VehicleHomePolicy.resolve(
            listOf(PairedVehicleSummary("car", "Recorder", 95000)), false, true, emptyList(),
            receiverStartedAtEpochMs = 90000, nowEpochMs = 100000,
        )
        assertEquals(VehicleConnectionStatus.RECENT, result.status)
    }

    @Test fun oldOrFutureActivityDoesNotClaimAConnection() {
        for (seen in listOf(0L, 999L, 100001L)) {
            val result = VehicleHomePolicy.resolve(
                listOf(PairedVehicleSummary("car", "Recorder", seen)), false, true, emptyList(),
                receiverStartedAtEpochMs = 1000, nowEpochMs = 100000,
            )
            assertEquals(VehicleConnectionStatus.OFFLINE, result.status)
        }
        val expired = VehicleHomePolicy.resolve(
            listOf(PairedVehicleSummary("car", "Recorder", 2000)), false, true, emptyList(),
            receiverStartedAtEpochMs = 1000, nowEpochMs = 100000,
        )
        assertEquals(VehicleConnectionStatus.OFFLINE, expired.status)
    }

    @Test fun stoppedReceiverCannotKeepAStaleOnlineFlag() {
        val result = VehicleHomePolicy.resolve(
            listOf(PairedVehicleSummary("car", "Recorder", 95000)), true, false, emptyList(),
            receiverStartedAtEpochMs = 90000, nowEpochMs = 100000,
        )
        assertEquals(VehicleConnectionStatus.OFFLINE, result.status)
        assertEquals(false, result.receiverReady)
    }

    @Test
    fun noPairedVehicleIsUnpairedEvenWhenReceiverServiceRuns() {
        val result = VehicleHomePolicy.resolve(
            pairedVehicles = emptyList(),
            carOnline = false,
            serviceRunning = true,
            endpointCandidates = listOf("192.0.0.2:8766"),
        )

        assertEquals(VehicleConnectionStatus.UNPAIRED, result.status)
        assertNull(result.vehicle)
        assertEquals("192.0.0.2:8766", result.preferredEndpoint)
    }

    @Test
    fun pairedVehicleRemainsOfflineUntilAuthenticatedCarConnects() {
        val vehicle = PairedVehicleSummary("car-a", "ZEEKR 7X", lastSeenEpochMs = 1234L)

        val result = VehicleHomePolicy.resolve(
            pairedVehicles = listOf(vehicle),
            carOnline = false,
            serviceRunning = true,
            endpointCandidates = emptyList(),
        )

        assertEquals(VehicleConnectionStatus.OFFLINE, result.status)
        assertEquals(vehicle, result.vehicle)
        assertEquals(true, result.receiverReady)
    }

    @Test
    fun authenticatedCarConnectionIsConnectedAndUsesMostRecentVehicle() {
        val older = PairedVehicleSummary("car-a", "Older car", lastSeenEpochMs = 100L)
        val recent = PairedVehicleSummary("car-b", "ZEEKR 7X", lastSeenEpochMs = 200L)

        val result = VehicleHomePolicy.resolve(
            pairedVehicles = listOf(older, recent),
            carOnline = true,
            serviceRunning = true,
            endpointCandidates = listOf("192.0.0.2:8766", "192.168.43.1:8766"),
        )

        assertEquals(VehicleConnectionStatus.CONNECTED, result.status)
        assertEquals(recent, result.vehicle)
        assertEquals("192.0.0.2:8766", result.preferredEndpoint)
    }
}
