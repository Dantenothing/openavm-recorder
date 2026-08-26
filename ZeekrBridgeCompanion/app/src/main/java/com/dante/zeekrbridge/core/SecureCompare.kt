package com.dante.zeekrbridge.core

import java.security.MessageDigest

/** Constant-time string comparison for ownership checks. */
object SecureCompare {
    fun equals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
    }
}
