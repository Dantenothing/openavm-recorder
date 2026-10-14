package com.dante.zeekrcapabilitylab.telemetry

import android.content.Context
import android.os.Build
import io.github.dantenothing.drivemap.*

/** Peaks from this head unit and firmware only. Playback does not rewrite the recorded raw values. */
internal object RecordedPedalHistory {
    private var cached: RelativePedalScale? = null
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        "pedal_display_reference_" + Build.FINGERPRINT.hashCode().toUInt().toString(16), Context.MODE_PRIVATE)
    @Synchronized fun read(context: Context): RelativePedalScale {
        cached?.let { return it }
        val prefs = preferences(context)
        fun value(key: String) = prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 } ?: 0.0
        return RelativePedalScale(value("throttle"), value("brake")).also { cached = it }
    }
    @Synchronized fun observe(context: Context, samples: List<VehicleTelemetrySample>): RelativePedalScale {
        val previous = read(context)
        val next = samples.fold(previous) { scale, sample ->
            if (!VehicleTelemetryPolicy.validStored(sample)) scale else scale.observe(DriveHudModel.frame(sample.elapsedMs,
                sample.values.mapValues { (_, v) -> DriveHudObservation(v.readElapsedMs, v.number, v.text, v.status) }))
        }
        if (next != previous) {
            cached = next
            preferences(context).edit().putString("throttle", next.throttlePeak.toString()).putString("brake", next.brakePeak.toString()).apply()
        }
        return next
    }
}
