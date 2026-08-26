package com.dante.zeekrbridge.core

/** Identity sanity rules applied before any pairing request is accepted. */
object PairingRules {
    /** Car identity must be a flat, bounded id (alphanumerics, `-`, `_`). */
    fun saneCarDeviceId(raw: String?): String? {
        val id = raw?.trim().orEmpty()
        if (id.isEmpty() || id.length > 128) return null
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return id
    }

    /** Display name must be non-empty, bounded and free of control characters. */
    fun saneDeviceName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > 64) return null
        if (name.any { it == '\u0000' || it.code < 0x20 }) return null
        return name
    }
}
