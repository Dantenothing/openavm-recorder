package com.dante.zeekrcheck.core

import java.util.Locale
import kotlin.math.abs

enum class PreparationPhase { READING, SENDING, ACCEPTED, RUNNING, COOLING, WARMING, READY, STOPPING, STOPPED, EXPIRED, FAILED, UNKNOWN, HANDED_OVER, SURFACE_FINISH, HOLD, DEGRADED }

/** Measured progress is separate from command receipt and from the unavailable thermostat setpoint. */
object PreparationProgress {
    fun inProgress(s: PreparationSession?, now: Long) = s != null && !s.finished && now < s.deadline + (if(s.thermal!=null) 120_000 else 0) &&
        s.phase !in setOf(PreparationPhase.FAILED, PreparationPhase.UNKNOWN, PreparationPhase.EXPIRED, PreparationPhase.STOPPED, PreparationPhase.HANDED_OVER)
    fun resumable(s: PreparationSession?, now: Long) = inProgress(s, now) && s!!.phase !in setOf(PreparationPhase.READING, PreparationPhase.SENDING)
    fun fresh(s: PreparationSession, now: Long) = s.lastSource?.let { now-it in -30_000..300_000 } == true
    fun observe(s: PreparationSession, snapshot: ClimateSnapshot, now: Long): PreparationSession {
        if (s.finished) return s
        if (s.thermal!=null) return ComfortPolicy.observe(s,snapshot,now)
        val source = snapshot.sourceTime?.toEpochMilli() ?: return s
        if (source < s.started || now-source !in -30_000..300_000 || source <= (s.lastSource ?: 0)) return s
        if (s.phase in setOf(PreparationPhase.FAILED, PreparationPhase.UNKNOWN,PreparationPhase.READING,PreparationPhase.SENDING)) return s
        val temp = snapshot.cabinTemperature
        val ran = s.remoteRunningObserved || s.phase in setOf(PreparationPhase.RUNNING,PreparationPhase.COOLING,PreparationPhase.WARMING,PreparationPhase.READY)
        // An off report from before the stop write cannot acknowledge that write. A queued stop
        // also must not be erased by the old off report while the start request is still settling.
        if (s.stopRequested && (s.stopSentAt == 0L || source < s.stopSentAt)) return s.copy(
            lastTemperature=temp,lastSource=source,remoteRunningObserved=ran || snapshot.acOn==true)
        if (snapshot.acOn == false && ClimateChannel.AC in s.channels && (s.stopRequested || ran)) return s.copy(
            phase=PreparationPhase.STOPPED, status="远程预空调已结束", finished=true, lastTemperature=temp, lastSource=source, endedAt=now)
        if (snapshot.acOn != true) return s.copy(lastTemperature=temp,lastSource=source,stableSince=null,remoteRunningObserved=ran,
            phase=if(s.stopRequested) PreparationPhase.STOPPING else PreparationPhase.ACCEPTED)
        val stable = temp?.let { abs(it-s.preferences.target)<=0.5 } == true
        val since = if(stable) s.stableSince?.takeIf { source-(s.lastSource ?: source) <= 300_000 } ?: source else null
        val phase = when {
            s.stopRequested -> PreparationPhase.STOPPING
            since != null && source-since >= 60_000 -> PreparationPhase.READY
            temp != null && s.initialTemperature != null && temp > s.preferences.target+0.5 && temp < s.initialTemperature-0.3 -> PreparationPhase.COOLING
            temp != null && s.initialTemperature != null && temp < s.preferences.target-0.5 && temp > s.initialTemperature+0.3 -> PreparationPhase.WARMING
            else -> PreparationPhase.RUNNING
        }
        return s.copy(phase=phase,lastTemperature=temp,lastSource=source,stableSince=since,status=title(phase),remoteRunningObserved=true)
    }
    fun phase(s: PreparationSession, now: Long): PreparationPhase = when {
        // Old sessions only recorded "finished"; that could mean the phone's observer expired.
        s.phase==PreparationPhase.STOPPED && s.lastSource==null -> if(now>=s.deadline) PreparationPhase.EXPIRED else PreparationPhase.UNKNOWN
        s.phase in setOf(PreparationPhase.STOPPED,PreparationPhase.FAILED,PreparationPhase.UNKNOWN,PreparationPhase.HANDED_OVER) -> s.phase
        s.stopRequested && !s.finished -> PreparationPhase.STOPPING
        s.finished && s.phase==PreparationPhase.READY -> PreparationPhase.READY
        s.thermal!=null && !s.finished && s.thermal.suspended -> PreparationPhase.DEGRADED
        s.thermal!=null && !s.finished && now>=ComfortPolicy.endAt(s) && now<s.deadline+120_000 -> PreparationPhase.DEGRADED
        now>=s.deadline -> PreparationPhase.EXPIRED
        s.phase in setOf(PreparationPhase.RUNNING,PreparationPhase.COOLING,PreparationPhase.WARMING,PreparationPhase.READY,PreparationPhase.SURFACE_FINISH,PreparationPhase.HOLD) && !fresh(s,now) -> PreparationPhase.ACCEPTED
        else -> s.phase
    }
    fun title(phase: PreparationPhase) = when(phase) {
        PreparationPhase.READING -> "正在准备…"
        PreparationPhase.SENDING -> "正在发送…"
        PreparationPhase.ACCEPTED -> "备车已受理"
        PreparationPhase.RUNNING -> "正在备车"
        PreparationPhase.COOLING -> "正在降温"
        PreparationPhase.WARMING -> "正在升温"
        PreparationPhase.READY -> "车温已达标"
        PreparationPhase.STOPPING -> "正在结束…"
        PreparationPhase.STOPPED -> "远程备车已结束"
        PreparationPhase.EXPIRED -> "备车已到时"
        PreparationPhase.FAILED -> "备车未启动"
        PreparationPhase.UNKNOWN -> "备车结果待核实"
        PreparationPhase.HANDED_OVER -> "已退出远程备车"
        PreparationPhase.SURFACE_FINISH -> "座椅继续处理"
        PreparationPhase.HOLD -> "保持待出发"
        PreparationPhase.DEGRADED -> "备车状态待核实"
    }
    fun detail(s: PreparationSession, now: Long): String = when(phase(s,now)) {
        PreparationPhase.READING -> "读取车温 · 目标 ${s.preferences.target}°C"
        PreparationPhase.SENDING -> "目标 ${s.preferences.target}°C · 请稍等"
        PreparationPhase.ACCEPTED -> "等待空调运行回传"
        PreparationPhase.RUNNING -> if(s.thermal?.airReady==true) ComfortPolicy.detail(s) else "空调已开 · 目标 ${s.preferences.target}°C"
        PreparationPhase.COOLING,PreparationPhase.WARMING -> "${degrees(s.initialTemperature)} → ${degrees(s.lastTemperature)}°C"
        PreparationPhase.READY -> if(s.finished) "已接近 ${s.preferences.target}°C · 本次无需启动" else if(s.thermal!=null) ComfortPolicy.detail(s) else "已接近 ${s.preferences.target}°C · 再点停止"
        PreparationPhase.STOPPING -> if(s.stopSentAt==0L) "已收到停止操作 · 等待当前请求结束" else "等待关闭状态回传"
        PreparationPhase.STOPPED -> "远程预空调已结束 · 车内舒适功能以车机为准"
        PreparationPhase.EXPIRED -> "本次跟踪已结束 · 车辆是否停止尚未确认"
        PreparationPhase.FAILED -> s.status
        PreparationPhase.UNKNOWN -> if(s.finished && s.lastSource==null) "没有车辆完成回传 · 可刷新车况" else s.status
        PreparationPhase.HANDED_OVER -> if(s.thermal!=null) s.status else if(s.status.contains("本次未发送")) "车辆处于行驶模式 · 本次未发送备车" else "车辆进入行驶模式 · 空调以车机为准"
        PreparationPhase.SURFACE_FINISH,PreparationPhase.HOLD -> ComfortPolicy.detail(s)
        PreparationPhase.DEGRADED -> ComfortPolicy.detail(s).ifBlank { "已到本次截止 · 等待车况核实结束" }
    }
    fun idle(s: PreparationSession, now: Long) = phase(s, now) != PreparationPhase.UNKNOWN &&
        (s.finished || phase(s, now) in setOf(PreparationPhase.STOPPED, PreparationPhase.EXPIRED, PreparationPhase.FAILED, PreparationPhase.HANDED_OVER))
    fun controlTitle(s: PreparationSession, now: Long) = if (idle(s, now)) "一键备车" else title(phase(s, now))
    fun button(s: PreparationSession, now: Long, target: Int = s.preferences.target): String {
        if (idle(s, now)) return "一键备车\n目标 ${target}°C"
        val phase=phase(s,now)
        val subtitle=when(phase) {
            PreparationPhase.READING,PreparationPhase.SENDING,PreparationPhase.ACCEPTED,
            PreparationPhase.RUNNING,PreparationPhase.COOLING,PreparationPhase.WARMING,PreparationPhase.READY,
            PreparationPhase.SURFACE_FINISH,PreparationPhase.HOLD,PreparationPhase.DEGRADED -> "再点停止 · ${s.preferences.target}°C"
            PreparationPhase.STOPPING -> if(s.stopSentAt==0L) "已收到停止操作" else "等待关闭回传"
            PreparationPhase.FAILED -> "点按可重试"
            PreparationPhase.UNKNOWN -> "请在应用核实"
            else -> detail(s,now)
        }
        return "${title(phase)}\n$subtitle"
    }
    /** Driving is an official mode, not an inference from unlocking, opening a door, or Bluetooth. */
    fun driving(probe: Probe?, now: Long, after: Long = 0): Boolean {
        val evidence = ParkingEvidence.from(probe) ?: return false
        return evidence.drivingMode && ParkingEvidence.fresh(evidence.basicTime, now) &&
            ParkingEvidence.fresh(evidence.fetched, now) && evidence.basicTime!! > after
    }
    fun observeVehicle(s: PreparationSession, probe: Probe, now: Long): PreparationSession {
        if (s.finished || s.phase in setOf(PreparationPhase.READING, PreparationPhase.SENDING, PreparationPhase.UNKNOWN, PreparationPhase.FAILED)) return s
        return if (driving(probe, now, s.started)) s.copy(phase = PreparationPhase.HANDED_OVER, finished = true,
            endedAt = now, status = "车辆进入行驶模式 · 已退出远程备车") else s
    }
    fun message(s: PreparationSession?, previous: String?, now: Long): String? {
        if (s == null || !idle(s, now)) return previous
        val unrelated = previous?.takeUnless { text -> text.contains("备车") || listOf("空调 ·", "正在发送：空调", "已接近目标温度").any(text::startsWith) }
        return unrelated ?: if (s.endedAt > 0 && now - s.endedAt in 0..45_000) "${title(phase(s, now))} · ${detail(s, now)}" else null
    }
    /** Losing the observer never replays a physical command or leaves an infinite sending spinner. */
    fun interrupted(s: PreparationSession) = when {
        s.finished -> s
        s.stopRequested && s.stopSentAt==0L -> s.copy(phase=PreparationPhase.UNKNOWN,finished=true,status="停止操作已中断 · 未自动重发，请核实车况")
        s.phase==PreparationPhase.READING -> s.copy(phase=PreparationPhase.FAILED,finished=true,status="读取已中断 · 本次未发送")
        s.phase==PreparationPhase.SENDING -> s.copy(phase=PreparationPhase.UNKNOWN,finished=true,status="发送已中断 · 结果待核实")
        s.thermal!=null -> s.copy(thermal=s.thermal.copy(suspended=true,suspensionReason="进度已恢复 · 自动调节已暂停，可手动停止"),phase=PreparationPhase.DEGRADED)
        else -> s
    }
    fun remaining(s: PreparationSession, now: Long) = "目标 ${s.preferences.target}°C · ${(kotlin.math.ceil((ComfortPolicy.endAt(s)-now).coerceAtLeast(0)/60_000.0)).toInt()} 分钟"
    private fun degrees(value: Double?) = value?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "—"
    /** Upgrade only the old, known accepted preparation gate, not arbitrary unknown body commands. */
    fun legacyAcceptedGate(state: AssistantState): Boolean {
        val s=state.activePreparation ?: return false
        val last=state.history.firstOrNull() ?: return false
        return state.operationPending && !s.stopRequested && !state.operationMessage.startsWith("正在发送") && last.result==CommandResult.ACCEPTED.label &&
            last.title.startsWith("空调 ·") && last.at-s.started in 0..120_000
    }
}
