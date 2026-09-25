package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AwayReportEvidenceTest {
    private val rows = listOf(
        AwayRow("VEHICLE_AWAY_TEST_MARKER", 10_000, 100, 100, "process-a"),
        AwayRow("RECORDER_START", 10_100, 200, 200, "process-a"),
        AwayRow("RECORDER_STOP", 10_800, 900, 900, "process-a", mapOf("authorityReason" to "CONTINUOUS_FAILED")),
        AwayRow("SCREEN_OFF", 10_900, 1000, 1000, "process-a"),
    )
    private fun fault(process: String?="process-a", version: Int=97, elapsed: Long=899): JsonObject = buildJsonObject {
        process?.let { put("processStartId", it) }
        put("capturedVersionCode", version); put("atElapsedMs", elapsed); put("atEpochMs", 10_799)
        put("lastErrorCode", "USB_WRITE_FAILED: content://private/video?token=secret")
        put("recordingBackend", "SHARED_INPUT_CONTINUOUS_CODEC"); put("storage", "USB_MEDIASTORE")
        put("segmentNumber", 7); put("currentFile", "private-personal-video.mp4")
        put("measured", buildJsonObject { put("encodedFrames", 100); put("privatePath", "/sdcard/private.mp4") })
    }

    @Test fun errorMessageExportsOnlyEnumPrefix() {
        assertEquals("USB_WRITE_FAILED", AwayReportEvidence.errorCode("USB_WRITE_FAILED: content://private/a?token=secret"))
        assertNull(AwayReportEvidence.errorCode("content://private/a"))
        assertNull(AwayReportEvidence.errorCode("/sdcard/PRIVATE.mp4"))
        assertNull(AwayReportEvidence.errorCode("A".repeat(1000)))
    }
    @Test fun structuredExceptionSurvivesWithoutExportingItsRawMessage() {
        val failed = AwayRow(AwayReportEvidence.CONTINUOUS_FAILURE, 10_300, 400, 400, "process-a", mapOf(
            "errorCode" to "UNCLASSIFIED_THROWABLE", "failureStage" to "FILE_WRITER",
            "exceptionType" to "java.lang.IllegalStateException", "faultElapsedMs" to "399",
            "rawMessage" to "Failed to write content://private/a?token=secret"))
        val facts = AwayReportEvidence.recording(rows.take(2)+failed)["lastContinuousFailure"]!!.jsonObject["facts"]!!.jsonObject
        assertEquals(JsonPrimitive("FILE_WRITER"), facts["failureStage"])
        assertEquals(JsonPrimitive("java.lang.IllegalStateException"), facts["exceptionType"])
        assertFalse(facts.toString().contains("secret"))
    }
    @Test fun firstContinuousFailureIsNotReplacedByCleanupFailure() {
        val original = AwayRow(AwayReportEvidence.CONTINUOUS_FAILURE, 10_300, 400, 400, "process-a", mapOf(
            "errorCode" to "UNCLASSIFIED_THROWABLE", "failureStage" to "CODEC_DRAIN",
            "exceptionType" to "java.lang.IllegalStateException", "faultElapsedMs" to "399"))
        val cleanup = original.copy(wall=10_800, elapsed=900, uptime=900,
            facts=mapOf("errorCode" to "PRODUCT_MEDIA_RELEASE_UNCONFIRMED", "faultElapsedMs" to "899"))
        val report = AwayReportEvidence.recording(rows.take(2)+original+cleanup)
        val first = report["firstContinuousFailure"]
        assertNotNull("The independent first-failure summary must survive later cleanup errors", first)
        assertEquals(JsonPrimitive("CODEC_DRAIN"), first!!.jsonObject["facts"]!!.jsonObject["failureStage"])
    }
    @Test fun structuredFirstFailureTerminationAndRealCleanupSurviveTheSameClipboardBudget() {
        val capture = com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureCapture("run-a", {399}, {10_299})
        val fault = capture.capture(IllegalStateException("Failed to write /private?token=secret"),
            com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureStage.FILE_WRITER,
            com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureSample(100, 1_234_567, 15, 100, 800_000, 2, 30))
        fun e(event: String, at: Long, facts: Map<String,String> = emptyMap()) =
            AwayRow(event,at,at,at,"process-a",mapOf("recordingSessionId" to "run-a")+facts)
        val events = listOf(e("RECORDER_START",200), e(AwayReportEvidence.CONTINUOUS_FAILURE,400,fault.facts()),
            e("RECORDER_SESSION_TERMINATED",500,mapOf("reason" to "CONTINUOUS_FAILED")),
            e("RECORDER_CLOSE_REQUESTED",600,mapOf("closeId" to "c", "deviceOwnerId" to "42")),
            e("RECORDER_CLOSE_SETTLED",800,mapOf("closeId" to "c", "safeToContinue" to "true", "deviceClosed" to "true"))) +
            (1..100).map { e("POWER_PASSIVE_SNAPSHOT",1_000L+it,mapOf("detail" to "x".repeat(700))) }
        val report = AwayReportEvidence.bounded(buildJsonObject {
            put("recordingEvidence",AwayReportEvidence.recording(events))
            put("lifecycleEvidence",AwayReportEvidence.lifecycle(events))
        },events)
        assertTrue(report.toString().toByteArray().size <= AwayReportEvidence.MAX_BYTES)
        assertFalse(report["timeline"]!!.toString().contains(AwayReportEvidence.CONTINUOUS_FAILURE))
        val facts = report["recordingEvidence"]!!.jsonObject["firstContinuousFailure"]!!.jsonObject["facts"]!!.jsonObject
        assertEquals(JsonPrimitive("FILE_WRITER"),facts["failureStage"])
        assertEquals(JsonPrimitive("1234567"),facts["faultQueueBytes"])
        assertEquals(JsonPrimitive("299"),facts["snapshotAgeMs"])
        assertEquals(JsonPrimitive("java.lang.IllegalStateException"),facts["exceptionType"])
        val life = report["lifecycleEvidence"]!!.jsonObject
        assertNotEquals(JsonNull, life["termination"])
        assertEquals(JsonPrimitive("true"),life["cleanupResult"]!!.jsonObject["facts"]!!.jsonObject["deviceClosed"])
        assertFalse(report.toString().contains("secret"))
    }
    @Test fun oldSessionContinuousFailureCannotBecomeNewSessionsFirstFailure() {
        val start = AwayRow("RECORDER_START",200,200,200,"p",mapOf("recordingSessionId" to "new"))
        val old = AwayRow(AwayReportEvidence.CONTINUOUS_FAILURE,300,300,300,"p",
            mapOf("recordingSessionId" to "old", "failureStage" to "FILE_CLEANUP", "faultElapsedMs" to "100"))
        assertEquals(JsonNull,AwayReportEvidence.recording(listOf(start,old))["firstContinuousFailure"])
    }
    @Test fun capturePointEvidenceSurvivesWithoutAControllerErrorAndDoesNotRewriteTermination() {
        val start = AwayRow("RECORDER_START",200,200,200,"p",mapOf("recordingSessionId" to "run"))
        val stopped = start.copy(event="RECORDER_SESSION_TERMINATED",wall=300,elapsed=300,uptime=300,
            facts=start.facts+mapOf("reason" to "USER_STOP"))
        val failure = start.copy(event=AwayReportEvidence.FIRST_CONTINUOUS_FAILURE,wall=500,elapsed=500,uptime=500,
            facts=start.facts+mapOf("failureStage" to "FILE_FINALIZE", "exceptionType" to "java.lang.IllegalStateException",
                "faultElapsedMs" to "499", "faultCaptureOrder" to "1"))
        val events = listOf(start,stopped,failure)
        val result = AwayReportEvidence.recording(events)
        assertEquals(JsonNull,result["lastContinuousFailure"])
        assertEquals(JsonPrimitive("FILE_FINALIZE"),result["firstContinuousFailure"]!!.jsonObject["facts"]!!.jsonObject["failureStage"])
        assertEquals(JsonPrimitive("USER_STOP"),AwayReportEvidence.lifecycle(events)["termination"]!!.jsonObject["facts"]!!.jsonObject["reason"])
    }
    @Test fun originalCaptureOrderBreaksSameMillisecondAsyncDeliveryTies() {
        val first = AwayRow(AwayReportEvidence.CONTINUOUS_FAILURE,800,800,800,"p",
            mapOf("faultElapsedMs" to "400", "faultCaptureOrder" to "1", "failureStage" to "CODEC_DRAIN"))
        val later = first.copy(elapsed=600,wall=600,uptime=600,
            facts=first.facts+mapOf("faultCaptureOrder" to "2", "failureStage" to "FILE_CLEANUP"))
        val facts = AwayReportEvidence.recording(listOf(later,first))["firstContinuousFailure"]!!.jsonObject["facts"]!!.jsonObject
        assertEquals(JsonPrimitive("CODEC_DRAIN"),facts["failureStage"])
    }
    @Test fun matchingFaultIsCompactAndDoesNotCopyPathsOrFreeText() {
        val report = AwayReportEvidence.faultInWindow(rows, fault(), 97)
        assertEquals("MATCHED_TEST_WINDOW", report["match"]!!.jsonPrimitive.content)
        val snapshot = report["snapshot"]!!.jsonObject
        assertEquals("USB_WRITE_FAILED", snapshot["lastErrorCode"]!!.jsonPrimitive.content)
        assertEquals(7, snapshot["segmentNumber"]!!.jsonPrimitive.int)
        assertEquals(100, snapshot["measured"]!!.jsonObject["encodedFrames"]!!.jsonPrimitive.int)
        listOf("private", "secret", "token", "currentFile", "content:").forEach { assertFalse(report.toString().contains(it)) }
    }
    @Test fun staleOrUncorrelatedFaultsAreNotAttributedToThisTest() {
        val candidates = listOf(
            fault(version=96) to "OTHER_VERSION", fault(process="other-process") to "OTHER_PROCESS",
            fault(process=null) to "LEGACY_FAULT_WITHOUT_PROCESS_ID", fault(elapsed=99) to "OUTSIDE_TEST_WINDOW",
            fault(elapsed=1001) to "OUTSIDE_TEST_WINDOW", null to "NO_SAVED_FAULT",
        )
        candidates.forEach { (candidate, reason) ->
            val report=AwayReportEvidence.faultInWindow(rows,candidate,97)
            assertEquals(reason,report["match"]!!.jsonPrimitive.content)
            assertEquals(JsonNull,report["snapshot"])
        }
    }
    @Test fun aFaultBeforeTheC0BoundaryCannotBeReplacedWithALaterExperimentFault() {
        assertEquals("OUTSIDE_TEST_WINDOW", AwayReportEvidence.faultInWindow(rows, fault(elapsed=1500),97)["match"]!!.jsonPrimitive.content)
    }
    @Test fun routineRowsCanBeTrimmedWithoutLosingTheStopOrError() {
        val failed = AwayRow(AwayReportEvidence.CONTINUOUS_FAILURE,10_799,899,899,"process-a",mapOf("errorCode" to "USB_WRITE_FAILED"))
        val events=rows.take(2)+failed+rows.drop(2)+(1..80).map {
            AwayRow("POWER_PASSIVE_SNAPSHOT",11_000L+it,1100L+it,1100L+it,"process-a",mapOf("detail" to "x".repeat(700)))
        }
        val report=AwayReportEvidence.bounded(buildJsonObject {
            put("recordingEvidence",AwayReportEvidence.recording(events))
            put("recorderFaultInTestWindow",AwayReportEvidence.faultInWindow(events,fault(),97))
        },events)
        assertTrue(report.toString().toByteArray(Charsets.UTF_8).size<=AwayReportEvidence.MAX_BYTES)
        assertTrue(report["timeline"]!!.jsonArray.size<48)
        assertFalse(report["timeline"]!!.toString().contains("RECORDER_STOP"))
        val evidence=report["recordingEvidence"]!!.jsonObject
        assertEquals("CONTINUOUS_FAILED",evidence["lastStop"]!!.jsonObject["facts"]!!.jsonObject["authorityReason"]!!.jsonPrimitive.content)
        assertEquals("USB_WRITE_FAILED",evidence["lastContinuousFailure"]!!.jsonObject["facts"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
        assertEquals(events.size-report["timeline"]!!.jsonArray.size,report["eventsOmittedFromCopy"]!!.jsonPrimitive.int)
    }
    @Test fun oldRc4StopRemainsUnknownRatherThanInventingAnErrorCode() {
        val report=AwayReportEvidence.recording(rows)
        assertEquals(JsonNull,report["lastContinuousFailure"])
        assertEquals(1,report["startRequests"]!!.jsonPrimitive.int)
        assertTrue(report["recordingObserved"]!!.jsonPrimitive.boolean)
    }
    @Test fun snapshotCanProveRecordingWasObservedWithoutInventingAStartRequest() {
        val report=AwayReportEvidence.recording(listOf(rows.first().copy(facts=mapOf("recorder" to "RECORDING"))))
        assertEquals(0,report["startRequests"]!!.jsonPrimitive.int)
        assertTrue(report["recordingObserved"]!!.jsonPrimitive.boolean)
    }
    @Test fun firstCameraCauseAndLateCleanupSurviveClippingWithoutInventingUsbSuccess() {
        fun e(name: String, at: Long, vararg facts: Pair<String,String>) = AwayRow(name,at,at,at,"p",
            mapOf("recordingSessionId" to "a") + facts.toMap())
        val events = listOf(e("RECORDER_START",0),
            e("RECORDER_CAMERA_CALLBACK",100,"callbackType" to "ON_ERROR", "cameraErrorCode" to "1", "errorCode" to "CAMERA_IN_USE"),
            e("RECORDER_CLOSE_REQUESTED",110,"closeId" to "c", "deviceOwnerId" to "42"),
            e("RECORDER_CLOSE_UNCONFIRMED",3110,"closeId" to "c", "errorCode" to "CAMERA_CLOSE_UNCONFIRMED"),
            e("RECORDER_SESSION_TERMINATED",3110,"reason" to "CAMERA_CLOSE_UNCONFIRMED", "screenOn" to "true"),
            e("RECORDER_CAMERA_CALLBACK",6000,"callbackType" to "ON_CLOSED", "deviceOwnerId" to "42"),
            e("RECORDER_CLOSE_SETTLED",6200,"closeId" to "c", "safeToContinue" to "true", "deviceClosed" to "true"),
            e("SCREEN_OFF",52_500)) + (1..100).map { e("POWER_PASSIVE_SNAPSHOT",53_000L+it,"detail" to "x".repeat(700)) }
        val result = AwayReportEvidence.bounded(buildJsonObject { put("lifecycleEvidence",AwayReportEvidence.lifecycle(events)) }, events)
        val life = result["lifecycleEvidence"]!!.jsonObject
        assertFalse(result["timeline"]!!.toString().contains("ON_ERROR"))
        assertEquals("CAMERA_IN_USE",life["firstCameraFailure"]!!.jsonObject["facts"]!!.jsonObject["errorCode"]!!.jsonPrimitive.content)
        assertEquals("CAMERA_CLOSE_UNCONFIRMED",life["termination"]!!.jsonObject["facts"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(6000L,life["closeCallback"]!!.jsonObject["elapsed"]!!.jsonPrimitive.long)
        assertEquals(6200L,life["cleanupResult"]!!.jsonObject["elapsed"]!!.jsonPrimitive.long)
        assertEquals(52500L,life["screenOffAfterTermination"]!!.jsonObject["elapsed"]!!.jsonPrimitive.long)
        assertEquals("NOT_VERIFIED_BY_THIS_REPORT",life["usbLastVideoIntegrity"]!!.jsonPrimitive.content)
        assertTrue(result.toString().toByteArray().size <= AwayReportEvidence.MAX_BYTES)
    }
    @Test fun aDifferentDeviceClosedDoesNotConfirmTheCurrentDevice() {
        val events = listOf(AwayRow("RECORDER_CLOSE_REQUESTED",1,1,1,"p",mapOf("closeId" to "c", "deviceOwnerId" to "42")),
            AwayRow("RECORDER_CAMERA_CALLBACK",2,2,2,"p",mapOf("callbackType" to "ON_CLOSED", "deviceOwnerId" to "43")))
        val life = AwayReportEvidence.lifecycle(events)
        assertEquals(JsonNull,life["closeCallback"]); assertEquals(JsonNull,life["cleanupResult"])
    }
    @Test fun lateOldSessionFailureDoesNotBecomeTheNewSessionsFirstCause() {
        val events = listOf(AwayRow("RECORDER_START",1,1,1,"p",mapOf("recordingSessionId" to "new")),
            AwayRow("RECORDER_CAMERA_CALLBACK",2,2,2,"p",mapOf("recordingSessionId" to "old", "callbackType" to "ON_ERROR")))
        assertEquals(JsonNull,AwayReportEvidence.lifecycle(events)["firstCameraFailure"])
    }
}
