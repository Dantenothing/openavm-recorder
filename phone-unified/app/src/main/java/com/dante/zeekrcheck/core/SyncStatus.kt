package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SyncReason(val label: String) {
    WIDGET("卡片刷新"), APP("打开应用"), PERIODIC("后台定期检查"), BLUETOOTH("车载蓝牙断开"), UNLOCK("手机解锁"), DIAGNOSTIC("只读自检"), HOME_CONFIRM("到家再次确认"), JOURNEY_CONFIRM("停车行程复查")
}

data class SyncStatus(
    val started: Long = 0, val completed: Long = 0, val success: Long = 0,
    val reason: String = "", val result: String = "尚未执行后台查询", val failures: Int = 0,
    val retryAfter: Long = 0, val rateLimited: Boolean = false,
) {
    fun started(now: Long, cause: SyncReason) = copy(started = now, reason = cause.label, result = "正在查询云端车况")
    fun finished(now: Long, outcome: ProbeOutcome, message: String) = copy(completed = now,
        success = if (outcome == ProbeOutcome.SUCCESS) now else success, result = message.take(180),
        failures = if (outcome == ProbeOutcome.SUCCESS) 0 else (failures + 1).coerceAtMost(6),
        retryAfter = when (outcome) {
            ProbeOutcome.SUCCESS -> 0
            ProbeOutcome.RATE_LIMITED -> now + 15 * 60_000L
            else -> now + (60_000L shl failures.coerceAtMost(4)).coerceAtMost(15 * 60_000L)
        }, rateLimited = outcome == ProbeOutcome.RATE_LIMITED)
    fun permits(now: Long, interactive: Boolean): Boolean {
        if (rateLimited && now < retryAfter) return false
        return interactive || (now >= retryAfter && (success == 0L || now - success !in 0..59_999L))
    }
    fun json() = buildJsonObject {
        put("started", started); put("completed", completed); put("success", success); put("reason", reason)
        put("result", result); put("failures", failures); put("retryAfter", retryAfter); put("rateLimited", rateLimited)
    }
    companion object {
        fun parse(e: JsonElement?) = SyncStatus(e.at("started").text()?.toLongOrNull() ?: 0,
            e.at("completed").text()?.toLongOrNull() ?: 0, e.at("success").text()?.toLongOrNull() ?: 0,
            e.at("reason").text()?.take(40) ?: "", e.at("result").text()?.take(180) ?: "尚未执行后台查询",
            e.at("failures").text()?.toIntOrNull()?.coerceIn(0, 6) ?: 0,
            e.at("retryAfter").text()?.toLongOrNull() ?: 0, e.at("rateLimited").text() == "true")
    }
}

object SyncPolicy {
    const val PERIOD_MS = 15 * 60_000L
    fun needsPeriodic(vehicleSelected: Boolean, hasWidget: Boolean, widgetEnabled: Boolean, guardEnabled: Boolean) =
        vehicleSelected && ((hasWidget && widgetEnabled) || guardEnabled)
    fun clockLabel(time: Long?, now: Instant): String {
        if (time == null || time <= 0) return "未查询"
        val zone = ZoneId.systemDefault()
        val observed = Instant.ofEpochMilli(time).atZone(zone)
        val pattern = if (observed.toLocalDate() == now.atZone(zone).toLocalDate()) "HH:mm" else "MM-dd HH:mm"
        return DateTimeFormatter.ofPattern(pattern).format(observed)
    }
}
