package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PreviewStartupEvidenceTest {
    @Test fun retainedConnectionEvidenceDistinguishesNativeOpenFromConsumerAttachmentWithoutPrivateData() {
        val names = listOf("CAMERA_CONNECTION_NATIVE_OPENED", "CAMERA_CONNECTION_PRODUCER_RELEASED_RETAINED",
            "CAMERA_CONNECTION_LEASE_ATTACHED", "CAMERA_CONNECTION_NATIVE_CLOSED")
        val rows = names.mapIndexed { index, name ->
            assertTrue(PreviewStartupEvidence.accepts(name))
            AwayRow(name, index.toLong(), index.toLong(), index.toLong(), "current",
                mapOf("cameraId" to "2", "connection" to "123", "nativeDevice" to "456", "token" to "private-value"))
        }
        val result = PreviewStartupEvidence.report(rows).toString()
        assertTrue(result.contains("123")); assertTrue(result.contains("456"))
        assertFalse(result.contains("private-value"))
    }
    @Test fun firstNativeFailureSurvivesLaterPreviewEventsButNeverLeaksIntoANewOwner() {
        val failure = AwayRow("MIRROR_PREVIEW_FIRST_CAPTURE_FAILED", 1, 1, 1, "p", mapOf(
            "previewOwner" to "owner1", "captureFrames" to "0", "captureFailures" to "1",
            "captureFailureReason" to "0", "failureFrameNumber" to "3", "failureSequenceId" to "1",
            "failureImageCaptured" to "false", "requestTemplate" to "PREVIEW", "privateLocation" to "secret"))
        val later = (2..15).map { AwayRow("MIRROR_PREVIEW_REQUEST_RESULT", it.toLong(), it.toLong(), it.toLong(), "p",
            mapOf("previewOwner" to "owner1", "captureFrames" to "0")) }
        val report = PreviewStartupEvidence.report(listOf(failure) + later)
        val facts = report["firstSourceFailure"]!!.jsonObject["facts"]!!.jsonObject
        assertEquals("0", facts["captureFailureReason"]!!.jsonPrimitive.content)
        assertEquals("false", facts["failureImageCaptured"]!!.jsonPrimitive.content)
        assertFalse(report.toString().contains("secret"))
        assertTrue(report.toString().toByteArray().size <= 3 * 1024)

        val next = AwayRow("MIRROR_PREVIEW_OPEN_REQUESTED", 16, 16, 16, "p", mapOf("previewOwner" to "owner2"))
        assertEquals(JsonNull, PreviewStartupEvidence.report(listOf(failure) + later + next)["firstSourceFailure"])
    }

    @Test fun firstNativeFailureAndVerboseTailShareTheSameThreeKiBBudget() {
        val facts = PreviewStartupEvidence.KEYS.associateWith { "A".repeat(80) } + ("previewOwner" to "same")
        val rows = listOf(AwayRow("MIRROR_PREVIEW_FIRST_CAPTURE_FAILED", 0, 0, 0, "p", facts)) +
            (1..10).map { AwayRow("MIRROR_PREVIEW_REQUEST_RESULT", it.toLong(), it.toLong(), it.toLong(), "p", facts) }
        val report = PreviewStartupEvidence.report(rows)
        assertTrue(report.toString().toByteArray().size <= 3 * 1024)
        assertNotNull(report["firstSourceFailure"])
        assertNotEquals(JsonNull, report["firstSourceFailure"])
    }

    @Test fun compactReportKeepsSeparateCameraInputAndDisplayStagesWithoutImagesOrLocations() {
        val facts = mapOf("reason" to "BEFORE_CLOSE", "captureFrames" to "20", "captureFailures" to "1",
            "previewBuffersLost" to "2", "inputNotifications" to "18", "inputFrames" to "16",
            "inputPollAttempts" to "3", "inputPolledFrames" to "1", "inputHeartbeatAgeMs" to "50",
            "displaySubmissions" to "14", "frames" to "12", "encodedFrames" to "19",
            "image" to "privatePixels", "latitude" to "privateLocation")
        val rows = listOf(AwayRow("RECORDER_MIRROR_PIPELINE_SNAPSHOT", 1, 1, 1, "p", facts))
        assertTrue(PreviewStartupEvidence.accepts(rows.single().event))
        val report = PreviewStartupEvidence.report(rows)
        val kept = report["events"]!!.jsonArray.single().jsonObject["facts"]!!.jsonObject
        facts.filterKeys { it in PreviewStartupEvidence.KEYS }.forEach { (key, value) ->
            assertEquals(value, kept[key]!!.jsonPrimitive.content)
        }
        assertFalse(report.toString().contains("private"))
        assertTrue(report.toString().toByteArray().size <= 3 * 1024)
    }
    @Test fun verboseAllowedFactsCannotCrowdOutTheRecordingFailureSummary() {
        val rows = (1..10).map { AwayRow("MIRROR_PREVIEW_REQUEST_RESULT",it.toLong(),it.toLong(),it.toLong(),"p",
            PreviewStartupEvidence.KEYS.associateWith { "A".repeat(80) }) }
        assertTrue(PreviewStartupEvidence.report(rows).toString().toByteArray().size <= 3 * 1024)
    }
    @Test fun oldProcessesAndUnrelatedPrivateFieldsCannotEnterStartupSummary() {
        val events = listOf(AwayRow("MIRROR_PREVIEW_REQUEST_RESULT",1,1,1,"old",mapOf("sourceFrames" to "999")),
            AwayRow("MIRROR_PREVIEW_REQUEST_RESULT",2,2,2,"new",mapOf("sourceFrames" to "0", "captureFrames" to "0", "path" to "/secret/file", "reason" to "https://secret/")))
        val result = PreviewStartupEvidence.report(events)
        assertEquals(1, result["events"]!!.jsonArray.size)
        assertFalse(result.toString().contains("999"))
        assertFalse(result.toString().contains("secret"))
        assertEquals("0", result["events"]!!.jsonArray.single().jsonObject["facts"]!!.jsonObject["sourceFrames"]!!.jsonPrimitive.content)
    }
    @Test fun routineSnapshotsCannotTrimTheRecentPreviewEvidenceOutOfTheClipboard() {
        val events = (1..15).map { AwayRow("MIRROR_PREVIEW_REQUEST_RESULT",it.toLong(),it.toLong(),it.toLong(),"p",mapOf("sourceFrames" to it.toString())) } +
            (16..100).map { AwayRow("POWER_PASSIVE_SNAPSHOT",it.toLong(),it.toLong(),it.toLong(),"p",mapOf("detail" to "x".repeat(800))) }
        val result = AwayReportEvidence.bounded(buildJsonObject { put("previewStartup", PreviewStartupEvidence.report(events)) }, events)
        val summary = result["previewStartup"]!!.jsonObject["events"]!!.jsonArray
        assertEquals(10, summary.size)
        assertEquals(15, summary.last().jsonObject["elapsed"]!!.jsonPrimitive.int)
        assertTrue(result.toString().toByteArray().size <= AwayReportEvidence.MAX_BYTES)
    }
}
