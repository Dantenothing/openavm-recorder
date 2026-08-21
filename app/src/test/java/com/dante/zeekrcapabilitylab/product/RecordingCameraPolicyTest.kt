package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingCameraPolicyTest {

    @Test
    fun noUsableCamerasMeansNoSelection() {
        assertNull(RecordingCameraPolicy.choose(RecordingCameraPolicy.AUTO, emptyList()))
        assertNull(RecordingCameraPolicy.choose("5", emptyList()))
    }

    @Test
    fun autoPrefersTheLegacyDefaultCameraWhenUsable() {
        assertEquals(
            RecordingCameraPolicy.Selection(cameraId = "2", fallbackFromRequested = false),
            RecordingCameraPolicy.choose(RecordingCameraPolicy.AUTO, listOf("0", "2", "5")),
        )
    }

    @Test
    fun autoFallsBackToTheFirstUsableCameraOtherwise() {
        assertEquals(
            RecordingCameraPolicy.Selection(cameraId = "0", fallbackFromRequested = false),
            RecordingCameraPolicy.choose(RecordingCameraPolicy.AUTO, listOf("0", "1", "5")),
        )
    }

    @Test
    fun anExplicitUsableSelectionIsHonoured() {
        assertEquals(
            RecordingCameraPolicy.Selection(cameraId = "5", fallbackFromRequested = false),
            RecordingCameraPolicy.choose("5", listOf("0", "2", "5")),
        )
    }

    @Test
    fun anExplicitUnusableSelectionFallsBackToAutoAndSaysSo() {
        assertEquals(
            RecordingCameraPolicy.Selection(cameraId = "2", fallbackFromRequested = true),
            RecordingCameraPolicy.choose("9", listOf("0", "2", "5")),
        )
        assertEquals(
            RecordingCameraPolicy.Selection(cameraId = "0", fallbackFromRequested = true),
            RecordingCameraPolicy.choose("9", listOf("0", "1")),
        )
    }
}
