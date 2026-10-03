package com.dante.zeekrcapabilitylab.enhancement.field

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FieldReportFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun backupOnlyReportIsDiscoveredForRecoveryAndRetention() {
        val backup = temporary.newFile("one.json.bak").apply { writeText("{}") }
        val files = FieldReportFiles.list(temporary.root)
        assertEquals(listOf(File(backup.path.removeSuffix(".bak"))), files)
    }
    @Test fun primaryAndBackupAreOneRunAndNonReportFilesAreIgnored() {
        temporary.newFile("one.json"); temporary.newFile("one.json.bak")
        temporary.newFile("one.json.new"); temporary.newFile("unrelated.txt")
        temporary.newFolder("folder.json")
        assertEquals(listOf(temporary.root.resolve("one.json")), FieldReportFiles.list(temporary.root))
    }
    @Test fun backupTimestampParticipatesInRetentionOrder() {
        temporary.newFile("old.json").setLastModified(1000)
        temporary.newFile("new.json").setLastModified(2000)
        temporary.newFile("old.json.bak").setLastModified(3000)
        assertEquals(listOf("old.json", "new.json"), FieldReportFiles.list(temporary.root).map { it.name })
    }
}
