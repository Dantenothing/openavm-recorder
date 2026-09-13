package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.CaptureSubmissionMode
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCadenceOwnershipPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePowerGateAction
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePowerGatePolicy
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseCaptureCadencePolicy
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseTeardownPolicy
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseTeardownStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLapseCaptureCadencePolicyTest {

    @Test
    fun normalRecordingKeepsTheExistingRepeatingEncoderRequest() {
        val plan = TimeLapseCaptureCadencePolicy.plan(RecordingMode.NORMAL, 1)

        assertEquals(CaptureSubmissionMode.REPEATING_ENCODER, plan.submissionMode)
        assertEquals(null, plan.encoderIntervalNs)
    }

    @Test
    fun timeLapseUsesPacedSingleEncoderCapturesForEveryMultiplier() {
        val expectedIntervalsNs = mapOf(
            2 to 66_666_667L,
            5 to 166_666_667L,
            10 to 333_333_333L,
            15 to 500_000_000L,
            30 to 1_000_000_000L,
            60 to 2_000_000_000L,
            90 to 3_000_000_000L,
            120 to 4_000_000_000L,
            150 to 5_000_000_000L,
        )

        expectedIntervalsNs.forEach { (multiplier, expectedIntervalNs) ->
            val plan = TimeLapseCaptureCadencePolicy.plan(RecordingMode.TIME_LAPSE, multiplier)

            assertEquals(CaptureSubmissionMode.PACED_SINGLE_ENCODER, plan.submissionMode)
            assertEquals(expectedIntervalNs, plan.encoderIntervalNs)
            assertEquals(30.0 / multiplier, plan.requestedEncoderFps, 0.000_001)
        }
    }

    @Test
    fun nextDeadlineAdvancesFromThePreviousDeadlineWithoutCreatingABacklog() {
        val intervalNs = 333_333_333L

        assertEquals(
            1_333_333_333L,
            TimeLapseCaptureCadencePolicy.nextDeadlineNs(
                previousDeadlineNs = 1_000_000_000L,
                completedAtNs = 1_100_000_000L,
                intervalNs = intervalNs,
            ),
        )
        assertTrue(
            TimeLapseCaptureCadencePolicy.nextDeadlineNs(
                previousDeadlineNs = 1_000_000_000L,
                completedAtNs = 2_000_000_000L,
                intervalNs = intervalNs,
            ) >= 2_333_333_333L,
        )
    }

    @Test
    fun staleSegmentSessionOrStopStateCannotOwnAPacedCapture() {
        fun owns(
            token: Long = 3,
            generation: Long = 8,
            sameSession: Boolean = true,
            sameEncoder: Boolean = true,
            recording: Boolean = true,
            stopping: Boolean = false,
            releasing: Boolean = false,
            captureAllowed: Boolean = true,
        ) = CaptureCadenceOwnershipPolicy.owns(
            token = token,
            currentToken = 3,
            segmentGeneration = generation,
            currentSegmentGeneration = 8,
            sameSession = sameSession,
            sameEncoder = sameEncoder,
            recording = recording,
            stopping = stopping,
            releasing = releasing,
            captureAllowed = captureAllowed,
        )

        assertTrue(owns())
        assertTrue(!owns(token = 2))
        assertTrue(!owns(generation = 7))
        assertTrue(!owns(sameSession = false))
        assertTrue(!owns(sameEncoder = false))
        assertTrue(!owns(recording = false))
        assertTrue(!owns(stopping = true))
        assertTrue(!owns(releasing = true))
        assertTrue(!owns(captureAllowed = false))
    }

    @Test
    fun timeLapseTerminatesOnFirstBackgroundPowerOffEdgeWithoutChangingNormalMode() {
        assertEquals(
            TimeLapsePowerGateAction.TERMINATE_SESSION,
            TimeLapsePowerGatePolicy.action(
                recordingMode = RecordingMode.TIME_LAPSE,
                recording = true,
                appForeground = false,
                interactive = false,
                mainDisplayOn = true,
            ),
        )
        assertEquals(
            TimeLapsePowerGateAction.TERMINATE_SESSION,
            TimeLapsePowerGatePolicy.action(
                recordingMode = RecordingMode.TIME_LAPSE,
                recording = true,
                appForeground = false,
                interactive = true,
                mainDisplayOn = false,
            ),
        )
        assertEquals(
            TimeLapsePowerGateAction.NONE,
            TimeLapsePowerGatePolicy.action(
                recordingMode = RecordingMode.NORMAL,
                recording = true,
                appForeground = false,
                interactive = false,
                mainDisplayOn = false,
            ),
        )
    }

    @Test
    fun timeLapseDoesNotTerminateForBackgroundAloneOrAfterRecordingStops() {
        assertEquals(
            TimeLapsePowerGateAction.NONE,
            TimeLapsePowerGatePolicy.action(
                recordingMode = RecordingMode.TIME_LAPSE,
                recording = true,
                appForeground = false,
                interactive = true,
                mainDisplayOn = true,
            ),
        )
        assertEquals(
            TimeLapsePowerGateAction.NONE,
            TimeLapsePowerGatePolicy.action(
                recordingMode = RecordingMode.TIME_LAPSE,
                recording = false,
                appForeground = false,
                interactive = false,
                mainDisplayOn = true,
            ),
        )
    }

    @Test
    fun safeTeardownIsOnlyRequiredForAnActiveTimeLapseProducer() {
        assertEquals(
            TimeLapseTeardownStage.DRAINING,
            TimeLapseTeardownPolicy.initialStage(
                recordingMode = RecordingMode.TIME_LAPSE,
                wasRecording = true,
                hasCaptureSession = true,
                hasEncoderSurface = true,
            ),
        )
        assertEquals(
            TimeLapseTeardownStage.NONE,
            TimeLapseTeardownPolicy.initialStage(
                recordingMode = RecordingMode.NORMAL,
                wasRecording = true,
                hasCaptureSession = true,
                hasEncoderSurface = true,
            ),
        )
    }

    @Test
    fun teardownEscalatesWithoutAssumingAbortOrCloseCompletedSynchronously() {
        assertEquals(
            TimeLapseTeardownStage.CLOSING_SESSION,
            TimeLapseTeardownPolicy.onTimeout(TimeLapseTeardownStage.DRAINING),
        )
        assertEquals(
            TimeLapseTeardownStage.CLOSING_DEVICE,
            TimeLapseTeardownPolicy.onTimeout(TimeLapseTeardownStage.CLOSING_SESSION),
        )
        assertEquals(
            TimeLapseTeardownStage.UNCONFIRMED,
            TimeLapseTeardownPolicy.onTimeout(TimeLapseTeardownStage.CLOSING_DEVICE),
        )
        assertEquals(
            TimeLapseTeardownStage.READY_TO_STOP_RECORDER,
            TimeLapseTeardownPolicy.onProducerIdle(TimeLapseTeardownStage.DRAINING),
        )
    }
}
