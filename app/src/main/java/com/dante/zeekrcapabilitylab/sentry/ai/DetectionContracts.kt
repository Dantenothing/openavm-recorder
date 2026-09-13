package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.ModeStamp
import com.dante.zeekrcapabilitylab.sentry.SentryTriggerType
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

@Serializable
data class ImagePoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) }
    fun distance(other: ImagePoint) = hypot(x - other.x, y - other.y)
}

/** Normalized, upright lane coordinates, after the frozen crop/rotation/mirror. No metres. */
@Serializable
data class DetectionBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(left < right && top < bottom)
    }
    val area: Double get() = (right - left) * (bottom - top)
    val foot: ImagePoint get() = ImagePoint((left + right) / 2, bottom)
    fun iou(other: DetectionBox): Double {
        val intersection = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0.0) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0.0)
        return intersection / (area + other.area - intersection)
    }
}

@Serializable
data class ImagePolygon(val vertices: List<ImagePoint>) {
    init {
        require(vertices.size in 3..16 && vertices.distinct().size == vertices.size)
        require(abs(vertices.indices.sumOf { i ->
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            a.x * b.y - b.x * a.y
        }) > 1e-8) { "Degenerate zone" }
        for (i in vertices.indices) for (j in i + 1 until vertices.size) {
            if (j == i + 1 || (i == 0 && j == vertices.lastIndex)) continue
            require(!intersects(vertices[i], vertices[(i + 1) % vertices.size],
                vertices[j], vertices[(j + 1) % vertices.size])) { "Self-intersecting zone" }
        }
    }

    fun contains(point: ImagePoint): Boolean {
        var inside = false
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            if (onSegment(a, b, point)) return true
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        }
        return inside
    }

    private fun cross(a: ImagePoint, b: ImagePoint, p: ImagePoint) =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
    private fun onSegment(a: ImagePoint, b: ImagePoint, p: ImagePoint) =
        abs(cross(a, b, p)) < 1e-9 && p.x >= minOf(a.x, b.x) - 1e-9 &&
            p.x <= maxOf(a.x, b.x) + 1e-9 && p.y >= minOf(a.y, b.y) - 1e-9 &&
            p.y <= maxOf(a.y, b.y) + 1e-9
    private fun intersects(a: ImagePoint, b: ImagePoint, c: ImagePoint, d: ImagePoint): Boolean =
        (cross(a, b, c) * cross(a, b, d) < 0 && cross(c, d, a) * cross(c, d, b) < 0) ||
            onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b)
}

