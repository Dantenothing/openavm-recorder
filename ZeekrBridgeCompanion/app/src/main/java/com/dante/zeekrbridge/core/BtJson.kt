package com.dante.zeekrbridge.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** JSON and bearer-token helpers for the Bluetooth control channel. */
object BtJson {
    /** Builds a reply that always echoes the original requestId when present. */
    fun reply(type: String, requestId: String?, fields: Map<String, String>): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            if (!requestId.isNullOrBlank()) put("requestId", JsonPrimitive(requestId))
            fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }

    /** Wraps a raw token with `Bearer ` unless it already carries the prefix. */
    fun normalizeBearer(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.startsWith("Bearer ", ignoreCase = true)) value else "Bearer $value"
    }
}
