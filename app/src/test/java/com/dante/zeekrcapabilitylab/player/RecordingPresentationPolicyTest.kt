package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPresentationPolicyTest {
    @Test
    fun schemaFourUsesFrozenMeaningEvenIfDimensionsLookDifferent() {
        val result = RecordingPresentationPolicy.resolve(
            sidecar(
                schema = 4,
                cameraId = "9",
                size = ProfileSize(1280, 5140),
                role = RecordingSourceRole.CABIN,
                layout = RecordingLayoutKind.SINGLE_V1,
            ),
        )

        assertEquals(RecordingSourceRole.CABIN, result.sourceRole)
        assertEquals(RecordingLayoutKind.SINGLE_V1, result.layoutKind)
    }

    @Test
    fun legacyCompositeIsFourLaneSurround() {
        val result = RecordingPresentationPolicy.resolve(
            sidecar(schema = 3, cameraId = "2", size = ProfileSize(1280, 5140)),
        )

        assertEquals(RecordingSourceRole.SURROUND, result.sourceRole)
        assertEquals(RecordingLayoutKind.FOUR_LANE_V1, result.layoutKind)
    }

    @Test
    fun legacyKnownSingleCameraUsesItsOwnRecordedCameraId() {
        val cabin = RecordingPresentationPolicy.resolve(
            sidecar(schema = 3, cameraId = "1", size = ProfileSize(1920, 1080)),
        )
        val ir = RecordingPresentationPolicy.resolve(
            sidecar(schema = 3, cameraId = "0", size = ProfileSize(1920, 1080)),
        )

        assertEquals(RecordingSourceRole.CABIN, cabin.sourceRole)
        assertEquals(RecordingLayoutKind.SINGLE_V1, cabin.layoutKind)
        assertEquals(RecordingSourceRole.IR, ir.sourceRole)
        assertEquals(RecordingLayoutKind.SINGLE_V1, ir.layoutKind)
    }

    private fun sidecar(
        schema: Int,
        cameraId: String,
        size: ProfileSize,
        role: RecordingSourceRole = RecordingSourceRole.SURROUND,
        layout: RecordingLayoutKind = RecordingLayoutKind.FOUR_LANE_V1,
    ) = SegmentSidecar(
        schemaVersion = schema,
        file = "recording.mp4",
        cameraId = cameraId,
        profile = CameraFormatProfile(size, 8_000_000),
        sourceRole = role,
        layoutKind = layout,
        segmentSeconds = 60,
        segmentNumber = 1,
        processStartId = "test",
        result = SegmentSidecar.RESULT_SUCCESS,
    )
}
