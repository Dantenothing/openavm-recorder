package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SupportUpdatePolicyTest {
    private val build="preflight-"+"a".repeat(20)
    private val target=obj("sha256" to "b".repeat(64),"versionCode" to 122,"buildId" to build,"bytes" to 17_000_000)
    private val pending=obj("phase" to "COMMITTED","commandId" to "11111111-1111-4111-8111-111111111111",
        "target" to target,"fromVersion" to 121)
    @Test fun exactNewBuildAndLiveConsentAreRequiredToResume() {
        assertTrue(SupportUpdatePolicy.canResume(pending,122,build,true,true))
        assertFalse(SupportUpdatePolicy.canResume(pending,122,build,false,true))
        assertFalse(SupportUpdatePolicy.canResume(pending,122,build,true,false))
        assertFalse(SupportUpdatePolicy.canResume(pending,121,build,true,true))
        assertFalse(SupportUpdatePolicy.canResume(pending,123,build,true,true))
        assertFalse(SupportUpdatePolicy.canResume(pending,122,"preflight-"+"c".repeat(20),true,true))
        for(phase in listOf("PREPARED","CANCELLED","FAILED",""))
            assertFalse(SupportUpdatePolicy.installed(JsonObject(pending+obj("phase" to phase)),122,build))
    }
    @Test fun configurationDoesNotAdmitServerUrlsScriptsTokensOrDowngrades() {
        SupportUpdatePolicy.target(target,121)
        for(extra in listOf("url","script","token","packageName","signer"))
            assertThrows(IllegalArgumentException::class.java) {SupportUpdatePolicy.payload(JsonObject(target+obj(extra to "anything")))}
        for(code in listOf(0,120,200))assertThrows(IllegalArgumentException::class.java) {
            SupportUpdatePolicy.payload(JsonObject(target+obj("versionCode" to code)))
        }
        assertThrows(IllegalArgumentException::class.java) {SupportUpdatePolicy.target(target,122)}
        assertThrows(IllegalArgumentException::class.java) {SupportUpdatePolicy.payload(JsonObject(target+obj("bytes" to 30_000_000)))}
        assertThrows(IllegalArgumentException::class.java) {SupportUpdatePolicy.payload(JsonObject(target+obj("bytes" to "17000000")))}
    }
    @Test fun missingOrBusyCameraEvidenceCannotAdmitInstallation() {
        val idle=obj("active" to false,"testActive" to false,"cameraBusy" to false,"pendingNativeOwners" to 0,"blocked" to false)
        assertTrue(SupportUpdatePolicy.idle(idle));assertFalse(SupportUpdatePolicy.idle(obj()))
        for(key in listOf("active","testActive","cameraBusy","blocked"))assertFalse(SupportUpdatePolicy.idle(JsonObject(idle+obj(key to true))))
        assertFalse(SupportUpdatePolicy.idle(JsonObject(idle+obj("pendingNativeOwners" to 1))))
    }
    @Test fun expiryAndBootIdentityRemainUnchangedAcrossPackageVersion() {
        val state=obj("armed" to true,"boot" to 12,"startedElapsed" to 1000,"deadlineElapsed" to 1000+LabPolicy.DURATION_MS)
        assertTrue(LabPolicy.active(state,2000,12))
        assertFalse(LabPolicy.active(state,2000,13))
        assertFalse(LabPolicy.active(state,1000+LabPolicy.DURATION_MS,12))
        assertFalse(LabPolicy.active(JsonObject(state+obj("deadlineElapsed" to 1000+LabPolicy.DURATION_MS+1)),2000,12))
    }
}
