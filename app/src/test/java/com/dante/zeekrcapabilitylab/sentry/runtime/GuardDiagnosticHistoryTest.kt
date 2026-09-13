package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GuardDiagnosticHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_789_215_000_000L
    private val json = Json { encodeDefaults = true }
    private fun event(index: Int, name: String = "RECORDER_SEGMENT_START") = ProbeEvent(index.toLong(), "app",
        now - 10_000 + index, 1_000 + index.toLong(), "SYSTEM", name, "INFO",
        payload = mapOf("file" to "延时视频.mp4", "recordingMode" to "TIME_LAPSE"))
    private fun write(directory: File, name: String, values: List<ProbeEvent>) = File(directory, name).apply {
        writeText(values.joinToString("\n", postfix = "\n") { json.encodeToString(it) })
    }

    @Test fun oldProductLogsSurviveRestartAndBrokenLinesWithoutReadingLabSessions() {
        val dir = temporary.newFolder()
        val start = event(1)
        val stop = event(2, "RECORDER_STOP")
        write(dir, "app-1789214990000.jsonl", listOf(start))
        File(dir, "app.jsonl").writeText("{torn\n" + json.encodeToString(stop) + "\n")
        write(dir, "private-lab.jsonl", listOf(event(3, "RECORDER_STOP")))
        val report = GuardDiagnosticHistory.collect(dir, listOf(stop), now)
        assertEquals(listOf(start, stop), report.events)
        assertEquals(1, report.malformedLines)
        assertEquals(2, report.filesRead)
        assertEquals("TIME_LAPSE", report.events.first().payload["recordingMode"])
        assertEquals("延时视频.mp4", report.events.first().payload["file"])
    }

    @Test fun noisyPowerUpdatesCannotEvictTheStartAndStopEvidence() {
        val dir = temporary.newFolder()
        val first = event(1, "RECORDER_START")
        val values = listOf(first) + (2..500).map { event(it, "RECORDER_POWER_SNAPSHOT_RECONCILED") }
        write(dir, "app.jsonl", values)
        val report = GuardDiagnosticHistory.collect(dir, emptyList(), now)
        assertTrue(report.truncated)
        assertTrue(first in report.events)
        assertEquals(41, report.events.size)
    }

    @Test fun byteWindowIsEnforcedAndUtf8PartialFirstLineDoesNotHideTheLastEvent() {
        val dir = temporary.newFolder()
        val last = event(9)
        val line = json.encodeToString(last)
        File(dir, "app.jsonl").writeText("中".repeat(3000) + "\n" + line + "\n")
        val report = GuardDiagnosticHistory.collect(dir, emptyList(), now, maxReadBytes = 1024)
        assertEquals(1024L, report.bytesRead)
        assertTrue(report.truncated)
        assertEquals(listOf(last), report.events)
    }

    @Test fun reportHasFiniteEventAndPayloadBudgets() {
        val dir = temporary.newFolder()
        val memory = (0..300).map { event(it).copy(payload = (0..30).associate { "field-$it" to "x".repeat(3000) }) }
        val report = GuardDiagnosticHistory.collect(dir, memory, now)
        assertTrue(report.truncated)
        assertTrue(report.events.size <= 120)
        assertTrue(report.events.all { it.payload.size <= 12 && it.payload.values.all { value -> value.length <= 240 } })
        assertTrue(report.events.sumOf { json.encodeToString(it).toByteArray().size } <= 60 * 1024)
    }

    @Test fun sequenceReuseAcrossRebootsIsNotMistakenForADuplicate() {
        val first = event(1)
        val second = first.copy(elapsedRealtimeMs = first.elapsedRealtimeMs + 5)
        val tooOld = first.copy(epochMs = now - 25 * 60 * 60_000L)
        val future = first.copy(epochMs = now + 1)
        val report = GuardDiagnosticHistory.collect(temporary.newFolder(), listOf(first, first, second, tooOld, future), now)
        assertEquals(listOf(first, second), report.events)
    }
}
