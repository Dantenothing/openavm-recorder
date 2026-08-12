package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.SegmentNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SegmentNamingTest {

    private val profile = CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000)

    @Test
    fun partialNameCarriesSegmentProfileAndTimestamp() {
        val dir = Files.createTempDirectory("seg-naming").toFile()
        val partial = SegmentNaming.partialFile(dir, 3, profile, 1_700_000_000_000L)

        assertEquals("seg-0003-1700000000000-1280x5140-14M.mp4.partial", partial.name)
        assertTrue(SegmentNaming.isPartial(partial.name))
        assertFalse(SegmentNaming.isFinalMp4(partial.name))
    }

    @Test
    fun finalFileDropsOnlyThePartialSuffix() {
        val dir = Files.createTempDirectory("seg-naming").toFile()
        val partial = SegmentNaming.partialFile(dir, 1, profile, 123L)

        val finalFile = SegmentNaming.finalFileFor(partial)

        assertEquals("seg-0001-123-1280x5140-14M.mp4", finalFile.name)
        assertTrue(SegmentNaming.isFinalMp4(finalFile.name))
        assertFalse(SegmentNaming.isPartial(finalFile.name))
    }

    @Test
    fun sidecarSitsNextToTheMp4() {
        val dir = Files.createTempDirectory("seg-naming").toFile()
        val mp4 = File(dir, "seg-0001-123-1280x5140-14M.mp4")

        val sidecar = SegmentNaming.sidecarFileFor(mp4)

        assertEquals(File(dir, "seg-0001-123-1280x5140-14M.mp4.sidecar.json"), sidecar)
    }

    @Test
    fun nonSegmentFilesAreNotTreatedAsFinalSegments() {
        assertFalse(SegmentNaming.isFinalMp4("rec-123.mp4.partial"))
        assertFalse(SegmentNaming.isFinalMp4("readme.txt"))
        assertFalse(SegmentNaming.isFinalMp4("frame-1.jpg"))
        assertTrue(SegmentNaming.isFinalMp4("seg-0001-123-1280x5140-14M.mp4"))
    }
}
