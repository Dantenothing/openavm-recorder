package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecentRuntimeEvidenceTest {
    @Test fun cleanupKeepsOnlyLastThreeTransactionsAndFourStageTimesWithoutPrivateDetail() {
        val lifecycle = buildJsonObject {
            put("unsettledOwners", 2)
            put("transactions", buildJsonObject {
                repeat(8) { transaction -> put("private-owner-$transaction", buildJsonArray {
                    repeat(12) { step -> add(buildJsonObject {
                        put("at", 1000 + step); put("elapsed", step); put("step", "DEVICE_CLOSE_RETURNED")
                        put("detail", "/storage/secret.mp4 https://private/?token=secret")
                    }) }
                }) }
            })
        }
        val result = RecentRuntimeEvidence.cleanup(lifecycle)
        val transactions = result["transactions"]!!.jsonArray
        assertEquals(3, transactions.size)
        assertEquals(4, transactions.last().jsonObject["steps"]!!.jsonArray.size)
        assertEquals(8, transactions.last().jsonObject["omittedSteps"]!!.jsonPrimitive.int)
        assertEquals(2, result["unsettledOwners"]!!.jsonPrimitive.int)
        assertFalse(result.toString().contains("private")); assertFalse(result.toString().contains("secret"))
    }

    @Test fun summaryKeepsExitReasonsAndHandoffOutcomeButDropsNativeTraceTokenAndSession() {
        val names = listOf("RECORDER_MIRROR_HANDOFF_STOPPED", "RECORDER_MIRROR_HANDOFF_RECORDING_STOP_CONFIRMED",
            "RECORDER_MIRROR_HANDOFF_RECORDING_STOP_ACKNOWLEDGED")
        val events = names.mapIndexed { index, name -> ProbeEvent(index.toLong(), "app", 100L + index,
            20L + index, "SYSTEM", name, "INFO", payload = mapOf("reason" to "CAMERA_RELEASE_UNCONFIRMED",
                "target" to "PREVIEW", "token" to "secret", "stoppedSession" to "private-uuid")) }
        val details = buildJsonObject {
            put("systemExitQuery", "AVAILABLE"); put("currentProcessStartedAtEpochMs", 300)
            put("historicalExits", buildJsonArray { repeat(12) { add(buildJsonObject {
                put("reason", "NATIVE_CRASH"); put("atEpochMs", 100); put("status", 6)
                put("nativeTrace", "private-stack"); put("process", "private-process")
            }) } })
            put("persistedEvents", ProcessExitReport.events(events))
        }
        val result = RecentRuntimeEvidence.processExits(details)
        assertEquals(8, result["historicalExits"]!!.jsonArray.size)
        assertEquals(3, result["handoffEvents"]!!.jsonArray.size)
        assertTrue(result.toString().contains("CAMERA_RELEASE_UNCONFIRMED"))
        assertTrue(result.toString().contains("PREVIEW"))
        assertFalse(result.toString().contains("private")); assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("nativeTrace"))
        assertEquals("NOT_DETERMINED", result["causalAttribution"]!!.jsonPrimitive.content)
    }

    @Test fun unavailableExitProviderAndMissingCleanupStayUnknown() {
        val result = RecentRuntimeEvidence.processExits(buildJsonObject { put("systemExitQuery", "QUERY_FAILED_SecurityException") })
        assertEquals("QUERY_FAILED_SecurityException", result["systemExitQuery"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, RecentRuntimeEvidence.cleanup(buildJsonObject {})["unsettledOwners"])
        assertNull(RecentRuntimeEvidence.code("failed /storage/private.mp4"))
    }

    @Test fun homePreviewReleaseOutcomeUsesItsOwnBoundedEventsWithoutRecompositionNoise() {
        val events = listOf("PRODUCT_HOME_PREVIEW_RESTORED", "PRODUCT_HOME_PREVIEW_RELEASE_TIMEOUT",
            "HOME_PREVIEW_RECOMPOSE").mapIndexed { index, name -> ProbeEvent(index.toLong(), "app", 100L + index,
            20L + index, "SYSTEM", name, "INFO", payload = mapOf("reason" to "RELEASE_CONFIRMED",
                "target" to "PREVIEW", "waitedElapsedMs" to "9001", "token" to "private")) }
        val result = RecentRuntimeEvidence.processExits(buildJsonObject { put("persistedEvents", ProcessExitReport.events(events)) })
        val home = result["homePreviewEvents"]!!.jsonArray
        assertEquals(2, home.size)
        assertEquals(9001, home.first().jsonObject["waitedElapsedMs"]!!.jsonPrimitive.int)
        assertEquals(0, result["handoffEvents"]!!.jsonArray.size)
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("RECOMPOSE"))
    }
}
