package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderConfigTest {

    private val gb = 1024L * 1024L * 1024L

    private fun config(
        cameraId: String = "2",
        profile: CameraFormatProfile = CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000),
        segmentSeconds: Int = 60,
        storageLimitBytes: Long = 15L * gb,
        minFreeBytes: Long = 20L * gb,
    ) = RecorderConfig(
        cameraId = cameraId,
        profile = profile,
        segmentSeconds = segmentSeconds,
        storageLimitBytes = storageLimitBytes,
        minFreeBytes = minFreeBytes,
        sourceFingerprint = "verified-fingerprint",
        sourceProfile = profile,
    )

    @Test
    fun validConfigPassesValidation() {
        assertTrue(config().validate().isEmpty())
        assertTrue(config(segmentSeconds = 10).validate().isEmpty())
        assertTrue(config(segmentSeconds = 30).validate().isEmpty())
        assertTrue(config(segmentSeconds = 120).validate().isEmpty())
        assertTrue(config(segmentSeconds = 180).validate().isEmpty())
        assertTrue(config(storageLimitBytes = 5L * gb).validate().isEmpty())
        assertTrue(config(storageLimitBytes = 10L * gb).validate().isEmpty())
        assertTrue(config(storageLimitBytes = 30L * gb).validate().isEmpty())
    }

    @Test
    fun invalidSegmentLengthIsRejected() {
        assertFalse(config(segmentSeconds = 5).validate().isEmpty())
        assertFalse(config(segmentSeconds = 0).validate().isEmpty())
        assertFalse(config(segmentSeconds = 90).validate().isEmpty())
    }

    @Test
    fun invalidStorageLimitIsRejected() {
        assertFalse(config(storageLimitBytes = 1L * gb).validate().isEmpty())
        assertFalse(config(storageLimitBytes = 4L * gb).validate().isEmpty())
        assertFalse(config(storageLimitBytes = 16L * gb).validate().isEmpty())
        assertFalse(config(storageLimitBytes = -1L).validate().isEmpty())
    }

    @Test
    fun invalidReserveIsRejected() {
        assertTrue(config(minFreeBytes = 10L * gb).validate().isEmpty())
        assertTrue(config(minFreeBytes = 30L * gb).validate().isEmpty())
        assertFalse(config(minFreeBytes = 1L * gb).validate().isEmpty())
    }

    @Test
    fun emulatorReserveRequiresAnExplicitDebugValidationGate() {
        val emulator = config(minFreeBytes = RecorderConfig.EMULATOR_TEST_MIN_FREE_BYTES)
            .copy(emulatorTestSource = true)

        assertFalse(emulator.validate().isEmpty())
        assertTrue(emulator.validate(allowEmulatorTestSource = true).isEmpty())
        assertFalse(
            emulator.copy(emulatorTestSource = false)
                .validate(allowEmulatorTestSource = true)
                .isEmpty(),
        )
    }

    @Test
    fun emulatorDirectFrontRequiresTheExplicitDebugValidationGate() {
        val profile = CameraFormatProfile(ProfileSize(1280, 720), 4_000_000)
        val emulatorFront = config(
            profile = profile,
            minFreeBytes = RecorderConfig.EMULATOR_TEST_MIN_FREE_BYTES,
        ).copy(
            recordingMode = RecordingMode.FRONT_ONLY,
            sourceKind = RecordingSourceKind.DIRECT_FRONT,
            emulatorTestSource = true,
        )

        assertFalse(emulatorFront.validate().isEmpty())
        assertTrue(emulatorFront.validate(allowEmulatorTestSource = true).isEmpty())
        assertFalse(
            emulatorFront.copy(emulatorTestSource = false)
                .validate(allowEmulatorTestSource = true)
                .isEmpty(),
        )
    }

    @Test
    fun blankCameraIdAndInvalidProfileAreRejected() {
        assertFalse(config(cameraId = "").validate().isEmpty())
        assertFalse(
            config(profile = CameraFormatProfile(ProfileSize(0, 0), 14_000_000))
                .validate()
                .isEmpty(),
        )
        assertFalse(
            config(profile = CameraFormatProfile(ProfileSize(1280, 5140), 0))
                .validate()
                .isEmpty(),
        )
    }

    @Test
    fun missingSourceIdentityIsRejected() {
        assertFalse(config().copy(sourceFingerprint = "").validate().isEmpty())
    }

    @Test
    fun profileExactMatchAgainstDeclaredSizes() {
        val declared = setOf(ProfileSize(1280, 5140), ProfileSize(3840, 2160))
        assertTrue(RecorderConfig.profileDeclared(CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000), declared))
        assertTrue(RecorderConfig.profileDeclared(CameraFormatProfile(ProfileSize(3840, 2160), 40_000_000), declared))
        assertFalse(RecorderConfig.profileDeclared(CameraFormatProfile(ProfileSize(1920, 1080), 8_000_000), declared))
    }

    @Test
    fun tallProfileIsNotFilteredByAspectRatio() {
        val declared = setOf(ProfileSize(1920, 1080), ProfileSize(1280, 5140))
        assertTrue(RecorderConfig.profileDeclared(CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000), declared))
        assertTrue(CameraProfileCatalog.availableProfiles(declared).any { it.size == ProfileSize(1280, 5140) })
    }
}
