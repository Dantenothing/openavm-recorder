package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionExportBindingTest {
    @get:Rule val temp = TemporaryFolder()
    private val anchor = VideoClockAnchor(elapsedRealtimeNs = 10_000_000_000, source = "CODEC_OUTPUT_OBSERVED")
    private fun source(): IndexedMediaSegment {
        val file = temp.newFile("cabin.mp4").apply { writeText("synthetic bytes") }
        return IndexedMediaSegment("cabin", file.path, file.name, null, file.length(), 123_000, 124_000, 1_000,
            1, "session", null, null, false, IndexedSourceRole.CABIN, IndexedLayoutKind.SINGLE_V1, null,
            emptyList(), 128, 128, channelId = "FRONT_CABIN", startedElapsedMs = 10_000, stoppedElapsedMs = 11_000,
            sessionStartedElapsedMs = 10_000, videoClockAnchor = anchor)
    }
    private fun plan(item: IndexedMediaSegment): MediaExportPlan {
        val timeline = SessionPlaybackTimeline("session", listOf(SessionVideoSegment(item.id, "FRONT_CABIN", "session",
            10_000, 11_000, 1_000, anchor)))
        return MediaExportPlanner.buildSession(listOf(item), timeline, "FRONT_CABIN", MediaExportTarget.ORIGINAL, 100, 900)
    }

    @Test fun plainVideoGetsFrozenBindingAndRequiresActualMeasuredDuration() {
        val item = source()
        val clip = plan(item).clips.single()
        assertNull(clip.raster)
        assertNotNull(clip.sessionBinding)
        MediaExportPlanner.validateFrozenInput(clip, item, 1_000)
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.validateFrozenInput(clip, item) }
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.validateFrozenInput(clip, item, 999) }
    }

    @Test fun queuedSamePathCannotAcquireAnotherChannelSessionClockOrLayout() {
        val item = source()
        val clip = plan(item).clips.single()
        listOf(item.copy(channelId = "REAR_CABIN"), item.copy(sourceRole = IndexedSourceRole.IR),
            item.copy(recordingSessionId = "replacement"), item.copy(startedElapsedMs = 10_001),
            item.copy(stoppedElapsedMs = 11_001), item.copy(sessionStartedElapsedMs = 9_000),
            item.copy(videoClockAnchor = anchor.copy(elapsedRealtimeNs = 10_001_000_000)),
            item.copy(originalWidth = 256), item.copy(recordingMode = IndexedRecordingMode.TIME_LAPSE))
            .forEach { changed -> assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.validateFrozenInput(clip, changed, 1_000) } }
    }

    @Test fun sameSizeReplacementWithNewModificationTimeIsRejectedOnRetry() {
        val item = source()
        val clip = plan(item).clips.single()
        assertTrue(item.file.setLastModified(item.file.lastModified() + 10_000))
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.validateFrozenInput(clip, item, 1_000) }
    }

    @Test fun sourceChangedSinceTimelineLoadCannotEvenEnterQueue() {
        val item = source()
        assertThrows(IllegalArgumentException::class.java) { plan(item.copy(videoClockAnchor = anchor.copy(elapsedRealtimeNs = 10_001_000_000))) }
    }

    @Test fun metadataDurationHintCannotReplaceTheMeasuredContainerDuration() {
        val item = source().copy(durationMs = 99_999)
        val clip = plan(item).clips.single()
        assertEquals(1_000L, clip.sourceDurationMs)
        MediaExportPlanner.validateFrozenInput(clip, item, 1_000)
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.validateFrozenInput(clip, item, 99_999) }
    }

    @Test fun oldUnboundPlainPlansKeepTheirOptionalFieldCompatibility() {
        val item = source()
        val old = PlannedExportClip(item.filePath, 1_000, 0, 1_000, null)
        assertNull(old.sessionBinding)
        MediaExportPlanner.validateFrozenInput(old, item)
    }
}
