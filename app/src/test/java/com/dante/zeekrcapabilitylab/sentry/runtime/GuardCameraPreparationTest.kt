package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class GuardCameraPreparationTest {
    private fun config(id: String = "2") = RecorderConfig(
        SessionSourceSnapshot(RecordingSourceRole.SURROUND, id,
            CameraFormatProfile(ProfileSize(1280, 5140), 28_000_000), RecordingLayoutKind.FOUR_LANE_V1),
        segmentSeconds = 60, storageLimitBytes = 5L * 1024 * 1024 * 1024)
    private fun capabilities(id: String = "2") = GuardCameraCapabilities(id, 1280, 5140, true, 15, 30, true,
        ProfileSize(1280, 5140), preparedAtEpochMs = 10)

    @Test fun capabilityQueryIsFinishedBeforeNormalStartsAndNeverRepeatedAtHandoff() {
        var metadataVisible = true
        var calls = 0
        val run = GuardRunConfiguration(prepareCamera = {
            calls++
            require(metadataVisible) { "cameraId does not match any known camera device" }
            capabilities()
        }) { config() }
        assertNotNull(run.get()) // Explicit foreground start, before camera/encoder resources are opened.
        val prepared = run.camera
        assertNotNull(prepared)
        metadataVisible = false // Reproduce Beta5's post-close metadata failure.
        repeat(3) {
            assertNotNull(run.get())
            assertSame(prepared, run.camera)
            run.camera!!.requireMatches(run.current!!.source)
        }
        assertEquals(1, calls)
    }

    @Test fun failedPreparationCannotPublishAHalfPreparedRun() {
        var fail = true
        val run = GuardRunConfiguration(prepareCamera = {
            if (fail) throw IllegalArgumentException("camera 2 unavailable")
            capabilities()
        }) { config() }
        assertTrue(runCatching { run.get() }.exceptionOrNull() is IllegalArgumentException)
        assertNull(run.current)
        assertNull(run.camera)
        fail = false
        assertNotNull(run.get())
        assertNotNull(run.camera)
    }

    @Test fun mismatchedCameraOrSizeCannotBorrowPreparedCapabilities() {
        for (prepared in listOf(capabilities("7"), capabilities().copy(sourceHeight = 5120))) {
            val run = GuardRunConfiguration(prepareCamera = { prepared }) { config() }
            assertEquals("PREPARED_CAMERA_SOURCE_MISMATCH", runCatching { run.get() }.exceptionOrNull()?.message)
            assertNull(run.current)
            assertNull(run.camera)
        }
    }

    @Test fun aNewExplicitRunRechecksItsOwnMappingAndCapabilities() {
        var selected = "2"
        var calls = 0
        fun run() = GuardRunConfiguration(prepareCamera = { calls++; capabilities(it.source.cameraId) }) { config(selected) }
        val first = run()
        first.get()
        selected = "7"
        assertEquals("2", first.get()?.source?.cameraId)
        val next = run()
        assertEquals("7", next.get()?.source?.cameraId)
        assertEquals("7", next.camera?.cameraId)
        assertEquals(2, calls)
    }

    @Test fun undeclaredSurfaceAndInvalidFrameRangeAreStillRejected() {
        assertEquals("ENCODER_SURFACE_SIZE_UNDECLARED", runCatching {
            capabilities().copy(surfaceDeclared = false).requireMatches(config().source)
        }.exceptionOrNull()?.message)
        assertEquals("NO_15_FPS_CAMERA_RANGE", runCatching {
            capabilities().copy(fpsLower = 30).requireMatches(config().source)
        }.exceptionOrNull()?.message)
    }

    @Test fun analysisSelectionKeepsMatchingCompositeGeometryAndExistingPixelBudget() {
        val source = config().source
        assertEquals(ProfileSize(640, 2570), GuardAnalysisSizePolicy.choose(
            listOf(ProfileSize(1280, 5140), ProfileSize(320, 1285), ProfileSize(640, 2570), ProfileSize(1920, 1080)), source))
        assertEquals(ProfileSize(1280, 5140), GuardAnalysisSizePolicy.choose(listOf(ProfileSize(1280, 5140)), source))
        assertNull(GuardAnalysisSizePolicy.choose(listOf(ProfileSize(2560, 10280), ProfileSize(1920, 1080)), source))
    }

    @Test fun diagnosticsKeepDeviceMessageCauseAndFrameWithoutUnboundedOutput() {
        val cause = IllegalArgumentException("camera 2 unavailable")
        val outer = IllegalStateException("preparing " + "x".repeat(5_000), cause)
        outer.stackTrace = arrayOf(StackTraceElement("CameraReader", "read", "CameraReader.kt", 27))
        val detail = GuardExceptionSummary.describe(outer)
        assertTrue(detail.contains("IllegalArgumentException: camera 2 unavailable"))
        assertTrue(detail.contains("CameraReader.kt:27"))
        assertTrue(detail.length <= 1_200)
    }
}
