package com.dante.zeekrbridge.core

import java.net.URLDecoder

/**
 * Pure parser for the car-displayed pairing QR payload:
 *   zeekr-sidecar://pair?v=1&car=<id>&host=<ipv4>&port=<port>&token=<one-time-token>&exp=<unix-time>
 */
object PairingPayload {
    const val SCHEME = "zeekr-sidecar"
    const val HOST = "pair"
    const val PROTOCOL_VERSION = 1
    const val MAX_TOKEN_TTL_MS = 5 * 60_000L * 4

    data class Parsed(
        val version: Int,
        val carId: String,
        val host: String,
        val port: Int,
        val token: String,
        val expiresAtEpochMs: Long,
    )

    fun parse(raw: String): Parsed? {
        if (raw.isBlank() || !raw.startsWith("$SCHEME://")) return null
        val afterScheme = raw.removePrefix("$SCHEME://")
        val hostEnd = afterScheme.indexOf('?')
        if (hostEnd <= 0) return null
        if (afterScheme.substring(0, hostEnd) != HOST) return null
        val params = mutableMapOf<String, String>()
        afterScheme.substring(hostEnd + 1).split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = try {
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                } catch (t: Throwable) {
                    return@forEach
                }
                params[key] = value
            }
        }
        val version = params["v"]?.toIntOrNull() ?: return null
        val carId = params["car"] ?: return null
        val host = params["host"] ?: return null
        val port = params["port"]?.toIntOrNull() ?: return null
        val token = params["token"] ?: return null
        val exp = params["exp"]?.toLongOrNull() ?: return null
        return Parsed(version, carId, host, port, token, exp)
    }

    fun isValid(p: Parsed, nowEpochMs: Long): Boolean {
        if (p.version != PROTOCOL_VERSION) return false
        if (p.carId.isBlank() || p.carId.length > 128) return false
        if (!isIpv4(p.host)) return false
        if (p.port !in 1..65535) return false
        if (p.token.isBlank() || p.token.length > 128) return false
        if (p.expiresAtEpochMs <= nowEpochMs) return false
        if (p.expiresAtEpochMs - nowEpochMs > MAX_TOKEN_TTL_MS) return false
        return true
    }

    fun isIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull()
            n != null && n in 0..255 && part == n.toString()
        }
    }
}
