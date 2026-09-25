package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class LabPolicyTest {
    private val id="33333333-3333-4333-8333-333333333333"
    private val session="11111111-1111-4111-8111-111111111111"
    private val hash="a".repeat(64)
    private fun armed()=obj("armed" to true,"boot" to 5,"startedElapsed" to 1000,"deadlineElapsed" to 1000+LabPolicy.DURATION_MS)
    private fun command(kind:String="P1_INPUT",payload:JsonObject=obj())=obj("id" to id,"session" to session,"kind" to kind,
        "issued" to 10_000,"expires" to 190_000,"expected_version" to 78,"payload" to payload)
    @Test fun localConsentCannotSurviveRebootOrExpiry() {
        assertTrue(LabPolicy.active(armed(),2000,5))
        assertFalse(LabPolicy.active(armed(),2000,6));assertFalse(LabPolicy.active(armed(),2000,-1))
        assertFalse(LabPolicy.active(armed(),999,5));assertFalse(LabPolicy.active(armed(),1000+LabPolicy.DURATION_MS,5))
        assertFalse(LabPolicy.active(JsonObject(armed()+obj("armed" to false)),2000,5))
    }
    @Test fun forgedDeadlineCannotExtendSession() {
        assertFalse(LabPolicy.active(JsonObject(armed()+obj("deadlineElapsed" to 1001+LabPolicy.DURATION_MS)),2000,5))
    }
    @Test fun staleVersionSessionExpiryAndArbitraryCommandsAreRejected() {
        LabPolicy.validateCommand(command(),session,78,10_000)
        for(c in listOf(command("SHELL"),command(payload=obj("bitrate" to 1)),
            JsonObject(command()+obj("session" to id)),JsonObject(command()+obj("expected_version" to 77)),
            JsonObject(command()+obj("expires" to 10_000)),JsonObject(command()+obj("issued" to 11_000))))
            assertThrows(IllegalArgumentException::class.java) {LabPolicy.validateCommand(c,session,78,10_000)}
    }
    @Test fun updateMustNameOneHashAndHigherVersionOnly() {
        LabPolicy.validateCommand(command("INSTALL_UPDATE",obj("sha256" to hash,"versionCode" to 79)),session,78,10_000)
        for(p in listOf(obj("sha256" to hash,"versionCode" to 78),obj("sha256" to "bad","versionCode" to 79),
            obj("sha256" to hash,"versionCode" to 79,"url" to "https://example.com")))
            assertThrows(IllegalArgumentException::class.java) {LabPolicy.validateCommand(command("INSTALL_UPDATE",p),session,78,10_000)}
    }
    @Test fun durableAcceptedCommandsAreNeverReplayedAfterProcessDeath() {
        assertTrue(LabPolicy.canAccept(id,obj()))
        for(state in listOf("ACCEPTED","RUNNING","UPDATING","WAITING_USER","FAILED","SUCCEEDED"))
            assertFalse(LabPolicy.canAccept(id,obj(id to obj("state" to state))))
        assertEquals("INTERRUPTED",LabPolicy.recoveryState("ACCEPTED"));assertEquals("INTERRUPTED",LabPolicy.recoveryState("RUNNING"))
        assertEquals("UPDATING",LabPolicy.recoveryState("UPDATING"));assertEquals("WAITING_USER",LabPolicy.recoveryState("WAITING_USER"))
    }
    @Test fun terminalPhaseAloneDoesNotCertifyTestSuccess() {
        val good=obj("cleanup" to "CONFIRMED","reason" to null,"blocked" to false,"results" to listOf("build: PASS","public_capabilities: PASS"))
        assertTrue(LabPolicy.testPassed(good))
        for(p in listOf(obj("results" to listOf("p1: FAIL")),obj("results" to emptyList<String>()),obj("cleanup" to "UNCONFIRMED"),
            obj("reason" to "USER_CANCELLED"),obj("blocked" to true)))assertFalse(LabPolicy.testPassed(JsonObject(good+p)))
    }
    @Test fun candidateApkMustKeepPackageSignerVersionAndContentIdentity() {
        fun verify(pkg:String="app",signers:Set<String> =setOf("cert"),version:Long=79,actual:String=hash,bytes:Long=1024) =
            LabPolicy.verifyApk("app",pkg,setOf("cert"),signers,78,version,79,actual,hash,bytes)
        verify()
        assertThrows(IllegalArgumentException::class.java) {verify(pkg="other")}
        assertThrows(IllegalArgumentException::class.java) {verify(signers=setOf("other"))}
        assertThrows(IllegalArgumentException::class.java) {verify(signers=emptySet())}
        assertThrows(IllegalArgumentException::class.java) {verify(version=78)}
        assertThrows(IllegalArgumentException::class.java) {verify(actual="b".repeat(64))}
        assertThrows(IllegalArgumentException::class.java) {verify(bytes=LabPolicy.MAX_APK+1)}
    }
    @Test fun capabilityReportWithExplicitlyDeferredCameraCoverageCanPassItsOwnScope() {
        // Matches PreflightService CAPABILITIES_ONLY: deferred future coverage is retained in the report.
        val report=obj("cleanup" to "CONFIRMED","results" to listOf("build: PASS","public_capabilities: PASS",
            "egl_small: SKIP","usb_fd: SKIP","closed_file: SKIP","baseline: SKIP",
            "synthetic_encode_decode: NOT_IMPLEMENTED","lossless_repack: NOT_IMPLEMENTED",
            "candidate_camera_smoke: NOT_IMPLEMENTED","candidate_soak: NOT_IMPLEMENTED",
            "window_scripts: NOT_IMPLEMENTED","pressure: NOT_IMPLEMENTED"))
        assertTrue(LabPolicy.testPassed(report))
        // That same declared capability report cannot certify a missing or skipped P1 run.
        assertFalse(LabPolicy.testPassed(report,"P1_INPUT"))
        assertFalse(LabPolicy.testPassed(report,"P1_BASIC"))
        val input=obj("cleanup" to "CONFIRMED","results" to listOf("build: PASS","public_capabilities: PASS","egl_small: PASS",
            "usb_fd: PASS","closed_file: SKIP","baseline: SKIP","p1_input: PASS","oes_input: PASS",
            "shared_pool_pressure: PASS","encoded_load: PASS","candidate_camera_smoke: NOT_IMPLEMENTED"))
        assertTrue(LabPolicy.testPassed(input,"P1_INPUT"))
        val skippedRequired=obj("cleanup" to "CONFIRMED","results" to listOf("build: PASS","public_capabilities: PASS","egl_small: PASS",
            "usb_fd: PASS","p1_input: PASS","oes_input: SKIP","shared_pool_pressure: PASS","encoded_load: PASS"))
        assertFalse(LabPolicy.testPassed(skippedRequired,"P1_INPUT"))
    }
}
