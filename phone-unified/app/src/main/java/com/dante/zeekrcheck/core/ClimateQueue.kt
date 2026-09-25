package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

enum class ClimatePhase(val label: String) {
    IDLE("等待操作"), QUEUED("正在合并连续操作"), SENDING("正在发送"), WAITING("已受理，等待车况回读"),
    MATCHED("车况回读已匹配"), NEEDS_CHECK("已受理 · 设定温度不回传"), REJECTED("请求未受理"), UNKNOWN("结果未确认，队列已停止"),
}
enum class ClimateResult { MATCHED, NEEDS_CHECK, REJECTED, UNKNOWN }
data class ClimateQueueState(
    val phase: ClimatePhase = ClimatePhase.IDLE,
    val inFlight: ClimateTarget? = null,
    val pending: List<ClimateTarget> = emptyList(),
    val last: ClimateTarget? = null,
    val halted: Boolean = false,
) {
    val active: Boolean get() = inFlight != null || pending.isNotEmpty()
    fun desired(channel: ClimateChannel): ClimateTarget? = pending.lastOrNull { it.channel == channel }
        ?: inFlight?.takeIf { it.channel == channel }
    fun export(): JsonObject = buildJsonObject {
        put("phase", phase.name); put("halted", halted); put("pendingCount", pending.size)
        put("lastTarget", (inFlight ?: last)?.label?.let(::JsonPrimitive) ?: JsonNull)
        put("physicalConfirmation", "UNTESTED"); put("background", "UNTESTED")
    }
}

/** Confined to the caller's UI dispatcher. No persistence, generic actions or implicit retries. */
class ClimateQueue(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 600,
    private val execute: suspend (ClimateTarget, () -> Unit) -> ClimateResult,
) {
    private val mutable = MutableStateFlow(ClimateQueueState())
    val state = mutable.asStateFlow()
    private val pending = linkedMapOf<ClimateChannel, ClimateTarget>()
    private var revision = 0
    private var worker: Job? = null
    private var closed = false

    fun submit(target: ClimateTarget): Boolean {
        if (closed || mutable.value.halted) return false
        // A new on-target replaces an unsent opposing mode on the same seat. In-flight work
        // remains observed and is never cancelled or resent. Selecting the UI mode alone sends nothing.
        if (target.value > 0) target.channel.alternateSeatMode?.let { pending.remove(it) }
        pending[target.channel] = target
        ++revision
        publish(if (mutable.value.inFlight == null) ClimatePhase.QUEUED else mutable.value.phase)
        if (worker?.isActive != true) worker = scope.launch { drain() }
        return true
    }
    private fun publish(phase: ClimatePhase = mutable.value.phase) {
        mutable.value = mutable.value.copy(phase = phase, pending = pending.values.toList())
    }
    private suspend fun drain() {
        try {
            while (pending.isNotEmpty()) {
                val changed = revision
                delay(debounceMs)
                if (changed != revision) continue
                val target = pending.values.first()
                pending.remove(target.channel)
                mutable.value = mutable.value.copy(inFlight = target, phase = ClimatePhase.SENDING, pending = pending.values.toList())
                val result = try { execute(target) { mutable.value = mutable.value.copy(phase = ClimatePhase.WAITING) } }
                    catch (_: TimeoutCancellationException) { ClimateResult.UNKNOWN }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { ClimateResult.UNKNOWN }
                // Tapping the same target while it was in flight must not duplicate the command.
                if (pending[target.channel] == target) pending.remove(target.channel)
                val phase = when (result) {
                    ClimateResult.MATCHED -> ClimatePhase.MATCHED
                    ClimateResult.NEEDS_CHECK -> ClimatePhase.NEEDS_CHECK
                    ClimateResult.REJECTED -> ClimatePhase.REJECTED
                    ClimateResult.UNKNOWN -> ClimatePhase.UNKNOWN
                }
                val halted = result == ClimateResult.UNKNOWN
                if (halted || result == ClimateResult.REJECTED) pending.clear()
                mutable.value = ClimateQueueState(phase, pending = pending.values.toList(), last = target, halted = halted)
                if (halted) return
            }
        } finally { worker = null }
    }
    /** Drops unsent intents; an already sent command cannot be recalled. Continue observing that command. */
    fun discardPending() {
        pending.clear(); ++revision
        publish(if (mutable.value.inFlight == null && !mutable.value.halted) ClimatePhase.IDLE else mutable.value.phase)
    }
    /** The user has checked the uncertain outcome. Old intents are never resumed. */
    fun acknowledgeOutcome() {
        if (mutable.value.active) return
        pending.clear()
        mutable.value = ClimateQueueState()
    }
    fun close() {
        closed = true; pending.clear(); ++revision
        worker?.cancel(); worker = null
        val wasInFlight = mutable.value.inFlight != null
        mutable.value = ClimateQueueState(if (wasInFlight) ClimatePhase.UNKNOWN else mutable.value.phase,
            last = mutable.value.inFlight ?: mutable.value.last, halted = wasInFlight || mutable.value.halted)
    }
}
