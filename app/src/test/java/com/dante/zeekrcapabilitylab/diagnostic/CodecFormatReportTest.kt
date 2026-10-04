package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousFormatFacts
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodecFormatReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "34695741-c1ed-4e36-bccf-a85e4015a775"
    private fun event(at: Long, decision: String, fields: Map<String, String> = emptyMap(), channel: String = "FRONT_CABIN", session: String = id) =
        ProbeEvent(at, "app", at, at, "SYSTEM", ContinuousFormatFacts.EVENT, "WARN", payload = mapOf(
            "recordingSessionId" to session, "channelId" to channel, "formatDecision" to decision,
            "formatNotifications" to at.toString(), "formatObservedElapsedMs" to at.toString()) + fields)
    private val change = mapOf("formatField_CSD_0" to "CHANGED|true|true|18|20", "formatCutPending" to "true")

    @Test fun appliedChangeSurvivesSubsequentKeepsStopJournalClippingAndDiskReload() {
        val formats = listOf(event(1, "INSTALL"), event(2, "ROLLOVER_PENDING", change), event(3, "ROLLOVER_APPLIED", change)) +
            (4L..700L).map { event(it, "KEEP") }
        val stopped = ProbeEvent(701, "app", 701, 701, "SYSTEM", "RECORDER_STOP", "INFO")
        val directory = temporary.newFolder()
        File(directory, "app.jsonl").writeText((formats + stopped).joinToString("\n", postfix = "\n") { Json.encodeToString(ProbeEvent.serializer(), it) })
        val history = ProcessExitDiagnostics.recentHistory(directory).events
        assertTrue(history.size <= 512)
        val report = CodecFormatReport.report(history.map(CodecFormatReport::row))
        val group = report["groups"]!!.jsonArray.single().jsonObject
        assertEquals("INSTALL", group["firstAvailable"]!!.jsonObject["format"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
        assertEquals("KEEP", group["latestAvailable"]!!.jsonObject["format"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
        val meaningful = group["latestMeaningfulChange"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("ROLLOVER_APPLIED", meaningful["decision"]!!.jsonPrimitive.content)
        assertEquals("CHANGED", meaningful["fields"]!!.jsonObject["CSD_0"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

        val away = formats.map { AwayRow(it.eventName, it.epochMs, it.elapsedRealtimeMs, it.elapsedRealtimeMs, "p", it.payload) } +
            (701L..2200L).map { AwayRow("POWER_PASSIVE_SNAPSHOT", it, it, it, "p") }
        val retained = AwayReportEvidence.retainRows(away)
        assertEquals(1200, retained.size)
        val persisted = Json.decodeFromString<List<AwayRow>>(Json.encodeToString(retained))
        val copy = AwayReportEvidence.bounded(buildJsonObject { put("recordingEvidence", AwayReportEvidence.recording(persisted)) }, persisted)
        val awayGroup = copy["recordingEvidence"]!!.jsonObject["codecFormats"]!!.jsonObject["groups"]!!.jsonArray.single().jsonObject
        assertEquals("ROLLOVER_APPLIED", awayGroup["latestMeaningfulChange"]!!.jsonObject["format"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
        assertTrue(copy.toString().toByteArray().size <= AwayReportEvidence.MAX_BYTES)
    }

    @Test fun sameMillisecondOwnersAndPreviousSessionFormatsRemainDistinctAfterRestart() {
        val rows = listOf("SURROUND", "FRONT_CABIN", "REAR_CABIN").map {
            val event = event(1, "INCOMPLETE", mapOf("formatField_CSD_1" to "MISSING|UNKNOWN|false|UNKNOWN|UNKNOWN"), it)
            AwayRow(event.eventName, 1, 1, 1, "p", event.payload)
        }
        assertEquals(3, CodecFormatReport.report(rows.map(CodecFormatReport::row))["groups"]!!.jsonArray.size)
        val nextId = "34695741-c1ed-4e36-bccf-a85e4015a776"
        val oldFault = rows.first().copy(event = "RECORDER_CONTINUOUS_FIRST_FAILURE", facts = rows.first().facts +
            mapOf("errorCode" to "ENCODER_TRACK_FORMAT_INCOMPLETE", "failureStage" to "OUTPUT_PREPARE"))
        val stopped = AwayRow("RECORDER_STOP", 2, 2, 2, "p", mapOf("recordingSessionId" to id))
        val newStart = AwayRow("RECORDER_START", 3, 3, 3, "p", mapOf("recordingSessionId" to nextId))
        val nextFormat = event(4, "INSTALL", channel = "FRONT_CABIN", session = nextId).let {
            AwayRow(it.eventName, it.epochMs, it.elapsedRealtimeMs, it.elapsedRealtimeMs, "p", it.payload)
        }
        val history = rows + oldFault + stopped + newStart + nextFormat
        val copy = AwayReportEvidence.bounded(buildJsonObject { put("recordingEvidence", AwayReportEvidence.recording(history)) }, history)
        val recording = copy["recordingEvidence"]!!.jsonObject
        assertEquals(JsonNull, recording["firstContinuousFailure"])
        val report = recording["codecFormats"]!!.jsonObject
        assertEquals("RETAINED_FORMAT_OBSERVATIONS_PER_SESSION_AND_CHANNEL", report["scope"]!!.jsonPrimitive.content)
        val groups = report["groups"]!!.jsonArray.map { it.jsonObject }
        val previous = groups.filter { it["sessionCorrelation"]!!.jsonPrimitive.content == ProcessExitReport.sessionCorrelation(id) }
        assertEquals(3, previous.size)
        assertTrue(previous.all { it["latestMeaningfulChange"] != JsonNull })
        val next = groups.single { it["sessionCorrelation"]!!.jsonPrimitive.content == ProcessExitReport.sessionCorrelation(nextId) }
        assertEquals("FRONT_CABIN", next["channelId"]!!.jsonPrimitive.content)
        assertEquals("INSTALL", next["latestAvailable"]!!.jsonObject["format"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
        assertTrue(next["latestAvailable"]!!.jsonObject["format"]!!.jsonObject["fields"]!!.jsonObject.isEmpty())
        assertEquals(JsonNull, next["latestMeaningfulChange"])
    }

    @Test fun worstCaseFieldsRemainBoundedWithoutDroppingTheirLabelsFromRetainedGroups() {
        val fields = ContinuousFormatFacts.fields.take(24).associate { "formatField_$it" to "TYPE_MISMATCH|UNKNOWN|UNKNOWN|2147483647|2147483647" }
        val rows = (1L..6L).flatMap { n ->
            val session = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
            listOf(event(n * 10, "INCOMPLETE", fields, session = session), event(n * 10 + 1, "INCOMPATIBLE", fields, session = session))
        }
        val report = CodecFormatReport.report(rows.map(CodecFormatReport::row))
        assertTrue(report.toString().toByteArray().size <= CodecFormatReport.MAX_BYTES)
        assertTrue(report["groups"]!!.jsonArray.isNotEmpty())
        report["groups"]!!.jsonArray.forEach { group ->
            val format = group.jsonObject["latestMeaningfulChange"]!!.jsonObject["format"]!!.jsonObject
            val labels = (format["fieldKinds"] ?: format["fields"])!!.jsonObject.keys
            assertEquals(fields.keys.map { it.removePrefix("formatField_") }.toSet(), labels)
        }
        val exits = buildJsonObject { put("codecFormats", report) }
        val short = ShortRecorderReport.report("test", 1, 32, buildJsonObject {}, null, processExit = exits)
        assertEquals(report, Json.parseToJsonElement(short).jsonObject["processExit"]!!.jsonObject["codecFormats"])
        assertTrue(short.toByteArray().size <= ShortRecorderReport.MAX_BYTES)
        val away = rows.map { AwayRow(it.eventName, it.epochMs, it.elapsedRealtimeMs, it.elapsedRealtimeMs, "p", it.payload) } +
            AwayRow("RECORDER_START", 1000, 1000, 1000, "p", mapOf("recordingSessionId" to id))
        val copy = AwayReportEvidence.bounded(buildJsonObject {
            put("recordingEvidence", AwayReportEvidence.recording(away))
            put("lifecycleEvidence", AwayReportEvidence.lifecycle(away))
        }, away)
        assertEquals(report, copy["recordingEvidence"]!!.jsonObject["codecFormats"])
        assertTrue(copy.toString().toByteArray().size <= AwayReportEvidence.MAX_BYTES)
        assertTrue(copy["eventsOmittedFromCopy"]!!.jsonPrimitive.int > 0)
    }

    @Test fun malformedOrPrivateFormatFieldsCannotEnterEitherClipboardProjectionOrAwayTimeline() {
        val row = event(1, "INCOMPLETE", mapOf("formatField_CSD_0" to "CHANGED|true|true|/storage/private|secret",
            "formatField_VENDOR_PRIVATE" to "secret", "formatNotifications" to "-1", "formatCutPending" to "secret",
            "formatField_CSD_1" to "MISSING|UNKNOWN|false|UNKNOWN|UNKNOWN"))
        val report = CodecFormatReport.report(listOf(CodecFormatReport.row(row)))
        val format = report["groups"]!!.jsonArray.single().jsonObject["latestAvailable"]!!.jsonObject["format"]!!.jsonObject
        assertEquals(setOf("CSD_1"), format["fields"]!!.jsonObject.keys)
        assertEquals(JsonNull, format["notifications"]); assertEquals(JsonNull, format["cutPending"])
        val away = AwayRow(row.eventName, 1, 1, 1, "p", row.payload)
        val copy = AwayReportEvidence.bounded(buildJsonObject { put("recordingEvidence", AwayReportEvidence.recording(listOf(away))) }, listOf(away))
        listOf("private", "secret", "vendor", "/storage").forEach { assertFalse((report.toString() + copy).contains(it, true)) }
    }
}
