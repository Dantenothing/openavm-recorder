package com.dante.zeekrbridge.core

enum class VehicleConnectionStatus {
    UNPAIRED,
    OFFLINE,
    CONNECTED,
    RECENT,
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
        receiverStartedAtEpochMs: Long = 0,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): VehicleHomeSnapshot {
        val currentVehicle = pairedVehicles.maxByOrNull { it.lastSeenEpochMs }
        val status = when {
            serviceRunning && carOnline -> VehicleConnectionStatus.CONNECTED
            serviceRunning && receiverStartedAtEpochMs > 0 && currentVehicle != null &&
                currentVehicle.lastSeenEpochMs >= receiverStartedAtEpochMs &&
                nowEpochMs - currentVehicle.lastSeenEpochMs in 0..60_000 -> VehicleConnectionStatus.RECENT
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
