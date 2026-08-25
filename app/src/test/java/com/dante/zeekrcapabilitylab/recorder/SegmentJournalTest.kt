package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.SegmentJournalIO
import com.dante.zeekrcapabilitylab.service.recorder.SegmentJournalStage
import com.dante.zeekrcapabilitylab.service.recorder.SegmentNaming
import com.dante.zeekrcapabilitylab.service.recorder.SegmentRecovery
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentJournalTest {
    private val profile = CameraFormatProfile(ProfileSize(1280, 1280), 8_000_000)

    @Test
    fun completedMediaAndSidecarRemoveJournalDuringRecovery() {
        val root = Files.createTempDirectory("journal-complete").toFile()
        val segments = File(root, "segments").apply { mkdirs() }
        val quarantine = File(root, "quarantine")
        val journals = File(root, "journals")
        val partial = SegmentNaming.partialFile(segments, 1, profile, 1000)
        val finalFile = SegmentNaming.finalFileFor(partial)
        val journal = SegmentJournalIO.begin(journals, partial, finalFile, 1000)
        partial.writeBytes(ByteArray(128))
        assertTrue(SegmentJournalIO.promote(partial, finalFile))
        SegmentSidecarIO.writeAtomic(
            finalFile,
            SegmentSidecar(
                file = finalFile.absolutePath,
                cameraId = "2",
                profile = profile,
                segmentSeconds = 60,
                segmentNumber = 1,
                processStartId = "test",
                result = SegmentSidecar.RESULT_SUCCESS,
                fileBytes = finalFile.length(),
            ),
        )
        SegmentJournalIO.update(journal, SegmentJournalStage.MEDIA_PROMOTED)

        val report = SegmentRecovery.reconcile(segments, quarantine, journals)

        assertEquals(1, report.completedJournals)
        assertTrue(finalFile.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun interruptedPartialIsQuarantinedNeverPromoted() {
        val root = Files.createTempDirectory("journal-partial").toFile()
        val segments = File(root, "segments").apply { mkdirs() }
        val quarantine = File(root, "quarantine")
        val journals = File(root, "journals")
        val partial = SegmentNaming.partialFile(segments, 1, profile, 1000)
        val finalFile = SegmentNaming.finalFileFor(partial)
        val journal = SegmentJournalIO.begin(journals, partial, finalFile, 1000)
        partial.writeBytes(ByteArray(128))
        SegmentJournalIO.update(journal, SegmentJournalStage.CAPTURING)

        val report = SegmentRecovery.reconcile(segments, quarantine, journals)

        assertEquals(1, report.quarantinedPartials)
        assertFalse(partial.exists())
        assertFalse(finalFile.exists())
        assertTrue(File(quarantine, partial.name).exists())
    }

    @Test
    fun promotedMediaWithoutSidecarIsQuarantined() {
        val root = Files.createTempDirectory("journal-orphan").toFile()
        val segments = File(root, "segments").apply { mkdirs() }
        val quarantine = File(root, "quarantine")
        val journals = File(root, "journals")
        val partial = SegmentNaming.partialFile(segments, 1, profile, 1000)
        val finalFile = SegmentNaming.finalFileFor(partial)
        val journal = SegmentJournalIO.begin(journals, partial, finalFile, 1000)
        partial.writeBytes(ByteArray(128))
        assertTrue(SegmentJournalIO.promote(partial, finalFile))
        SegmentJournalIO.update(journal, SegmentJournalStage.MEDIA_PROMOTED)

        val report = SegmentRecovery.reconcile(segments, quarantine, journals)

        assertEquals(1, report.quarantinedOrphans)
        assertFalse(finalFile.exists())
        assertTrue(File(quarantine, finalFile.name).exists())
    }
}
