package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.SegmentNaming
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecorderLibraryTest {

    private val profile = CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000)

    private fun managedMp4(dir: File, name: String, protected: Boolean = false): File {
        val mp4 = File(dir, name)
        mp4.writeBytes(ByteArray(1024))
        SegmentSidecarIO.writeAtomic(
            mp4,
            SegmentSidecar(
                file = mp4.absolutePath,
                cameraId = "2",
                profile = profile,
                segmentSeconds = 60,
                segmentNumber = 1,
                processStartId = "1-1",
                result = SegmentSidecar.RESULT_SUCCESS,
                fileBytes = 1024,
                protected = protected,
            ),
        )
        return mp4
    }

    @Test
    fun onlyFinalizedMp4WithParseableSidecarAreListed() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val managed = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4")
        val noSidecar = File(dir, "seg-0002-2-1280x5140-14M.mp4").apply { writeBytes(ByteArray(10)) }
        val partial = SegmentNaming.partialFile(dir, 3, profile, 123L).apply { writeBytes(ByteArray(10)) }

        val listed = RecorderLibrary.listFinalized(dir)

        assertEquals(listOf(managed), listed)
        assertFalse(listed.contains(noSidecar))
        assertFalse(listed.contains(partial))
    }

    @Test
    fun selectManagedOnlyReturnsAlreadyListedFiles() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val a = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4")
        val b = File(dir, "seg-0002-2-1280x5140-14M.mp4").apply { writeBytes(ByteArray(10)) }

        val files = listOf(a, b)
        val selected = RecorderLibrary.selectManaged(files, setOf(a.name, b.name))

        assertEquals(listOf(a), selected)
    }

    @Test
    fun markProtectedAtomicallyUpdatesSidecar() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val managed = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4", protected = false)

        assertTrue(RecorderLibrary.pinForUpload(managed))
        val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(managed))
        assertEquals(true, sidecar?.uploadPinned)
        assertEquals(false, sidecar?.protected)
        // Idempotent second call.
        assertTrue(RecorderLibrary.pinForUpload(managed))
    }

    @Test
    fun markProtectedRejectsUnmanagedFiles() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val noSidecar = File(dir, "seg-0001-1-1280x5140-14M.mp4").apply { writeBytes(ByteArray(10)) }

        assertFalse(RecorderLibrary.pinForUpload(noSidecar))
    }

    @Test
    fun releaseUploadPinKeepsBookmarkProtection() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val managed = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4", protected = true)
        assertTrue(RecorderLibrary.pinForUpload(managed))

        assertTrue(RecorderLibrary.releaseUploadPin(managed))
        val sidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(managed))

        assertEquals(false, sidecar?.uploadPinned)
        assertEquals(true, sidecar?.protected)
        val protection = RecorderLibrary.protectionOf(managed)
        assertEquals(true, protection?.bookmarked)
        assertEquals(false, protection?.uploadPinned)
    }

    @Test
    fun deleteManagedRemovesFinalizedVideoAndItsSidecar() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val managed = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4")
        val sidecar = SegmentSidecarIO.sidecarFileFor(managed)

        val result = RecorderLibrary.deleteManaged(dir, managed)

        assertTrue(result.deleted)
        assertEquals(null, result.reason)
        assertFalse(managed.exists())
        assertFalse(sidecar.exists())
    }

    @Test
    fun deleteManagedRefusesBookmarksAndUploadPins() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val bookmarked = managedMp4(
            dir,
            "seg-0001-1-1280x5140-14M.mp4",
            protected = true,
        )
        val uploadPinned = managedMp4(dir, "seg-0002-2-1280x5140-14M.mp4")
        assertTrue(RecorderLibrary.pinForUpload(uploadPinned))

        val bookmarkedResult = RecorderLibrary.deleteManaged(dir, bookmarked)
        val pinnedResult = RecorderLibrary.deleteManaged(dir, uploadPinned)

        assertFalse(bookmarkedResult.deleted)
        assertEquals(RecorderLibrary.DELETE_BOOKMARKED, bookmarkedResult.reason)
        assertFalse(pinnedResult.deleted)
        assertEquals(RecorderLibrary.DELETE_UPLOAD_PINNED, pinnedResult.reason)
        assertTrue(bookmarked.exists())
        assertTrue(uploadPinned.exists())
    }

    @Test
    fun explicitUserDeleteRemovesProtectedRecordingButStillRefusesUploadPin() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val bookmarked = managedMp4(
            dir,
            "seg-0001-1-1280x5140-14M.mp4",
            protected = true,
        )
        val uploadPinned = managedMp4(dir, "seg-0002-2-1280x5140-14M.mp4")
        assertTrue(RecorderLibrary.pinForUpload(uploadPinned))

        val bookmarkedResult = RecorderLibrary.deleteManagedByUser(dir, bookmarked)
        val pinnedResult = RecorderLibrary.deleteManagedByUser(dir, uploadPinned)

        assertTrue(bookmarkedResult.deleted)
        assertFalse(bookmarked.exists())
        assertFalse(SegmentSidecarIO.sidecarFileFor(bookmarked).exists())
        assertFalse(pinnedResult.deleted)
        assertEquals(RecorderLibrary.DELETE_UPLOAD_PINNED, pinnedResult.reason)
        assertTrue(uploadPinned.exists())
    }

    @Test
    fun deleteManagedRefusesUnknownFiles() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val unknown = File(dir, "notes.mp4").apply { writeBytes(ByteArray(10)) }

        val result = RecorderLibrary.deleteManaged(dir, unknown)

        assertFalse(result.deleted)
        assertEquals(RecorderLibrary.DELETE_NOT_MANAGED, result.reason)
        assertTrue(unknown.exists())
    }

    @Test
    fun deleteManagedRefusesEvenValidSegmentOutsideExplicitRecorderDirectory() {
        val allowedDir = Files.createTempDirectory("rec-lib-allowed").toFile()
        val outsideDir = Files.createTempDirectory("rec-lib-outside").toFile()
        val outside = managedMp4(outsideDir, "seg-0001-1-1280x5140-14M.mp4")

        val result = RecorderLibrary.deleteManaged(allowedDir, outside)

        assertFalse(result.deleted)
        assertEquals(RecorderLibrary.DELETE_NOT_MANAGED, result.reason)
        assertTrue(outside.exists())
    }

    @Test
    fun provisionalFailedOrPathMismatchedSidecarsAreNotManaged() {
        val dir = Files.createTempDirectory("rec-lib").toFile()
        val provisional = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4")
        val provisionalSidecar = SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(provisional))!!
        SegmentSidecarIO.writeAtomic(provisional, provisionalSidecar.copy(provisional = true))

        val failed = File(dir, "seg-0002-2-1280x5140-14M.mp4").apply { writeBytes(ByteArray(10)) }
        SegmentSidecarIO.writeAtomic(
            failed,
            provisionalSidecar.copy(
                file = failed.absolutePath,
                segmentNumber = 2,
                result = SegmentSidecar.RESULT_FAILED,
            ),
        )

        val pathMismatch = File(dir, "seg-0003-3-1280x5140-14M.mp4").apply { writeBytes(ByteArray(10)) }
        SegmentSidecarIO.writeAtomic(
            pathMismatch,
            provisionalSidecar.copy(
                file = "some/other/path.mp4",
                segmentNumber = 3,
            ),
        )

        assertEquals(emptyList<File>(), RecorderLibrary.listFinalized(dir))
        assertFalse(RecorderLibrary.isManaged(provisional))
        assertFalse(RecorderLibrary.isManaged(failed))
        assertFalse(RecorderLibrary.isManaged(pathMismatch))
    }

    @Test
    fun clearAllRemovesManagedRecordingsButKeepsPinnedAndUnknownFiles() {
        val dir = Files.createTempDirectory("rec-lib-clear-all").toFile()
        val ordinary = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4")
        val protected = managedMp4(
            dir,
            "seg-0002-2-1280x5140-14M.mp4",
            protected = true,
        )
        val uploadPinned = managedMp4(dir, "seg-0003-3-1280x5140-14M.mp4")
        assertTrue(RecorderLibrary.pinForUpload(uploadPinned))
        val unknown = File(dir, "do-not-touch.txt").apply { writeText("owner unknown") }

        val result = RecorderLibrary.deleteAllManagedByUser(dir)

        assertEquals(2, result.deleted)
        assertEquals(1, result.blocked)
        assertFalse(ordinary.exists())
        assertFalse(protected.exists())
        assertTrue(uploadPinned.exists())
        assertTrue(unknown.exists())
    }

    @Test
    fun clearUnprotectedKeepsBookmarksPinsAndUnknownFiles() {
        val dir = Files.createTempDirectory("rec-lib-clear-unprotected").toFile()
        val ordinary = managedMp4(dir, "seg-0001-1-1280x5140-14M.mp4")
        val protected = managedMp4(
            dir,
            "seg-0002-2-1280x5140-14M.mp4",
            protected = true,
        )
        val uploadPinned = managedMp4(dir, "seg-0003-3-1280x5140-14M.mp4")
        assertTrue(RecorderLibrary.pinForUpload(uploadPinned))
        val unknown = File(dir, "do-not-touch.txt").apply { writeText("owner unknown") }

        val result = RecorderLibrary.deleteAllUnprotected(dir)

        assertEquals(1, result.deleted)
        assertEquals(2, result.blocked)
        assertFalse(ordinary.exists())
        assertTrue(protected.exists())
        assertTrue(uploadPinned.exists())
        assertTrue(unknown.exists())
    }
}
