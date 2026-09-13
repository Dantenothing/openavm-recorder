package com.dante.zeekrcapabilitylab.sentry.runtime

import org.junit.Assert.*
import org.junit.Test

class GuardFailurePolicyTest {
    @Test fun firstFailureTimeAndCauseSurviveDelayedCloseErrorsAndUiReturn() {
        val listening = GuardState(runId = "one", running = true, phase = "TRANSITION_TO_SENTRY",
            mode = "SENTRY", power = "interactive=false main=OFF")
        val failed = GuardFailurePolicy.record(listening, "NO_DECLARED_SURROUND_PROFILE", 100_000, 50_000, false)
        val cleanup = GuardFailurePolicy.record(failed, "CODEC_RELEASE_UNCONFIRMED", 700_000, 650_000, true)
        assertEquals(failed.firstFailure, cleanup.firstFailure)
        assertEquals("NO_DECLARED_SURROUND_PROFILE", cleanup.error)
        assertEquals(100_000L, cleanup.firstFailure?.atEpochMs)
        assertFalse(cleanup.firstFailure!!.appForeground)
        assertEquals("TRANSITION_TO_SENTRY", cleanup.firstFailure?.phase)
    }

    @Test fun normalStopCallbackCannotClearAnEarlierRunFailure() {
        val failed = GuardFailurePolicy.record(GuardState(), "CAMERA_ACCESS_FAILURE", 10, 20, false)
        val lateCallback = failed.copy(error = null, message = "Stopped")
        assertEquals("CAMERA_ACCESS_FAILURE", GuardFailurePolicy.preserve(lateCallback).error)
        val userStopped = GuardState(message = "Stopped")
        assertNull(GuardFailurePolicy.preserve(userStopped).error)
    }

    @Test fun oldReportsRemainReadableAndNewReportsKeepFailureEvidence() {
        val legacy = GuardEventStore.json.decodeFromString<GuardState>("""{"phase":"FAULT","error":"NO_DECLARED_SURROUND_PROFILE"}""")
        assertNull(legacy.firstFailure)
        assertNull(legacy.runSource)
        val recorded = GuardFailurePolicy.record(legacy, "NO_DECLARED_SURROUND_PROFILE", 10, 20, false)
        val encoded = GuardEventStore.json.encodeToString(GuardState.serializer(), recorded)
        assertEquals(recorded, GuardEventStore.json.decodeFromString<GuardState>(encoded))
    }
}
