package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.json.*

/** Redacts existing diagnostics for the session report; these observations are not session-scoped. */
internal object RecentRuntimeEvidence {
    private val code = Regex("[A-Z][A-Z0-9_]{0,79}")
    fun code(value: String?): String? = value?.takeIf(code::matches)
    private fun number(value: JsonElement?): JsonElement =
        (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull

    fun cleanup(lifecycle: JsonObject): JsonObject = buildJsonObject {
        put("unsettledOwners", number(lifecycle["unsettledOwners"]))
        put("transactions", buildJsonArray {
            (lifecycle["transactions"] as? JsonObject)?.values?.toList()?.takeLast(3)?.forEach { transaction ->
                val steps = transaction as? JsonArray ?: return@forEach
                add(buildJsonObject {
                    put("omittedSteps", (steps.size - 4).coerceAtLeast(0))
                    put("steps", buildJsonArray {
                        steps.takeLast(4).forEach step@{ raw ->
                            val item = raw as? JsonObject ?: return@step
                            add(buildJsonObject {
                                put("at", number(item["at"])); put("elapsed", number(item["elapsed"]))
                                put("step", code((item["step"] as? JsonPrimitive)?.contentOrNull))
                            })
                        }
                    })
                })
            }
        })
        put("scope", "RECENT_TRANSACTIONS_MAY_PRECEDE_CURRENT_PROCESS")
    }

    fun processExits(details: JsonObject): JsonObject = buildJsonObject {
        put("systemExitQuery", ProcessExitReport.symbol((details["systemExitQuery"] as? JsonPrimitive)?.contentOrNull, 80))
        put("currentProcessStartedAtEpochMs", number(details["currentProcessStartedAtEpochMs"]))
        put("historicalExits", buildJsonArray {
            (details["historicalExits"] as? JsonArray)?.take(8)?.forEach { raw ->
                val row = raw as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("atEpochMs", number(row["atEpochMs"]))
                    put("reason", code((row["reason"] as? JsonPrimitive)?.contentOrNull))
                    put("status", number(row["status"]))
                })
            }
        })
        put("handoffEvents", buildJsonArray {
            (details["persistedEvents"] as? JsonArray)?.filter { raw ->
                (raw as? JsonObject)?.get("event")?.jsonPrimitive?.contentOrNull in setOf(
                    "RECORDER_MIRROR_HANDOFF_STOPPED", "RECORDER_MIRROR_HANDOFF_RECORDING_STOP_CONFIRMED",
                    "RECORDER_MIRROR_HANDOFF_RECORDING_STOP_ACKNOWLEDGED")
            }?.takeLast(6)?.forEach { raw ->
                val event = raw.jsonObject
                add(buildJsonObject {
                    put("at", number(event["at"])); put("elapsed", number(event["elapsed"]))
                    put("event", code(event["event"]?.jsonPrimitive?.contentOrNull))
                    val facts = event["facts"] as? JsonObject
                    put("reason", code((facts?.get("reason") as? JsonPrimitive)?.contentOrNull))
                    put("target", code((facts?.get("target") as? JsonPrimitive)?.contentOrNull))
                })
            }
        })
        put("homePreviewEvents", buildJsonArray {
            (details["persistedEvents"] as? JsonArray)?.filter { raw ->
                (raw as? JsonObject)?.get("event")?.jsonPrimitive?.contentOrNull in setOf(
                    "PRODUCT_HOME_PREVIEW_RESTORED", "PRODUCT_HOME_PREVIEW_RELEASE_TIMEOUT")
            }?.takeLast(6)?.forEach { raw ->
                val event = raw.jsonObject
                add(buildJsonObject {
                    put("at", number(event["at"])); put("elapsed", number(event["elapsed"]))
                    put("event", code(event["event"]?.jsonPrimitive?.contentOrNull))
                    val facts = event["facts"] as? JsonObject
                    put("reason", code((facts?.get("reason") as? JsonPrimitive)?.contentOrNull))
                    put("target", code((facts?.get("target") as? JsonPrimitive)?.contentOrNull))
                    put("waitedElapsedMs", number(facts?.get("waitedElapsedMs")))
                })
            }
        })
        put("causalAttribution", "NOT_DETERMINED")
    }
}
