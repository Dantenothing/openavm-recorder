package io.github.dantenothing.drivemap

/** A learned display reference, never a calibration or a claim about physical pedal travel. */
data class RelativePedalScale(val throttlePeak: Double = 0.0, val brakePeak: Double = 0.0) {
    fun observe(frame: DriveHudFrame): RelativePedalScale = copy(
        throttlePeak = maxOf(throttlePeak, usable(frame.acceleratorDepth) ?: 0.0),
        brakePeak = maxOf(brakePeak, usable(frame.brakeDepth) ?: 0.0),
    )
    fun throttleFraction(pedal: DriveHudPedal): Float? = fraction(pedal, throttlePeak)
    fun brakeFraction(pedal: DriveHudPedal): Float? = fraction(pedal, brakePeak)
    companion object {
        fun usable(pedal: DriveHudPedal): Double? = pedal.raw?.takeIf {
            pedal.status == "PROVISIONAL_RAW" && it.isFinite() && it in 0.0..1_000_000.0
        }
        private fun fraction(pedal: DriveHudPedal, peak: Double): Float? {
            val raw = usable(pedal) ?: return null
            if (!peak.isFinite() || peak <= 0) return if (raw == 0.0) 0f else null
            return (raw / peak).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
