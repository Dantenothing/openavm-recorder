package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM state-machine tests for the product live-preview coordinator. These
 * verify the decision logic only; they cannot prove a real Zeekr Camera HAL
 * behaves, so real-car verification remains a separate step.
 */
class PreviewStateMachineTest {

    private class Harness {
        val states = mutableListOf<PreviewUiState>()
        var enumerateRequests = 0
        var openRequests = 0
        var closeRequests = 0
        var lateCloses = 0

        val machine = PreviewStateMachine(object : PreviewStateMachine.Listener {
            override fun onStateChanged(state: PreviewUiState) {
                states += state
            }

            override fun onEnumerateRequested() {
                enumerateRequests++
            }

            override fun onOpenRequested() {
                openRequests++
            }

            override fun onCloseRequested() {
                closeRequests++
            }

            override fun onLateCameraClosed() {
                lateCloses++
            }
        })

        fun phase(): PreviewPhase = states.lastOrNull()?.phase ?: PreviewPhase.IDLE

        /** Mirrors the adapter: async close is issued, then the machine completes. */
        fun completeClose() {
            machine.onCloseComplete()
        }

        fun startCycle() {
            machine.setLifecycleResumed(true)
            machine.setPageVisible(true)
            machine.setPermissionGranted(true)
            machine.setCameraAvailable(true)
            assertEquals(PreviewPhase.DEBOUNCING, phase())
        }

        fun debounceAndOpen() {
            machine.onDebounceElapsed()
            assertEquals(PreviewPhase.ENUMERATING, phase())
            machine.onEnumerationComplete(machine.currentGeneration)
            assertEquals(PreviewPhase.OPENING, phase())
        }

        fun toPreviewing() {
            debounceAndOpen()
            val gen = machine.currentGeneration
            machine.onOpened(gen)
            machine.onSessionConfigured(gen)
            assertEquals(PreviewPhase.PREVIEWING, phase())
        }
    }

