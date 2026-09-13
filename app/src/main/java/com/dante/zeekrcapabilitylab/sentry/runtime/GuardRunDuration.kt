package com.dante.zeekrcapabilitylab.sentry.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class GuardRunDuration(val minutes: Int?) {
    MINUTES_30(30), HOURS_1(60), HOURS_2(120), HOURS_4(240), HOURS_8(480), HOURS_12(720), UNLIMITED(null);

    val durationMs: Long? get() = minutes?.toLong()?.times(60_000L)
    fun remainingMs(startElapsedMs: Long, nowElapsedMs: Long): Long? =
        durationMs?.let { (it - (nowElapsedMs - startElapsedMs).coerceAtLeast(0)).coerceAtLeast(0) }

    companion object {
        fun fromStored(value: String?): GuardRunDuration = entries.firstOrNull { it.name == value } ?: HOURS_12
    }
}
