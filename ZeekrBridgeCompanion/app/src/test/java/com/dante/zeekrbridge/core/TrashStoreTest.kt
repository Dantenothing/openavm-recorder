package com.dante.zeekrbridge.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun moveAndRestoreKeepsVideoAndSidecarTogether() {
        val received = temp.newFolder("received")
        val trash = temp.newFolder("trash")
        val video = File(received, "drive.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sidecar = File(received, "drive.json").apply { writeText("{\"recordingSessionId\":\"one\"}") }
        val repository = TrashRepository(received, trash) { 1234L }

        assertEquals(1, repository.moveToTrash(listOf(video)))
        assertFalse(video.exists())
        assertFalse(sidecar.exists())
        val entry = repository.list().single()
        assertEquals(1234L, entry.deletedAtEpochMs)
        assertEquals(listOf("drive.mp4"), entry.videoNames)

        assertEquals(1, repository.restore(entry.id))
        assertTrue(video.isFile)
        assertTrue(sidecar.isFile)
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun restoreCollisionRenamesVideoAndMatchingMetadata() {
        val received = temp.newFolder("received-collision")
        val trash = temp.newFolder("trash-collision")
        val original = File(received, "drive.mp4").apply { writeText("old") }
        File(received, "drive.mp4.sidecar.json").writeText("old-sidecar")
        val repository = TrashRepository(received, trash) { 1234L }
        assertEquals(1, repository.moveToTrash(listOf(original)))
        File(received, "drive.mp4").writeText("new")
        File(received, "drive.mp4.sidecar.json").writeText("new-sidecar")

        assertEquals(1, repository.restore(repository.list().single().id))

        assertEquals("new", File(received, "drive.mp4").readText())
        assertEquals("old", File(received, "drive (2).mp4").readText())
        assertEquals("old-sidecar", File(received, "drive (2).mp4.sidecar.json").readText())
    }

    @Test
    fun usbMetadataFollowsVideoThroughTrashAndCollisionRestore() {
        val received = temp.newFolder("received-usb")
        val trash = temp.newFolder("trash-usb")
        val original = File(received, "drive.mp4").apply { writeText("old") }
        val sidecar = File(received, "drive.sidecar.json").apply { writeText("old-layout") }
        val repository = TrashRepository(received, trash) { 1234L }
        assertEquals(1, repository.moveToTrash(listOf(original)))
        assertFalse(sidecar.exists())
        original.writeText("new")
        sidecar.writeText("new-layout")
        assertEquals(1, repository.restore(repository.list().single().id))
        assertEquals("new-layout", sidecar.readText())
        assertEquals("old-layout", File(received, "drive (2).sidecar.json").readText())
    }

    @Test
    fun permanentDeleteRemovesOneEntryOnly() {
        val received = temp.newFolder("received-delete")
        val trash = temp.newFolder("trash-delete")
        val first = File(received, "first.mp4").apply { writeText("first") }
        val second = File(received, "second.mp4").apply { writeText("second") }
        var now = 1000L
        val repository = TrashRepository(received, trash) { now }
        repository.moveToTrash(listOf(first))
        now = 2000L
        repository.moveToTrash(listOf(second))
        val entries = repository.list()

        assertTrue(repository.deletePermanently(entries.first().id))
        assertEquals(1, repository.list().size)
    }

    @Test
    fun hiddenDriveAttachmentFollowsTrashAndCollisionRename() {
        val received = temp.newFolder("received-drive")
        val trash = temp.newFolder("trash-drive")
        val original = File(received, "drive.mp4").apply { writeText("old video") }
        val attachment = DriveDataFiles.forVideo(original)!!.apply { parentFile!!.mkdirs(); writeText("old drive data") }
        File(received, "drive.json").writeText("old sidecar")
        val repository = TrashRepository(received, trash) { 1_234L }
        assertEquals(1, repository.moveToTrash(listOf(original)))
        assertFalse(attachment.exists())
        original.writeText("new video")
        attachment.writeText("new drive data")
        File(received, "drive.json").writeText("new sidecar")
        assertEquals(1, repository.restore(repository.list().single().id))
        val restored = File(received, "drive (2).mp4")
        assertEquals("old drive data", DriveDataFiles.forVideo(restored)!!.readText())
        assertEquals("new drive data", attachment.readText())
        assertEquals("old sidecar", File(received, "drive (2).json").readText())
        assertFalse(ReceivedFiles.visibleFiles(received).any { it.name.endsWith(DriveDataFiles.SUFFIX) })
    }
}
