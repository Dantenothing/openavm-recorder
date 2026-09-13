package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.ModeStamp

internal object AiFixtures {
    val stamp = ModeStamp(7, 3)
    const val detector = "fixture-detector-v1"
    fun rect(left: Double, top: Double, right: Double, bottom: Double) = ImagePolygon(listOf(
        ImagePoint(left, top), ImagePoint(right, top), ImagePoint(right, bottom), ImagePoint(left, bottom)))
    fun profile(laneCount: Int = 1) = RiskProfile("fixture-zones-v1",
        DetectorLayout("fixture-layout-v1", 100, 100 * laneCount, (1..laneCount).map {
            DetectorLane(it, 0, (it - 1) * 100, 100, it * 100)
        }), (1..laneCount).map { lane -> LaneRiskZones(lane, listOf(
            ZoneRegion(RiskZone.MID, rect(0.0, 0.35, 1.0, 0.65)),
            ZoneRegion(RiskZone.NEAR, rect(0.0, 0.65, 1.0, 0.85)),
            ZoneRegion(RiskZone.CRITICAL, rect(0.0, 0.85, 1.0, 1.0)),
        ), doors = listOf(rect(0.25, 0.8, 0.75, 1.0))) })
    fun engine(rules: RiskRules = RiskRules(), profile: RiskProfile = profile()) = VisualRiskEngine(stamp, 0, detector, profile, rules)
    fun person(box: DetectionBox = DetectionBox(0.42, 0.45, 0.58, 0.9), kind: ObjectKind = ObjectKind.PERSON) = ObjectDetection(kind, 0.9, box)
    fun frame(timeUs: Long, objects: List<ObjectDetection> = listOf(person()), quality: SceneQuality = SceneQuality.GOOD, laneCount: Int = 1) = DetectionFrame(
        runGeneration = stamp.run, transitionGeneration = stamp.transition, frameId = timeUs,
        monotonicUs = timeUs, wallEpochMs = 1_700_000_000_000 + timeUs / 1000,
        detectorVersion = detector, layoutVersion = "fixture-layout-v1",
        lanes = (1..laneCount).map { LaneDetections(it, quality, objects) })
    fun feed(engine: VisualRiskEngine, start: Long, end: Long, objects: List<ObjectDetection> = listOf(person())) =
        (start..end step 500_000).map { engine.process(frame(it, objects), it) }
}
