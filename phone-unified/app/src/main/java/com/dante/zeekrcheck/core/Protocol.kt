package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Never use a data class here: generated toString() would disclose key material. */
class ProtocolConfig private constructor(
    val accessKey: String, val hmacSecret: String, val passwordKey: PublicKey,
    val prodSecret: String, val vinKey: ByteArray, val vinIv: ByteArray,
) {
    companion object {
        fun parse(text: String): ProtocolConfig {
            require(text.length <= 65_536) { "配置文件超过 64 KB" }
            val obj = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
                ?: throw IllegalArgumentException("需要 JSON 对象格式的协议配置")
            fun required(name: String): String {
                val value = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(!value.isNullOrBlank() && value.length <= 8_192 && !value.startsWith("<")) { "缺少或无效字段：$name" }
                return value
            }
            val access = required("hmac_access_key")
            require(access.all { it.code in 33..126 }) { "hmac_access_key 格式无效" }
            val hmac = required("hmac_secret_key")
            val prod = required("prod_secret")
            val key = required("vin_key").toByteArray(UTF_8)
            val iv = required("vin_iv").toByteArray(UTF_8)
            require(key.size in setOf(16, 24, 32) && iv.size == 16) { "VIN key 应为 16/24/32 字节，IV 为 16 字节（按 UTF-8 文本读取）" }
            val rsaText = required("password_public_key")
            val rsa = try {
                val der = Base64.getDecoder().decode(rsaText.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").filterNot(Char::isWhitespace))
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            } catch (_: Exception) { throw IllegalArgumentException("password_public_key 需要 Base64 X.509 RSA 公钥") }
            return ProtocolConfig(access, hmac, rsa, prod, key, iv)
        }
    }
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(accessKey.toByteArray(UTF_8), hmacSecret.toByteArray(UTF_8), passwordKey.encoded,
            prodSecret.toByteArray(UTF_8), vinKey, vinIv).forEach { bytes ->
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    override fun toString() = "ProtocolConfig(redacted)"
}

/** Port of Fryyyyy/zeekr_ev_api at 4dc9e17. MIT; see UPSTREAM_LICENSE.txt. */
object Signatures {
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun hmac(data: String, key: String): String = b64(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(UTF_8), "HmacSHA256"))
        doFinal(data.toByteArray(UTF_8))
    })
    fun encryptPassword(password: String, key: PublicKey): String = b64(Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
        init(Cipher.ENCRYPT_MODE, key); doFinal(password.toByteArray(UTF_8))
    })
    fun encryptVin(vin: String, config: ProtocolConfig): String = b64(Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(config.vinKey, "AES"), IvParameterSpec(config.vinIv))
        doFinal(vin.toByteArray(UTF_8))
    })
    fun authHeaders(method: String, url: HttpUrl, config: ProtocolConfig, now: Instant, body: String? = null): Map<String, String> {
        val date = DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC).format(now)
        val path = "/" + url.encodedPath.split('/').filter(String::isNotEmpty).joinToString("/")
        val query = url.encodedQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .entries.sortedBy { it.key.lowercase(Locale.ROOT) }.joinToString("&") { "${it.key}=${it.value}" }
        val base = listOf(method, path, query, config.accessKey, date, "").joinToString("\n")
        return mapOf(
            "X-HMAC-ALGORITHM" to "hmac-sha256", "X-HMAC-SIGNATURE" to hmac(base, config.hmacSecret),
            "X-HMAC-ACCESS-KEY" to config.accessKey, "X-DATE" to date,
            // Audited official 1.6.6 th.f intercept(): digest the actual outgoing UTF-8 body.
            "X-HMAC-DIGEST" to hmac(body.orEmpty(), config.hmacSecret),
        )
    }
    private val signedHeaders = setOf("x-app-id", "content-type", "x-api-signature-nonce", "x-timestamp",
        "x-api-signature-version", "x-project-id", "authorization", "accept-language", "x-vin", "x-device-id", "x-platform")

    fun appSignature(method: String, url: HttpUrl, headers: Map<String, String>, body: String?, secret: String): String {
        val lower = headers.mapKeys { it.key.lowercase(Locale.ROOT) }
        val canonicalHeaders = lower.filter { (k, v) -> k in signedHeaders && (k !in setOf("x-vin", "authorization") || v.isNotEmpty()) }
            .toSortedMap().entries.joinToString("") { "${it.key}:${it.value}\n" }
        // Official 1.6.6 oj.h reads the encoded query; duplicate names use the last value.
        val query = url.encodedQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }.toSortedMap().entries.joinToString("&") { (key, encoded) ->
            val value = encoded.replace("*", "%2A").replace("%2F", "/").replace("%3F", "?")
            "$key=$value"
        }
        val hash = if (!body.isNullOrEmpty() && lower["content-type"].orEmpty().contains("application/json")) {
            b64(MessageDigest.getInstance("MD5").digest(canonicalJson(Json.parseToJsonElement(body)).toByteArray(UTF_8)))
        } else ""
        return hmac(buildString {
            append(canonicalHeaders)
            if (query.isNotEmpty()) append(query).append('\n')
            if (hash.isNotEmpty()) append(hash).append('\n')
            append(method).append('\n').append(url.encodedPath.trimEnd())
        }, secret)
    }
    internal fun canonicalJson(element: JsonElement): String = when (element) {
        // Gson retains object insertion order and Unicode, with HTML escaping disabled.
        is JsonObject -> element.entries.joinToString(",", "{", "}") { "${gsonJsonString(it.key)}:${canonicalJson(it.value)}" }
        is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonicalJson)
        is JsonPrimitive -> if (element.isString) gsonJsonString(element.content) else element.toString()
    }
    private fun gsonJsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch -> when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (ch.code < 32 || ch == '\u2028' || ch == '\u2029') append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
        } }
        append('"')
    }
}

internal fun JsonElement?.at(path: String): JsonElement? = path.split('.').fold(this) { value, key -> (value as? JsonObject)?.get(key) }
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