    @Test
    fun firstFrameNeverEnumeratesOrOpensCamera() {
        val h = Harness()
        assertEquals(PreviewPhase.IDLE, h.phase())
        assertEquals(0, h.enumerateRequests)
        assertEquals(0, h.openRequests)

        // Page visible + permission + HAL available, but the activity is not
        // RESUMED yet: exactly the startup moment. No Camera2 work may happen.
        h.machine.setPageVisible(true)
        h.machine.setPermissionGranted(true)
        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())
        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())
        assertEquals(0, h.enumerateRequests)
        assertEquals(0, h.openRequests)

        assertFalse(
            PreviewStartPolicy.shouldAttempt(
                lifecycleResumed = false,
                pageVisible = true,
                recordingActive = false,
                cameraAvailable = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun previewDoesNotStartBeforeResumed() {
        val h = Harness()
        h.machine.setPageVisible(true)
        h.machine.setPermissionGranted(true)
        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())

        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())
        assertEquals(0, h.enumerateRequests)

        h.machine.setLifecycleResumed(true)
        assertEquals(PreviewPhase.DEBOUNCING, h.phase())
        assertEquals(0, h.enumerateRequests)
    }

    @Test
    fun startsOnlyAfterAvailabilityAndDebounce() {
        val h = Harness()
        h.machine.setLifecycleResumed(true)
        h.machine.setPageVisible(true)
        h.machine.setPermissionGranted(true)
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())

        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())
        assertEquals(0, h.enumerateRequests)

        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.DEBOUNCING, h.phase())
        assertEquals(0, h.enumerateRequests)

        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.ENUMERATING, h.phase())
        assertEquals(1, h.enumerateRequests)

        h.machine.onEnumerationComplete(h.machine.currentGeneration)
        assertEquals(PreviewPhase.OPENING, h.phase())
        assertEquals(1, h.openRequests)

        val gen = h.machine.currentGeneration
        h.machine.onOpened(gen)
        h.machine.onSessionConfigured(gen)
        assertEquals(PreviewPhase.PREVIEWING, h.phase())
        assertEquals(1, h.enumerateRequests)
        assertEquals(1, h.openRequests)
    }

    @Test
    fun enumerationFailureEntersUnavailableAndUiCanRetry() {
        val h = Harness()
        h.startCycle()
        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.ENUMERATING, h.phase())

        h.machine.onEnumerationFailed(h.machine.currentGeneration, "CAMERA_IN_USE")
        assertEquals(PreviewPhase.CLOSING, h.phase())
        assertEquals(1, h.closeRequests)
        h.completeClose()
        assertEquals(PreviewPhase.UNAVAILABLE, h.phase())

        // UI stays usable: a manual retry goes through the debounce again.
        h.machine.retry()
        assertEquals(PreviewPhase.DEBOUNCING, h.phase())
        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.ENUMERATING, h.phase())
        assertEquals(2, h.enumerateRequests)
    }

    @Test
    fun openCameraWithoutCallbackEntersTimedOut() {
        val h = Harness()
        h.startCycle()
        h.debounceAndOpen()

        h.machine.onOpenTimeout()
        assertEquals(PreviewPhase.CLOSING, h.phase())
        assertEquals(1, h.closeRequests)
        h.completeClose()
        assertEquals(PreviewPhase.TIMED_OUT, h.phase())
    }

    @Test
    fun openFailureEntersUnavailableWithoutWaitingForWatchdog() {
        val h = Harness()
        h.startCycle()
        h.debounceAndOpen()

        h.machine.onStartFailed(h.machine.currentGeneration, "CAMERA_IN_USE")
        assertEquals(PreviewPhase.CLOSING, h.phase())
        assertEquals(1, h.closeRequests)
        h.completeClose()
        assertEquals(PreviewPhase.UNAVAILABLE, h.phase())
    }

    @Test
    fun lateOpenedAfterTimeoutIsClosedAndDoesNotOverrideState() {
        val h = Harness()
        h.startCycle()
        h.debounceAndOpen()
        val staleGen = h.machine.currentGeneration

        h.machine.onOpenTimeout()
        assertEquals(PreviewPhase.CLOSING, h.phase())
        assertEquals(1, h.closeRequests)
        h.completeClose()
        assertEquals(PreviewPhase.TIMED_OUT, h.phase())

        assertFalse(h.machine.onOpened(staleGen))
        assertEquals(PreviewPhase.TIMED_OUT, h.phase())
        assertEquals(1, h.lateCloses)

        // Retry with a fresh generation; the previous attempt's late session
        // callback must not override the new OPENING state either.
        h.machine.retry()
        h.machine.onDebounceElapsed()
        h.machine.onEnumerationComplete(h.machine.currentGeneration)
        assertEquals(PreviewPhase.OPENING, h.phase())

        assertFalse(h.machine.onSessionConfigured(staleGen))
        assertEquals(PreviewPhase.OPENING, h.phase())
        assertEquals(2, h.lateCloses)
        assertEquals(2, h.enumerateRequests)
        assertEquals(2, h.openRequests)
    }

    @Test
    fun lifecycleStopPageHideAndRecordingCancelAndClosePreview() {
        val h = Harness()
        h.startCycle()
        h.toPreviewing()

        // Activity STOP.
        h.machine.setLifecycleResumed(false)
        assertEquals(PreviewPhase.CLOSING, h.phase())
        h.machine.onCloseComplete()
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())
        assertEquals(1, h.closeRequests)

        // Page hidden (tab switch away).
        h.machine.setLifecycleResumed(true)
        h.machine.setPageVisible(true)
        h.machine.setCameraAvailable(true)
        h.machine.onDebounceElapsed()
        h.machine.onEnumerationComplete(h.machine.currentGeneration)
        val gen1 = h.machine.currentGeneration
        h.machine.onOpened(gen1)
        h.machine.onSessionConfigured(gen1)
        assertEquals(PreviewPhase.PREVIEWING, h.phase())
        h.machine.setPageVisible(false)
        assertEquals(PreviewPhase.CLOSING, h.phase())
        h.machine.onCloseComplete()
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())

        // Recording starts while previewing.
        h.machine.setPageVisible(true)
        h.machine.setCameraAvailable(true)
        h.machine.onDebounceElapsed()
        h.machine.onEnumerationComplete(h.machine.currentGeneration)
        val gen2 = h.machine.currentGeneration
        h.machine.onOpened(gen2)
        h.machine.onSessionConfigured(gen2)
        assertEquals(PreviewPhase.PREVIEWING, h.phase())
        h.machine.setRecordingActive(true)
        assertEquals(PreviewPhase.CLOSING, h.phase())
        h.machine.onCloseComplete()
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())
        assertEquals(PreviewStateMachine.REASON_RECORDING, h.states.last().reason)
    }

    @Test
    fun recordingNeverTriggersSecondPreviewAttempt() {
        val h = Harness()
        h.machine.setLifecycleResumed(true)
        h.machine.setPageVisible(true)
        h.machine.setPermissionGranted(true)
        h.machine.setRecordingActive(true)
        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())

        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())
        assertEquals(0, h.enumerateRequests)

        // Availability jitter while recording changes nothing.
        h.machine.setCameraAvailable(false)
        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())
        assertEquals(0, h.enumerateRequests)

        // Stop recording: safe recovery only after debounce.
        h.machine.setRecordingActive(false)
        assertEquals(PreviewPhase.DEBOUNCING, h.phase())
        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.ENUMERATING, h.phase())
        assertEquals(1, h.enumerateRequests)
    }

    @Test
    fun availabilityJitterNeverCausesParallelOpensOrHighFrequencyRetries() {
        val h = Harness()
        h.startCycle()

        // Flicker during the debounce restarts the debounce (still one attempt).
        h.machine.setCameraAvailable(false)
        assertEquals(PreviewPhase.WAITING_FOR_AVAILABILITY, h.phase())
        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.DEBOUNCING, h.phase())
        h.machine.onDebounceElapsed()
        assertEquals(PreviewPhase.ENUMERATING, h.phase())
        assertEquals(1, h.enumerateRequests)
        h.machine.onEnumerationComplete(h.machine.currentGeneration)
        assertEquals(PreviewPhase.OPENING, h.phase())
        assertEquals(1, h.openRequests)

        // Flicker while the open is in flight must not cancel or reopen.
        val gen = h.machine.currentGeneration
        h.machine.setCameraAvailable(false)
        assertEquals(PreviewPhase.OPENING, h.phase())
        h.machine.setCameraAvailable(true)
        assertEquals(PreviewPhase.OPENING, h.phase())
        assertEquals(1, h.openRequests)
        assertEquals(0, h.closeRequests)

        h.machine.onOpened(gen)
        h.machine.onSessionConfigured(gen)
        assertEquals(PreviewPhase.PREVIEWING, h.phase())
        assertEquals(1, h.openRequests)
        assertEquals(0, h.closeRequests)
    }

    @Test
    fun staleEnumerationResultCannotOpenACamera() {
        val h = Harness()
        h.startCycle()
        h.machine.onDebounceElapsed()
        val staleGen = h.machine.currentGeneration

        h.machine.setLifecycleResumed(false)
        assertEquals(PreviewPhase.CLOSING, h.phase())
        h.machine.onCloseComplete()
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())

        h.machine.onEnumerationComplete(staleGen)
        assertEquals(PreviewPhase.WAITING_FOR_LIFECYCLE, h.phase())
        assertEquals(0, h.openRequests)
    }

    @Test
    fun productConfigIsNeverComputedAtCompositionOrStartup() {
        assertTrue(RecordStartPolicy.shouldComputeConfig(RecordStartPolicy.TRIGGER_USER_START))
        assertFalse(RecordStartPolicy.shouldComputeConfig(RecordStartPolicy.TRIGGER_COMPOSITION))
        assertFalse(RecordStartPolicy.shouldComputeConfig(RecordStartPolicy.TRIGGER_STARTUP))
        assertEquals(8_000L, RecordStartPolicy.CONFIG_TIMEOUT_MS)
    }

    @Test
    fun timingConstantsMatchPlan() {
        assertEquals(1_000L, PreviewTimingPolicy.DEBOUNCE_MS)
        assertTrue(PreviewTimingPolicy.OPEN_TIMEOUT_MS in 6_000L..8_000L)
        assertEquals(8_000L, PreviewTimingPolicy.MAX_OPEN_TIMEOUT_MS)
    }
}
