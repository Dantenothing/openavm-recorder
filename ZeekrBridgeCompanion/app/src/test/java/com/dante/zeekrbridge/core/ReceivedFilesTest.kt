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
    fun relatedMetadataFindsBothSupportedSidecarNames() {
        val video = tmp.newFile("drive.mp4")
        val legacy = tmp.newFile("drive.mp4.sidecar.json")
        val transferred = tmp.newFile("drive.json")

        assertEquals(
            setOf(legacy.absolutePath, transferred.absolutePath),
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
}
