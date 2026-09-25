package com.dante.zeekrcapabilitylab.preflight.cloud

import com.dante.zeekrcapabilitylab.preflight.obj
import org.junit.Assert.*
import org.junit.Test

class DiagnosticPolicyTest {
    private val run="11111111-1111-4111-8111-111111111111"
    private fun bytes()=obj("schemaVersion" to 1,"exampleOnly" to false,"productionSwitchAllowed" to false,
        "format" to "OPENAVM_PREFLIGHT","runId" to run,"version" to "4.1.0-beta18","phase" to "COMPLETE_P1_INPUT","tests" to emptyList<Any>()).toString().toByteArray()
    @Test fun staleReportCannotRepresentNewRun() {
        assertThrows(IllegalStateException::class.java) { DiagnosticPolicy.identity(bytes(),"22222222-2222-4222-8222-222222222222") }
        assertEquals("4.1.0-beta18",DiagnosticPolicy.identity(bytes(),run).version)
    }
    @Test fun endpointRequiresHttpsOriginWithoutCredentialOrRedirectParameters() {
        assertEquals("https://example.com",DiagnosticPolicy.origin("https://example.com/"))
        for(url in listOf("http://example.com","https://key@example.com","https://example.com/p","https://example.com?a=b","https://example.com#token","https://example.com:443","https://localhost"))
            assertThrows(IllegalArgumentException::class.java) { DiagnosticPolicy.origin(url) }
    }
    @Test fun fullReceiptMustMatchBodyRunVersionAndSize() {
        val id=DiagnosticPolicy.identity(bytes())
        fun receipt(hash:String=id.hash,version:String=id.version,size:Int=id.bytes)=obj("schemaVersion" to 1,"format" to "OPENAVM_DIAGNOSTIC_RECEIPT",
            "runId" to id.run,"reportSha256" to hash,"reportVersion" to version,"bytes" to size,"receiptId" to id.hash,"receivedAtEpochMs" to 100)
        DiagnosticPolicy.verifyReceipt(receipt(),id)
        for(bad in listOf(receipt(hash="0".repeat(64)),receipt(version="4.1.0-beta20"),receipt(size=id.bytes-1)))
            assertThrows(IllegalArgumentException::class.java) { DiagnosticPolicy.verifyReceipt(bad,id) }
    }
    @Test fun oversizedReportAndCopiedPartRejected() {
        assertThrows(IllegalArgumentException::class.java) { DiagnosticPolicy.identity(ByteArray(DiagnosticPolicy.MAX_BYTES+1)) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticPolicy.identity(String(bytes()).replace("OPENAVM_PREFLIGHT","OPENAVM_PREFLIGHT_JSON_PART").toByteArray()) }
    }
    @Test fun permanentAuthAndQuotaFailuresDoNotRetryForever() {
        for(code in listOf(400,401,403,409,413,507)) assertFalse(DiagnosticPolicy.retryHttp(code))
        for(code in listOf(408,429,500,502,503,504)) assertTrue(DiagnosticPolicy.retryHttp(code))
        assertTrue(DiagnosticPolicy.retryAllowed(5,503))
        assertFalse(DiagnosticPolicy.retryAllowed(6,503))
        assertFalse(DiagnosticPolicy.retryAllowed(1,403))
    }
    @Test fun brokenAttemptJournalCannotFallBackToAnOldReport() {
        assertNull(DiagnosticPolicy.expectedRun(false,null))
        assertThrows(IllegalStateException::class.java) { DiagnosticPolicy.expectedRun(true,null) }
        assertThrows(IllegalStateException::class.java) { DiagnosticPolicy.expectedRun(true,obj("runId" to "broken")) }
        assertEquals(run,DiagnosticPolicy.expectedRun(true,obj("runId" to run)))
    }
    @Test fun privateFieldsAreRejectedBeforeTransmissionEvenWhenNested() {
        for(key in listOf("vin","PASSWORD","latitude","itemUri","accessToken")) {
            val raw=String(bytes()).dropLast(1)+",\"nested\":[{\"$key\":\"private\"}]}"
            assertThrows(IllegalArgumentException::class.java) { DiagnosticPolicy.identity(raw.toByteArray()) }
        }
    }
    @Test fun deeplyNestedReportsAreRejectedBeforeTransmission() {
        val raw=String(bytes()).dropLast(1)+",\"nested\":"+"[".repeat(50)+"0"+"]".repeat(50)+"}"
        assertThrows(IllegalArgumentException::class.java) { DiagnosticPolicy.identity(raw.toByteArray()) }
    }
}
