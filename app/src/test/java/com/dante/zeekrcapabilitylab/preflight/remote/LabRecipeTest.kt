package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LabRecipeTest {
    private fun plan(vararg steps:JsonObject)=LabRecipe.parse(obj("recipeVersion" to 1,"steps" to steps.toList()))
    private val start=obj("op" to "START","experiment" to obj("profile" to "HEALTH_SHARED","bitrateBps" to 2_000_000))
    private val wait=obj("op" to "WAIT","event" to "RUN_FINISHED","timeoutMs" to 60_000)
    private class Host:LabRecipeEngine.Host {
        var starts=0;val cancels=mutableListOf<String>();val events=mutableListOf<String>()
        var state=LabRecipeEngine.View("",false,"CONFIRMED","PASS",emptySet(),obj())
        override fun start(experiment:LabExperiment):String { starts++;val id="run-$starts";state=state.copy(runId=id,active=true,cleanup="PENDING");return id }
        override fun view()=state
        override fun cancel(runId:String){cancels+=runId}
        override fun event(type:String,data:JsonObject){events+=type}
    }
    @Test fun cancellationIsNotCompleteUntilTheSameRunConfirmsCleanup() {
        val host=Host();val e=LabRecipeEngine("cmd",plan(start,wait),0,host)
        e.tick(0,true);e.cancel(1);e.tick(2,true);assertFalse(e.terminal);assertEquals(listOf("run-1"),host.cancels)
        host.state=host.state.copy(runId="different-run",active=false,cleanup="CONFIRMED");e.tick(3,true);assertFalse(e.terminal)
        host.state=host.state.copy(runId="run-1",outcome="CANCELLED");e.tick(4,true)
        assertTrue(e.terminal);assertEquals("CANCELLED",e.result!!.string("outcome"))
    }
    @Test fun offlineFinishesCurrentRunButNeverStartsTheNextRun() {
        val host=Host();val e=LabRecipeEngine("cmd",plan(start,wait,start,wait),0,host)
        e.tick(0,true);host.state=host.state.copy(active=false,cleanup="CONFIRMED",outcome="PASS")
        e.tick(10,false);assertEquals(1,host.starts);assertFalse(e.terminal)
        e.tick(20,true);assertEquals(2,host.starts)
    }
    @Test fun failedOrUnconfirmedRunStopsTheRemainingPlan() {
        for(cleanup in listOf("CONFIRMED","UNKNOWN")) {
            val host=Host();val e=LabRecipeEngine("cmd",plan(start,wait,start,wait),0,host)
            e.tick(0,true);host.state=host.state.copy(active=false,cleanup=cleanup,outcome="FAIL");e.tick(10,true)
            assertTrue(e.terminal);assertEquals(1,host.starts);assertNotEquals("PASS",e.result!!.string("outcome"))
        }
    }
    @Test fun timeoutRequestsCleanupAndNeverReplaysStart() {
        val host=Host();val e=LabRecipeEngine("cmd",plan(start,wait),0,host)
        e.tick(0,true);e.tick(60_001,true);assertFalse(e.terminal);assertEquals(1,host.cancels.size)
        e.tick(100_002,true);assertTrue(e.terminal);assertEquals("UNKNOWN",e.result!!.string("cleanupState"));assertEquals(1,host.starts)
    }
    @Test fun recipesRejectUnknownInputsReferenceOverridesAndUnjoinedRuns() {
        assertThrows(IllegalArgumentException::class.java) {plan(start)}
        assertThrows(IllegalArgumentException::class.java) {plan(start,start,wait)}
        assertThrows(IllegalArgumentException::class.java) {LabExperiment.parse(obj("profile" to "HEALTH_SHARED","frameRate" to 15))}
        assertThrows(IllegalArgumentException::class.java) {LabExperiment.parse(obj("profile" to "REFERENCE_INPUT","bitrateBps" to 8_000_000))}
        assertThrows(IllegalArgumentException::class.java) {LabExperiment.parse(obj("profile" to "HEALTH_SHARED","bitrateBps" to "2000000"))}
        assertEquals(6,plan(obj("op" to "REPEAT","times" to 3,"steps" to listOf(start,wait))).steps.size)
    }
    @Test fun payloadIdentityIsOrderIndependentButCoversParameters() {
        assertEquals(payloadHash(obj("a" to 1,"b" to obj("x" to 2))),payloadHash(obj("b" to obj("x" to 2),"a" to 1)))
        assertNotEquals(payloadHash(start),payloadHash(obj("op" to "START","experiment" to obj("profile" to "HEALTH_SHARED"))))
    }
}
