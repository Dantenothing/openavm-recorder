package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class GuardRunConfigurationTest {
    private fun config(cameraId: String = "2") = RecorderConfig(
        SessionSourceSnapshot(RecordingSourceRole.SURROUND, cameraId,
            CameraFormatProfile(ProfileSize(1280, 5140), 4_000_000), RecordingLayoutKind.FOUR_LANE_V1),
        segmentSeconds = 60, storageLimitBytes = 5L * 1024 * 1024 * 1024)

    @Test fun successfulRecordingSourceSurvivesEmptyInventoryDuringAwayAndReturn() {
        var visible: RecorderConfig? = config()
        var queries = 0
        val run = GuardRunConfiguration { queries++; visible }
        val started = run.get()
        assertNotNull(started)
        visible = null // Same service, OEM camera discovery no longer yields a source.
        assertEquals(started, run.get())
        assertEquals(started, run.get())
        assertEquals(1, queries)
    }

    @Test fun mappingChangesApplyToTheNextExplicitRunNotMidHandoff() {
        var selected = config("2")
        val first = GuardRunConfiguration { selected }
        assertEquals("2", first.get()?.source?.cameraId)
        selected = config("7")
        assertEquals("2", first.get()?.source?.cameraId)
        val next = GuardRunConfiguration { selected }
        assertEquals("7", next.get()?.source?.cameraId)
    }

    @Test fun noDeclaredSourceAtUserStartNeverBorrowsAPreviousRun() {
        val first = GuardRunConfiguration { config() }
        assertNotNull(first.get())
        val afterRestart = GuardRunConfiguration { null }
        assertNull(afterRestart.get())
    }
}
