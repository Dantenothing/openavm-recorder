package com.dante.zeekrcapabilitylab.sentry.ai

import org.junit.Assert.*
import org.junit.Test

class DetectorGeometryTest {
    @Test fun edgeBelongsToHighestZoneAndDoorUsesLaneCoordinates() {
        val zones = AiFixtures.profile().lanes.single()
        assertEquals(RiskZone.NEAR, zones.zoneAt(ImagePoint(0.5, 0.65)))
        assertEquals(RiskZone.CRITICAL, zones.zoneAt(ImagePoint(0.5, 0.85)))
        assertEquals(RiskZone.FAR, zones.zoneAt(ImagePoint(0.5, 0.1)))
        assertTrue(zones.atDoor(ImagePoint(0.5, 0.9)))
        assertFalse(zones.atDoor(ImagePoint(0.1, 0.9)))
    }
    @Test fun rotationThenMirrorMatchesUprightCropAndRejectsSeparator() {
        val lane = DetectorLane(2, 0, 1288, 1280, 2568, 90, true)
        assertEquals(ImagePoint(0.25, 0.75), lane.sourcePointToLane(960.0, 1608.0))
        assertNull(lane.sourcePointToLane(500.0, 1285.0))
        assertEquals(ImagePoint(0.25, 0.75), lane.copy(rotationDegrees = 180, mirrorHorizontal = false).sourcePointToLane(960.0, 1608.0))
        assertEquals(ImagePoint(0.25, 0.25), lane.copy(rotationDegrees = 270, mirrorHorizontal = false).sourcePointToLane(960.0, 1608.0))
    }
    @Test fun invalidGeometryCannotEnterInferenceOrRiskEvaluation() {
        assertThrows(IllegalArgumentException::class.java) { DetectionBox(Double.NaN, 0.0, 1.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { DetectionBox(0.2, 0.0, 0.1, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { DetectorLane(1, 0, 0, 1, 1, 45) }
        assertThrows(IllegalArgumentException::class.java) { DetectorLayout("bad", 100, 100, listOf(DetectorLane(1, 0, 0, 120, 80))) }
        assertThrows(IllegalArgumentException::class.java) { ImagePolygon(listOf(ImagePoint(0.0, 0.0), ImagePoint(0.8, 0.8), ImagePoint(0.0, 1.0), ImagePoint(1.0, 0.0))) }
        assertThrows(IllegalArgumentException::class.java) { ObjectDetection(ObjectKind.PERSON, Double.NaN, AiFixtures.person().box) }
    }
    @Test fun armProfileCopiesMutableZoneLists() {
        val mutableZones = AiFixtures.profile().lanes.single().regions.toMutableList()
        val profile = AiFixtures.profile().copy(lanes = listOf(AiFixtures.profile().lanes.single().copy(regions = mutableZones)))
        val engine = AiFixtures.engine(profile = profile)
        mutableZones.clear()
        assertEquals(RiskZone.CRITICAL, engine.process(AiFixtures.frame(4_000_000), 4_000_000).tracks.single().zone)
    }
}
