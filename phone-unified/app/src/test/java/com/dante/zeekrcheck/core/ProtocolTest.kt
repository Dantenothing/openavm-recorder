package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

internal object Fixture {
    val vectors: JsonObject = Json.parseToJsonElement(javaClass.getResource("/reference-vectors.json")!!.readText()).jsonObject
    val official166: JsonObject = Json.parseToJsonElement(javaClass.getResource("/reference-166-vectors.json")!!.readText()).jsonObject
    fun config() = ProtocolConfig.parse(vectors.getValue("config").toString())
    fun transport(client: okhttp3.OkHttpClient) = ReadOnlyTransport(client, CloudRequestGate().apply { open() }.permit())
}

class ProtocolTest {
    @Test fun appSignaturesMatchAudited166RulesForReadBodyUnicodeAndQueryEdges() {
        Fixture.official166.getValue("appVectors").jsonArray.forEach { vector ->
            val headers = vector.at("headers")!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(vector.at("signature").text(), Signatures.appSignature(vector.at("method").text()!!,
                vector.at("url").text()!!.toHttpUrl(), headers, vector.at("body").text(), Fixture.config().prodSecret))
        }
    }
    @Test fun authDigestCoversExactOutgoingBodyPerOfficial166() {
        val actual = Signatures.authHeaders("POST", (RequestPolicy.USER_BASE + "auth/checkUserV2").toHttpUrl(), Fixture.config(), Instant.parse("2026-09-19T06:00:00Z"), Fixture.official166["authBody"].text())
        val expected = Fixture.official166.getValue("authHeaders").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(expected, actual)
        val empty = Signatures.authHeaders("POST", (RequestPolicy.USER_BASE + "auth/checkUserV2").toHttpUrl(), Fixture.config(), Instant.parse("2026-09-19T06:00:00Z"))
        assertNotEquals(empty["X-HMAC-DIGEST"], actual["X-HMAC-DIGEST"])
    }
    @Test fun vinEncryptionUsesUtf8TextRatherThanHexDecoding() {
        assertEquals(Fixture.vectors["encryptedVin"].text(), Signatures.encryptVin(Fixture.vectors["vin"].text()!!, Fixture.config()))
    }
    @Test fun configDoesNotLeakViaToStringOrValidationErrors() {
        val config = Fixture.config()
        assertFalse(config.toString().contains(config.hmacSecret))
        val obj = Fixture.vectors.getValue("config").jsonObject.toMutableMap()
        obj["vin_key"] = JsonPrimitive("private-value-not-valid-size")
        val error = assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse(JsonObject(obj).toString()) }
        assertFalse(error.message!!.contains("private-value"))
    }
    @Test fun missingKeysFailBeforeAnyNetworkIsPossible() {
        assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse("{}") }
        assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse("not-json") }
        assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse(" ".repeat(65_537)) }
    }
}
