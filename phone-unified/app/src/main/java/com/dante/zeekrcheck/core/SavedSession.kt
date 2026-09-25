package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.util.UUID

/** Never a data class: credentials must not appear in generated toString(), logs or reports. */
class SavedSession(val userToken: String, val accessToken: String, val deviceId: String,
    val protocolFingerprint: String) {
    init {
        require(validToken(userToken) && validToken(accessToken)) { "会话凭据格式无效" }
        require(runCatching { UUID.fromString(deviceId).toString() == deviceId }.getOrDefault(false)) { "会话设备格式无效" }
        require(protocolFingerprint.matches(Regex("[a-f0-9]{64}"))) { "会话配置格式无效" }
    }
    fun matches(config: ProtocolConfig) = protocolFingerprint == config.fingerprint()
    internal fun encode(): String = buildJsonObject {
        put("schema", 1); put("region", "AU/SEA")
        put("userOrigin", RequestPolicy.USER_BASE); put("vehicleOrigin", RequestPolicy.TSP_BASE)
        put("userToken", userToken); put("accessToken", accessToken)
        put("deviceId", deviceId); put("protocolFingerprint", protocolFingerprint)
    }.toString()
    override fun toString() = "SavedSession(redacted)"
    companion object {
        // Opaque Authorization values can include a scheme and space ("Bearer ...").
        // Preserve the server's value; reject blank, oversized and non-printable header content.
        fun validToken(value: String) = value.length in 1..16_384 && value.isNotBlank() && value.all { it.code in 32..126 }
        internal fun parse(text: String): SavedSession {
            try {
                require(text.length <= 65_536)
                val obj = Json.parseToJsonElement(text).jsonObject
                require(obj.keys == setOf("schema", "region", "userOrigin", "vehicleOrigin", "userToken",
                    "accessToken", "deviceId", "protocolFingerprint"))
                require(obj["schema"] == JsonPrimitive(1) && obj["region"] == JsonPrimitive("AU/SEA"))
                require(obj["userOrigin"] == JsonPrimitive(RequestPolicy.USER_BASE) && obj["vehicleOrigin"] == JsonPrimitive(RequestPolicy.TSP_BASE))
                fun field(key: String): String = obj.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
                return SavedSession(field("userToken"), field("accessToken"), field("deviceId"), field("protocolFingerprint"))
            } catch (_: Exception) { throw IllegalArgumentException("本机会话格式无效，请重新登录。") }
        }
    }
}
