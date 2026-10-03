package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReceivedFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun visibleFilesExcludePartials() {
        val root = tmp.newFolder("received")
        File(root, "a.mp4").writeBytes(ByteArray(1))
        File(root, "sub/b.mp4").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(1))
        File(root, "a.mp4.partial").writeBytes(ByteArray(1))
        File(root, "sub/b.mp4.partial").writeBytes(ByteArray(1))

        val visible = ReceivedFiles.visibleFiles(root)
        assertEquals(listOf("a.mp4", "sub/b.mp4"), visible.map { root.toPath().relativize(it.toPath()).toString().replace('\\', '/') })
        assertFalse(visible.any { it.name.endsWith(".partial") })
    }

    @Test
    fun relatedMetadataFindsAllSupportedSidecarNames() {
        val video = tmp.newFile("drive.mp4")
        val legacy = tmp.newFile("drive.mp4.sidecar.json")
        val transferred = tmp.newFile("drive.json")
        val usb = tmp.newFile("drive.sidecar.json")

        assertEquals(
            setOf(legacy.absolutePath, transferred.absolutePath, usb.absolutePath),
            ReceivedFiles.relatedMetadataFiles(video).map { it.absolutePath }.toSet(),
        )
    }

    @Test
    fun cleanupRemovesOnlyProtocolNamedPartials() {
        val root = tmp.newFolder("received")
        File(root, "a.mp4").writeBytes(ByteArray(1))
        File(root, "a.mp4.partial").writeBytes(ByteArray(1))
        File(root, "b.partial").writeBytes(ByteArray(1))
        // Base name ".." is not a valid protocol file name, so this survives cleanup.
        File(root, "..partial").writeBytes(ByteArray(1))

        ReceivedFiles.cleanupStalePartials(root)

        assertTrue(File(root, "a.mp4").exists())
        assertFalse(File(root, "a.mp4.partial").exists())
        assertFalse(File(root, "b.partial").exists())
        assertTrue(File(root, "..partial").exists())
    }

    @Test
    fun driveAttachmentIsHiddenButBelongsToItsVideoMetadata() {
        val root = tmp.newFolder("received-drive")
        val video = File(root, "drive.mp4").apply { writeBytes(byteArrayOf(1)) }
        val attachment = DriveDataFiles.forVideo(video)!!.apply { parentFile!!.mkdirs(); writeText("private attachment") }
        assertEquals(listOf(video), ReceivedFiles.visibleFiles(root))
        assertEquals(listOf(attachment), ReceivedFiles.relatedMetadataFiles(video))
    }

    @Test
    fun startupCleanupPreservesReservedAndCommittedAttachmentsOnly() {
        val root = tmp.newFolder("received-drive-orphans")
        val committed = File(root, "committed.mp4").apply { writeBytes(byteArrayOf(1)) }
        val reserved = File(root, "reserved.mp4")
        val orphan = File(root, "orphan.mp4")
        listOf(committed, reserved, orphan).forEach { video ->
            DriveDataFiles.forVideo(video)!!.apply { parentFile!!.mkdirs(); writeText("attachment") }
        }
        val partial = File(DriveDataFiles.forVideo(reserved)!!.absolutePath + ".partial").apply { writeText("partial") }
        DriveDataFiles.cleanupOrphans(root, setOf(reserved.name))
        assertTrue(DriveDataFiles.forVideo(committed)!!.isFile)
        assertTrue(DriveDataFiles.forVideo(reserved)!!.isFile)
        assertFalse(DriveDataFiles.forVideo(orphan)!!.exists())
        assertFalse(partial.exists())
    }
}
