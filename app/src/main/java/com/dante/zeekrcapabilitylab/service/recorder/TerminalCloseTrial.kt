package com.dante.zeekrcapabilitylab.service.recorder

import java.util.concurrent.atomic.AtomicReference

/** Private process-local comparison. Not serialized, persisted or accepted from remote parameters. */
enum class TerminalCloseOrder { DRAIN_FIRST, DEVICE_FIRST }

class TerminalCloseTrial internal constructor(val id: String, val order: TerminalCloseOrder) {
    private val firstFailure = AtomicReference<String?>()
    val failure: String? get() = firstFailure.get()
    internal fun fail(code: String) { firstFailure.compareAndSet(null, code) }

    internal fun needsDeviceFence(supportEnabled: Boolean, terminal: Boolean,
                                  reason: String, otherProducerInFlight: Boolean): Boolean =
        RecorderClosePolicy.needsDeviceFence(reason, otherProducerInFlight) ||
            supportEnabled && terminal && order == TerminalCloseOrder.DEVICE_FIRST
}

/** A small vocabulary; exception messages and file paths never enter the exported trace. */
internal object RecorderCloseTrace {
    fun facts(step: String, detail: String): Map<String, String>? {
        val edge = when {
            step == "STAGE" -> "STAGE_$detail"
            step == "PRODUCER_CONFIRMED" -> "PRODUCER_CONFIRMED"
            step == "WAITING_FOR_DEVICE_FENCE" -> "WAITING_FOR_DEVICE_FENCE"
            step == "DEVICE_CLOSED" -> "DEVICE_CLOSE_ACK_PROCESSED"
            step in setOf("NATIVE_ENTER", "NATIVE_RETURN") -> when (detail) {
                "stopRepeating" -> "STOP_REPEATING_$step"
                "abortCaptures" -> "ABORT_CAPTURES_$step"
                "deviceClose" -> if (step == "NATIVE_ENTER") "DEVICE_CLOSE_CALL_ENTER" else "DEVICE_CLOSE_CALL_RETURN"
                "sessionClose" -> "SESSION_CLOSE_$step"
                "stop" -> "RECORDER_STOP_$step"
                "reset" -> "RECORDER_RESET_$step"
                "release" -> "RECORDER_RELEASE_$step"
                "outputClose" -> "OUTPUT_CLOSE_$step"
                else -> return null
            }
            else -> return null
        }
        if (!edge.matches(Regex("[A-Z_]+"))) return null
        return mapOf("closeStep" to edge) + if (step in setOf("PRODUCER_CONFIRMED", "WAITING_FOR_DEVICE_FENCE") &&
            detail.matches(Regex("[A-Z_]+"))) mapOf("producerEvidence" to detail) else emptyMap()
    }
}
