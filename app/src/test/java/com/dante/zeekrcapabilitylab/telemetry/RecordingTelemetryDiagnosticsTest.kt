package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class RecordingTelemetryDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "00000000-0000-4000-8000-000000000001"
    private fun gps() = File(temporary.root, "gps")
    private fun vehicle() = File(temporary.root, "vehicle")
    @Test fun pedalEvidenceContainsSourceAndAvailabilityButNoRawReadings() {
        VehicleTelemetryStore(vehicle()).open(id, 0, 0).use { writer ->
            writer.append(VehicleTelemetrySample(1_000, 1_790_000_000_000, mapOf(
                "brake_depth" to VehicleTelemetryValue(990, number = 731.246, status = "PROVISIONAL_RAW"),
                "throttle_depth" to VehicleTelemetryValue(995, status = "NO_VALUE"))))
        }
        val report = RecordingTelemetryDiagnostics.inspect(gps(), vehicle())
        val source = report.getValue("sessions").jsonArray.single().jsonObject.getValue("vehicle").jsonObject.getValue("pedalSources").jsonObject
        assertEquals("UNVERIFIED_RAW_NO_PERCENTAGE", source.getValue("scale").jsonPrimitive.content)
        assertEquals(1, source.getValue("brake_depth").jsonObject.getValue("rawAvailableCount").jsonPrimitive.int)
        assertEquals("0x00101300", source.getValue("brake_depth").jsonObject.getValue("sourceId").jsonPrimitive.content)
        assertFalse(report.toString().contains("731.246"))
    }
    @Test fun summariesKeepCountsButExcludeCoordinatesRawValuesAndPaths() {
        TelemetryStore(gps()).open(id, 0, 0).use {
            it.append(TelemetryFix(1_000, 1_790_000_000_000, -34.9234567, 138.6123456, 4f, "gps", 17.5f))
            it.append(TelemetryFix(2_000, 1_790_000_001_000, -34.9234566, 138.6123455, 4f, "gps", null))
        }
        VehicleTelemetryStore(vehicle()).open(id, 0, 0).use {
            it.append(VehicleTelemetrySample(1_000, 1_790_000_000_000, mapOf("speed" to VehicleTelemetryValue(1_000, number = 53.7))))
            it.append(VehicleTelemetrySample(1_250, 1_790_000_000_250, emptyMap(), true))
        }
        val result = RecordingTelemetryDiagnostics.inspect(gps(), vehicle())
        val encoded = result.toString()
        for (privateValue in listOf("-34.9234567", "138.6123456", "17.5", "53.7", "latitude", "longitude", temporary.root.absolutePath)) assertFalse(encoded.contains(privateValue))
        assertEquals("OK", result.getValue("status").jsonPrimitive.content)
        val session = result.getValue("sessions").jsonArray.single().jsonObject
        val gpsSummary = session.getValue("gps").jsonObject
        assertEquals(2, gpsSummary.getValue("pointCount").jsonPrimitive.int)
        assertEquals(1, gpsSummary.getValue("locationSpeedCount").jsonPrimitive.int)
        assertEquals(1_000L, gpsSummary.getValue("durationMs").jsonPrimitive.long)
        val summary = session.getValue("vehicle").jsonObject
        assertEquals(1, summary.getValue("validCounts").jsonObject.getValue("speed").jsonPrimitive.int)
        assertEquals(1, summary.getValue("unknownCounts").jsonObject.getValue("speed").jsonPrimitive.int)
        assertEquals(2, summary.getValue("unknownCounts").jsonObject.getValue("gear").jsonPrimitive.int)
    }
    @Test fun activeRecordingIsSkippedBeforeAnyDirectoryAccess() {
        val result = RecordingTelemetryDiagnostics.inspect(gps(), vehicle()) { true }
        assertEquals("SKIPPED_RECORDING_ACTIVE", result.getValue("status").jsonPrimitive.content)
        assertFalse(result.containsKey("sessions"))
        assertFalse(gps().exists()); assertFalse(vehicle().exists())
    }
    @Test fun atMostThreeNewestSessionsAreReturnedWithoutUnrelatedFileNames() {
        val store = VehicleTelemetryStore(vehicle())
        val ids = (1..5).map { UUID.randomUUID().toString() }
        ids.forEachIndexed { index, session ->
            store.open(session, 0, 0).close()
            File(vehicle(), "$session.jsonl").setLastModified(1_000L + index)
        }
        File(vehicle(), "private-notes.jsonl").writeText("private text")
        val result = RecordingTelemetryDiagnostics.inspect(gps(), vehicle())
        val sessions = result.getValue("sessions").jsonArray.map { it.jsonObject.getValue("sessionId").jsonPrimitive.content }
        assertEquals(ids.takeLast(3).reversed(), sessions)
        assertFalse(result.toString().contains("private-notes"))
    }
    @Test fun recordingStartedDuringInspectionDiscardsPartialSummary() {
        VehicleTelemetryStore(vehicle()).open(id, 0, 0).close()
        var calls = 0
        val result = RecordingTelemetryDiagnostics.inspect(gps(), vehicle()) { ++calls >= 4 }
        assertEquals("SKIPPED_RECORDING_ACTIVE", result.getValue("status").jsonPrimitive.content)
        assertFalse(result.containsKey("sessions"))
    }
    @Test fun oversizeHistoryIsCountedButNeverLoaded() {
        vehicle().mkdirs()
        RandomAccessFile(File(vehicle(), "$id.jsonl"), "rw").use { it.setLength(VehicleTelemetryStore.MAX_FILE_BYTES + 1) }
        val result = RecordingTelemetryDiagnostics.inspect(gps(), vehicle())
        assertEquals("NO_HISTORY", result.getValue("status").jsonPrimitive.content)
        assertEquals(1, result.getValue("oversizeFilesSkipped").jsonPrimitive.int)
        assertTrue(result.getValue("sessions").jsonArray.isEmpty())
    }
}
