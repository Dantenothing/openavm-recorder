package com.dante.zeekrbridge.core

/** SHA-256 hex normalization/validation shared by create and complete paths. */
object ShaHex {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** Returns the lowercase 64-hex form, or null when the value is not a valid SHA-256. */
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        return if (SHA256.matches(value)) value else null
    }
}
