package com.dante.zeekrcapabilitylab.telemetry

/** Two bounded process-local summaries. No signal values, coordinates, account data, or SDK probes. */
internal object VehicleTelemetryHealth {
    private data class Slot(val owner: Any, val status: VehicleTelemetryStatus = VehicleTelemetryStatus.IDLE,
        val lastRead: Long? = null, val timeouts: Int = 0, val recovered: Int = 0, val waitingAfterTimeout: Boolean = false)
    private val slots = mutableMapOf<String, Slot>()
    @Synchronized fun claim(owner: Any, liveOnly: Boolean) {
        slots[if (liveOnly) "preview" else "recording"] = Slot(owner)
    }
    @Synchronized fun observe(owner: Any, liveOnly: Boolean, value: VehicleTelemetryCaptureState) {
        val key = if (liveOnly) "preview" else "recording"
        val old = slots[key]?.takeIf { it.owner === owner } ?: return
        val timedOut = value.status == VehicleTelemetryStatus.SDK_TIMEOUT && old.status != value.status
        val recovered = value.status == VehicleTelemetryStatus.RECORDING && old.waitingAfterTimeout
        slots[key] = old.copy(status = value.status, lastRead = value.lastElapsedMs ?: old.lastRead,
            timeouts = old.timeouts + if (timedOut) 1 else 0, recovered = old.recovered + if (recovered) 1 else 0,
            waitingAfterTimeout = if (recovered) false else old.waitingAfterTimeout || timedOut)
    }
    @Synchronized fun report(now: Long): Map<String, Map<String, String>> = slots.mapValues { (_, slot) -> mapOf(
        "status" to slot.status.name, "lastReadAgeMs" to (slot.lastRead?.let { (now - it).coerceAtLeast(0).toString() } ?: "UNKNOWN"),
        "timeouts" to slot.timeouts.toString(), "recoveries" to slot.recovered.toString(),
    ) }
}
