package com.dante.zeekrcapabilitylab.preflight.continuous

internal object ProbeCompletion {
    fun status(sequencePassed: Boolean, realtimePassed: Boolean, retainedFiles: Int, pressurePassed: Boolean = true): String =
        if (!sequencePassed) "FAIL" else if (!realtimePassed || retainedFiles > 0 || !pressurePassed) "WARN" else "PASS"

    /** Never export provider exception messages, paths or raw URI text. */
    fun deletionReason(error: String?): String = when {
        error == null -> "DELETE_UNCONFIRMED"
        error.startsWith("OWNERSHIP_MISMATCH:") -> "OWNERSHIP_MISMATCH"
        error.startsWith("DELETE_QUERY_FAILED") -> "DELETE_QUERY_FAILED"
        error.startsWith("DELETE_FAILED") -> "DELETE_FAILED"
        else -> "DELETE_UNCONFIRMED"
    }
}
