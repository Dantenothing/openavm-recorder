package com.dante.zeekrcapabilitylab.service.recorder

import io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor

/** Observation is evidence of delivery, not of exposure time. No assumed camera/codec latency. */
internal object RecordingClockPolicy {
    fun observedAnchor(firstDeliveredElapsedMs: Long?, source: String): VideoClockAnchor? {
        if (firstDeliveredElapsedMs == null || firstDeliveredElapsedMs !in 0..VideoClockAnchor.MAX_TIME_MS ||
            source !in setOf("CODEC_OUTPUT_OBSERVED", "CALLBACK_OBSERVED")) return null
        return VideoClockAnchor(mediaPositionUs = 0, elapsedRealtimeNs = firstDeliveredElapsedMs * 1_000_000L,
            source = source, uncertaintyMs = null)
    }

}
