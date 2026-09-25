package com.dante.zeekrcapabilitylab.preflight.continuous

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProbeLoadEvidenceTest {
    @Test fun beta18ObservedLoadCannotPassAsThe28MbpsTarget() {
        val load=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.TARGET,28_000_000,109676332,497L*1_000_000/30,17_000_000_000,true)
        assertEquals("WARN",load.getValue("status").jsonPrimitive.content)
        assertEquals("LOAD_ABOVE_TARGET_RANGE",load.getValue("reason").jsonPrimitive.content)
        assertTrue(load.getValue("actualPayloadBps").jsonPrimitive.double>52_900_000)
    }
    @Test fun completeHighLoadIsSeparateAndIncompleteNeverPasses() {
        val high=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.HIGH,28_000_000,132_500_000,20_000_000,21_000_000_000,true)
        assertEquals("PASS",high.getValue("status").jsonPrimitive.content)
        val partial=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.HIGH,28_000_000,109676332,497L*1_000_000/30,null,false)
        assertEquals("INCOMPLETE",partial.getValue("status").jsonPrimitive.content)
        assertEquals(JsonNull,partial["wallPayloadBps"])
    }
    @Test fun staticLowLoadAndSlowUsbCompletionCannotQualify() {
        val low=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.TARGET,28_000_000,3308562,20_000_000,20_000_000_000,true)
        assertEquals("WARN",low.getValue("status").jsonPrimitive.content)
        val slow=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.TARGET,28_000_000,70_000_000,20_000_000,30_000_000_000,true)
        assertEquals("WARN",slow.getValue("status").jsonPrimitive.content)
        val target=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.TARGET,28_000_000,70_000_000,20_000_000,21_000_000_000,true)
        assertEquals("PASS",target.getValue("status").jsonPrimitive.content)
    }
    @Test fun explicitCbrRequiresItsOwnPositiveDeclaration() {
        val requested=ContinuousProbeSpec.surroundRepack().encoding.copy(bitrateMode=ProbeBitrateMode.CBR)
        val unknown=ProbeCodecDeclaration("hw.avc",requested,true,true,true)
        assertFalse(unknown.accepts(requested))
        assertTrue(unknown.copy(bitrateModeSupported=true).accepts(requested))
        assertFalse(unknown.copy(bitrateModeSupported=false).accepts(requested))
        assertFalse(unknown.copy(bitrateModeSupported=true).accepts(requested.copy(bitrateMode=null)))
    }
}
