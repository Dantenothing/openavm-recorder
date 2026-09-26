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

class TelemetryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "00000000-0000-4000-8000-000000000001"
    private fun fix(at: Long) = TelemetryFix(at, 1_790_000_000_000 + at, -34.9, 138.6, 5f, "gps", 0f)

    @Test fun roundTripKeepsNullableSpeedAndSessionIdentity() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 100, System.currentTimeMillis()).use {
            it.append(fix(1_000).copy(speedMps = null)); it.append(fix(2_000))
        }
        val track = store.load(id)!!
        assertEquals(id, track.header.sessionId)
        assertNull(track.points[0].speedMps)
        assertEquals(0f, track.points[1].speedMps)
        assertTrue(track.points.first().breakBefore)
    }
    @Test fun incompleteTailIsTrimmedBeforeAppendingAfterProcessDeath() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 0, 0).use { it.append(fix(1_000)) }
        File(temporary.root, "$id.jsonl").appendText("{\"elapsedMs\":1500,")
        store.open(id, 2_000, System.currentTimeMillis()).use { it.append(fix(2_000).copy(breakBefore = true)) }
        assertEquals(listOf(1_000L, 2_000L), store.load(id)!!.points.map { it.elapsedMs })
    }
    @Test fun completeButUncommittedTailDoesNotMoveAppendCursor() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 0, 0).use { it.append(fix(1_000)) }
        File(temporary.root, "$id.jsonl").appendText(Json.encodeToString(fix(9_000)))
        store.open(id, 2_000, System.currentTimeMillis()).use { it.append(fix(2_000)) }
        assertEquals(listOf(1_000L, 2_000L), store.load(id)!!.points.map { it.elapsedMs })
    }
    @Test fun corruptMiddleAndUnorderedLinesCreateAGap() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 0, 0).use { it.append(fix(1_000)) }
        File(temporary.root, "$id.jsonl").appendText("garbage\n" + Json.encodeToString(fix(900)) + "\n" + Json.encodeToString(fix(2_000)) + "\n")
        val points = store.load(id)!!.points
        assertEquals(2, points.size)
        assertTrue(points[1].breakBefore)
    }
    @Test fun pathTraversalUnknownSchemasAndWrongSessionFailClosed() {
        val store = TelemetryStore(temporary.root)
        assertNull(store.load("../outside"))
        assertThrows(IllegalArgumentException::class.java) { store.open("../outside", 0, 0) }
        File(temporary.root, "$id.jsonl").writeText(Json.encodeToString(TelemetryHeader(2, id, 0)) + "\n")
        assertNull(store.load(id))
        File(temporary.root, "$id.jsonl").writeText(Json.encodeToString(TelemetryHeader(1, UUID.randomUUID().toString(), 0)) + "\n")
        assertNull(store.load(id))
    }
    @Test fun rebootedClockCannotAppendToAnOldSession() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 10_000, 0).use { it.append(fix(11_000)) }
        assertThrows(IllegalStateException::class.java) { store.open(id, 100, 0) }
    }
    @Test fun cannotClearOrOpenATrackWhileItsWriterIsActive() {
        val store = TelemetryStore(temporary.root)
        val otherInstance = TelemetryStore(temporary.root)
        store.open(id, 0, 0).use {
            assertFalse(otherInstance.clear())
            assertThrows(IllegalStateException::class.java) { otherInstance.open(id, 0, 0) }
        }
        assertTrue(otherInstance.clear())
        assertNull(store.load(id))
    }
    @Test fun clearingNeverDeletesVideosOrUnrelatedFiles() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 0, 0).close()
        val video = File(temporary.root, "video.mp4").apply { writeText("video placeholder") }
        val unknown = File(temporary.root, "notes.jsonl").apply { writeText("unrelated") }
        assertTrue(store.clear())
        assertTrue(video.isFile); assertTrue(unknown.isFile)
    }
    @Test fun expiredHistoryIsPrunedWhenANewRecordingStarts() {
        val store = TelemetryStore(temporary.root)
        store.open(id, 0, 0).close()
        val old = File(temporary.root, "$id.jsonl")
        old.setLastModified(1_000)
        store.open(UUID.randomUUID().toString(), 0, TelemetryStore.MAX_AGE_MS + 2_000).close()
        assertFalse(old.exists())
    }
    @Test fun oversizeHistoryIsNotParsed() {
        val store = TelemetryStore(temporary.root)
        RandomAccessFile(File(temporary.root, "$id.jsonl"), "rw").use { it.setLength(TelemetryStore.MAX_FILE_BYTES + 1) }
        assertNull(store.load(id))
        assertThrows(IllegalStateException::class.java) { store.open(id, 0, 0) }
    }
    @Test fun quotaReservesRoomAndPrunesOnlyOwnedHistory() {
        val store = TelemetryStore(temporary.root)
        repeat(9) { index ->
            val file = File(temporary.root, UUID.randomUUID().toString() + ".jsonl")
            RandomAccessFile(file, "rw").use { it.setLength(TelemetryStore.MAX_FILE_BYTES) }
            file.setLastModified(10_000L + index)
        }
        store.open(id, 0, 20_000).close()
        assertTrue(temporary.root.listFiles()!!.sumOf { it.length() } <= TelemetryStore.MAX_TOTAL_BYTES)
    }

    @Test fun sampleLimitSurvivesReopeningAnExistingJournal() {
        val target = File(temporary.root, "$id.jsonl")
        target.bufferedWriter().use { out ->
            out.write(Json.encodeToString(TelemetryHeader(sessionId = id, startedElapsedMs = 0)) + "\n")
            repeat(TelemetryStore.MAX_POINTS) { out.write(Json.encodeToString(fix((it + 1) * 1_000L)) + "\n") }
        }
        val store = TelemetryStore(temporary.root)
        store.open(id, TelemetryStore.MAX_POINTS * 1_000L, 0).use { writer ->
            val error = assertThrows(IllegalStateException::class.java) { writer.append(fix((TelemetryStore.MAX_POINTS + 1) * 1_000L)) }
            assertEquals("HISTORY_LIMIT", error.message)
        }
        assertEquals(TelemetryStore.MAX_POINTS, store.load(id)!!.points.size)
    }

    @Test fun corruptLinesCannotPushNewSamplesBeyondTheReadersLimit() {
        File(temporary.root, "$id.jsonl").bufferedWriter().use { out ->
            out.write(Json.encodeToString(TelemetryHeader(sessionId = id, startedElapsedMs = 0)) + "\n")
            repeat(TelemetryStore.MAX_POINTS - 1) { out.write("corrupt\n") }
            out.write(Json.encodeToString(fix(1_000)) + "\n")
        }
        TelemetryStore(temporary.root).open(id, 2_000, 0).use { writer ->
            assertEquals("HISTORY_LIMIT", assertThrows(IllegalStateException::class.java) {
                writer.append(fix(2_000))
            }.message)
        }
    }
}
