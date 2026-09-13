package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test

class UsbCanaryCommitTest {
    private val intended = UsbCanaryRecord("11111111-1111-1111-1111-111111111111", "content://media/test/downloads", "app.test")
    private class Fake(private val intended: UsbCanaryRecord, private val failAt: String? = null) : UsbCanaryJournal, UsbCanaryBackend {
        var record: UsbCanaryRecord? = null
        val log = mutableListOf<String>()
        var owner = intended.ownerPackage
        var path = UsbCanaryRecord.RELATIVE_PATH
        var decodable = true
        val uri = "${intended.collectionUri}/7"
        private fun step(name: String) { log += name; if (failAt == name) error("INJECTED_FAILURE") }
        override fun read() = record
        override fun write(record: UsbCanaryRecord) { step("JOURNAL_${record.phase}"); this.record = record }
        override fun insertPending(record: UsbCanaryRecord): String { step("INSERT"); return uri }
        override fun inspect(uri: String): UsbCanaryMetadata { step("INSPECT"); return UsbCanaryMetadata(uri, intended.displayName, path, owner) }
        override fun openMux(uri: String): UsbCanaryMuxSession {
            step("OPEN")
            return object : UsbCanaryMuxSession {
                override fun writeTriggeredSamples() = step("WRITE")
                override fun stopAndRelease(): Long { step("STOP_RELEASE"); return 12 }
                override fun syncAndClose() = step("SYNC_CLOSE")
                override fun abortClose() = step("ABORT_CLOSE")
            }
        }
        override fun verify(uri: String): UsbCanaryVerification { step("VERIFY"); return UsbCanaryVerification(decodable, 50, 500, "hash") }
        override fun publish(uri: String) = step("PUBLISH")
        override fun delete(uri: String) = step("DELETE")
    }

    @Test fun publicationRequiresDurableOwnershipStopSyncAndVerificationInOrder() {
        val fake = Fake(intended)
        val result = UsbCanaryCommit(fake, fake).run(intended)
        assertTrue(result.passed)
        assertEquals(listOf("JOURNAL_INTENDED", "INSERT", "JOURNAL_BOUND", "INSPECT", "OPEN", "JOURNAL_MUXING", "WRITE",
            "STOP_RELEASE", "JOURNAL_MUX_STOPPED", "SYNC_CLOSE", "JOURNAL_SYNCED", "INSPECT", "VERIFY", "JOURNAL_VERIFIED",
            "INSPECT", "PUBLISH", "JOURNAL_PUBLISHED"), fake.log)
    }

    @Test fun everyPrepublicationFailureRemainsIncompleteAndNeverDeletes() {
        for (stage in listOf("JOURNAL_INTENDED", "INSERT", "JOURNAL_BOUND", "OPEN", "JOURNAL_MUXING", "WRITE", "STOP_RELEASE",
            "JOURNAL_MUX_STOPPED", "SYNC_CLOSE", "JOURNAL_SYNCED", "VERIFY", "JOURNAL_VERIFIED", "PUBLISH")) {
            val fake = Fake(intended, stage)
            val result = UsbCanaryCommit(fake, fake).run(intended)
            assertFalse(stage, result.passed)
            assertFalse(stage, "DELETE" in fake.log)
            if (stage != "PUBLISH") assertFalse(stage, "PUBLISH" in fake.log)
            if (stage == "JOURNAL_BOUND") {
                assertNull(fake.record!!.itemUri)
                assertFalse("OPEN" in fake.log)
                assertEquals("UNBOUND_INSERT_NO_SCAN_AUTHORITY", UsbCanaryCommit(fake, fake).recover().reason)
            }
        }
    }

    @Test fun factoryPathOrDifferentOwnerCanNeverBeMutatedOrDeleted() {
        for (factory in listOf(true, false)) {
            val fake = Fake(intended)
            if (factory) fake.path = "SentryMode/" else fake.owner = "factory.app"
            val engine = UsbCanaryCommit(fake, fake)
            assertFalse(engine.run(intended).passed)
            assertFalse("OPEN" in fake.log)
            engine.cleanup()
            assertFalse("DELETE" in fake.log)
        }
    }

    @Test fun interruptedMp4MustBeProbedAndOnlyVerifiedPartialIsRetained() {
        val fake = Fake(intended, "WRITE")
        val engine = UsbCanaryCommit(fake, fake)
        engine.run(intended)
        assertEquals("VERIFIED_PARTIAL_RETAINED", engine.recover().reason)
        fake.decodable = false
        assertEquals("UNPLAYABLE_PENDING_RETAINED", engine.recover().reason)
        assertFalse("DELETE" in fake.log)
        engine.cleanup()
        assertEquals("DELETE", fake.log[fake.log.lastIndex - 1])
        assertEquals(UsbCanaryPhase.CLEANED, fake.record!!.phase)
    }

    @Test fun bindFailureCleanupCannotUseMatchingFilenameAsAuthority() {
        val fake = Fake(intended, "JOURNAL_BOUND")
        val engine = UsbCanaryCommit(fake, fake)
        engine.run(intended)
        assertEquals("UNBOUND_INSERT_NO_SCAN_AUTHORITY", engine.cleanup().reason)
        assertFalse("DELETE" in fake.log)
    }
}
