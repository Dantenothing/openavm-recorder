package com.dante.zeekrcheck.core

enum class WidgetPinPhase { WAITING, ADDED, NOT_CONFIRMED, UNSUPPORTED, FAILED }

/** A launcher accepting a request does not mean it has actually bound a new widget. */
data class WidgetPinAttempt(
    val beforeIds: Set<Int>?,
    val startedAt: Long,
    val phase: WidgetPinPhase,
) {
    fun observe(currentIds: Set<Int>, now: Long): WidgetPinAttempt {
        if (beforeIds == null || phase == WidgetPinPhase.ADDED) return this
        if (currentIds.any { it !in beforeIds }) return copy(phase = WidgetPinPhase.ADDED)
        return if (phase == WidgetPinPhase.WAITING && now - startedAt >= CONFIRMATION_WAIT_MS)
            copy(phase = WidgetPinPhase.NOT_CONFIRMED) else this
    }

    companion object {
        const val CONFIRMATION_WAIT_MS = 6_000L
    }
}
