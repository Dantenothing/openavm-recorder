package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*

/** Correlated with a temperature task, never created by an ordinary STATUS timestamp. */
data class TemperatureReceipt(val vehicleKey: String, val source: Long, val temperature: Double) {
    fun recent(key: String, now: Long) = vehicleKey == key && now - source in 0..300_000
    fun json() = buildJsonObject { put("vehicleKey", vehicleKey); put("source", source); put("temperature", temperature) }
    companion object {
        fun from(task: TemperatureUpdate): TemperatureReceipt? = task.source?.let { source ->
            task.temperature?.takeIf { it.isFinite() && it in -60.0..100.0 }?.let { TemperatureReceipt(task.vehicleKey, source, it) }
        }
        fun parse(e: JsonElement?): TemperatureReceipt? = runCatching {
            TemperatureReceipt(e.at("vehicleKey").text()!!.also { require(it.length <= 80) },
                e.at("source").text()!!.toLong().also { require(it > 0) },
                e.at("temperature").text()!!.toDouble().also { require(it.isFinite() && it in -60.0..100.0) })
        }.getOrNull()
    }
}

/** Prevent repeated temporary HVAC starts after a verified sample, never inferred from a status query. */
fun AssistantState.temperatureReadDelay(key: String, now: Long): String? = when {
    temperatureReceipt?.recent(key, now) == true ||
        activePreparation?.let { it.vehicleKey == key && !it.finished && it.remoteRunningObserved &&
            it.lastTemperature != null && it.lastSource?.let { source ->
                source >= it.started && now - source in 0..300_000 } == true } == true ->
        "5 分钟内已读取车温 · 未重复开启空调"
    now < temperatureRetryAfter -> "车温暂时无法更新 · 请稍后重试"
    else -> null
}

fun TemperatureUpdate?.temperatureActionLabel(now: Long): String = when {
    this?.expiredOwnership(now) == true -> "核对空调"
    this?.active == true && cancelRequested -> "结束中"
    this?.active == true -> "结束取温"
    this?.needsStop == true -> "停止临时空调"
    else -> "更新车温"
}

fun TemperatureUpdate?.temperatureActionDescription(now: Long): String =
    if (this?.active == true || this?.needsStop == true) "$message · ${temperatureActionLabel(now)}"
    else "更新车温 · 必要时短暂开启空调"
