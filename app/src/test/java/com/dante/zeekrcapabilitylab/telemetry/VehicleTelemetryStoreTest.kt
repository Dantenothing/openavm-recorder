package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class VehicleTelemetryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "00000000-0000-4000-8000-000000000001"
    private val json = Json { encodeDefaults = true }
    private fun sample(at: Long) = VehicleTelemetrySample(at, 1_790_000_000_000 + at,
        mapOf("speed" to VehicleTelemetryValue(at, number = 0.0), "gear" to VehicleTelemetryValue(at, text = "P")))
    private fun journal() = File(temporary.root, "$id.jsonl")
    private fun header(start: Long = 0) = json.encodeToString(VehicleTelemetryHeader(sessionId = id, startedElapsedMs = start)) + "\n"

    @Test fun provisionalPedalRoundTripKeepsRawStatusAndGetterClock() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 0, 0).use { writer -> writer.append(sample(1_000).copy(values = mapOf(
            "throttle_depth" to VehicleTelemetryValue(990, number = 0.0, status = "PROVISIONAL_RAW"),
            "brake_depth" to VehicleTelemetryValue(995, number = 37.25, status = "PROVISIONAL_RAW")))) }
        val loaded = store.load(id)!!.samples.single()
        assertEquals(990L, loaded.values.getValue("throttle_depth").readElapsedMs)
        assertEquals(0.0, loaded.values.getValue("throttle_depth").number!!, 0.0)
        assertEquals("PROVISIONAL_RAW", loaded.values.getValue("brake_depth").status)
        assertNull(loaded.brakePressed)
    }

    @Test fun roundTripPreservesSourceTimeAndMissingControls() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 100, 0).use { it.append(sample(1_000)); it.append(sample(1_250).copy(values = mapOf("speed" to VehicleTelemetryValue(1_200, status = "SENTINEL")))) }
        val track = store.load(id)!!
        assertEquals(id, track.header.sessionId)
        assertEquals(2, track.samples.size)
        assertEquals(0.0, track.samples.first().speedKmh!!, 0.0)
        assertNull(track.samples.last().speedKmh)
        assertEquals(1_200L, track.samples.last().values.getValue("speed").readElapsedMs)
        assertTrue(track.samples.first().breakBefore)
    }
    @Test fun validJsonWithoutNewlineIsNotCommittedOrUsedForReopenClock() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 0, 0).use { it.append(sample(1_000)) }
        journal().appendText(json.encodeToString(sample(9_000)))
        assertEquals(listOf(1_000L), store.load(id)!!.samples.map { it.elapsedMs })
        store.open(id, 2_000, 0).use { it.append(sample(2_000)) }
        assertEquals(listOf(1_000L, 2_000L), store.load(id)!!.samples.map { it.elapsedMs })
        assertTrue(store.load(id)!!.samples.last().breakBefore)
    }
    @Test fun interruptedTailAndMalformedMiddleDoNotPreventValidLaterData() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 0, 0).use { it.append(sample(1_000)) }
        journal().appendText("broken\n" + json.encodeToString(sample(900)) + "\n" + json.encodeToString(sample(2_000)) + "\n{\"elapsedMs\":")
        val points = store.load(id)!!.samples
        assertEquals(listOf(1_000L, 2_000L), points.map { it.elapsedMs })
        assertTrue(points.last().breakBefore)
        store.open(id, 3_000, 0).use { it.append(sample(3_000)) }
        assertEquals(3, store.load(id)!!.samples.size)
    }
    @Test fun oversizeLineIsSkippedWithinAFixedMemoryBudget() {
        journal().writeText(header() + "x".repeat(VehicleTelemetryStore.MAX_LINE_BYTES * 2) + "\n" + json.encodeToString(sample(1_000)) + "\n")
        assertEquals(1, VehicleTelemetryStore(temporary.root).load(id)!!.samples.size)
    }
    @Test fun corruptRowInsideFreshnessWindowDoesNotCarryEarlierControlsAcrossTheGap() {
        journal().writeText(header() + json.encodeToString(sample(1_000)) + "\n" +
            "{\"elapsedMs\":1250,broken}\n" + json.encodeToString(sample(1_500)) + "\n")
        val samples = VehicleTelemetryStore(temporary.root).load(id)!!.samples
        assertEquals(2, samples.size)
        assertNotNull(VehicleTelemetryPlayback.sampleAt(samples, 1_000))
        assertNull(VehicleTelemetryPlayback.sampleAt(samples, 1_400))
        assertNotNull(VehicleTelemetryPlayback.sampleAt(samples, 1_500))
    }
    @Test fun hostileSchemaKeysPayloadAndClockCannotBecomeUsableData() {
        val store = VehicleTelemetryStore(temporary.root)
        assertNull(store.load("../outside"))
        assertThrows(IllegalArgumentException::class.java) { store.open("../outside", 0, 0) }
        journal().writeText(json.encodeToString(VehicleTelemetryHeader(2, id, 0)) + "\n")
        assertNull(store.load(id))
        journal().writeText(json.encodeToString(VehicleTelemetryHeader(1, UUID.randomUUID().toString(), 0)) + "\n")
        assertNull(store.load(id))
        journal().writeText(header(1_000) + json.encodeToString(sample(1_250).copy(values = mapOf("speed" to VehicleTelemetryValue(900, number = 10.0)))) + "\n")
        assertTrue(store.load(id)!!.samples.isEmpty())
        journal().writeText(header() + json.encodeToString(sample(1_250).copy(values = mapOf("url" to VehicleTelemetryValue(1_250, text = "https://example.test")))) + "\n")
        assertTrue(store.load(id)!!.samples.isEmpty())
    }
    @Test fun writerRejectsReorderedOldAndInvalidData() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 1_000, 0).use { writer ->
            assertThrows(IllegalArgumentException::class.java) { writer.append(sample(900)) }
            assertThrows(IllegalArgumentException::class.java) { writer.append(sample(1_250).copy(values = mapOf("speed" to VehicleTelemetryValue(900, number = 0.0)))) }
            writer.append(sample(1_000))
            assertThrows(IllegalArgumentException::class.java) { writer.append(sample(1_000)) }
            assertThrows(IllegalArgumentException::class.java) { writer.append(sample(1_250).copy(values = mapOf("speed" to VehicleTelemetryValue(1_250, number = Double.NaN)))) }
        }
        assertThrows(IllegalStateException::class.java) { store.open(id, 50, 0) }
    }
    @Test fun activeJournalOwnershipPreventsRacingClearAndWriters() {
        val store = VehicleTelemetryStore(temporary.root)
        val second = VehicleTelemetryStore(temporary.root)
        store.open(id, 0, 0).use {
            assertFalse(second.clear())
            assertThrows(IllegalStateException::class.java) { second.open(id, 0, 0) }
        }
        assertTrue(second.clear())
        assertNull(store.load(id))
    }
    @Test fun clearAndRetentionOnlyAffectOwnedJournals() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 0, 0).close()
        journal().setLastModified(1_000)
        val unrelated = File(temporary.root, "video.mp4").apply { writeText("video") }
        val notes = File(temporary.root, "notes.jsonl").apply { writeText("notes") }
        store.open(UUID.randomUUID().toString(), 0, VehicleTelemetryStore.MAX_AGE_MS + 2_000).close()
        assertFalse(journal().exists())
        assertTrue(store.clear())
        assertTrue(unrelated.exists()); assertTrue(notes.exists())
    }
    @Test fun hostileRecordCountCannotBypassQuotaThroughSkippedLinesOrReopen() {
        journal().bufferedWriter().use { writer ->
            writer.write(header())
            repeat(VehicleTelemetryStore.MAX_SAMPLES - 1) { writer.write("x\n") }
            writer.write(json.encodeToString(sample(1_000)) + "\n")
        }
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 2_000, 0).use { writer ->
            assertEquals("HISTORY_LIMIT", assertThrows(IllegalStateException::class.java) { writer.append(sample(2_000)) }.message)
        }
        journal().appendText("x\n")
        assertNull(store.load(id))
    }
    @Test fun oversizeFileNeverLoadsAndStorageQuotaReservesEachNewJournal() {
        val store = VehicleTelemetryStore(temporary.root)
        RandomAccessFile(journal(), "rw").use { it.setLength(VehicleTelemetryStore.MAX_FILE_BYTES + 1) }
        assertNull(store.load(id))
        assertThrows(IllegalStateException::class.java) { store.open(id, 0, 0) }
        journal().delete()
        repeat(9) { index ->
            val file = File(temporary.root, UUID.randomUUID().toString() + ".jsonl")
            RandomAccessFile(file, "rw").use { it.setLength(VehicleTelemetryStore.MAX_FILE_BYTES) }
            file.setLastModified(10_000L + index)
        }
        store.open(id, 0, 20_000).close()
        assertTrue(temporary.root.listFiles()!!.sumOf { it.length() } <= VehicleTelemetryStore.MAX_TOTAL_BYTES)
    }
    @Test fun gapAndReopenAlwaysBreakPlaybackContinuity() {
        val store = VehicleTelemetryStore(temporary.root)
        store.open(id, 0, 0).use { it.append(sample(1_000)); it.append(sample(1_250)); it.append(sample(5_000)) }
        store.open(id, 5_250, 0).use { it.append(sample(5_250)) }
        assertEquals(listOf(true, false, true, true), store.load(id)!!.samples.map { it.breakBefore })
    }
}
