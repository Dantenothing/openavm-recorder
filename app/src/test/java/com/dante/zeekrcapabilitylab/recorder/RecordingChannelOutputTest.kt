package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RecordingChannelOutputTest {
    @Test fun parallelFilesCannotOverwriteEachOtherAndLivePartialsStayOwnedUntilClose() {
        val directory = Files.createTempDirectory("channels").toFile()
        val profile = CameraFormatProfile(ProfileSize(640, 480), 2_000_000)
        val front = InternalRecordingOutputSink(directory) { "front_cabin-" }
        val rear = InternalRecordingOutputSink(directory) { "rear_cabin-" }
        val a = front.openSegment(1, profile, 1000)
        val b = rear.openSegment(1, profile, 1000)
        try {
            val af = requireNotNull(a.localWorkingFile); val bf = requireNotNull(b.localWorkingFile)
            assertNotEquals(af, bf)
            af.writeText("existing video")
            assertTrue(InternalRecordingOutputOwners.owns(af))
            assertTrue(InternalRecordingOutputOwners.owns(bf))
            assertTrue(runCatching { front.openSegment(1, profile, 1000) }.isFailure)
            assertEquals("existing video", af.readText())
            a.close()
            assertFalse(InternalRecordingOutputOwners.owns(af))
            assertTrue(InternalRecordingOutputOwners.owns(bf))
            assertTrue(SegmentNaming.isFinalMp4(SegmentNaming.finalFileFor(bf).name))
        } finally { a.close(); b.close(); directory.deleteRecursively() }
    }

    @Test fun channelPrefixCannotEscapeRecordingDirectory() {
        val directory = Files.createTempDirectory("channel-name").toFile()
        try {
            assertTrue(runCatching { InternalRecordingOutputSink(directory) { "../" }.openSegment(1,
                CameraFormatProfile(ProfileSize(640, 480), 2_000_000), 1000) }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