@Serializable
data class DetectorLane(
    val lane: Int,
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false,
) {
    init {
        require(lane in 1..4 && x0 >= 0 && y0 >= 0 && x1 > x0 && y1 > y0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }

    /** Used for geometry verification. The detector itself receives the already upright crop. */
    fun sourcePointToLane(x: Double, y: Double): ImagePoint? {
        if (!x.isFinite() || !y.isFinite() || x < x0 || x > x1 || y < y0 || y > y1) return null
        val u = (x - x0) / (x1 - x0)
        val v = (y - y0) / (y1 - y0)
        val rotated = when (rotationDegrees) {
            90 -> ImagePoint(1 - v, u)
            180 -> ImagePoint(1 - u, 1 - v)
            270 -> ImagePoint(v, 1 - u)
            else -> ImagePoint(u, v)
        }
        return if (mirrorHorizontal) ImagePoint(1 - rotated.x, rotated.y) else rotated
    }
}

@Serializable
data class DetectorLayout(val version: String, val sourceWidth: Int, val sourceHeight: Int, val lanes: List<DetectorLane>) {
    init {
        require(version.isNotBlank() && version.length <= 128 && sourceWidth > 0 && sourceHeight > 0)
        require(lanes.size in 1..4 && lanes.map { it.lane }.distinct().size == lanes.size)
        require(lanes.all { it.x1 <= sourceWidth && it.y1 <= sourceHeight })
        for (i in lanes.indices) for (j in i + 1 until lanes.size) {
            val a = lanes[i]
            val b = lanes[j]
            require(minOf(a.x1, b.x1) <= maxOf(a.x0, b.x0) || minOf(a.y1, b.y1) <= maxOf(a.y0, b.y0))
        }
    }
}

@Serializable enum class RiskZone { FAR, MID, NEAR, CRITICAL }
@Serializable data class ZoneRegion(val zone: RiskZone, val polygon: ImagePolygon)
@Serializable
data class LaneRiskZones(val lane: Int, val regions: List<ZoneRegion>, val doors: List<ImagePolygon> = emptyList()) {
    init { require(lane in 1..4 && regions.size in 1..12 && doors.size <= 4) }
    fun zoneAt(point: ImagePoint) = regions.filter { it.polygon.contains(point) }.maxOfOrNull { it.zone } ?: RiskZone.FAR
    fun atDoor(point: ImagePoint) = doors.any { it.contains(point) }
}

@Serializable
data class RiskProfile(
    val version: String,
    val layout: DetectorLayout,
    val lanes: List<LaneRiskZones>,
    /** Offline examples are deliberately false; a version string is not a calibration claim. */
    val vehicleCalibrated: Boolean = false,
) {
    init {
        require(version.isNotBlank() && version.length <= 128)
        require(lanes.size == layout.lanes.size && lanes.map { it.lane }.toSet() == layout.lanes.map { it.lane }.toSet())
    }
    fun frozenCopy() = copy(layout = layout.copy(lanes = layout.lanes.toList()), lanes = lanes.map { lane ->
        lane.copy(regions = lane.regions.map { it.copy(polygon = it.polygon.copy(vertices = it.polygon.vertices.toList())) },
            doors = lane.doors.map { it.copy(vertices = it.vertices.toList()) })
    })
}

@Serializable enum class ObjectKind { PERSON, VEHICLE, TWO_WHEELER }
@Serializable enum class SceneQuality { GOOD, EXPOSURE_CHANGE, CAMERA_SHAKE, LOW_VISIBILITY, UNKNOWN }
@Serializable
data class ObjectDetection(val kind: ObjectKind, val confidence: Double, val box: DetectionBox) {
    init { require(confidence.isFinite() && confidence in 0.0..1.0) }
}
@Serializable data class LaneDetections(val lane: Int, val quality: SceneQuality, val objects: List<ObjectDetection>)
@Serializable
data class DetectionFrame(
    val schemaVersion: Int = 1,
    val runGeneration: Long,
    val transitionGeneration: Long,
    val frameId: Long,
    val monotonicUs: Long,
    val wallEpochMs: Long,
    val detectorVersion: String,
    val layoutVersion: String,
    /** Includes every configured lane, even when its detection list is empty. */
    val lanes: List<LaneDetections>,
) {
    fun stamp() = ModeStamp(runGeneration, transitionGeneration)
}

@Serializable
data class RiskRules(
    val version: String = "risk-rules-offline-v1",
    val minConfidence: Double = 0.5,
    val maxObjectsPerLane: Int = 32,
    val maxTracksPerLane: Int = 24,
    val historySamples: Int = 24,
    val matchIou: Double = 0.2,
    val maxFootJump: Double = 0.25,
    val trackExpiryUs: Long = 2_000_000,
    val maxObservationGapUs: Long = 750_000,
    val maxResultAgeUs: Long = 1_000_000,
    val baselineEnrollmentUs: Long = 500_000,
    val baselineLearningUs: Long = 3_000_000,
    val baselineLifetimeUs: Long = 30_000_000,
    val stationaryFootTolerance: Double = 0.03,
    val stationaryAreaRatio: Double = 1.2,
    val trendWindowUs: Long = 2_000_000,
    val trendMinUs: Long = 500_000,
    val approachAreaRatio: Double = 1.3,
    val approachFootDistance: Double = 0.035,
    val dwellUs: Long = 4_000_000,
    val longDwellUs: Long = 10_000_000,
    val triggerScore: Int = 70,
    val releaseScore: Int = 50,
    val confirmationSamples: Int = 3,
    val confirmationUs: Long = 1_000_000,
    val triggerRepeatUs: Long = 10_000_000,
    val sceneRecoveryUs: Long = 1_500_000,
) {
    init {
        require(version.isNotBlank() && version.length <= 128 && minConfidence in 0.0..1.0)
        require(maxObjectsPerLane in 1..64 && maxTracksPerLane in 1..64 && historySamples in 3..64)
        require(matchIou in 0.01..1.0 && maxFootJump in 0.01..1.0)
        require(maxObservationGapUs in 1..5_000_000 && trackExpiryUs in maxObservationGapUs..30_000_000)
        require(maxResultAgeUs in 1..5_000_000 && baselineEnrollmentUs in 0..baselineLearningUs)
        require(baselineLearningUs in 0..30_000_000 && baselineLifetimeUs in baselineLearningUs..120_000_000)
        require(stationaryFootTolerance in 0.0..0.2 && stationaryAreaRatio in 1.0..2.0)
        require(trendMinUs in 1..trendWindowUs && trendWindowUs <= 10_000_000)
        require(approachAreaRatio in 1.01..4.0 && approachFootDistance in 0.0..1.0)
        require(dwellUs in 1..60_000_000 && longDwellUs in dwellUs..120_000_000)
        require(releaseScore in 0 until triggerScore && triggerScore <= 100)
        require(confirmationSamples in 2..30 && confirmationUs in 1..30_000_000)
        require(triggerRepeatUs in confirmationUs..60_000_000 && sceneRecoveryUs in 0..30_000_000)
    }
}

@Serializable
data class TrackRiskEvidence(
    val lane: Int, val trackId: Long, val kind: ObjectKind, val confidence: Double,
    val box: DetectionBox, val zone: RiskZone, val score: Int, val reasons: List<String>,
    val baselineDiscounted: Boolean, val confirmed: Boolean,
)

@Serializable
data class SentryTriggerSignal(
    val runGeneration: Long, val transitionGeneration: Long,
    val providerId: String, val type: SentryTriggerType,
    val monotonicUs: Long, val wallEpochMs: Long, val confidence: Double,
    val lanes: Set<Int>, val tracks: List<TrackRiskEvidence> = emptyList(),
    val detectorVersion: String? = null, val ruleConfigVersion: String? = null,
    /** Capture time is separate from the monotonic time at which the rule decision was made. */
    val observedAtMonotonicUs: Long? = null,
) {
    fun stamp() = ModeStamp(runGeneration, transitionGeneration)
}

@Serializable enum class RiskFrameStatus { ACCEPTED, DISARMED, STALE_SESSION, STALE_FRAME, STALE_RESULT, INVALID_INPUT, VERSION_MISMATCH }
@Serializable
data class RiskFrameResult(
    val status: RiskFrameStatus,
    val watch: Boolean = false,
    val tracks: List<TrackRiskEvidence> = emptyList(),
    val triggers: List<SentryTriggerSignal> = emptyList(),
    val suppressedLanes: Set<Int> = emptySet(),
    val retainedTrackCount: Int = 0,
    val droppedObjects: Int = 0,
)
