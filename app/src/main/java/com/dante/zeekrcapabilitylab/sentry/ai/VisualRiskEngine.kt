package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.ModeStamp
import com.dante.zeekrcapabilitylab.sentry.SentryTriggerType
import java.util.ArrayDeque

/**
 * Deterministic, bounded, single-owner rule engine. No Android, images, wall-clock decisions or I/O.
 * A new arm/transition/model/layout requires a new instance. Nothing here enables a camera producer.
 */
class VisualRiskEngine(
    private val stamp: ModeStamp,
    private val armedAtMonotonicUs: Long,
    private val detectorVersion: String,
    profile: RiskProfile,
    private val rules: RiskRules = RiskRules(),
) {
    private val profile = profile.frozenCopy()
    val ruleConfigVersion: String = "${rules.version}/${profile.version}"
    private val zones = this.profile.lanes.associateBy { it.lane }
    private val lanes = zones.keys.sorted().associateWith { LaneState() }
    private var armed = true
    private var lastTimeUs = -1L
    private var lastFrameId = -1L
    private var nextTrackId = 0L

    init {
        require(validTime(armedAtMonotonicUs) && stamp.run >= 0 && stamp.transition >= 0)
        require(detectorVersion.isNotBlank() && detectorVersion.length <= 128)
    }

    fun disarm() {
        armed = false
        lanes.values.forEach { it.tracks.clear() }
    }

    fun process(frame: DetectionFrame, nowMonotonicUs: Long): RiskFrameResult {
        if (!armed) return rejected(RiskFrameStatus.DISARMED)
        if (frame.stamp() != stamp) return rejected(RiskFrameStatus.STALE_SESSION)
        if (!validTime(frame.monotonicUs) || !validTime(nowMonotonicUs) || frame.frameId < 0 || !validTime(frame.wallEpochMs)) {
            return invalid(RiskFrameStatus.INVALID_INPUT)
        }
        if (frame.frameId <= lastFrameId || frame.monotonicUs <= lastTimeUs || frame.monotonicUs < armedAtMonotonicUs) {
            return rejected(RiskFrameStatus.STALE_FRAME)
        }
        if (frame.monotonicUs > nowMonotonicUs || nowMonotonicUs - frame.monotonicUs > rules.maxResultAgeUs) {
            return invalid(RiskFrameStatus.STALE_RESULT)
        }
        if (frame.detectorVersion != detectorVersion || frame.layoutVersion != profile.layout.version) {
            return invalid(RiskFrameStatus.VERSION_MISMATCH)
        }
        if (frame.schemaVersion != 1 || frame.lanes.size != lanes.size ||
            frame.lanes.map { it.lane }.toSet() != lanes.keys || frame.lanes.any { it.objects.size > 256 }) {
            return invalid(RiskFrameStatus.INVALID_INPUT)
        }
        lastTimeUs = frame.monotonicUs
        lastFrameId = frame.frameId
        val now = frame.monotonicUs
        val evidence = mutableListOf<TrackRiskEvidence>()
        val emitted = mutableListOf<TrackRiskEvidence>()
        val suppressed = mutableSetOf<Int>()
        var dropped = 0
        for (input in frame.lanes.sortedBy { it.lane }) {
            val lane = lanes.getValue(input.lane)
            lane.tracks.values.removeAll { now - it.lastSeenUs > rules.trackExpiryUs }
            if (input.quality != SceneQuality.GOOD) lane.suppressedUntilUs = now + rules.sceneRecoveryUs
            if (input.quality != SceneQuality.GOOD || now < lane.suppressedUntilUs) {
                suppressed += input.lane
                lane.tracks.values.forEach { it.resetContinuity(); it.baselineRevoked = true }
                continue
            }
            val objects = nonMaxSuppression(input.objects)
            dropped += input.objects.size - objects.size
            val assigned = associate(lane, objects)
            val observedIds = mutableSetOf<Long>()
            for ((index, detection) in objects.withIndex()) {
                var track = assigned[index]
                if (track == null) {
                    if (lane.tracks.size >= rules.maxTracksPerLane) { dropped++; continue }
                    track = Track(++nextTrackId, detection.kind, now, detection.box)
                    lane.tracks[track.id] = track
                }
                observedIds += track.id
                if (now - track.lastSeenUs > rules.maxObservationGapUs) {
                    track.resetContinuity()
                    track.baselineRevoked = true
                }
                track.lastSeenUs = now
                track.box = detection.box
                val region = zones.getValue(input.lane)
                val zone = region.zoneAt(detection.box.foot)
                val item = score(input.lane, track, detection, zone, region, now)
                evidence += item
                if (item.confirmed && (track.lastEmittedUs == null || now - track.lastEmittedUs!! >= rules.triggerRepeatUs)) {
                    track.lastEmittedUs = now
                    emitted += item
                }
            }
            lane.tracks.values.filter { it.id !in observedIds }.forEach {
                it.resetContinuity()
                // Occlusion breaks the initial stationary proof; an unseen target cannot keep an exemption.
                it.baselineRevoked = true
            }
        }
        val signals = if (emitted.isEmpty()) emptyList() else listOf(SentryTriggerSignal(
            runGeneration = stamp.run, transitionGeneration = stamp.transition,
            providerId = "visual-risk", type = SentryTriggerType.VISUAL_RISK,
            monotonicUs = nowMonotonicUs, wallEpochMs = frame.wallEpochMs + (nowMonotonicUs - now) / 1000,
            confidence = emitted.maxOf { it.confidence }, lanes = emitted.map { it.lane }.toSortedSet(),
            tracks = emitted.sortedWith(compareByDescending<TrackRiskEvidence> { it.score }.thenBy { it.trackId }).take(16),
            detectorVersion = detectorVersion, ruleConfigVersion = ruleConfigVersion,
            observedAtMonotonicUs = now,
        ))
        return RiskFrameResult(RiskFrameStatus.ACCEPTED,
            watch = suppressed.isNotEmpty() || evidence.any { it.zone >= RiskZone.MID },
            tracks = evidence, triggers = signals, suppressedLanes = suppressed,
            retainedTrackCount = lanes.values.sumOf { it.tracks.size }, droppedObjects = dropped)
    }

    private fun score(lane: Int, track: Track, detection: ObjectDetection, zone: RiskZone, region: LaneRiskZones, now: Long): TrackRiskEvidence {
        val reasons = mutableListOf<String>()
        val originRatio = maxOf(detection.box.area / track.origin.area, track.origin.area / detection.box.area)
        if (detection.box.foot.distance(track.origin.foot) > rules.stationaryFootTolerance || originRatio > rules.stationaryAreaRatio) {
            track.baselineRevoked = true
        }
        track.observations++
        val learning = now - armedAtMonotonicUs < rules.baselineLearningUs
        val baseline = rules.baselineLearningUs > 0 && !track.baselineRevoked &&
            track.firstSeenUs - armedAtMonotonicUs <= rules.baselineEnrollmentUs &&
            now - armedAtMonotonicUs < rules.baselineLifetimeUs && track.observations >= rules.confirmationSamples

        track.history.addLast(Sample(now, detection.box, zone))
        while (track.history.size > rules.historySamples || now - track.history.first.timeUs > rules.trendWindowUs) track.history.removeFirst()
        val previous = track.history.first
        val inward = zone > previous.zone
        val approaching = now - previous.timeUs >= rules.trendMinUs &&
            detection.box.area / previous.box.area >= rules.approachAreaRatio &&
            (detection.box.foot.distance(previous.box.foot) >= rules.approachFootDistance || inward)
        if (zone >= RiskZone.NEAR) {
            if (track.nearSinceUs == null) track.nearSinceUs = now
        } else track.nearSinceUs = null
        val dwell = track.nearSinceUs?.let { now - it } ?: 0
        var value = when (zone) {
            RiskZone.FAR -> 0
            RiskZone.MID -> 10
            RiskZone.NEAR -> if (track.kind == ObjectKind.VEHICLE) 15 else 25
            RiskZone.CRITICAL -> if (track.kind == ObjectKind.VEHICLE) 35 else 40
        }
        reasons += "ZONE_${zone.name}"
        if (approaching && zone >= RiskZone.MID) { value += if (track.kind == ObjectKind.VEHICLE) 35 else 30; reasons += "APPROACHING" }
        if (inward) { value += 10; reasons += "ZONE_ENTRY" }
        if (track.kind != ObjectKind.VEHICLE && dwell >= rules.dwellUs) { value += 25; reasons += "NEAR_DWELL" }
        if (track.kind != ObjectKind.VEHICLE && dwell >= rules.longDwellUs) { value += 10; reasons += "LONG_DWELL" }
        if (track.kind == ObjectKind.PERSON && zone >= RiskZone.NEAR && region.atDoor(detection.box.foot)) { value += 20; reasons += "DOOR_REGION" }
        if (baseline) { value -= 35; reasons += "ARM_BASELINE_DISCOUNT" }
        value = value.coerceIn(0, 100)

        if (learning) {
            track.resetConfirmation()
            reasons += "BASELINE_LEARNING"
        } else if (value >= rules.triggerScore) {
            if (track.riskSinceUs == null) track.riskSinceUs = now
            track.highSamples++
        } else if (value < rules.releaseScore) track.resetConfirmation()
        // Hysteresis may retain the candidate, but the emitting frame must itself be high risk.
        val confirmed = !learning && value >= rules.triggerScore && track.highSamples >= rules.confirmationSamples &&
            track.riskSinceUs?.let { now - it >= rules.confirmationUs } == true
        if (confirmed) reasons += "CONTINUOUS_CONFIRMATION"
        return TrackRiskEvidence(lane, track.id, track.kind, detection.confidence, detection.box, zone, value, reasons, baseline, confirmed)
    }

    private fun nonMaxSuppression(input: List<ObjectDetection>): List<ObjectDetection> {
        val kept = mutableListOf<ObjectDetection>()
        val sorted = input.filter { it.confidence >= rules.minConfidence }.sortedWith(
            compareByDescending<ObjectDetection> { it.confidence }.thenBy { it.kind.ordinal }
                .thenBy { it.box.left }.thenBy { it.box.top }.thenBy { it.box.right }.thenBy { it.box.bottom })
        for (candidate in sorted) {
            if (kept.none { it.kind == candidate.kind && it.box.iou(candidate.box) >= 0.65 }) kept += candidate
            if (kept.size == rules.maxObjectsPerLane) break
        }
        return kept
    }

    private fun associate(lane: LaneState, objects: List<ObjectDetection>): Map<Int, Track> {
        data class Pairing(val index: Int, val track: Track, val iou: Double)
        val pairs = objects.flatMapIndexed { index, detection ->
            lane.tracks.values.mapNotNull { track ->
                val iou = track.box.iou(detection.box)
                if (track.kind == detection.kind && iou >= rules.matchIou &&
                    track.box.foot.distance(detection.box.foot) <= rules.maxFootJump) Pairing(index, track, iou) else null
            }
        }.sortedWith(compareByDescending<Pairing> { it.iou }.thenBy { it.track.id }.thenBy { it.index })
        val result = mutableMapOf<Int, Track>()
        val used = mutableSetOf<Long>()
        for (pair in pairs) if (pair.index !in result && used.add(pair.track.id)) result[pair.index] = pair.track
        return result
    }

    private fun invalid(status: RiskFrameStatus): RiskFrameResult {
        lanes.values.forEach { lane -> lane.tracks.values.forEach { it.resetContinuity(); it.baselineRevoked = true } }
        return rejected(status)
    }
    private fun rejected(status: RiskFrameStatus) = RiskFrameResult(status, retainedTrackCount = lanes.values.sumOf { it.tracks.size })
    private fun validTime(value: Long) = value in 0..Long.MAX_VALUE / 4
    private class LaneState {
        val tracks = linkedMapOf<Long, Track>()
        var suppressedUntilUs = -1L
    }
    private data class Sample(val timeUs: Long, val box: DetectionBox, val zone: RiskZone)
    private class Track(val id: Long, val kind: ObjectKind, val firstSeenUs: Long, val origin: DetectionBox) {
        var lastSeenUs = firstSeenUs
        var box = origin
        var observations = 0
        var baselineRevoked = false
        val history = ArrayDeque<Sample>()
        var nearSinceUs: Long? = null
        var riskSinceUs: Long? = null
        var highSamples = 0
        var lastEmittedUs: Long? = null
        fun resetConfirmation() { riskSinceUs = null; highSamples = 0 }
        fun resetContinuity() { history.clear(); nearSinceUs = null; resetConfirmation() }
    }
}
