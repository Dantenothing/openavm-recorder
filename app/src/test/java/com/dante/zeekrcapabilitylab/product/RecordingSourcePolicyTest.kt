package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSourcePolicyTest {
    private fun camera(id: String, composite: Boolean = false) = RecordingCameraCapability(
        cameraId = id,
        profile = CameraFormatProfile(
            if (composite) ProfileSize(1280, 5140) else ProfileSize(1920, 1080),
            14_000_000,
        ),
        isFourLaneComposite = composite,
    )

    @Test
    fun testedVehicleDefaultsResolveToTwoOneZero() {
        val cameras = listOf(camera("0"), camera("1"), camera("2", composite = true))

        assertEquals("2", RecordingSourcePolicy.resolve(RecordingSourceRole.SURROUND, "auto", cameras)?.capability?.cameraId)
        assertEquals("1", RecordingSourcePolicy.resolve(RecordingSourceRole.CABIN, "1", cameras)?.capability?.cameraId)
        assertEquals("0", RecordingSourcePolicy.resolve(RecordingSourceRole.IR, "0", cameras)?.capability?.cameraId)
    }

    @Test
    fun surroundAutoUsesOnlyACompositeAndNeverFirstCameraFallback() {
        val cameras = listOf(camera("0"), camera("7", composite = true))

        val resolved = RecordingSourcePolicy.resolve(RecordingSourceRole.SURROUND, "auto", cameras)

        assertEquals("7", resolved?.capability?.cameraId)
        assertEquals(RecordingLayoutKind.FOUR_LANE_V1, resolved?.layoutKind)
        assertNull(RecordingSourcePolicy.resolve(RecordingSourceRole.SURROUND, "auto", listOf(camera("0"))))
    }

    @Test
    fun ambiguousSurroundRequiresKnownDefaultOrManualMapping() {
        val noKnownDefault = listOf(camera("6", true), camera("7", true))
        assertNull(RecordingSourcePolicy.resolve(RecordingSourceRole.SURROUND, "auto", noKnownDefault))

        val withKnownDefault = noKnownDefault + camera("2", true)
        assertEquals("2", RecordingSourcePolicy.resolve(RecordingSourceRole.SURROUND, "auto", withKnownDefault)?.capability?.cameraId)
    }

    @Test
    fun staleOrSemanticallyInvalidMappingDoesNotFallback() {
        val cameras = listOf(camera("0"), camera("1"), camera("2", true))

        assertNull(RecordingSourcePolicy.resolve(RecordingSourceRole.CABIN, "9", cameras))
        assertNull(RecordingSourcePolicy.resolve(RecordingSourceRole.IR, "9", cameras))
        assertNull(RecordingSourcePolicy.resolve(RecordingSourceRole.SURROUND, "1", cameras))
    }

    @Test
    fun cabinAndIrUseSingleLayout() {
        val cameras = listOf(camera("0"), camera("1"))
        assertEquals(RecordingLayoutKind.SINGLE_V1, RecordingSourcePolicy.resolve(RecordingSourceRole.CABIN, "1", cameras)?.layoutKind)
        assertEquals(RecordingLayoutKind.SINGLE_V1, RecordingSourcePolicy.resolve(RecordingSourceRole.IR, "0", cameras)?.layoutKind)
    }
}
