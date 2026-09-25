package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.remote.LabExperiment
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProbeRateControlEvidenceTest {
    @Test fun oldReferenceStillRequiresCbrAndStressStillOmitsTheMode() {
        assertTrue(ProbeExperimentCandidates.specs(true,true,false,null).all {it.encoding.bitrateMode==ProbeBitrateMode.CBR})
        assertTrue(ProbeExperimentCandidates.specs(true,true,true,null).all {it.encoding.bitrateMode==null})
        val old=LabExperiment.parse(obj("profile" to "TARGET_SHARED"))
        assertTrue(ProbeExperimentCandidates.specs(true,true,false,old).all {it.encoding.bitrateMode==ProbeBitrateMode.CBR})
    }
    @Test fun explicitProfileNeverFallsBackAndKeepsFullRasterAndFrameRate() {
        val e=LabExperiment.parse(obj("profile" to "RATE_CONTROL_SHARED","bitrateMode" to "VBR","avcProfile" to "HIGH","bitrateBps" to 14_000_000))
        val specs=ProbeExperimentCandidates.specs(true,true,false,e)
        assertEquals(1,specs.size)
        val v=specs.single().encoding
        assertEquals(RepackRaster(3840,1728),v.raster)
        assertEquals(30,v.frameRate);assertEquals(14_000_000,v.bitrateBps)
        assertEquals(ProbeAvcProfile.HIGH,v.profile);assertEquals(0,v.maxBFrames)
        assertEquals(ProbeBitrateMode.VBR,v.bitrateMode)
        for(support in listOf<Boolean?>(false,null)) {
            val d=ProbeCodecDeclaration("hw.avc",v,true,true,true,bitrateModeSupported=support)
            assertFalse(d.accepts(v))
        }
        val declared=ProbeCodecDeclaration("hw.avc",v,true,true,true,bitrateModeSupported=true)
        assertTrue(declared.accepts(v))
        assertFalse(declared.accepts(v.copy(bitrateMode=ProbeBitrateMode.CBR)))
    }
    @Test fun absentZeroAndFailedRuntimeFieldsRemainDistinct() {
        val present=mapOf<String,Any>("bitrate-mode" to 0,"bitrate" to 14_000_000,"profile" to 1)
        val captured=ProbeFormatObservation.capture(present::containsKey) {key ->
            if(key=="profile")error("READ_FAILURE") else present[key]
        }
        val fields=captured.getValue("fields").jsonObject
        assertEquals("OMITTED",fields.getValue("max-bframes").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(0,fields.getValue("bitrate-mode").jsonObject.getValue("value").jsonPrimitive.int)
        assertEquals("READ_ERROR",fields.getValue("profile").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(JsonNull,fields.getValue("profile").jsonObject["value"])
    }
    @Test fun rawVendorFieldsAndBuffersAreNotReadOrSerialized() {
        val queried=mutableListOf<String>()
        val observed=ProbeFormatObservation.capture({true}) { key -> queried+=key;ByteArray(8) }
        assertFalse("csd-0" in queried);assertFalse(queried.any {it.startsWith("vendor.")})
        assertTrue(observed.getValue("fields").jsonObject.values.all {it.jsonObject.getValue("status").jsonPrimitive.content=="READ_ERROR"})
    }
    @Test fun measuredLoadIsStillRequiredEvenWhenRequestedAndReportedRatesMatch() {
        val load=ProbeLoadEvidence.measure(ProbeLoadEvidence.Group.TARGET,28_000_000,132_310_381,20_000_000,21_440_769_523,true)
        assertEquals("WARN",load.getValue("status").jsonPrimitive.content)
        assertEquals("LOAD_ABOVE_TARGET_RANGE",load.getValue("reason").jsonPrimitive.content)
    }
}
