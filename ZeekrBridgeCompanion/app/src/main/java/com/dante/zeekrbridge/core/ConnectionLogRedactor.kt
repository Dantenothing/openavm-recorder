package com.dante.zeekrbridge.core

object ConnectionLogRedactor {
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretField = Regex("(?i)([\\\"]?(?:pairingId|pairingCode|code|token|exchangeToken|authorization)[\\\"]?\\s*[:=]\\s*)[\\\"]?[^\\s,}\\\"]+[\\\"]?")
    fun redact(line: String): String = secretField.replace(bearer.replace(line, "Bearer <redacted>")) {
        it.groupValues[1] + "<redacted>"
    }
}
