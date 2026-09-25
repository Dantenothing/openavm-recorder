package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Local phone transport security, independent of the v1 upload/raster protocol. */
object PhoneSecurityProtocol {
    const val VERSION = 2
    const val TLS_PORT = 8767
    const val TLS_NAME = "openavm-phone.invalid"
    const val IDENTITY_PATH = "/api/security/identity"
    const val SESSION_PATH = "/api/session"
    const val MAX_IDENTITY_BYTES = 32 * 1024
    const val MAX_CERTIFICATE_BYTES = 8 * 1024
}

@Serializable
data class PhoneSecurityIdentity(
    val securityVersion: Int,
    val phoneDeviceId: String,
    val tlsPort: Int,
    val certificateDerBase64: String,
)

@Serializable
data class PhoneSecuritySession(
    val service: String,
    val version: Int,
    val securityVersion: Int,
    val phoneDeviceId: String,
    val carDeviceId: String,
)

/** Required securityVersion here: an old pair response must not grant v2 trust. */
@Serializable
data class SecurePhonePairResponse(
    val token: String,
    val phoneDeviceId: String,
    val deviceName: String,
    val securityVersion: Int,
) {
    override fun toString() = "SecurePhonePairResponse(securityVersion=$securityVersion, token=<redacted>)"
}

/** Reject duplicate top-level keys, including escaped spellings, before trusting a candidate. */
object PhoneIdentityJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(text: String): PhoneSecurityIdentity {
        require(text.toByteArray(Charsets.UTF_8).size <= PhoneSecurityProtocol.MAX_IDENTITY_BYTES)
        require(json.parseToJsonElement(text) is JsonObject)
        val keys = mutableSetOf<String>()
        var depth = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val start = index++
                    while (index < text.length) {
                        if (text[index] == '\\') index++
                        else if (text[index] == '"') break
                        index++
                    }
                    require(index < text.length)
                    var next = index + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (depth == 1 && next < text.length && text[next] == ':') {
                        val key = json.decodeFromString<String>(text.substring(start, index + 1))
                        require(keys.add(key)) { "Duplicate identity field" }
                    }
                }
            }
            index++
        }
        return json.decodeFromString(PhoneSecurityIdentity.serializer(), text)
    }
}
