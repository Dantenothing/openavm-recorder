package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ShortRecorderReportTest {
    @Test fun recoveredInputIsDistinctFromNotificationsAndDoesNotClaimEncodedFrames() {
        val snapshot = ShortRecorderReport.snapshot(RecorderState(status = RecorderStatus.RECORDING),
            CaptureEvidence(), buildJsonObject {}, emptyList(), 20_000, 20_000,
            com.dante.zeekrcapabilitylab.mirror.MirrorEvidence(
                gl = com.dante.zeekrcapabilitylab.mirror.MirrorGlEvidence(
                    inputNotifications = 1, inputFrames = 6, inputPollAttempts = 9, inputPolledFrames = 5,
                    lastInputNotificationAgeMs = 500, lastInputPollAgeMs = 10)))
        val gl = snapshot.getValue("mirror").jsonObject.getValue("gl").jsonObject
        assertEquals(1L, gl.getValue("inputNotifications").jsonPrimitive.long)
        assertEquals(6L, gl.getValue("inputFrames").jsonPrimitive.long)
        assertEquals(9L, gl.getValue("inputPollAttempts").jsonPrimitive.long)
        assertEquals(5L, gl.getValue("inputPolledFrames").jsonPrimitive.long)
        assertEquals(500L, gl.getValue("lastInputNotificationAgeMs").jsonPrimitive.long)
        assertEquals(JsonNull, snapshot.getValue("measured").jsonObject["encodedFrames"])
    }
    @Test fun independentInputCanBeFreshWhileDisplayIsStaleWithoutClaimingRecordingFailure() {
        val mirror = com.dante.zeekrcapabilitylab.mirror.MirrorEvidence(
            frames = 120, lastFrameAgeMs = 9_000, textureCallbacks = 121, displayRecoveryAttempts = 1,
            gl = com.dante.zeekrcapabilitylab.mirror.MirrorGlEvidence(backend = "OWNED_GL_INPUT",
                inputNotifications = 900, inputFrames = 890, inputFrameAgeMs = 15,
                inputHeartbeatAgeMs = 100, displaySubmissions = 125, displaySubmitAgeMs = 8_900,
                poolSlots = 3, displayWorkers = 2))
        val snapshot = ShortRecorderReport.snapshot(RecorderState(status = RecorderStatus.RECORDING,
            activeStorageKind = RecordingStorageKind.USB_MEDIASTORE), CaptureEvidence(), buildJsonObject {},
            emptyList(), 20_000, 20_000, mirror)
        val display = snapshot.getValue("mirror").jsonObject
        assertEquals("RECORDING", snapshot.getValue("status").jsonPrimitive.content)
        assertEquals("USB_MEDIASTORE", snapshot.getValue("storage").jsonPrimitive.content)
        assertEquals(JsonNull, snapshot["lastErrorCode"])
        assertEquals("TEXTURE_VIEW_UPDATED", display.getValue("textureCallbackSource").jsonPrimitive.content)
        assertEquals(15L, display.getValue("gl").jsonObject.getValue("inputFrameAgeMs").jsonPrimitive.long)
        assertEquals(9_000L, display.getValue("lastFrameAgeMs").jsonPrimitive.long)
        assertEquals(JsonNull, snapshot.getValue("measured").jsonObject["encodedFrames"])
        assertTrue(ShortRecorderReport.report("test", 1, 32, snapshot, snapshot).toByteArray().size < ShortRecorderReport.MAX_BYTES)
    }
    @Test fun expectedPreviewReleaseDuringStopIsNotStoredAsAFault() {
        listOf("IDLE", "STOPPED", "FINALIZING").forEach { status ->
            assertFalse(ShortRecorderReport.shouldRecordPreviewFault("OUTPUT_RELEASED", status, false))
        }
        assertTrue(ShortRecorderReport.shouldRecordPreviewFault("OUTPUT_RELEASED", "RECORDING", false))
        assertTrue(ShortRecorderReport.shouldRecordPreviewFault("OUTPUT_RELEASED", "STOPPED", true))
        assertTrue(ShortRecorderReport.shouldRecordPreviewFault("FRAME_STALLED", "RECORDING", false))
    }

    @Test fun nativeBackendEvidenceSurvivesIdleWithoutInventingEncodedFrames() {
        val native = ShortRecorderReport.snapshot(RecorderState(recordingBackend = "CONTINUOUS_MEDIA_RECORDER",
            nativeFileSwitches = 3, nativePendingFiles = 5), CaptureEvidence(), buildJsonObject {}, emptyList(), 10, 10)
        val idle = ShortRecorderReport.snapshot(RecorderState(), CaptureEvidence(), buildJsonObject {}, emptyList(), 20, 20)
        val report = Json.parseToJsonElement(ShortRecorderReport.report("test", 1, 32, idle, null, native)).jsonObject
        val run = report.getValue("lastRun").jsonObject
        assertEquals("CONTINUOUS_MEDIA_RECORDER", run["recordingBackend"]!!.jsonPrimitive.content)
        assertEquals(3, run["nativeFileSwitches"]!!.jsonPrimitive.int)
        assertEquals(5, run["nativePendingFiles"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, run["measured"]!!.jsonObject["encodedFrames"])
    }
    @Test fun fallbackReasonAndDisplayProbeRemainVisibleWithoutInventingEncodedFrames() {
        val selection = ContinuousEncoderSelectionPolicy.choose(true, listOf(ContinuousCodecCandidate(
            "vendor.avc", surfaceInput = true, formatSupported = false, sizeAndRateSupported = false,
            baselineAdvertised = true, maximumHeight = 4096)))
        val state = RecorderState(recordingBackend = "MEDIA_RECORDER", encoderSelection = selection)
        val mirror = com.dante.zeekrcapabilitylab.mirror.MirrorEvidence(redrawAttempts = 1, redrawFramesResumed = 0)
        val snapshot = ShortRecorderReport.snapshot(state, CaptureEvidence(), buildJsonObject {}, emptyList(), 1000, 1000, mirror)
        val codec = snapshot.getValue("encoderSelection").jsonObject
        assertEquals("CODEC_FORMAT_NOT_DECLARED", codec.getValue("reason").jsonPrimitive.content)
        assertTrue(codec.getValue("cameraSizeDeclared").jsonPrimitive.boolean)
        assertFalse(codec.getValue("candidates").jsonArray[0].jsonObject.getValue("formatSupported").jsonPrimitive.boolean)
        assertEquals(1, snapshot.getValue("mirror").jsonObject.getValue("redrawAttempts").jsonPrimitive.int)
        assertEquals(0, snapshot.getValue("mirror").jsonObject.getValue("redrawFramesResumed").jsonPrimitive.int)
        assertEquals(JsonNull, snapshot.getValue("measured").jsonObject["encodedFrames"])
    }

    @Test fun continuousCodecEvidenceRemainsSeparateFromCaptureAndRejectsStaleSessions() {
        val state = RecorderState(recordingSessionId = "codec", segmentNumber = 4,
            recordingBackend = "CONTINUOUS_CODEC", captureSessionRevision = 1)
        val evidence = CaptureEvidence("codec", 4, SegmentFrameStats(count = 300), 900,
            encoder = EncoderEvidence(61, 1_000, 2_001_000, 990, 12345, 3, 3))
        val snapshot = ShortRecorderReport.snapshot(state, evidence, buildJsonObject {}, emptyList(), 2000, 1000)
        val measured = snapshot.getValue("measured").jsonObject
        assertEquals("CONTINUOUS_CODEC", snapshot.getValue("recordingBackend").jsonPrimitive.content)
        assertEquals(1L, snapshot.getValue("captureSessionRevision").jsonPrimitive.long)
        assertEquals(30.0, measured.getValue("encodedFps").jsonPrimitive.double, 0.0001)
        assertEquals(61L, measured.getValue("encodedFrames").jsonPrimitive.long)
        assertEquals(300L, measured.getValue("captureCallbackCount").jsonPrimitive.long)
        assertEquals(10L, measured.getValue("lastEncodedOutputAgeMs").jsonPrimitive.long)
        assertEquals(12345L, measured.getValue("encoderQueueBytes").jsonPrimitive.long)
        val stale = ShortRecorderReport.snapshot(state.copy(recordingSessionId = "next"), evidence,
            buildJsonObject {}, emptyList(), 2000, 1000).getValue("measured").jsonObject
        assertEquals(JsonNull, stale["encodedFrames"])
        assertEquals(JsonNull, stale["encoderQueueBytes"])
    }

    @Test fun previewResultsAndTextureCallbacksDoNotBecomeEncodedFrameEvidence() {
        val state = RecorderState(recordingSessionId = "run", segmentNumber = 2)
        val capture = CaptureEvidence("run", 2, preview = PreviewCaptureStats(
            completedWithPreviewTarget = 244, lastCompletedHadPreviewTarget = true,
            lastPreviewResultElapsedMs = 9_979, previewBuffersLost = 241))
        val mirror = com.dante.zeekrcapabilitylab.mirror.MirrorEvidence(
            frames = 1685, lastFrameAgeMs = 8_071, textureCallbacks = 1926,
            duplicateTimestampCallbacks = 241, lastTextureCallbackAgeMs = 21,
            drawPasses = 2000, lastDrawAgeMs = 20, textureAttached = true, textureAvailable = true)
        val snapshot = ShortRecorderReport.snapshot(state, capture, buildJsonObject {}, emptyList(), 20_000, 10_000, mirror)
        val preview = snapshot.getValue("previewCapture").jsonObject
        assertEquals(244L, preview.getValue("completedWithPreviewTarget").jsonPrimitive.long)
        assertEquals(21L, preview.getValue("lastPreviewResultAgeMs").jsonPrimitive.long)
        assertEquals(241L, preview.getValue("previewBuffersLost").jsonPrimitive.long)
        assertEquals(1926L, snapshot.getValue("mirror").jsonObject.getValue("textureCallbacks").jsonPrimitive.long)
        assertEquals(JsonNull, snapshot.getValue("measured").jsonObject["encodedFps"])
        assertEquals(JsonNull, ShortRecorderReport.snapshot(state.copy(segmentNumber = 3), capture,
            buildJsonObject {}, emptyList(), 20_000, 10_000)["previewCapture"])
    }

    @Test fun previewTargetAndSegmentStartFactsSurviveTheShortReport() {
        val events = listOf(
            ProbeEvent(1, "id", 100, 100, "SYSTEM", "RECORDER_PREVIEW_TARGET_CHANGED", "INFO",
                payload = mapOf("enabled" to "true", "requested" to "true", "segment" to "2")),
            ProbeEvent(2, "id", 101, 101, "SYSTEM", "RECORDER_SEGMENT_START", "INFO",
                payload = mapOf("segment" to "2", "previewActive" to "true", "storageKind" to "USB_MEDIASTORE",
                    "file" to "/storage/private.mp4")),
        )
        val snapshot = ShortRecorderReport.snapshot(RecorderState(), CaptureEvidence(), buildJsonObject {}, events, 200, 200)
        val retained = snapshot.getValue("recentEvents").jsonArray
        assertEquals("true", retained[0].jsonObject.getValue("facts").jsonObject["enabled"]?.jsonPrimitive?.content)
        assertEquals("true", retained[0].jsonObject.getValue("facts").jsonObject["requested"]?.jsonPrimitive?.content)
        assertEquals("2", retained[1].jsonObject.getValue("facts").jsonObject["segment"]?.jsonPrimitive?.content)
        assertEquals("true", retained[1].jsonObject.getValue("facts").jsonObject["previewActive"]?.jsonPrimitive?.content)
        assertFalse(snapshot.toString().contains("/storage"))
    }
    @Test fun displayFailureDoesNotInventACameraFailureOrLoseItsFrameEvidence() {
        val state = RecorderState(status = RecorderStatus.RECORDING, recordingSessionId = "run", segmentNumber = 4,
            previewRequested = true, previewActive = true)
        val capture = CaptureEvidence("run", 4, SegmentFrameStats(count = 100), 9_990)
        val mirror = com.dante.zeekrcapabilitylab.mirror.MirrorEvidence(reason = "FRAME_STALLED", frames = 12, lastFrameAgeMs = 8_001)
        val snapshot = ShortRecorderReport.snapshot(state, capture, buildJsonObject {}, emptyList(), 20_000, 10_000, mirror)
        assertEquals(RecorderStatus.RECORDING, snapshot["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, snapshot["lastErrorCode"])
        assertEquals(10L, snapshot["measured"]!!.jsonObject["lastCaptureAgeMs"]!!.jsonPrimitive.long)
        assertEquals(8_001L, snapshot["mirror"]!!.jsonObject["lastFrameAgeMs"]!!.jsonPrimitive.long)
        assertTrue(snapshot["previewRequested"]!!.jsonPrimitive.boolean)
        assertFalse(snapshot["previewFallbackUsed"]!!.jsonPrimitive.boolean)
    }
    @Test fun addedRecoveryFactsStillFitInOneBoundedClipboardReport() {
        val facts = listOf("reason", "status", "phase", "availability", "attempts", "resumeAllowed", "route",
            "cameraGeneration", "frames", "lastFrameAgeMs", "generation", "segmentNumber", "outputLost", "bufferWidth", "bufferHeight")
            .associateWith { "x".repeat(80) }
        val events = (1..20).map { ProbeEvent(it.toLong(), "id", 100, 100, "SYSTEM", "RECORDER_STOP", "ERROR", payload = facts) }
        val selection = ContinuousEncoderSelectionPolicy.choose(true, List(12) {
            ContinuousCodecCandidate("x".repeat(160), false, false, false, false, 8192, 8192, 16, 16, "x".repeat(80)) })
        val snapshot = ShortRecorderReport.snapshot(RecorderState(encoderSelection = selection), CaptureEvidence(), buildJsonObject {}, events, 100, 100)
        val run = JsonObject(snapshot.filterKeys { it in setOf("atEpochMs", "atElapsedMs", "status", "recordingMode",
            "segmentNumber", "storage", "recordingBackend", "encoderSelection", "captureSessionRevision",
            "cameraGeneration", "nativeFileSwitches", "nativePendingFiles", "requested") })
        val text = ShortRecorderReport.report("4.1.0-beta12", 69, 30, snapshot, snapshot, run)
        assertTrue(text.toByteArray().size <= ShortRecorderReport.MAX_BYTES)
        assertEquals(8, snapshot["recentEvents"]!!.jsonArray.first().jsonObject["facts"]!!.jsonObject.size)
    }
    @Test fun shortReportDistinguishesRequestedPreviewFromEncoderAndRecoveryCleanup() {
        val state = RecorderState(cameraGeneration = 9, cleanupPending = true,
            recovery = CameraRecoverySnapshot(phase = CameraRecoveryPhase.WAITING_CAMERA, attemptsMade = 2))
        val mirror = com.dante.zeekrcapabilitylab.mirror.MirrorEvidence(
            requestedWidth = 1280, requestedHeight = 5140, bufferWidth = 1280, bufferHeight = 5140,
            viewWidth = 1280, viewHeight = 5140, lensMode = "STANDARD", zoom = 2f, cameraGeneration = 8)
        val snapshot = ShortRecorderReport.snapshot(state, CaptureEvidence(), buildJsonObject {}, emptyList(), 100, 100, mirror)
        assertEquals(5140, snapshot["mirror"]!!.jsonObject["declaredBufferHeight"]!!.jsonPrimitive.int)
        assertEquals("STANDARD", snapshot["mirror"]!!.jsonObject["lensMode"]!!.jsonPrimitive.content)
        assertEquals(2, snapshot["recovery"]!!.jsonObject["attempts"]!!.jsonPrimitive.int)
        assertTrue(snapshot["recovery"]!!.jsonObject["cleanupPending"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, snapshot["measured"]!!.jsonObject["encodedFps"])
    }
    @Test fun captureEvidenceIsMeasuredSeparatelyFromUnavailableEncoderStatistics() {
        val state = RecorderState(recordingSessionId = "one", segmentNumber = 2)
        val evidence = CaptureEvidence("one", 2, SegmentFrameStats(25, 10, 1_000_000_010, 42_000_000), 2000)
        val snapshot = ShortRecorderReport.snapshot(state, evidence, buildJsonObject {}, emptyList(), 5000, 2500)
        val measured = snapshot.getValue("measured").jsonObject
        assertEquals(24.0, measured.getValue("captureCallbackFps").jsonPrimitive.double, 0.0001)
        assertEquals(500L, measured.getValue("lastCaptureAgeMs").jsonPrimitive.long)
        assertEquals(JsonNull, measured["encodedFps"])
        val old = ShortRecorderReport.snapshot(state.copy(segmentNumber = 3), evidence, buildJsonObject {}, emptyList(), 5000, 2500)
        assertEquals(JsonNull, old["measured"]!!.jsonObject["captureCallbackCount"])
    }

    @Test fun detailedHistoryIsBoundedAndPathsAndTokensDoNotEnterTheShortReport() {
        val lifecycle = buildJsonObject {
            put("unsettledOwners", 1)
            put("transactions", buildJsonObject {
                repeat(12) { index -> put("transaction-$index", buildJsonArray {
                    repeat(160) { add(buildJsonObject { put("at", 123L); put("elapsed", 42L)
                        put("step", "WAITING"); put("detail", "/storage/private.mp4?secret-token=" + "x".repeat(600)) }) }
                }) }
            })
        }
        val events = (1..1000).map { ProbeEvent(it.toLong(), "private-id", 100, 100, "SYSTEM",
            "RECORDER_STOP", "ERROR", payload = mapOf("file" to "/storage/private.mp4", "reason" to "USER", "url" to "secret-token")) }
        val snapshot = ShortRecorderReport.snapshot(RecorderState(lastError = "USB_LOST: /storage/private.mp4"),
            CaptureEvidence(), lifecycle, events, 1000, 1000)
        val text = ShortRecorderReport.report("4.1.0-beta1", 58, 30, snapshot, snapshot)
        assertTrue(text.toByteArray().size < ShortRecorderReport.MAX_BYTES)
        assertFalse(text.contains("/storage"))
        assertFalse(text.contains("secret-token"))
        assertEquals(20, snapshot["recentEvents"]!!.jsonArray.size)
        assertEquals(3, snapshot["cameraCleanup"]!!.jsonObject["transactions"]!!.jsonArray.size)
        assertEquals("USB_LOST", snapshot["lastErrorCode"]!!.jsonPrimitive.content)
    }
}
