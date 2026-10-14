package com.dante.zeekrcapabilitylab.transfer

import kotlinx.serialization.Serializable

@Serializable
data class DriveDataWindow(val startElapsedMs: Long, val endElapsedMs: Long) {
    fun valid() = startElapsedMs >= 0 && endElapsedMs > startElapsedMs
    fun contains(at: Long) = at >= startElapsedMs && at < endElapsedMs
}

/** Frozen separately from legacy includeDriveData. Null retains the old queue's consent. */
@Serializable
data class DriveDataSelection(
    val gps: Boolean = false,
    val vehicle: Boolean = false,
    val windows: List<DriveDataWindow> = emptyList(),
    val requestId: String? = null,
) {
    val enabled: Boolean get() = gps || vehicle
    fun valid() = windows.size <= 2_000 && windows.all { it.valid() } &&
        (requestId == null || requestId.matches(Regex("[a-zA-Z0-9-]{1,64}")))
    fun contains(at: Long) = windows.isEmpty() || windows.any { it.contains(at) }
}
