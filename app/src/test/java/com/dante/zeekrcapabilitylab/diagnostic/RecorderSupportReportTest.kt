package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelHealth
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelJournal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class RecorderSupportReportTest {
    private val okay = RecorderSupportReport.section { buildJsonObject { put("status", "IDLE") } }

    @Test fun anUnavailableSectionPreservesTheOtherSnapshotsWithoutExportingItsException() {
        val failed = RecorderSupportReport.section { throw IOException("private/account/token") }
        val report = Json.parseToJsonElement(RecorderSupportReport.build("test", 1, 100, okay, failed, emptyList())).jsonObject
        assertTrue(report["recorder"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        assertFalse(report["away"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
        assertFalse(report.toString().contains("private"))
        assertFalse(report["videoIncluded"]!!.jsonPrimitive.boolean)
        assertFalse(report["coordinatesIncluded"]!!.jsonPrimitive.boolean)
    }

    @Test fun channelEvidenceIsBoundedAndDoesNotExposeRawSessionIdsOrArbitraryErrors() {
        val id = "00000000-0000-0000-0000-000000000001"
        val channels = (1..20).map { RecordingChannelHealth(id, "SURROUND", status = "WRITING",
            writtenFrames = it.toLong(), errorCode = "failure at /private/file") }
        val text = RecorderSupportReport.build("test", 1, 100, okay, okay, channels)
        val rows = Json.parseToJsonElement(text).jsonObject["runtimeChannels"]!!.jsonArray
        assertEquals(9, rows.size)
        assertEquals(20L, rows.last().jsonObject["writtenFrames"]!!.jsonPrimitive.long)
        assertFalse(text.contains(id)); assertFalse(text.contains("private"))
        assertTrue(text.contains("sha256:"))
    }

    @Test fun oneUtf8FileContainsBothSectionsWithoutClipboardParts() {
        val text = RecorderSupportReport.build("test", 1, 100, okay, okay, emptyList())
        val output = ByteArrayOutputStream()
        RecorderSupportReport.write(text, output)
        assertEquals(text, output.toString("UTF-8"))
        assertFalse(text.contains("JSON_PART"))
    }

    @Test fun historicalChannelsAreSeparateFromLiveStateAndKeepTheSessionCorrelation() {
        val saved = RecordingChannelJournal.Saved("AVAILABLE", listOf(RecordingChannelHealth(
            "00000000-0000-0000-0000-000000000001", "FRONT_CABIN", errorCode = "AUXILIARY_NO_WRITTEN_FRAMES")))
        val report = Json.parseToJsonElement(RecorderSupportReport.build("test", 1, 100, okay, okay,
            emptyList(), RecorderSupportReport.retainedChannels(saved))).jsonObject
        assertTrue(report["runtimeChannels"]!!.jsonArray.isEmpty())
        assertEquals(1, report["retainedChannels"]!!.jsonObject["channels"]!!.jsonArray.size)
        assertEquals("SAVED_HISTORY_MAY_PREDATE_THIS_PROCESS_NOT_CURRENT_CAMERA_STATE",
            report["retainedChannels"]!!.jsonObject["scope"]!!.jsonPrimitive.content)
    }

    @Test fun wrongFormatAndOversizeAreRejectedBeforeWritingAnyBytes() {
        listOf("{}", "x".repeat(RecorderSupportReport.MAX_BYTES + 1)).forEach { text ->
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { RecorderSupportReport.write(text, output) }
            assertEquals(0, output.size())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderSupportReport.build("test", 1, 100,
                buildJsonObject { put("tooLarge", "x".repeat(RecorderSupportReport.MAX_BYTES)) }, okay, emptyList())
        }
    }
}
