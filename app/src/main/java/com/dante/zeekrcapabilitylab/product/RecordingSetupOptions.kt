package com.dante.zeekrcapabilitylab.product

/** Installation choices. Channel keys stay compatible with existing recording metadata. */
data class RecordingSetupOptions(
    val frontCabin: Boolean = false,
    val rearCabin: Boolean = false,
    val vehicleHud: Boolean = false,
    val gpsRoute: Boolean = false,
    val playbackMap: Boolean = false,
) {
    internal fun changesCaptureFrom(other: RecordingSetupOptions): Boolean =
        frontCabin != other.frontCabin || rearCabin != other.rearCabin ||
            vehicleHud != other.vehicleHud || gpsRoute != other.gpsRoute
}
