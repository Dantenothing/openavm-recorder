package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.canary.CanarySnapshot
import org.junit.Assert.*
import org.junit.Test

class GuardNormalEvidenceTest {
    @Test fun unavailableCameraCauseSurvivesStopCallbackAndCleanup() {
        var state = GuardNormalEvidencePolicy.observe(GuardState(phase = "NORMAL_ACTIVE", normalStatus = RecorderStatus.CAMERA_UNAVAILABLE),
            RecorderState(status = RecorderStatus.CAMERA_UNAVAILABLE, cameraId = "2", lastError = "CAMERA_IN_USE"), 1_000)
        state = GuardFailurePolicy.record(state, GuardNormalEvidencePolicy.stopReason(state), 1_000, 700, false)
        val closed = GuardNormalEvidencePolicy.observe(state, RecorderState(status = RecorderStatus.STOPPED), 1_100)
        assertEquals("CAMERA_IN_USE", closed.firstFailure?.normal?.lastError)
        assertEquals("NORMAL_CAMERA_UNAVAILABLE: CAMERA_IN_USE", closed.firstFailure?.reason)
        assertEquals("2", closed.firstFailure?.normal?.cameraId)
    }

    @Test fun usbToLocalShortSegmentRemainsVisibleAfterFinalStop() {
        var state = GuardState()
        val usb = RecorderState(status = RecorderStatus.RECORDING, segmentNumber = 4,
            activeStorageKind = RecordingStorageKind.USB_MEDIASTORE, currentFile = "/usb/segment-4.mp4")
        state = GuardNormalEvidencePolicy.observe(state, usb, 1_000)
        val local = usb.copy(segmentNumber = 5, activeStorageKind = RecordingStorageKind.INTERNAL,
            currentFile = "/data/recordings/segment-5.mp4", lastError = "USB_UNMOUNTED")
        state = GuardNormalEvidencePolicy.observe(state, local, 2_000)
        state = GuardNormalEvidencePolicy.observe(state, local.copy(status = RecorderStatus.STOPPED, currentFile = null), 9_000)
        assertEquals(listOf("USB_MEDIASTORE", "INTERNAL", "INTERNAL"), state.normalTransitions.map { it.storageKind })
        assertEquals("segment-5.mp4", state.normalTransitions[1].fileName)
        assertEquals(7_000L, state.normalTransitions.last().atEpochMs - state.normalTransitions[1].atEpochMs)
    }

    @Test fun repeatedTelemetryDoesNotFillTransitionHistoryAndRetentionIsBounded() {
        val value = RecorderState(status = RecorderStatus.RECORDING)
        var state = GuardState()
        repeat(100) { state = GuardNormalEvidencePolicy.observe(state, value, it.toLong()) }
        assertEquals(1, state.normalTransitions.size)
        repeat(30) { state = GuardNormalEvidencePolicy.observe(state, value.copy(segmentNumber = it + 1), 100L + it) }
        assertEquals(12, state.normalTransitions.size)
        assertEquals(30, state.normalEvidence?.segmentNumber)
    }

    @Test fun captureFailureKeepsOriginalExceptionDetailAcrossLaterErrors() {
        val state = GuardState(capture = CanarySnapshot(failureDetail = "IllegalArgumentException: camera 2 unavailable"))
        val first = GuardFailurePolicy.record(state, "ILLEGALARGUMENTEXCEPTION_FAILURE", 10, 20, false)
        val later = GuardFailurePolicy.record(first.copy(capture = CanarySnapshot(failureDetail = "cleanup error")),
            "CODEC_RELEASE_UNCONFIRMED", 20, 30, true)
        assertEquals("IllegalArgumentException: camera 2 unavailable", later.firstFailure?.failureDetail)
        assertEquals(first.firstFailure, later.firstFailure)
        val encoded = GuardEventStore.json.encodeToString(GuardState.serializer(), later)
        assertEquals(later, GuardEventStore.json.decodeFromString<GuardState>(encoded))
    }
}
