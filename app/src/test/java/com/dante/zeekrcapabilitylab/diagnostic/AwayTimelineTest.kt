package com.dante.zeekrcapabilitylab.diagnostic
import org.junit.Assert.*
import org.junit.Test
class AwayTimelineTest {
    private fun r(event:String,t:Long=1,u:Long=t,p:String="p")=AwayRow(event,t,t,u,p)
    @Test fun noSignalIsNotMisreportedAsSuccessfulStop() { assertEquals("NO_CONFIRMED_AWAY_SIGNAL",AwayTimeline.classify(listOf(r("APP_BACKGROUND")))) }
    @Test fun distinguishesStopMissingFromNewStart() {
        assertEquals("AWAY_CONFIRMED_WITHOUT_STOP_EVENT",AwayTimeline.classify(listOf(r("RECORDER_VEHICLE_AWAY_CONFIRMED"))))
        assertEquals("STOP_THEN_NEW_RECORDING_REQUEST",AwayTimeline.classify(listOf(r("RECORDER_STOP"),r("RECORDER_START"))))
    }
    @Test fun stopCommandDoesNotClaimHardwareReleased() { assertEquals("STOP_REQUEST_OBSERVED_CHECK_RESOURCE_RESULT",AwayTimeline.classify(listOf(r("RECORDER_STOP")))) }
    @Test fun sleepGapNeverCrossesProcessOrBoot() {
        assertEquals(900L,AwayTimeline.sleepEvidence(listOf(r("s",1000,100),r("s",2000,200),r("s",4000,100,"new"))))
        assertEquals(0L,AwayTimeline.sleepEvidence(listOf(r("s",1000,100),r("s",10,1))))
    }
    @Test fun screenOnCannotRearmShadowAfterStop() {
        assertEquals("false",AwayTimeline.shadow(listOf(r("RECORDER_START"),r("RECORDER_STOP"),r("SCREEN_ON"),r("APP_FOREGROUND")))["expectedRecording"])
        assertEquals("true",AwayTimeline.shadow(listOf(r("RECORDER_START"),r("APP_BACKGROUND")))["expectedRecording"])
    }
    @Test fun recordingRetestErrorStopIsNotClassifiedAsAnOrdinaryStop() {
        val rows = listOf(r("RECORDER_START"),
            r("RECORDER_STOP", 369_279).copy(facts=mapOf("authorityReason" to "CONTINUOUS_FAILED")),
            r("SCREEN_OFF", 370_095).copy(facts=mapOf("recorder" to "IDLE")))
        assertEquals("RECORDING_ERROR_STOP_OBSERVED_CHECK_RESOURCE_RESULT", AwayTimeline.classify(rows))
    }
    @Test fun purePreviewExitIsUsefulEvidenceButDoesNotTestRecordingStop() {
        val rows = listOf(r("APP_BACKGROUND").copy(facts=mapOf("recorder" to "IDLE", "standalonePreview" to "true")),
            r("DISPLAY_CHANGED", 314_207).copy(facts=mapOf("recorder" to "IDLE", "interactive" to "false",
                "displays" to "0:OFF,2:OFF,3:OFF", "standalonePreview" to "false", "floatingControls" to "false")))
        assertEquals("PREVIEW_EXIT_OBSERVED_RECORDING_NOT_TESTED", AwayTimeline.classify(rows))
    }
    @Test fun offWithoutAnObservedPreviewDoesNotClaimPreviewExit() {
        val rows = listOf(r("APP_BACKGROUND"), r("SCREEN_OFF", 10).copy(facts=mapOf("interactive" to "false",
            "displays" to "0:OFF", "standalonePreview" to "false", "floatingControls" to "false")))
        assertEquals("NO_CONFIRMED_AWAY_SIGNAL", AwayTimeline.classify(rows))
    }
    @Test fun terminalInputEndsShadowWithoutReadingActualIdleOrClaimingNormalAway() {
        val rows = listOf(r("RECORDER_START").copy(facts=mapOf("recordingSessionId" to "a")),
            r("RECORDER_SESSION_TERMINATED", 10).copy(facts=mapOf("recordingSessionId" to "a", "reason" to "CAMERA_CLOSE_UNCONFIRMED")),
            r("SCREEN_OFF", 52_000), r("APP_FOREGROUND", 70_000))
        assertEquals("false", AwayTimeline.shadow(rows)["expectedRecording"])
        assertEquals("SESSION_TERMINATED_CHECK_RESOURCE_RESULT", AwayTimeline.classify(rows))
    }
    @Test fun oldTerminalOrStopCannotEndNewRunShadow() {
        val rows = listOf(r("RECORDER_START").copy(facts=mapOf("recordingSessionId" to "b")),
            r("RECORDER_SESSION_TERMINATED", 10).copy(facts=mapOf("recordingSessionId" to "a")),
            r("RECORDER_STOP", 11).copy(facts=mapOf("recordingSessionId" to "a")))
        assertEquals("true", AwayTimeline.shadow(rows)["expectedRecording"])
        assertEquals("INSUFFICIENT_BACKGROUND_EVIDENCE", AwayTimeline.classify(rows))
    }
}
