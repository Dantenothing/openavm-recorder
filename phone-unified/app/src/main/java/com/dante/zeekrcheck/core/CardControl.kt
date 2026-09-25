package com.dante.zeekrcheck.core

import java.time.Instant

/** A fixed intent from the state the user saw, never an unbounded "toggle" on the server. */
object CardControl {
    val actions = setOf("prepare", "find", "lock", "guard", "trunk", "port")
    fun field(action: String) = if (action == "guard") "sentry" else action
    fun endpoint(action: String) = if (action == "guard") Endpoint.SENTRY else Endpoint.STATUS
    fun target(action: String, shown: String?): BodyAction? = when (action) {
        "lock" -> when (shown) { "已锁" -> BodyAction.UNLOCK; "未锁" -> BodyAction.LOCK; else -> null }
        "guard" -> when (shown) { "开启" -> BodyAction.SENTRY_OFF; "关闭" -> BodyAction.SENTRY_ON; else -> null }
        // The unknown-state UI says "open charge lid", which is an explicit target, not a guessed state.
        "port" -> when (shown) { "打开" -> BodyAction.PORT_CLOSE; "关闭",null -> BodyAction.PORT_OPEN; else -> null }
        // Only latch unlock is established in the SNC transport. RDL is NOT a powered-close command.
        "trunk" -> if (shown == "关闭") BodyAction.TRUNK_UNLOCK else null
        "find" -> BodyAction.HORN
        else -> null
    }
    fun unavailable(action: String, value: String?) = if (action == "trunk" && value == "打开")
        "尾门已打开 · 电动关闭尚未接入" else "状态已读取 · 请再点一次操作"

    /** Fetch immediately before a body action. A changed/unknown state never reverses the user's intent. */
    suspend fun run(action: String, shown: String?, fetch: suspend (Endpoint) -> Probe,
        publish: (Probe) -> Unit, send: suspend (BodyAction) -> Unit, feedback: (String) -> Unit) {
        if (action !in actions || action == "prepare") return
        if (action == "find") { send(BodyAction.HORN); return }
        val target = target(action, shown)
        val probe = fetch(endpoint(action))
        publish(probe)
        if (probe.outcome != ProbeOutcome.SUCCESS) { feedback("读取失败 · ${probe.outcome.label} · 未发送操作"); return }
        val latest = Capabilities.parse(listOf(probe)).firstOrNull { it.id == field(action) && it.read == ReadEvidence.FOUND }?.value
        if (latest == null) {
            if(action=="port" && shown==null) send(BodyAction.PORT_OPEN)
            else feedback("车辆未返回此项状态 · 未发送操作")
            return
        }
        if(action=="port" && shown==null) {
            if(latest=="打开") feedback("充电口已上报打开 · 无需重复发送") else send(BodyAction.PORT_OPEN)
            return
        }
        if (target == null) { feedback(unavailable(action, latest)); return }
        if (latest != shown) { feedback("车况已变化 · 已更新卡片，请重新点按"); return }
        send(target)
    }
}

/** Untimestamped fields can confirm a *changed* observation, never an unchanged cached success. */
data class PendingBody(val action: BodyAction, val sentAt: Long, val before: String?) {
    fun matches(probe: Probe): Boolean {
        val expected = action.observation ?: return false
        if (probe.outcome != ProbeOutcome.SUCCESS || probe.fetchedAt.toEpochMilli() < sentAt) return false
        val field = Capabilities.parse(listOf(probe)).firstOrNull { it.id == expected.first && it.read == ReadEvidence.FOUND } ?: return false
        if (field.value != expected.second) return false
        return if (field.sourceTime != null) !field.sourceTime.isBefore(Instant.ofEpochMilli(sentAt)) &&
            !field.sourceTime.isAfter(probe.fetchedAt.plusSeconds(30))
        else before != null && before != expected.second
    }
}
