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
