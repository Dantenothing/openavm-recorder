package com.dante.zeekrcapabilitylab.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStorageProbeModelsTest {
    @Test
    fun mountParserKeepsPhysicalStorageAndExcludesEmulatedStorage() {
        val mounts = UsbMountTableParser.parse(
            """
            /dev/block/vold/public:8,1 /mnt/media_rw/1234-5678 exfat rw,dirsync 0 0
            /dev/fuse /storage/1234-5678 fuse rw,nosuid 0 0
            /dev/fuse /storage/emulated fuse rw,nosuid 0 0
            tmpfs /storage/self tmpfs rw 0 0
            """.trimIndent(),
        )

        assertEquals(listOf("/mnt/media_rw/1234-5678", "/storage/1234-5678"), mounts.map { it.mountPoint })
        assertEquals(listOf("exfat", "fuse"), mounts.map { it.fileSystem })
    }

    @Test
    fun mountParserDecodesEscapedSpaces() {
        val mounts = UsbMountTableParser.parse(
            "/dev/block/test /storage/MY\\040USB exfat rw 0 0",
        )

        assertEquals("/storage/MY USB", mounts.single().mountPoint)
    }

    @Test
    fun capabilitySummaryRequiresAnExplicitSuccessfulProbe() {
        val successful = UsbWriteProbeResult(
            createSucceeded = true,
            writeSucceeded = true,
            syncSucceeded = true,
            seekSucceeded = true,
            readBackSucceeded = true,
            renameSucceeded = true,
            cleanupSucceeded = true,
        )
        val report = report(
            appSpecific = listOf(appObservation(removable = true, writeProbe = null)),
            safTree = safObservation(writeProbe = successful),
        )

        val summary = UsbProbeCapabilitySummarizer.summarize(report)

        assertFalse(summary.first { it.backend == "APP_SPECIFIC_EXTERNAL" }.exportCandidate)
        assertTrue(summary.first { it.backend == "SAF_TREE" }.exportCandidate)
        assertTrue(summary.first { it.backend == "SAF_TREE" }.fileDescriptorCandidate)
    }

    @Test
    fun rawMountEvidenceNeverClaimsWriteCapability() {
        val report = report(
            mounts = listOf(MountObservation("/dev/block/test", "/storage/ABCD-1234", "exfat", listOf("rw"))),
            allFilesAccessGranted = true,
        )

        val raw = UsbProbeCapabilitySummarizer.summarize(report)
            .first { it.backend == "RAW_REMOVABLE_PATH" }

        assertTrue(raw.visible)
        assertFalse(raw.exportCandidate)
        assertFalse(raw.fileDescriptorCandidate)
    }

    private fun report(
        appSpecific: List<AppSpecificStorageObservation> = emptyList(),
        mounts: List<MountObservation> = emptyList(),
        safTree: SafTreeObservation? = null,
        allFilesAccessGranted: Boolean = false,
    ) = UsbProbeReport(
        generatedAtEpochMs = 1L,
        buildVersion = "test",
        buildGitSha = "test",
        allFilesAccessGranted = allFilesAccessGranted,
        appSpecific = appSpecific,
        storageVolumes = emptyList(),
        mounts = mounts,
        safTree = safTree,
    )

    private fun appObservation(
        removable: Boolean,
        writeProbe: UsbWriteProbeResult?,
    ) = AppSpecificStorageObservation(
        index = 0,
        path = "/storage/test/Android/data/app/files",
        exists = true,
        removable = removable,
        readable = true,
        writable = true,
        usableBytes = 1L,
        totalBytes = 2L,
        writeProbe = writeProbe,
    )

    private fun safObservation(writeProbe: UsbWriteProbeResult?) = SafTreeObservation(
        uri = "content://test/tree",
        documentId = "test:",
        displayName = "test",
        providerFlags = 0L,
        persistedRead = true,
        persistedWrite = true,
        supportsCreate = true,
        supportsRename = true,
        writeProbe = writeProbe,
    )
}
