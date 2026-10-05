package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PreviewStartupEvidenceTest {
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
