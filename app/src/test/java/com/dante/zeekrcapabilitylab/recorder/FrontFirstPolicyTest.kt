package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.RuntimeCameraSource
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.service.recorder.CameraSourceFacts
import com.dante.zeekrcapabilitylab.service.recorder.CameraSourceFingerprint
import com.dante.zeekrcapabilitylab.service.recorder.EncoderProfile
import com.dante.zeekrcapabilitylab.service.recorder.FrontCropPolicy
import com.dante.zeekrcapabilitylab.service.recorder.FrontCalibrationConfirmationPolicy
import com.dante.zeekrcapabilitylab.service.recorder.FrontEncoderProfilePolicy
import com.dante.zeekrcapabilitylab.service.recorder.FrontTextureCoordinates
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind
import com.dante.zeekrcapabilitylab.service.recorder.VideoEncoderCapability
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontFirstPolicyTest {
    private val composite = ProfileSize(1280, 5140)
    private val fingerprint = "abc123"
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun sourceKindUsesTheMatchingCamera2SurfaceClassSizes() {
        val recorder = listOf(ProfileSize(1280, 5140))
        val texture = listOf(ProfileSize(640, 2570))
        val codec = listOf(ProfileSize(1280, 1280))
        val source = RuntimeCameraSource("2", "fingerprint", recorder, texture, codec)

        assertEquals(recorder, source.sizesFor(RecordingSourceKind.COMPOSITE))
        assertEquals(texture, source.sizesFor(RecordingSourceKind.COMPOSITE_CROP))
        assertEquals(codec, source.sizesFor(RecordingSourceKind.DIRECT_FRONT))
        assertEquals(
            ProfileSize(1280, 1280),
            ProductRecorderConfigFactory.directFrontSourceSize(source, ProfileSize(1280, 1280)),
        )
    }

    @Test
    fun fingerprintIsOrderStableAndChangesWithGeometry() {
        val facts = CameraSourceFacts(
            cameraId = "2",
            lensFacing = 1,
            hardwareLevel = 2,
            sensorOrientation = 90,
            activeArray = "0 0 1280 5140",
            capabilities = listOf(3, 1),
            recordSizes = listOf(ProfileSize(1920, 1080), composite),
        )
        val reordered = facts.copy(
            capabilities = facts.capabilities.reversed(),
            recordSizes = facts.recordSizes.reversed(),
        )

        assertEquals(CameraSourceFingerprint.create(facts), CameraSourceFingerprint.create(reordered))
        assertNotEquals(
            CameraSourceFingerprint.create(facts),
            CameraSourceFingerprint.create(facts.copy(recordSizes = listOf(ProfileSize(1280, 5120)))),
        )
    }

    @Test
    fun allFourVerticalLanesExcludeKnownSeparatorBands() {
        (1..4).forEach { lane ->
            val crop = requireNotNull(FrontCropPolicy.laneCrop(composite, lane))
            assertEquals(0f, crop.left)
            assertEquals(1f, crop.right)
            val expectedTop = (4f + (lane - 1) * 1284f) / 5140f
            assertEquals(expectedTop, crop.top, 0.000001f)
            assertEquals(expectedTop + 1280f / 5140f, crop.bottom, 0.000001f)
            assertTrue(crop.validate().isEmpty())
        }
    }

    @Test
    fun fixedFrontSelectionAlwaysUsesFirstLaneWithoutRotation() {
        val selection = requireNotNull(FrontCropPolicy.fixedFrontSelection(fingerprint, composite))

        assertEquals(FrontCropPolicy.FIXED_FRONT_LANE, selection.frontLane)
        assertEquals(1, selection.frontLane)
        assertEquals(FrontCropPolicy.FIXED_FRONT_ROTATION_DEGREES, selection.rotationDegrees)
        assertEquals(0, selection.rotationDegrees)
        assertEquals(requireNotNull(FrontCropPolicy.laneCrop(composite, 1)), selection.crop)
        assertTrue(selection.matches(fingerprint, composite))
        assertNull(FrontCropPolicy.fixedFrontSelection(fingerprint, ProfileSize(1920, 1080)))
    }

    @Test
    fun calibrationRequiresVisibleFirstFrameFromUnchangedSource() {
        assertTrue(FrontCalibrationConfirmationPolicy.canSave(true, true, true, true))
        assertFalse(FrontCalibrationConfirmationPolicy.canSave(false, true, true, true))
        assertFalse(FrontCalibrationConfirmationPolicy.canSave(true, false, true, true))
        assertFalse(FrontCalibrationConfirmationPolicy.canSave(true, true, false, true))
        assertFalse(FrontCalibrationConfirmationPolicy.canSave(true, true, true, false))
    }

    @Test
    fun horizontalLanesAndInvalidInputsAreHandledWithoutGuessing() {
        val crop = requireNotNull(FrontCropPolicy.laneCrop(ProfileSize(5120, 1280), 3))
        assertEquals(0.5f, crop.left)
        assertEquals(0.75f, crop.right)
        assertEquals(0f, crop.top)
        assertEquals(1f, crop.bottom)
        assertNull(FrontCropPolicy.laneCrop(ProfileSize(1920, 1080), 1))
        assertNull(FrontCropPolicy.laneCrop(composite, 5))
    }

    @Test
    fun cropRotationCoordinatesAreExplicit() {
        val crop = requireNotNull(FrontCropPolicy.laneCrop(composite, 2))
        val zero = FrontTextureCoordinates.interleaved(crop, 0)
        val rotated = FrontTextureCoordinates.interleaved(crop, 90)

        assertArrayEquals(
            floatArrayOf(
                -1f, -1f, crop.left, 1f - crop.bottom,
                1f, -1f, crop.right, 1f - crop.bottom,
                -1f, 1f, crop.left, 1f - crop.top,
                1f, 1f, crop.right, 1f - crop.top,
            ),
            zero,
            0.0001f,
        )
        assertArrayEquals(
            floatArrayOf(
                -1f, -1f, crop.right, 1f - crop.bottom,
                1f, -1f, crop.right, 1f - crop.top,
                -1f, 1f, crop.left, 1f - crop.bottom,
                1f, 1f, crop.left, 1f - crop.top,
            ),
            rotated,
            0.0001f,
        )
        assertFalse(zero.contentEquals(rotated))
    }

    @Test
    fun frontLaneCropIsConvertedToSurfaceTexturePreTransformCoordinates() {
        val front = requireNotNull(FrontCropPolicy.laneCrop(composite, 1))
        val coordinates = FrontTextureCoordinates.interleaved(front, 0)

        assertEquals(1f - front.bottom, coordinates[3], 0.000001f)
        assertEquals(1f - front.top, coordinates[11], 0.000001f)
        assertTrue(coordinates[3] > 0.7f)
        assertTrue(coordinates[11] > coordinates[3])
    }

    @Test
    fun encoderSelectionRequiresExactValidatedSurfaceProfile() {
        val supported = VideoEncoderCapability(
            codecName = "test.avc.encoder",
            widthAlignment = 16,
            heightAlignment = 16,
            widthRange = 128..4096,
            heightRange = 128..4096,
            frameRateRange = 1..60,
            bitrateRange = 100_000..20_000_000,
            surfaceInput = true,
        )

        assertEquals("test.avc.encoder", FrontEncoderProfilePolicy.select(listOf(supported))?.codecName)
        assertNull(FrontEncoderProfilePolicy.select(listOf(supported.copy(surfaceInput = false))))
        assertNull(FrontEncoderProfilePolicy.select(listOf(supported.copy(hardwareAccelerated = false))))
        assertNull(FrontEncoderProfilePolicy.select(listOf(supported.copy(widthRange = 128..1279))))
    }

    @Test
    fun frontOnlyNeverAcceptsUncroppedCompositeOrStaleCalibration() {
        val output = CameraFormatProfile(ProfileSize(1280, 1280), 8_000_000)
        val source = CameraFormatProfile(composite, 28_000_000)
        val encoder = EncoderProfile("video/avc", "encoder", 1280, 1280, 30, 8_000_000)
        val calibration = requireNotNull(FrontCropPolicy.calibration(fingerprint, composite, 1, 0))
        val valid = RecorderConfig(
            cameraId = "2",
            profile = output,
            segmentSeconds = 60,
            storageLimitBytes = 15L * gb,
            minFreeBytes = 20L * gb,
            recordingMode = RecordingMode.FRONT_ONLY,
            sourceFingerprint = fingerprint,
            sourceKind = RecordingSourceKind.COMPOSITE_CROP,
            sourceProfile = source,
            frontCalibration = calibration,
            encoderProfile = encoder,
            calibrationVersion = calibration.calibrationVersion,
        )

        assertTrue(valid.validate().isEmpty())
        assertFalse(valid.copy(sourceKind = RecordingSourceKind.COMPOSITE).validate().isEmpty())
        assertFalse(valid.copy(sourceFingerprint = "changed").validate().isEmpty())
        assertFalse(valid.copy(frontCalibration = null).validate().isEmpty())
    }
}
