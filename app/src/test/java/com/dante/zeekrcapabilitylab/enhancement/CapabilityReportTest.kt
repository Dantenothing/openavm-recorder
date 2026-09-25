package com.dante.zeekrcapabilitylab.enhancement

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CapabilityReportTest {
    @Test fun aDeclaredPairDoesNotImplyThreeCamerasOrMeasuredSupport() {
        val sets = listOf(setOf("2", "1"), setOf("2", "0"))
        assertEquals("DECLARED_NOT_TESTED", ConcurrentCapabilityPolicy.classify(listOf("2", "1"), 30, false, sets))
        assertEquals("NOT_DECLARED", ConcurrentCapabilityPolicy.classify(listOf("2", "1", "0"), 30, false, sets))
    }
    @Test fun emptyMissingAndFailedQueriesRemainDistinct() {
        assertEquals("NOT_DECLARED", ConcurrentCapabilityPolicy.classify(listOf("1", "2"), 30, false, emptyList()))
        assertEquals("API_UNAVAILABLE", ConcurrentCapabilityPolicy.classify(listOf("1", "2"), 29, false, emptyList()))
        assertEquals("QUERY_FAILED", ConcurrentCapabilityPolicy.classify(listOf("1", "2"), 30, true, emptyList()))
    }
    @Test fun duplicateRoleMappingsCannotBeCalledConcurrent() {
        for (ids in listOf(listOf("1", "1"), listOf("1"), listOf("", "2"))) {
            assertEquals("INVALID_SELECTION", ConcurrentCapabilityPolicy.classify(ids, 30, false, listOf(setOf("1", "2"))))
        }
    }
    @Test fun limitsUseUtf8BytesAndAlwaysProduceOneParseableObject() {
        val details = List(8) { buildJsonObject { put("metadata", "界".repeat(6000)) } }
        val encoded = CapabilityReport.encode(buildJsonObject { put("sdk", 30) }, details, details)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= CapabilityReport.MAX_BYTES)
        val report = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("false", report["hardwareTested"].toString())
        assertTrue(report["omittedEncoders"]!!.jsonPrimitive.int > 0)
        assertTrue(report["omittedCameras"]!!.jsonPrimitive.int > 0)
    }
    @Test fun excessCameraAndCodecCountsAreDisclosedEvenIfAdapterAlreadyCappedThem() {
        val report = Json.parseToJsonElement(CapabilityReport.encode(buildJsonObject {}, List(8) { buildJsonObject {} }, emptyList(), 20, 4)).jsonObject
        assertEquals(12, report["omittedCameras"]!!.jsonPrimitive.int)
        assertEquals(4, report["omittedEncoders"]!!.jsonPrimitive.int)
    }
    @Test fun pathologicalHeaderReturnsExplicitFailureNotBrokenJson() {
        val report = Json.parseToJsonElement(CapabilityReport.encode(buildJsonObject { put("oversize", "界".repeat(30000)) }, emptyList(), emptyList())).jsonObject
        assertEquals("HEADER_EXCEEDS_LIMIT", report["error"]!!.jsonPrimitive.content)
        assertFalse(report["cameraOpened"]!!.jsonPrimitive.boolean)
    }
}
