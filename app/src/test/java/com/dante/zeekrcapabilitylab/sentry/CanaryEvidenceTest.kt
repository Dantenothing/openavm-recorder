package com.dante.zeekrcapabilitylab.sentry

import com.dante.zeekrcapabilitylab.sentry.canary.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanaryEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun id(n: Int) = "%08x-0000-0000-0000-%012x".format(n, n)
    private fun clip(n: Int = 1) = CanaryClipResult("${id(n)}.mp4", true, true, false, "VERIFIED", 30_000_000, 10_000_000, 100, "ab".repeat(32))
    private fun measured() = CanarySnapshot(runId = id(1), outputFrames = 601, actualFps = 15.0, actualBitrateBps = 4_000_000,
        largestSyncIntervalUs = 2_000_000, encodedCapacityBytes = 64L * 1024 * 1024, encodedHighWaterBytes = 40L * 1024 * 1024,
        clip = clip(), stopReason = "MANUAL_STOP", telemetry = CanaryTelemetry(45_000, true, 31.0, 200_000, 1))
    private fun ram(n: Int, snapshot: CanarySnapshot = measured(), done: Boolean = true) = CanaryEvidenceRecord(id(n), CanaryEvidenceKind.RAM,
        "test", 30, 1000, if (done) 2000 else null, ram = snapshot.copy(runId = id(n)))
    private fun current(snapshot: CanarySnapshot) = CombinedCanaryReport(build = "unit-test", sdk = 30, ramAndClip = snapshot, usb = UsbCanaryJobSnapshot())
    private fun statuses(snapshot: CanarySnapshot) = CanaryEvidenceEvaluator.ram(snapshot).checks.associate { it.code to it.status }
    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() } }
        return result
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun observedSoftwareChecksNeverAcceptTheMissingHardwareGates() {
        val result = statuses(measured().copy(clockMethod = "CLAIMED_CALIBRATED"))
        assertEquals(CanaryCheckStatus.PASS, result["ENCODED_BUDGET"])
        assertEquals(CanaryCheckStatus.PASS, result["RAM_HISTORY"])
        assertEquals(CanaryCheckStatus.PASS, result["SHUTDOWN_RELEASE"])
        assertEquals(CanaryCheckStatus.PASS, result["CLIP_1"])
        for (code in listOf("CLOCK_CALIBRATION", "FULL_PLAYBACK", "NORMAL_REGRESSION")) assertEquals(CanaryCheckStatus.PENDING, result[code])
        assertEquals("PENDING_VEHICLE_REVIEW", CanaryEvidenceEvaluator.ram(measured()).hardwareAcceptance)
    }

    @Test fun missingObservationsAndIncompleteClipsCannotPass() {
        assertEquals(CanaryCheckStatus.PENDING, statuses(CanarySnapshot())["ENCODED_BUDGET"])
        assertEquals(CanaryCheckStatus.PENDING, statuses(measured().copy(telemetry = null, historySeconds = 35.0))["RAM_HISTORY"])
        assertEquals(CanaryCheckStatus.PENDING, statuses(measured().copy(running = true))["SHUTDOWN_RELEASE"])
        assertEquals(CanaryCheckStatus.FAIL, statuses(measured().copy(encodedHighWaterBytes = 64L * 1024 * 1024 + 1))["ENCODED_BUDGET"])
        assertEquals(CanaryCheckStatus.FAIL, statuses(measured().copy(droppedGops = 1))["GOP_CONTINUITY"])
        assertEquals(CanaryCheckStatus.FAIL, statuses(measured().copy(clip = clip().copy(partial = true)))["CLIP_1"])
        assertEquals(CanaryCheckStatus.FAIL, statuses(measured().copy(clip = clip().copy(postRollAchievedUs = 9_999_999)))["CLIP_1"])
        assertEquals(CanaryCheckStatus.FAIL, statuses(measured().copy(actualFps = Double.NaN))["OUTPUT_MEASURED"])
    }

    @Test fun telemetryBoundsSamplesButRetainsEarlyHighWaterObservations() {
        val tracker = CanaryTelemetryAccumulator(3)
        tracker.observe(measured().copy(running = true, ramReady = true, historySeconds = 31.0, processPssKiB = 999, thermalStatus = 3), 0)
        repeat(100) { tracker.observe(CanarySnapshot(running = true, processPssKiB = 100), (it + 1) * 5000L) }
        tracker.observe(CanarySnapshot(processPssKiB = 9999), 100)
        val result = tracker.snapshot()
        assertEquals(3, result.samples.size)
        assertEquals(999, result.peakPssKiB)
        assertEquals(3, result.peakThermal)
        assertTrue(result.ramReadyObserved)
        assertEquals(31.0, result.maximumHistorySeconds, 0.0)
        assertEquals(500_000, result.elapsedMs)
    }

    @Test fun archiveKeepsCompletedEvidenceAgainstDelayedStartsAndBoundsHistory() {
        val started = ram(1, done = false)
        val complete = ram(1)
        var archive = CanaryEvidenceArchive().append(started).append(complete).append(started)
        assertEquals(listOf(complete), archive.records)
        repeat(40) { archive = archive.append(ram(it + 2)) }
        assertEquals(24, archive.records.size)
        assertEquals(17, archive.evictedRecords)
        assertEquals(18, archive.records.first().id.take(8).toInt(16))
        val serialized = CanaryEvidenceJson.format.encodeToString(archive)
        assertEquals(archive, CanaryEvidenceJson.format.decodeFromString<CanaryEvidenceArchive>(serialized))
        assertTrue(runCatching { CanaryEvidenceJson.format.decodeFromString<CanaryEvidenceArchive>(serialized.replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")) }.isFailure)
    }

    @Test fun cleanupAndRecoveryNeverOverwriteOrManufactureUsbSuccess() {
        val verification = UsbCanaryVerification(true, 100, 1000, "ab".repeat(32), 20)
        val success = UsbCanaryJobSnapshot(operation = "RUN", result = UsbCanaryResult(UsbCanaryPhase.PUBLISHED, true, "VERIFIED", 15, verification), jobId = id(1))
        val cleanup = UsbCanaryJobSnapshot(operation = "CLEANUP", result = UsbCanaryResult(UsbCanaryPhase.CLEANED, reason = "CLEANED"), jobId = id(2))
        fun record(job: UsbCanaryJobSnapshot) = CanaryEvidenceRecord(job.jobId, CanaryEvidenceKind.USB, "unit", 30, 1, 2, usb = job)
        val archive = CanaryEvidenceArchive().append(record(success)).append(record(cleanup))
        assertEquals(2, archive.records.size)
        assertEquals(CanaryCheckStatus.PASS, CanaryEvidenceEvaluator.usb(archive.records.first().usb!!).checks.first().status)
        assertEquals(CanaryCheckStatus.PENDING, CanaryEvidenceEvaluator.usb(cleanup).checks.first().status)
        assertEquals(CanaryCheckStatus.PENDING, CanaryEvidenceEvaluator.usb(success.copy(operation = "RECOVER")).checks.first().status)
        assertEquals(CanaryCheckStatus.FAIL, CanaryEvidenceEvaluator.usb(success.copy(result = success.result!!.copy(stopDurationMs = 5001))).checks.first().status)
    }

    @Test fun repeatedFailuresCannotHidePreviouslyCommittedClips() {
        var history = CanaryClipHistory(emptyList(), 0).append(clip(1)).append(clip(2))
        repeat(100) { history = history.append(CanaryClipResult(reason = "STORAGE_LIMIT")) }
        assertEquals(listOf(clip(1), clip(2)), history.retained.filter { it.fileName != null })
        assertEquals(4, history.retained.count { it.fileName == null })
        assertEquals(96, history.omitted)
    }

    @Test fun bundleContainsOnlyReferencedHashVerifiedClipsAndReportsOmissions() {
        val directory = temporary.newFolder("clips")
        val bytes = "synthetic committed test sample".toByteArray()
        val valid = clip(10).copy(byteCount = bytes.size.toLong(), sha256 = hash(bytes))
        File(directory, valid.fileName!!).writeBytes(bytes)
        val changed = clip(11).copy(byteCount = bytes.size.toLong(), sha256 = hash(bytes))
        File(directory, changed.fileName!!).writeBytes(ByteArray(bytes.size))
        val traversal = clip(12).copy(fileName = "../unrelated.mp4")
        File(directory.parentFile, "unrelated.mp4").writeText("NOT_AUTHORIZED_FOR_EXPORT")
        val snapshot = measured().copy(clip = valid, clips = listOf(valid, changed, traversal))
        val output = ByteArrayOutputStream()
        val result = CanaryEvidenceBundleWriter.write(output, directory, CanaryEvidenceArchive().append(ram(1, snapshot)), current(snapshot), 3000)
        assertEquals(1, result.includedClips)
        assertEquals(2, result.omittedClips)
        val entries = zipEntries(output.toByteArray())
        assertEquals(setOf("clips/${valid.fileName}", "report.json", "summary.txt"), entries.keys)
        assertArrayEquals(bytes, entries.getValue("clips/${valid.fileName}"))
        val bundle = CanaryEvidenceJson.format.decodeFromString<CanaryEvidenceBundle>(entries.getValue("report.json").toString(Charsets.UTF_8))
        assertEquals(setOf("INCLUDED", "HASH_MISMATCH", "INVALID_NAME"), bundle.clips.map { it.status }.toSet())
        assertFalse(entries.getValue("report.json").toString(Charsets.UTF_8).contains(directory.absolutePath))
        assertFalse(output.toString(Charsets.ISO_8859_1.name()).contains("NOT_AUTHORIZED_FOR_EXPORT"))
    }

    @Test fun videoByteLimitDoesNotDiscardTheRemainingReports() {
        val directory = temporary.newFolder("large-clips")
        val size = 33L * 1024 * 1024 // Four real-sized entries exceed the unchanged 128 MiB bundle budget.
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(64 * 1024)
        repeat((size / block.size).toInt()) { digest.update(block) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val clips = (1..4).map { n -> clip(n + 20).copy(byteCount = size, sha256 = hash).also {
            RandomAccessFile(File(directory, it.fileName!!), "rw").use { file -> file.setLength(size) }
        } }
        val snapshot = measured().copy(clips = clips, clip = clips.last())
        val output = ByteArrayOutputStream()
        val result = CanaryEvidenceBundleWriter.write(output, directory, CanaryEvidenceArchive(), current(snapshot), 1)
        assertEquals(3, result.includedClips)
        assertEquals(1, result.omittedClips)
        assertTrue(result.videoBytes <= CanaryEvidenceBundleWriter.MAX_VIDEO_BYTES)
        val entries = zipEntries(output.toByteArray())
        val bundle = CanaryEvidenceJson.format.decodeFromString<CanaryEvidenceBundle>(entries.getValue("report.json").toString(Charsets.UTF_8))
        assertEquals(1, bundle.clips.count { it.status == "BUNDLE_LIMIT" })
        assertTrue(entries.containsKey("summary.txt"))
    }
}
