package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.mirror.MirrorEvidence
import com.dante.zeekrcapabilitylab.mirror.MirrorGlEvidence
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ShortRecorderReportBoundsTest {
    @Test fun maximumBoundedSectionsStayCopyableAndPreserveFirstFailure() {
        val failureEvents = (1L..6L).flatMap { group -> (1L..2L).map { edge ->
            val at = group * 10 + edge
            ProbeEvent(at, "app", at, at, "SYSTEM", "RECORDER_CONTINUOUS_FIRST_FAILURE", "WARN", payload = mapOf(
                "recordingSessionId" to "00000000-0000-0000-0000-${group.toString().padStart(12, '0')}",
                "channelId" to "SURROUND", "failureStage" to "SOURCE_WATCHDOG", "errorCode" to "PRODUCT_SOURCE_WAIT_TIMEOUT",
                "exceptionType" to "java.lang.IllegalStateException", "causeType" to "java.io.IOException",
                "codecErrorCode" to Int.MIN_VALUE.toString(), "errno" to Int.MAX_VALUE.toString(),
                "codecTransient" to "false", "codecRecoverable" to "false") +
                listOf("faultEpochMs", "faultElapsedMs", "faultCaptureOrder", "snapshotAgeMs", "faultEncodedFrames",
                    "faultEncodedBytes", "faultCompletedFiles", "faultQueueItems", "faultQueueBytes", "faultSourceAgeAtSampleMs")
                    .associateWith { Long.MAX_VALUE.toString() })
        } }
        val faults = ProcessExitReport.continuousFailures(failureEvents)
        assertEquals(6, faults["groups"]!!.jsonArray.size)
        val snapshot = largeSnapshot()
        val run = JsonObject(snapshot.filterKeys { it in setOf("atEpochMs", "atElapsedMs", "status", "recordingMode",
            "segmentNumber", "storage", "recordingBackend", "encoderSelection", "captureSessionRevision", "requested") })
        val persisted = ProcessExitReport.events((1L..24L).map { at ->
            ProbeEvent(at, "app", Long.MAX_VALUE - at, Long.MAX_VALUE - at, "SYSTEM", "RECORDER_SEGMENT_START", "INFO",
                payload = mapOf("segment" to Int.MAX_VALUE.toString()))
        })
        assertEquals(24, persisted.size)
        val exits = buildJsonObject {
            put("systemExitQuery", "AVAILABLE"); put("persistedEvents", persisted); put("continuousFailures", faults)
            put("historicalExits", buildJsonArray { repeat(8) { index -> add(buildJsonObject {
                put("atEpochMs", Long.MAX_VALUE); put("reason", "NATIVE_CRASH"); put("status", Int.MAX_VALUE)
                put("pid", Int.MAX_VALUE); put("process", "a".repeat(120)); put("importance", Int.MAX_VALUE)
                put("pssKiB", Long.MAX_VALUE); put("rssKiB", Long.MAX_VALUE)
                if (index < 2) put("nativeTrace", buildJsonObject {
                    put("status", "AVAILABLE"); put("tid", Long.MAX_VALUE); put("signal", "SIGABRT")
                    put("signalNumber", Int.MAX_VALUE)
                    put("frames", buildJsonArray { repeat(8) { add(buildJsonObject {
                        put("library", "x".repeat(120)); put("function", "f".repeat(240)); put("relativePc", "f".repeat(16))
                    }) } })
                })
            }) } })
            put("lastJavaCrash", buildJsonObject {
                put("atEpochMs", Long.MAX_VALUE); put("exceptionType", "a".repeat(120)); put("thread", "w".repeat(80))
                put("frames", buildJsonArray { repeat(12) { add("f".repeat(180)) } })
                put("causes", buildJsonArray { repeat(4) { add("e".repeat(120)) } })
            })
        }
        val observation = BootObservation(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE, "123456789012-1234567890123456")
        val startup = buildJsonObject {
            put("classification", "UNKNOWN"); put("reason", "CLOCK_CONFLICT"); put("rebootCause", "NOT_DETERMINED")
            put("starts", Json.encodeToJsonElement(List(4) { BootStartEvidence(observation,
                BootAssessment(BootClassification.UNKNOWN, BootEvidenceReason.PREVIOUS_CHECKPOINT_UNAVAILABLE)) }))
            put("transferBoundaries", Json.encodeToJsonElement(List(16) { TransferBoundaryEvidence(observation,
                Int.MAX_VALUE, TransferDiagnosticSource.FACTORY_SENTRY_USB, TransferDiagnosticPhase.RESTORED_WAITING_RETRY) }))
        }
        val text = ShortRecorderReport.report("5.1.0-drive7", 112, 32, snapshot, snapshot, run, exits, startup)
        val report = Json.parseToJsonElement(text).jsonObject
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= ShortRecorderReport.MAX_BYTES)
        assertTrue(report["omittedForSize"]!!.jsonArray.isNotEmpty())
        assertEquals(faults, report["processExit"]!!.jsonObject["continuousFailures"])
        assertEquals(startup, report["startupAndTransfer"])
        val first = faults["groups"]!!.jsonArray.first().jsonObject["firstAvailable"]!!.jsonObject
        assertEquals(Long.MAX_VALUE, first["faultEncodedBytes"]!!.jsonPrimitive.long)
        assertEquals(Long.MAX_VALUE, first["faultCompletedFiles"]!!.jsonPrimitive.long)
    }

    @Test fun unexpectedlyLargeNativeDetailsAreMarkedOmittedWithoutDroppingTypedFirstCause() {
        val faults = buildJsonObject { put("scope", "RETAINED_HISTORY_NOT_SELECTED_SESSION"); put("groups", buildJsonArray {}) }
        val exits = buildJsonObject {
            put("continuousFailures", faults)
            put("historicalExits", buildJsonArray { add(buildJsonObject {
                put("reason", "NATIVE_CRASH"); put("nativeTrace", "x".repeat(60 * 1024))
            }) })
        }
        val text = ShortRecorderReport.report("test", 1, 32, buildJsonObject {}, null, processExit = exits)
        val report = Json.parseToJsonElement(text).jsonObject
        assertTrue(text.toByteArray().size <= ShortRecorderReport.MAX_BYTES)
        assertEquals(faults, report["processExit"]!!.jsonObject["continuousFailures"])
        assertTrue(report["omittedForSize"]!!.jsonArray.any { it.jsonPrimitive.content == "processExit.historicalExits.nativeTrace" })
    }

    private fun largeSnapshot(): JsonObject {
        val id = "00000000-0000-0000-0000-000000000006"
        val selection = ContinuousEncoderSelectionPolicy.choose(true, List(8) {
            ContinuousCodecCandidate("c".repeat(160), true, true, true, true,
                Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, "e".repeat(80)) })
        val stages = buildJsonObject {
            listOf("observedAtElapsedMs", "writtenFrames", "lastWrittenPtsUs", "lastWriteElapsedMs", "ownedNativeFiles",
                "nextPreparedFile", "inputSubmissions", "sourceFrameAgeMs", "timelineOffsetUs", "sourceLastPtsUs",
                "playbackLastPtsUs", "timeLapseMultiplier", "keyframeTimeoutMs").forEach { put(it, Long.MAX_VALUE) }
            put("ending", false); put("continuationRevoked", false); put("closeProblem", "FINALIZER_NATIVE_RELEASE_UNCONFIRMED")
            put("cut", buildJsonObject { listOf("file", "requestedAtMs", "keyAcceptedAtMs", "writerReceivedAtMs", "sourceWaitMs")
                .forEach { put(it, Long.MAX_VALUE) } })
            put("recentClosers", buildJsonArray { repeat(4) { add(buildJsonObject {
                put("file", Int.MAX_VALUE); put("stage", "USB_SYNC_CLOSE"); put("stageAtMs", Long.MAX_VALUE)
                put("acceptedAtMs", Long.MAX_VALUE); put("completedAtMs", Long.MAX_VALUE)
                put("nativeReleased", false); put("failureType", "e".repeat(160))
            }) } })
        }
        val lifecycle = buildJsonObject { put("unsettledOwners", 3); put("transactions", buildJsonObject {
            repeat(3) { index -> put("$index", buildJsonArray { repeat(16) { add(buildJsonObject {
                put("at", Long.MAX_VALUE); put("elapsed", Long.MAX_VALUE); put("step", "s".repeat(80))
            }) } }) }
        }) }
        val facts = listOf("reason", "status", "phase", "availability", "attempts", "resumeAllowed", "route", "cameraGeneration")
            .associateWith { "v".repeat(80) }
        val events = (1L..20L).map { ProbeEvent(it, "app", Long.MAX_VALUE, Long.MAX_VALUE,
            "SYSTEM", "RECORDER_STOP", "ERROR", payload = facts) }
        return ShortRecorderReport.snapshot(RecorderState(recordingSessionId = id, channelId = "SURROUND", segmentNumber = 2,
            encoderSelection = selection, recordingBackend = "SHARED_INPUT_CONTINUOUS_CODEC", lastError = "CAMERA_CLOSE_UNCONFIRMED"),
            CaptureEvidence(id, 2, SegmentFrameStats(Long.MAX_VALUE, 10, Long.MAX_VALUE, Long.MAX_VALUE), 1,
                encoder = EncoderEvidence(Long.MAX_VALUE, 1, Long.MAX_VALUE, 1, Long.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE,
                    Long.MAX_VALUE, stages, stages, Long.MAX_VALUE, 1), channelId = "SURROUND"),
            lifecycle, events, Long.MAX_VALUE, Long.MAX_VALUE,
            MirrorEvidence(frames = Long.MAX_VALUE, lastFrameAgeMs = Long.MAX_VALUE,
                textureCallbacks = Long.MAX_VALUE, drawPasses = Long.MAX_VALUE,
                gl = MirrorGlEvidence(inputNotifications = Long.MAX_VALUE, inputFrames = Long.MAX_VALUE,
                    inputFrameAgeMs = Long.MAX_VALUE, inputHeartbeatAgeMs = Long.MAX_VALUE, inputPollAttempts = Long.MAX_VALUE,
                    inputPolledFrames = Long.MAX_VALUE, displaySubmissions = Long.MAX_VALUE, poolBytes = Long.MAX_VALUE)))
    }
}
