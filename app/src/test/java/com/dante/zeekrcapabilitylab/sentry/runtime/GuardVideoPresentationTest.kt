package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class GuardVideoPresentationTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private fun event(triggers: List<GuardTrigger>) = GuardEvent(
        id = id, runId = "test", source = SessionSourceSnapshot(RecordingSourceRole.SURROUND, "2",
            CameraFormatProfile(ProfileSize(1280, 5140), 4_000_000), RecordingLayoutKind.FOUR_LANE_V1),
        createdAtEpochMs = 100_000, triggerPtsUs = 120_000_000, targetEndPtsUs = 180_000_000,
        triggers = triggers, ai = GuardAiState())
    private fun asset(first: Long, last: Long, duration: Long? = null) =
        GuardAsset(GuardVideoPresentation.assetName(id, 1), 1, first, last, 120, actualDurationMs = duration)

    @Test fun earlyTriggerUsesActualPreRollAndKeepsEveryRecordedRepeat() {
        val source = event(listOf(
            GuardTrigger("MANUAL", 120_000_000, 100_000),
            GuardTrigger("VISUAL_RISK", 135_000_000, 115_000, setOf(0, 1, 3, 5)),
            GuardTrigger("MANUAL", 137_500_000, 117_500)))
        val points = GuardVideoPresentation.markers(source, asset(103_000_000, 162_000_000, 59_030))
        assertEquals(listOf(17_000L, 32_000L, 34_500L), points.map { it.positionMs })
        assertEquals(setOf(1, 3), points[1].lanes)
        assertEquals(115_000L, points[1].epochMs)
    }

    @Test fun partialEventGapsNeverProduceFabricatedWarningPositions() {
        val source = event(listOf(
            GuardTrigger("MANUAL", 101_000_000, 1),
            GuardTrigger("VISUAL_RISK", 125_000_000, 2),
            GuardTrigger("MANUAL", 141_000_000, 3)))
        val first = GuardVideoPresentation.markers(source, asset(100_000_000, 110_000_000))
        val second = GuardVideoPresentation.markers(source, asset(140_000_000, 150_000_000))
        assertEquals(listOf(1L, 3L), (first + second).map { it.epochMs })
        assertEquals(listOf(1_000L, 1_000L), (first + second).map { it.positionMs })
    }

    @Test fun lateTriggerMetadataCanBeRebuiltForAnAlreadyCommittedChunk() {
        val original = event(listOf(GuardTrigger("MANUAL", 120_000_000, 1)))
        val chunk = asset(100_000_000, 130_000_000)
        assertEquals(1, GuardVideoPresentation.markers(original, chunk).size)
        val updated = original.copy(triggers = original.triggers + GuardTrigger("VISUAL_RISK", 129_000_000, 2))
        assertEquals(listOf(20_000L, 29_000L), GuardVideoPresentation.markers(updated, chunk).map { it.positionMs })
    }

    @Test fun badAssetBoundsAndOutOfDurationPointsAreRejected() {
        val source = event(listOf(GuardTrigger("MANUAL", 120_000_000, 1)))
        assertTrue(GuardVideoPresentation.markers(source, asset(-1, 130_000_000)).isEmpty())
        assertTrue(GuardVideoPresentation.markers(source, asset(130_000_000, 110_000_000)).isEmpty())
        assertTrue(GuardVideoPresentation.markers(source, asset(100_000_000, 130_000_000, 20_000)).isEmpty())
    }

    @Test fun recognizableNamesStayCompatibleWithExistingEventsAndRejectForeignPaths() {
        assertEquals("OpenAVM_Sentry_" + id + "-2.mp4", GuardVideoPresentation.assetName(id, 2))
        assertTrue(GuardVideoPresentation.validAssetName(id + "-1.mp4"))
        assertTrue(GuardVideoPresentation.validAssetName("OpenAVM_Sentry_" + id + "-8.mp4"))
        for (name in listOf("../" + id + "-1.mp4", id + "-0.mp4", id + "-9.mp4",
            "SentryMode/01.mp4", id + "-1.mp4.partial", "OpenAVM_Sentry_" + id + "-1.json")) {
            assertFalse(name, GuardVideoPresentation.validAssetName(name))
        }
    }
}
