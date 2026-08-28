package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommands
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePolicy
import org.junit.Assert.assertEquals
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
        sourceRole: RecordingSourceRole = RecordingSourceRole.SURROUND,
        layoutKind: RecordingLayoutKind = RecordingLayoutKind.FOUR_LANE_V1,
        recordingMode: RecordingMode = RecordingMode.NORMAL,
        timeLapseMultiplier: Int = 1,
    ) = RecorderConfig(
        source = SessionSourceSnapshot(
            sourceRole = sourceRole,
            cameraId = cameraId,
            profile = profile,
            layoutKind = layoutKind,
        ),
        segmentSeconds = segmentSeconds,
        storageLimitBytes = storageLimitBytes,
        minFreeBytes = minFreeBytes,
        recordingMode = recordingMode,
        timeLapseMultiplier = timeLapseMultiplier,
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

    @Test
    fun sourceSnapshotSurvivesForegroundServiceJsonHandoff() {
        val expected = config()
        val encoded = RecorderCommands.json.encodeToString(RecorderConfig.serializer(), expected)
        val decoded = RecorderCommands.json.decodeFromString(RecorderConfig.serializer(), encoded)

        assertTrue(decoded == expected)
        assertTrue(decoded.source.sourceRole == RecordingSourceRole.SURROUND)
        assertTrue(decoded.source.layoutKind == RecordingLayoutKind.FOUR_LANE_V1)
    }

    @Test
    fun normalModeNeverRequestsCaptureRateAndKeepsConfiguredSegmentLength() {
        val value = config(segmentSeconds = 180)

        assertEquals(null, value.captureRateFpsOrNull())
        assertEquals(180, value.effectiveSegmentSeconds())
        assertEquals(180, value.estimatedEncodedSeconds())
        assertTrue(value.validate().isEmpty())
        assertFalse(config(recordingMode = RecordingMode.NORMAL, timeLapseMultiplier = 30).validate().isEmpty())
    }

    @Test
    fun everyTimeLapseMultiplierWorksWithEveryLogicalSource() {
        val sources = listOf(
            Triple(RecordingSourceRole.SURROUND, RecordingLayoutKind.FOUR_LANE_V1, "2"),
            Triple(RecordingSourceRole.CABIN, RecordingLayoutKind.SINGLE_V1, "1"),
            Triple(RecordingSourceRole.IR, RecordingLayoutKind.SINGLE_V1, "0"),
        )
        sources.forEach { (role, layout, cameraId) ->
            TimeLapsePolicy.MULTIPLIERS.forEach { multiplier ->
                val value = config(
                    cameraId = cameraId,
                    profile = if (role == RecordingSourceRole.SURROUND) {
                        CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000)
                    } else {
                        CameraFormatProfile(ProfileSize(3840, 2160), 28_000_000)
                    },
                    sourceRole = role,
                    layoutKind = layout,
                    recordingMode = RecordingMode.TIME_LAPSE,
                    timeLapseMultiplier = multiplier,
                )
                assertTrue("$role ${multiplier}x: ${value.validate()}", value.validate().isEmpty())
                assertEquals(TimeLapsePolicy.SAFETY_CHUNK_SECONDS, value.effectiveSegmentSeconds())
                assertEquals(30.0 / multiplier, value.captureRateFpsOrNull()!!, 0.000_001)
                assertEquals(
                    (TimeLapsePolicy.SAFETY_CHUNK_SECONDS + multiplier - 1) / multiplier,
                    value.estimatedEncodedSeconds(),
                )
            }
        }
    }

    @Test
    fun unsupportedTimeLapseMultiplierIsRejected() {
        assertFalse(
            config(recordingMode = RecordingMode.TIME_LAPSE, timeLapseMultiplier = 1)
                .validate()
                .isEmpty(),
        )
        assertFalse(
            config(recordingMode = RecordingMode.TIME_LAPSE, timeLapseMultiplier = 200)
                .validate()
                .isEmpty(),
        )
    }
}
