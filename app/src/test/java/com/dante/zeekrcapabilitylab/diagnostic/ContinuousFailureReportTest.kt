package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.CaptureEvidence
import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContinuousFailureReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val session = "34695741-c1ed-4e36-bccf-a85e4015a775"

    @Test fun firstTypedFaultSurvivesLaterCloseFailureAndMoreThan512OrdinaryEvents() {
        val first = failure(10)
        val events = listOf(first) + (11L..700L).map(::stop)
        val retained = ProcessExitReport.retainHistory(events)
        assertEquals(512, retained.size)
        assertTrue(retained.contains(first))
        val faults = ProcessExitReport.continuousFailures(retained)
        val close = ShortRecorderReport.snapshot(RecorderState(recordingSessionId = session,
            channelId = "FRONT_CABIN", lastError = "CAMERA_CLOSE_UNCONFIRMED"), CaptureEvidence(),
            buildJsonObject {}, emptyList(), 1000, 1000)
        val report = Json.parseToJsonElement(ShortRecorderReport.report("test", 1, 32, close, close,
            processExit = buildJsonObject { put("continuousFailures", faults) })).jsonObject
        assertEquals("CAMERA_CLOSE_UNCONFIRMED", report["lastFault"]!!.jsonObject["lastErrorCode"]!!.jsonPrimitive.content)
        val group = report["processExit"]!!.jsonObject["continuousFailures"]!!.jsonObject["groups"]!!.jsonArray.single().jsonObject
        assertEquals(close["sessionCorrelation"], group["sessionCorrelation"])
        assertEquals("FILE_WRITER", group["firstAvailable"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
        assertEquals("PRODUCT_ENCODER_BACKPRESSURE", group["firstAvailable"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
        assertFalse(report.toString().contains(session))
    }

    @Test fun boundedDiskReaderKeepsFaultBeforeHundredsOfLaterStops() {
        val events = listOf(failure(1)) + (2L..700L).map(::stop)
        val directory = temporary.newFolder()
        File(directory, "app.jsonl").writeText(events.joinToString("\n", postfix = "\n") {
            Json.encodeToString(ProbeEvent.serializer(), it)
        })
        val history = ProcessExitDiagnostics.recentHistory(directory)
        assertEquals(512, history.events.size)
        assertTrue(history.events.any { it.eventName == "RECORDER_CONTINUOUS_FIRST_FAILURE" })
        assertEquals(1, ProcessExitReport.continuousFailures(history.events)["groups"]!!.jsonArray.size)
    }

    @Test fun sameSessionDifferentChannelsStaySeparateWithFirstAndLatestEvidence() {
        val first = failure(10)
        val latest = failure(20, "RECORDER_CONTINUOUS_FAILED", extra = mapOf("failureStage" to "FILE_CLEANUP"))
        val surround = failure(15, extra = mapOf("channelId" to "SURROUND", "failureStage" to "SOURCE_WATCHDOG"))
        val groups = ProcessExitReport.continuousFailures(listOf(latest, surround, first))["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, groups.size)
        val cabin = groups.single { it["channelId"]!!.jsonPrimitive.content == "FRONT_CABIN" }
        assertEquals("FILE_WRITER", cabin["firstAvailable"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
        assertEquals("FILE_CLEANUP", cabin["latestAvailable"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
        assertEquals(groups[0]["sessionCorrelation"], groups[1]["sessionCorrelation"])
    }

    @Test fun missingChannelsNeverMergePotentiallyDifferentCameraOwners() {
        val first = failure(10).let { it.copy(payload = it.payload - "channelId") }
        val second = failure(20).let { it.copy(payload = it.payload - "channelId") }
        val groups = ProcessExitReport.continuousFailures(listOf(first, second))["groups"]!!.jsonArray
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.jsonObject["channelId"]!!.jsonPrimitive.content == "UNKNOWN" })
    }

    @Test fun catchOrderWinsWhenFirstCatchIsPublishedAfterAnotherCatchInTheSameMillisecond() {
        val secondCatch = failure(100, extra = mapOf("faultCaptureOrder" to "2", "failureStage" to "CODEC_DRAIN"))
            .copy(sequence = 10)
        val firstCatch = failure(100, extra = mapOf("faultCaptureOrder" to "1", "failureStage" to "FILE_WRITER"))
            .copy(sequence = 11)
        val retained = ProcessExitReport.retainHistory(listOf(secondCatch, firstCatch) + (101L..700L).map(::stop))
        assertTrue(retained.contains(firstCatch))
        val group = ProcessExitReport.continuousFailures(retained)["groups"]!!.jsonArray.single().jsonObject
        assertEquals(1L, group["firstAvailable"]!!.jsonObject["faultCaptureOrder"]!!.jsonPrimitive.long)
        assertEquals("FILE_WRITER", group["firstAvailable"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
    }

    @Test fun typedExceptionAndCodeTakePriorityOverCallbackWrapperWithoutPrivateText() {
        val event = failure(1, "RECORDER_CONTINUOUS_FAILED", extra = mapOf(
            "exceptionType" to "android.media.MediaCodec\$CodecException", "codecErrorCode" to "-1100", "errno" to "5",
            "codecTransient" to "false", "codecRecoverable" to "true", "recordingBackend" to "CONTINUOUS_CODEC"))
            .copy(errorMessage = "CONTINUOUS_ENCODER_FAILED:/storage/private.mp4?token=secret")
        val brief = ProcessExitReport.events(listOf(event)).single().jsonObject
        assertEquals("PRODUCT_ENCODER_BACKPRESSURE", brief["errorCode"]!!.jsonPrimitive.content)
        assertEquals("android.media.MediaCodec\$CodecException", brief["exceptionType"]!!.jsonPrimitive.content)
        val failure = ProcessExitReport.continuousFailures(listOf(event))["groups"]!!.jsonArray.single().jsonObject["firstAvailable"]!!.jsonObject
        assertEquals(-1100, failure["codecErrorCode"]!!.jsonPrimitive.int)
        assertFalse(failure["codecTransient"]!!.jsonPrimitive.boolean)
        assertTrue(failure["codecRecoverable"]!!.jsonPrimitive.boolean)
        assertFalse((brief.toString() + failure).contains("secret"))
    }

    @Test fun malformedFieldsAreRejectedAndExportIsBounded() {
        val malformed = failure(1, extra = mapOf("recordingSessionId" to "private-account", "channelId" to "/storage/private",
            "failureStage" to "https://example/private", "exceptionType" to "secret message", "errorCode" to "SECRET_TOKEN",
            "errno" to "99999999999999999999999", "faultQueueItems" to "-5"))
        val group = ProcessExitReport.continuousFailures(listOf(malformed))["groups"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, group["sessionCorrelation"])
        assertEquals("UNKNOWN", group["channelId"]!!.jsonPrimitive.content)
        val edge = group["firstAvailable"]!!.jsonObject
        assertEquals("UNKNOWN", edge["stage"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, edge["errno"])
        assertEquals(JsonNull, edge["faultQueueItems"])
        assertFalse(group.toString().contains("private"))
        assertFalse(group.toString().contains("SECRET_TOKEN"))
        val many = (1L..100L).map { n -> failure(n, extra = mapOf("recordingSessionId" to
            "00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))) }
        val bounded = ProcessExitReport.continuousFailures(many)
        assertEquals(6, bounded["groups"]!!.jsonArray.size)
        assertTrue(bounded.toString().toByteArray().size <= 10 * 1024)
    }

    @Test fun sessionCorrelationIsStableCanonicalAndNeverHashesAccountStrings() {
        assertEquals(ProcessExitReport.sessionCorrelation(session), ProcessExitReport.sessionCorrelation(session.uppercase()))
        assertNull(ProcessExitReport.sessionCorrelation("test@example.com"))
        assertNull(ProcessExitReport.sessionCorrelation("12345678901234567"))
        val group = ProcessExitReport.continuousFailures(listOf(failure(1,
            extra = mapOf("errorCode" to "UNCLASSIFIED_THROWABLE"))))["groups"]!!.jsonArray.single().jsonObject
        assertEquals("UNCLASSIFIED_THROWABLE", group["firstAvailable"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
    }

    private fun failure(at: Long, name: String = "RECORDER_CONTINUOUS_FIRST_FAILURE", extra: Map<String, String> = emptyMap()) =
        ProbeEvent(at, "app", at, at, "SYSTEM", name, "WARN", payload = mapOf(
            "recordingSessionId" to session, "channelId" to "FRONT_CABIN", "failureStage" to "FILE_WRITER",
            "errorCode" to "PRODUCT_ENCODER_BACKPRESSURE", "exceptionType" to "java.lang.IllegalStateException",
            "causeType" to "UNKNOWN", "faultElapsedMs" to at.toString(), "faultCaptureOrder" to "1") + extra)
    private fun stop(at: Long) = ProbeEvent(at, "app", at, at, "SYSTEM", "RECORDER_STOP", "INFO")
}
