package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.service.recorder.SegmentLaneLayout

/** Take this snapshot when arming. Never infer AI geometry from a filename or later UI settings. */
object FrozenLaneLayoutAdapter {
    fun freeze(source: SegmentLaneLayout, version: String, mirroredLanes: Set<Int> = emptySet()): DetectorLayout {
        require(mirroredLanes.all { lane -> source.lanes.any { it.lane == lane } })
        return DetectorLayout(version, source.originalWidth, source.originalHeight, source.lanes.map {
            DetectorLane(it.lane, it.x0, it.y0, it.x1, it.y1, it.rotationDegrees, it.lane in mirroredLanes)
        })
    }
}
