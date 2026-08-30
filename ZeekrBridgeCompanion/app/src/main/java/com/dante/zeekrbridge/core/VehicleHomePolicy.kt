package com.dante.zeekrbridge.core

enum class VehicleConnectionStatus {
    UNPAIRED,
    OFFLINE,
    CONNECTED,
}

data class PairedVehicleSummary(
    val id: String,
    val name: String,
    val lastSeenEpochMs: Long,
)

data class VehicleHomeSnapshot(
    val status: VehicleConnectionStatus,
    val vehicle: PairedVehicleSummary?,
    val receiverReady: Boolean,
    val preferredEndpoint: String?,
)

object VehicleHomePolicy {
    fun resolve(
        pairedVehicles: List<PairedVehicleSummary>,
        carOnline: Boolean,
        serviceRunning: Boolean,
        endpointCandidates: List<String>,
    ): VehicleHomeSnapshot {
        val currentVehicle = pairedVehicles.maxByOrNull { it.lastSeenEpochMs }
        val status = when {
            carOnline -> VehicleConnectionStatus.CONNECTED
            currentVehicle != null -> VehicleConnectionStatus.OFFLINE
            else -> VehicleConnectionStatus.UNPAIRED
        }
        return VehicleHomeSnapshot(
            status = status,
            vehicle = currentVehicle,
            receiverReady = serviceRunning,
            preferredEndpoint = endpointCandidates.firstOrNull(),
        )
    }
}
