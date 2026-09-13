package com.dante.zeekrcapabilitylab.sentry

import com.dante.zeekrcapabilitylab.sentry.canary.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class CanaryTextReportTest {
    private fun id(n: Int) = "%08x-0000-0000-0000-%012x".format(n, n)
    private fun snapshot(n: Int = 1) = CanarySnapshot(runId = id(n), phase = "STOPPED", stopReason = "MANUAL_STOP",
        telemetry = CanaryTelemetry(elapsedMs = 600_000, peakPssKiB = 300_000, samples = (1..120).map {
            CanaryTelemetryPoint(it * 5000L, 200_000, 1, 31.0, 16_000_000, 15.0)
        }))
    private fun current(ram: CanarySnapshot = CanarySnapshot(), build: String = "test") = CombinedCanaryReport(
        build = build, sdk = 30, ramAndClip = ram, usb = UsbCanaryJobSnapshot())
    private fun ram(n: Int) = CanaryEvidenceRecord(id(n), CanaryEvidenceKind.RAM, "test", 30, 1000, 601000, ram = snapshot(n))
    private fun large() = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(records = (1..24).map { ram(it) }, evictedRecords = 7),
        current(snapshot(24)), 999)
    private fun parts(report: CanaryPreparedTextReport) = report.parts.map {
        assertTrue(it.toByteArray(Charsets.UTF_8).size <= CanaryTextReportWriter.MAX_COPY_BYTES)
        CanaryEvidenceJson.format.decodeFromString<CanaryTextReportPart>(it)
    }
    private fun sha(json: String) = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Test fun singleCopyContainsHistoryCurrentAndChecksWithoutRequiringVideo() {
        val run = UsbCanaryJobSnapshot(operation = "RUN", jobId = id(2),
            result = UsbCanaryResult(UsbCanaryPhase.PUBLISHED, true, "VERIFIED", 15,
                UsbCanaryVerification(true, 100, 1000, "ab".repeat(32), 20)))
        val cleanup = UsbCanaryJobSnapshot(operation = "CLEANUP", jobId = id(3), result = UsbCanaryResult(UsbCanaryPhase.CLEANED, reason = "CLEANED"))
        fun usb(job: UsbCanaryJobSnapshot) = CanaryEvidenceRecord(job.jobId, CanaryEvidenceKind.USB, "test", 30, 1, 2, usb = job)
        val archive = CanaryEvidenceArchive(records = listOf(ram(1), usb(run), usb(cleanup)))
        val current = current(snapshot().copy(phase = "STOPPING", running = true))
        val prepared = CanaryTextReportWriter.prepare(archive, current, 123)
        assertEquals(listOf(prepared.json), prepared.parts)
        val decoded = CanaryEvidenceJson.format.decodeFromString<CanaryTextReport>(prepared.parts.single())
        assertEquals(archive, decoded.archive)
        assertEquals(current, decoded.current)
        assertEquals(123, decoded.exportedAtEpochMs)
        assertFalse(decoded.videoIncluded)
        assertEquals(CanaryCheckStatus.PASS, decoded.assessments.getValue("USB:${id(2)}").checks.first().status)
        assertEquals(CanaryCheckStatus.PENDING, decoded.assessments.getValue("USB:${id(3)}").checks.first().status)
        assertEquals(CanaryCheckStatus.PENDING, decoded.assessments.getValue("CURRENT_RAM").checks.first { it.code == "SHUTDOWN_RELEASE" }.status)
        assertTrue(decoded.assessments.values.all { it.hardwareAcceptance == "PENDING_VEHICLE_REVIEW" })
    }

    @Test fun unavailableArchiveIsExplicitAndDoesNotPreventCopyingLiveState() {
        val live = current(CanarySnapshot(phase = "STOPPING", running = true))
        val prepared = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), live, 123, "EVIDENCE_READ_FAILED")
        val decoded = CanaryEvidenceJson.format.decodeFromString<CanaryTextReport>(prepared.json)
        assertEquals("EVIDENCE_READ_FAILED", decoded.archiveReadError)
        assertEquals(live, decoded.current)
        assertTrue(decoded.archive.records.isEmpty())
        assertEquals(setOf("CURRENT_RAM", "CURRENT_USB"), decoded.assessments.keys)
    }

    @Test fun fullArchiveSplitsIntoBoundedJsonWithAllTelemetryPreserved() {
        val prepared = large()
        assertTrue(prepared.parts.size > 1)
        val decoded = parts(prepared)
        assertEquals((1..decoded.size).toList(), decoded.map { it.partNumber })
        assertTrue(decoded.all { it.partCount == decoded.size && it.reportSha256 == sha(prepared.json) &&
            it.reportUtf8Bytes == prepared.json.toByteArray(Charsets.UTF_8).size })
        val restored = decoded.sortedBy { it.partNumber }.joinToString("") { it.jsonText }
        assertEquals(prepared.json, restored)
        val report = CanaryEvidenceJson.format.decodeFromString<CanaryTextReport>(restored)
        assertEquals((1..24).map { ram(it) }, report.archive.records)
        assertEquals(7, report.archive.evictedRecords)
        assertTrue(report.archive.records.all { it.ram!!.telemetry!!.samples.size == 120 })
    }

    @Test fun chineseEmojiAndEscapedCharactersRoundTripAcrossParts() {
        val text = "车机📷\"\\\n\t\u0001\u2028".repeat(16_000)
        val prepared = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), current(build = text), 123)
        assertTrue(prepared.parts.size > 1)
        val decoded = parts(prepared)
        assertTrue(decoded.none { it.jsonText.first().isLowSurrogate() || it.jsonText.last().isHighSurrogate() })
        val restored = decoded.joinToString("") { it.jsonText }
        assertEquals(prepared.json, restored)
        assertEquals(text, CanaryEvidenceJson.format.decodeFromString<CanaryTextReport>(restored).current.build)
    }

    @Test fun exactCopyLimitFitsAndOneMoreByteUsesParts() {
        val base = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), current(build = ""), 123)
        val padding = CanaryTextReportWriter.MAX_COPY_BYTES - base.json.toByteArray(Charsets.UTF_8).size
        val exact = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), current(build = "a".repeat(padding)), 123)
        assertEquals(CanaryTextReportWriter.MAX_COPY_BYTES, exact.json.toByteArray(Charsets.UTF_8).size)
        assertEquals(1, exact.parts.size)
        val larger = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), current(build = "a".repeat(padding + 1)), 123)
        assertTrue(larger.parts.size > 1)
        assertEquals(larger.json, parts(larger).joinToString("") { it.jsonText })
    }

    @Test fun reopeningTheSnapshotKeepsIdenticalPartNumbersHashesAndContents() {
        val prepared = large()
        val reopened = CanaryTextReportWriter.fromJson(prepared.json)
        assertEquals(prepared, reopened)
        val changed = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), current(build = "b".repeat(150_000)), 123)
        assertNotEquals(parts(prepared).first().reportSha256, parts(changed).first().reportSha256)
    }

    @Test fun corruptUnknownAndOversizeSavedReportsFailExplicitly() {
        val prepared = CanaryTextReportWriter.prepare(CanaryEvidenceArchive(), current(), 123)
        for (invalid in listOf("{broken", prepared.json.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 999"),
            prepared.json.replace("OPENAVM_SENTRY_TEXT_REPORT", "UNKNOWN"), "x".repeat(CanaryTextReportWriter.MAX_REPORT_BYTES + 1))) {
            assertTrue(runCatching { CanaryTextReportWriter.fromJson(invalid) }.isFailure)
        }
    }
}
