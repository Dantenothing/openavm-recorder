package com.dante.zeekrcapabilitylab.service.recorder

/** Device retirement and the user's recording-session authority are separate decisions. */
object RecorderClosePolicy {
    fun mergeReason(current: String, incoming: String, terminal: Boolean): String =
        when {
            terminal && isInterruption(incoming) -> current
            terminal || isInterruption(incoming) -> incoming
            else -> current
        }

    fun needsDeviceFence(reason: String, otherProducerInFlight: Boolean): Boolean =
        otherProducerInFlight || isInterruption(reason)

    fun isInterruption(reason: String): Boolean = reason in setOf(
        "CAMERA_LOSS", "RECOVERY_ATTEMPT_CONTENTION", "RECOVERY_ATTEMPT_TERMINAL",
    )
}

/** Adapter budgets, not permission to release an unacknowledged native resource. */
object RecorderCloseTiming {
    const val DEFAULT_MEDIA_MS = 8_000L
    const val INITIAL_RELEASE_WATCHDOG_MS = 15_000L
    const val CONTINUOUS_RELEASE_CEILING_MS = 50_000L

    fun mediaReleaseMs(backend: String?): Long = when (backend) {
        // GL wait/join 5.5s + codec join 4s + writer 10s + final joins 2s.
        "SHARED_INPUT_CONTINUOUS_CODEC" -> 25_000L
        // stop: worker 4s + EOS 3s + writer 3s; release: two workers 8s + joins 1s.
        "CONTINUOUS_CODEC" -> 21_000L
        else -> DEFAULT_MEDIA_MS
    }

    /** Keep the existing worker/IO watchdog unless an active transaction owns finite phase timers. */
    fun releaseWatchdogDeferralMs(backend: String?, elapsedMs: Long, transactionSupervised: Boolean): Long {
        if (!transactionSupervised || elapsedMs < 0 || mediaReleaseMs(backend) == DEFAULT_MEDIA_MS) return 0
        return (CONTINUOUS_RELEASE_CEILING_MS - elapsedMs).coerceAtLeast(0)
    }
}
