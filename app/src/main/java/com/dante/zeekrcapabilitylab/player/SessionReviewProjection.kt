package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import io.github.dantenothing.drivemap.*

/** Projects original observations onto one session axis, never a sum of parallel camera durations. */
object SessionReviewProjection {
    fun gps(timeline: SessionPlaybackTimeline, track: TelemetryTrack?): List<TelemetryFix> {
        if (track?.header?.sessionId != timeline.sessionId) return emptyList()
        val windows = intervals(timeline)
        var broken = true
        var previous: TelemetryFix? = null
        return buildList {
            track.points.take(TelemetryStore.MAX_POINTS).forEach { point ->
                val at = timeline.elapsedToSession(point.elapsedMs)
                if (!TelemetryPolicy.validStored(point) || at == null || containing(windows, point.elapsedMs) == null ||
                    previous?.let { point.elapsedMs <= it.elapsedMs } == true) {
                    broken = true
                } else {
                    add(point.copy(breakBefore = broken || point.breakBefore || previous?.let {
                        !TelemetryPlayback.connected(it, point) || !continuouslyCovered(windows, it.elapsedMs, point.elapsedMs)
                    } == true))
                    previous = point; broken = false
                }
            }
        }
    }

    fun vehicle(timeline: SessionPlaybackTimeline, track: VehicleTelemetryTrack?): List<VehicleTelemetrySample> {
        if (track?.header?.sessionId != timeline.sessionId) return emptyList()
        val windows = intervals(timeline)
        var broken = true
        var previous: VehicleTelemetrySample? = null
        return buildList {
            track.samples.take(VehicleTelemetryStore.MAX_SAMPLES).forEach { sample ->
                val at = timeline.elapsedToSession(sample.elapsedMs)
                if (!VehicleTelemetryPolicy.validStored(sample) || at == null || containing(windows, sample.elapsedMs) == null ||
                    previous?.let { sample.elapsedMs <= it.elapsedMs } == true) {
                    broken = true
                } else {
                    add(sample.copy(breakBefore = broken || sample.breakBefore || previous?.let {
                        sample.elapsedMs - it.elapsedMs > VehicleTelemetryPolicy.PLAYBACK_AGE_MS ||
                            !continuouslyCovered(windows, it.elapsedMs, sample.elapsedMs)
                    } == true))
                    previous = sample; broken = false
                }
            }
        }
    }

    fun observations(timeline: SessionPlaybackTimeline, samples: List<VehicleTelemetrySample>): List<DriveEventObservation> =
        samples.map { sample ->
            val frame = DriveHudModel.frame(sample.elapsedMs, sample.values.mapValues { (_, v) ->
                DriveHudObservation(v.readElapsedMs, v.number, v.text, v.status)
            })
            val available = sample.values.any { (key, value) ->
                VehicleTelemetryPolicy.validValue(key, value) && DriveHudModel.fresh(sample.elapsedMs, value.readElapsedMs) &&
                    value.status in setOf("OK", "PROVISIONAL_RAW") && (value.number != null || value.text != null)
            }
            DriveEventObservation(sample.elapsedMs - timeline.originElapsedMs, frame.turn, frame.brakePressed,
                sample.breakBefore, available)
        }

    /** Sorted union, built once; hundreds of thousands of getters never scan all segment files. */
    private fun intervals(timeline: SessionPlaybackTimeline): List<Pair<Long, Long>> = buildList {
        timeline.segments.forEach { segment ->
            val start = segment.mediaStartElapsedMs!!; val end = segment.mediaEndElapsedMs!!
            val previous = lastOrNull()
            if (previous != null && start <= previous.second) this[lastIndex] = previous.first to maxOf(previous.second, end)
            else add(start to end)
        }
    }

    private fun containing(windows: List<Pair<Long, Long>>, at: Long): Pair<Long, Long>? {
        val index = windows.binarySearchBy(at) { it.first }.let { if (it >= 0) it else -it - 2 }
        return windows.getOrNull(index)?.takeIf { at < it.second }
    }

    private fun continuouslyCovered(windows: List<Pair<Long, Long>>, from: Long, to: Long): Boolean =
        containing(windows, from)?.let { to < it.second } == true
}
