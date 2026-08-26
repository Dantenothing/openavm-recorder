package com.dante.zeekrcapabilitylab.product

import android.hardware.camera2.CameraCharacteristics
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorRecordingPolicyTest {
    @Test
    fun emulatorDetectionRecognizesAvdWithoutMatchingPhysicalDeviceNames() {
        assertTrue(
            EmulatorDevicePolicy.isEmulator(
                fingerprint = "google/sdk_gphone64_x86_64/emu64xa:15/test-keys",
                model = "sdk_gphone64_x86_64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64xa",
                hardware = "ranchu",
                product = "sdk_gphone64_x86_64",
            ),
        )
        assertFalse(
            EmulatorDevicePolicy.isEmulator(
                fingerprint = "zeekr/headunit/device:15/release-keys",
                model = "Head Unit",
                manufacturer = "Zeekr",
                brand = "Zeekr",
                device = "headunit",
                hardware = "vehicle-soc",
                product = "vehicle",
            ),
        )
    }

    @Test
    fun backCameraAndConservativeProfileWin() {
        val sizes = listOf(ProfileSize(640, 480), ProfileSize(1280, 720))
        val front = RuntimeCameraSource(
            cameraId = "1",
            fingerprint = "front",
            mediaRecorderSizes = sizes,
            surfaceTextureSizes = sizes,
            mediaCodecSizes = sizes,
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
        )
        val back = front.copy(
            cameraId = "10",
            fingerprint = "back",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
        )

        assertEquals(back, EmulatorRecordingPolicy.selectSource(listOf(front, back)))
        assertEquals(ProfileSize(1280, 720), EmulatorRecordingPolicy.selectSize(back))
    }

    @Test
    fun sourceWithoutSupportedRecorderSizeIsRejected() {
        val source = RuntimeCameraSource(
            cameraId = "10",
            fingerprint = "unsupported",
            mediaRecorderSizes = listOf(ProfileSize(1920, 1080)),
            surfaceTextureSizes = emptyList(),
            mediaCodecSizes = emptyList(),
        )

        assertNull(EmulatorRecordingPolicy.selectSource(listOf(source)))
    }

    @Test
    fun selectedModeControlsTheDebugSourceSemantics() {
        assertEquals(
            RecordingSourceKind.DIRECT_FRONT,
            EmulatorRecordingPolicy.sourceKindFor(RecordingMode.FRONT_ONLY),
        )
        assertEquals(
            RecordingSourceKind.COMPOSITE,
            EmulatorRecordingPolicy.sourceKindFor(RecordingMode.SURROUND_360),
        )
    }
}
