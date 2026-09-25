package com.dante.zeekrcapabilitylab.preflight.cloud

import com.dante.zeekrcapabilitylab.preflight.obj
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DiagnosticOutboxTest {
    @get:Rule val folder=TemporaryFolder()
    private val files=object: DiagnosticAtomicFiles {
        var failReceipt=false
        override fun read(file:File):ByteArray = (File(file.path+".bak").takeIf { it.exists() } ?: file).readBytes()
        override fun write(file:File,bytes:ByteArray) {
            if(failReceipt && file.name=="receipt.json") throw IOException("injected commit failure")
            file.writeBytes(bytes)
        }
        override fun delete(file:File) { file.delete(); File(file.path+".bak").delete() }
    }
    private fun bytes(n:Int=0)=obj("schemaVersion" to 1,"exampleOnly" to false,"productionSwitchAllowed" to false,
        "format" to "OPENAVM_PREFLIGHT","runId" to "11111111-1111-4111-8111-111111111111",
        "version" to "4.1.0-beta20","tests" to emptyList<Any>(),"counter" to n).toString().toByteArray()
    private fun receipt(id:DiagnosticIdentity)=obj("schemaVersion" to 1,"format" to "OPENAVM_DIAGNOSTIC_RECEIPT",
        "runId" to id.run,"reportSha256" to id.hash,"reportVersion" to id.version,"bytes" to id.bytes,
        "receiptId" to id.hash,"receivedAtEpochMs" to 100L)
    @Test fun queueSurvivesRecreationAndDeduplicatesAnIdenticalReport() {
        val first=DiagnosticOutbox(folder.root,files);first.enqueue(bytes());first.enqueue(bytes())
        val reopened=DiagnosticOutbox(folder.root,files)
        assertEquals(1,reopened.pending().size);assertArrayEquals(bytes(),reopened.read(reopened.pending().single()))
    }
    @Test fun backupOnlyCommittedEntryIsStillFoundAfterRestart() {
        val box=DiagnosticOutbox(folder.root,files);box.enqueue(bytes());val entry=box.pending().single()
        assertTrue(entry.renameTo(File(entry.path+".bak")))
        val restarted=DiagnosticOutbox(folder.root,files)
        assertEquals(listOf(entry),restarted.pending());assertArrayEquals(bytes(),restarted.read(entry))
    }
    @Test fun failedOrMismatchedReceiptNeverDeletesThePendingEvidence() {
        val box=DiagnosticOutbox(folder.root,files);val id=box.enqueue(bytes());val entry=box.pending().single()
        assertThrows(IllegalArgumentException::class.java) { box.acknowledge(entry,id,receipt(DiagnosticPolicy.identity(bytes(1)))) }
        files.failReceipt=true
        assertThrows(IOException::class.java) { box.acknowledge(entry,id,receipt(id)) }
        assertEquals(1,box.pending().size)
    }
    @Test fun acknowledgedPrivateCopyIsRemovedButOtherFilesAreNeverDeleted() {
        val original=File(folder.root,"original-report.json").apply { writeBytes(bytes()) }
        val box=DiagnosticOutbox(folder.root,files);val id=box.enqueue(bytes());val entry=box.pending().single()
        box.attempting(entry);box.acknowledge(entry,id,receipt(id));box.enqueue(bytes())
        assertTrue(original.exists());assertTrue(box.pending().isEmpty());assertNotNull(box.receipt())
    }
    @Test fun attemptBudgetPersistsAndCorruptStateRequiresManualRetry() {
        val box=DiagnosticOutbox(folder.root,files);box.enqueue(bytes());val entry=box.pending().single()
        repeat(6) { box.attempting(entry) }
        val restarted=DiagnosticOutbox(folder.root,files)
        assertFalse(DiagnosticPolicy.retryAllowed(restarted.attempts(entry)))
        File(entry.path+".retry").writeText("broken")
        assertFalse(DiagnosticPolicy.retryAllowed(restarted.attempts(entry)))
        restarted.resetRetries();assertEquals(0,restarted.attempts(entry))
    }
    @Test fun fullQueueRetainsExistingReportsInsteadOfOverwritingThem() {
        val box=DiagnosticOutbox(folder.root,files)
        repeat(DiagnosticPolicy.MAX_PENDING) { box.enqueue(bytes(it)) }
        assertThrows(IllegalStateException::class.java) { box.enqueue(bytes(99)) }
        assertEquals(DiagnosticPolicy.MAX_PENDING,box.pending().size)
    }
}
