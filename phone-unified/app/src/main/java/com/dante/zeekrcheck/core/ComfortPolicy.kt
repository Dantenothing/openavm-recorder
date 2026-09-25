package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import kotlin.math.abs

enum class SurfacePhase { WAITING, SENT, RUNNING, STOPPING, DONE, LIMITED, SKIPPED }

/** Source intervals are evidence, not a wall-clock estimate of a surface temperature. */
data class SurfaceTask(
    val channel: ClimateChannel, val level: Int, val notBefore: Long, val requiredMs: Long,
    val coolBelow: Double? = null, val phase: SurfacePhase = SurfacePhase.WAITING,
    val sentAt: Long = 0, val leaseEnd: Long = 0, val lastSource: Long = 0,
    val previousQualified: Boolean = false, val confirmedMs: Long = 0,
    val stopSentAt: Long = 0, val reason: String = "",
) {
    val settled get() = phase in setOf(SurfacePhase.DONE, SurfacePhase.LIMITED, SurfacePhase.SKIPPED)
    val pending get() = phase == SurfacePhase.SENT || phase == SurfacePhase.STOPPING
    val owned get() = sentAt > 0 && phase != SurfacePhase.SKIPPED
    fun json() = buildJsonObject {
        put("channel",channel.name); put("level",level); put("notBefore",notBefore); put("requiredMs",requiredMs)
        put("coolBelow",coolBelow?.let(::JsonPrimitive) ?: JsonNull); put("phase",phase.name)
        put("sentAt",sentAt); put("leaseEnd",leaseEnd); put("lastSource",lastSource)
        put("previousQualified",previousQualified); put("confirmedMs",confirmedMs); put("stopSentAt",stopSentAt); put("reason",reason)
    }
    companion object {
        fun parse(e: JsonElement) = SurfaceTask(ClimateChannel.valueOf(e.at("channel").text()!!),
            e.at("level").text()!!.toInt().also { require(it in 1..3) }, e.at("notBefore").text()!!.toLong(),
            e.at("requiredMs").text()!!.toLong().also { require(it in 0..720_000) },
            e.at("coolBelow").text()?.toDoubleOrNull(), SurfacePhase.valueOf(e.at("phase").text()!!),
            e.at("sentAt").text()!!.toLong(), e.at("leaseEnd").text()!!.toLong(), e.at("lastSource").text()!!.toLong(),
            e.at("previousQualified").text()=="true", e.at("confirmedMs").text()!!.toLong().coerceIn(0,720_000),
            e.at("stopSentAt").text()!!.toLong(), e.at("reason").text()?.take(100) ?: "")
    }
}

/** No dynamic positive reconfiguration is enabled until the vehicle timer semantics are verified. */
data class ThermalSession(
    val departureAt: Long? = null, val appointmentId: String? = null, val startElapsed: Long,
    val tasks: List<SurfaceTask> = emptyList(), val airStableSince: Long? = null,
    val airReady: Boolean = false, val suspended: Boolean = false, val suspensionReason: String = "",
    val autoEndAttempted: Boolean = false, val note: String = "",
) {
    fun json() = buildJsonObject {
        put("version",1); put("departureAt",departureAt?.let(::JsonPrimitive) ?: JsonNull)
        put("appointmentId",appointmentId?.let(::JsonPrimitive) ?: JsonNull); put("startElapsed",startElapsed)
        putJsonArray("tasks") { tasks.forEach { add(it.json()) } }; put("airStableSince",airStableSince?.let(::JsonPrimitive) ?: JsonNull)
        put("airReady",airReady); put("suspended",suspended); put("suspensionReason",suspensionReason)
        put("autoEndAttempted",autoEndAttempted); put("note",note)
    }
    companion object {
        fun parse(e: JsonElement?): ThermalSession? = runCatching {
            require(e.at("version").text()=="1")
            ThermalSession(e.at("departureAt").text()?.toLongOrNull(),e.at("appointmentId").text()?.take(80),
                e.at("startElapsed").text()!!.toLong(),(e.at("tasks") as JsonArray).take(5).map(SurfaceTask::parse),
                e.at("airStableSince").text()?.toLongOrNull(),e.at("airReady").text()=="true",e.at("suspended").text()=="true",
                e.at("suspensionReason").text()?.take(120) ?: "",e.at("autoEndAttempted").text()=="true",e.at("note").text()?.take(120) ?: "")
        }.getOrNull()
    }
}

data class ComfortStart(val thermal: ThermalSession, val command: VehicleCommand.Comfort?)
data class ComfortDecision(val session: PreparationSession, val command: VehicleCommand.Comfort? = null, val end: Boolean = false)

/** The unattended path keeps a normal AC target until rapid-mode expiry/reversion is verified. */
object ComfortPolicy {
    const val FRESH_MS = 300_000L // Existing telemetry tolerance; not a claim of a 30-second vehicle sample rate.
    const val MAX_SAMPLE_GAP_MS = 120_000L
    const val AIR_STABLE_MS = 120_000L
    const val CONFIRM_TIMEOUT_MS = 120_000L
    private val durations = listOf(30,20,15,10,5)
    fun durationWithin(remainingMs: Long) = durations.firstOrNull { it * 60_000L <= remainingMs }
    fun fresh(snapshot: ClimateSnapshot, now: Long) = snapshot.sourceTime?.toEpochMilli()?.let { now-it in 0..FRESH_MS } == true
    fun endAt(s: PreparationSession) = minOf(s.deadline,s.thermal?.departureAt ?: Long.MAX_VALUE)

    fun start(p: ComfortPreferences, snapshot: ClimateSnapshot, now: Long, elapsed: Long,
              departureAt: Long? = null, appointmentId: String? = null,
              wheelHeatingVerified: Boolean = false): ComfortStart {
        val end = minOf(now+p.minutes*60_000L,departureAt ?: Long.MAX_VALUE)
        val fitting = durationWithin(end-now) ?: return ComfortStart(ThermalSession(departureAt,appointmentId,elapsed,
            note="剩余时间不足，未启动新的备车动作"),null)
        // Initial AC can cover departure without ending a whole duration step early after the GET.
        // Its lease remains inside the session budget. Departure itself still requests a verified stop;
        // without connectivity the native timer can end later, never a promise of exact departure-off.
        val minutes = if(departureAt!=null) durations.reversed().firstOrNull {
            it*60_000L>=end-now && it<=p.minutes
        } ?: fitting else fitting
        val temp = snapshot.cabinTemperature?.takeIf { fresh(snapshot,now) && it.isFinite() }
        val tasks = buildList {
            val hot = temp != null && temp >= 26 && temp > p.target+1
            val cold = temp != null && temp < 18 && temp < p.target-1
            val seats = if(p.bothSeats) listOf(ClimateChannel.FRONT_LEFT,ClimateChannel.FRONT_RIGHT) else listOf(ClimateChannel.FRONT_LEFT)
            if(hot && p.seatVentilation) seats.forEach { channel ->
                val level = if(temp!!>=35) 3 else if(temp>=30) 2 else 1
                val run = if(temp>=35) 300_000L else if(temp>=30) 180_000L else 120_000L
                add(eligible(SurfaceTask(channel,level,now,run,minOf(28.0,p.target+6.0)),snapshot))
            }
            if(cold && p.seatHeat) seats.forEach { vent ->
                val before = if(temp!!<=5) 12 else if(temp<=12) 8 else 5
                // A 3-minute high setting cannot be bounded by the current 5-minute minimum lease.
                // Use low heat for one 5-minute request; never renew or claim the full proposal is enabled.
                val task = SurfaceTask(vent.alternateSeatMode!!,1,maxOf(now,(departureAt ?: now)-before*60_000L),300_000L)
                add(eligible(task,snapshot))
            }
            if(cold && p.steeringHeat && temp!!<=16) {
                val before = if(temp<=10) 8 else 5
                val task = SurfaceTask(ClimateChannel.STEERING,1,
                    maxOf(now,(departureAt ?: now)-before*60_000L),before*60_000L)
                // Production callers keep this false until physical switching and native expiry
                // are verified. Preferences alone must not bypass that readiness gate.
                add(if(wheelHeatingVerified) eligible(task,snapshot) else task.copy(phase=SurfacePhase.SKIPPED,
                    reason="方向盘自动加热待实车验证，本次未参与"))
            }
        }
        val thermal = ThermalSession(departureAt,appointmentId,elapsed,tasks,
            note=if(temp==null) "车温暂不可核实，本次仅设置空调目标" else "")
        if(departureAt==null && temp!=null && abs(temp-p.target)<=1 && tasks.none { !it.settled } && p.stopAtTarget)
            return ComfortStart(thermal,null)
        val targets = mutableListOf(ClimateTarget(ClimateChannel.AC,p.target,minutes))
        val prepared = tasks.map { task ->
            if(task.phase!=SurfacePhase.WAITING || task.notBefore>now) task else {
                val duration = if(task.channel in setOf(ClimateChannel.FRONT_LEFT,ClimateChannel.FRONT_RIGHT)) minutes
                    else surfaceMinutes(task,end-now) ?: return@map task.copy(phase=SurfacePhase.SKIPPED,reason="剩余时间不足，本次未启动舒适项目")
                targets.add(ClimateTarget(task.channel,task.level,duration))
                task.copy(phase=SurfacePhase.SENT,sentAt=now,leaseEnd=now+duration*60_000L)
            }
        }
        return ComfortStart(thermal.copy(tasks=prepared),VehicleCommand.Comfort(targets))
    }

    private fun eligible(task: SurfaceTask, snapshot: ClimateSnapshot): SurfaceTask {
        val current = snapshot.level(task.channel)
        if(task.channel==ClimateChannel.STEERING) return when(current) {
            0 -> task
            1 -> task.copy(phase=SurfacePhase.SKIPPED,reason="方向盘已在加热，本次保留原设置")
            else -> task.copy(phase=SurfacePhase.SKIPPED,reason="方向盘状态未知，本次未自动开启")
        }
        val opposite = task.channel.alternateSeatMode?.let(snapshot::seat)
        val reason = when {
            current == null || opposite == null -> "座椅状态不完整，本次未自动操作"
            current > 0 || opposite > 0 -> "座椅已有操作，本次保留原设置"
            else -> return task
        }
        return task.copy(phase=SurfacePhase.SKIPPED,reason=reason)
    }

    private fun surfaceMinutes(task: SurfaceTask, remaining: Long): Int? = when {
        task.channel==ClimateChannel.STEERING && task.requiredMs>=480_000 && remaining>=480_000 -> 8
        remaining>=300_000 -> 5
        else -> null
    }

    fun observe(s: PreparationSession, snapshot: ClimateSnapshot, now: Long): PreparationSession {
        val thermal = s.thermal ?: return s
        if(s.finished || s.phase in setOf(PreparationPhase.READING,PreparationPhase.SENDING,PreparationPhase.UNKNOWN,PreparationPhase.FAILED)) return s
        val source = snapshot.sourceTime?.toEpochMilli() ?: return s
        if(!fresh(snapshot,now) || source<s.started || source<=(s.lastSource ?: 0)) return s
        val observed = s.copy(lastSource=source,lastTemperature=snapshot.cabinTemperature,
            remoteRunningObserved=s.remoteRunningObserved || snapshot.acOn==true)
        if(s.stopRequested) {
            val allOff = s.stopSentAt>0 && source>=s.stopSentAt && s.channels.all { channel ->
                if(channel==ClimateChannel.AC) snapshot.acOn==false else snapshot.level(channel)==0
            }
            return if(allOff) observed.copy(phase=PreparationPhase.STOPPED,finished=true,endedAt=now,status="本次备车控制已确认结束")
                else observed.copy(phase=PreparationPhase.STOPPING)
        }
        if(snapshot.acOn==false && s.remoteRunningObserved) return handover(observed,now,"远程空调已结束 · 已退出自动调节")
        var manual = false
        val tasks = thermal.tasks.map { task ->
            if(task.settled || task.phase==SurfacePhase.WAITING || source<task.sentAt) return@map task
            val level = snapshot.level(task.channel)
            if(task.phase==SurfacePhase.STOPPING) return@map if(level==0 && source>=task.stopSentAt)
                task.copy(phase=if(task.confirmedMs>=task.requiredMs) SurfacePhase.DONE else SurfacePhase.LIMITED,lastSource=source)
                else task.copy(lastSource=source,previousQualified=false)
            if(level==0 && source>=task.leaseEnd) return@map task.copy(phase=if(task.confirmedMs>=task.requiredMs) SurfacePhase.DONE else SurfacePhase.LIMITED,
                reason=if(task.confirmedMs<task.requiredMs) "本次定时已结束，舒适程度未完全核实" else "",lastSource=source,previousQualified=false)
            if(task.phase==SurfacePhase.RUNNING && level!=null && level!=task.level) manual=true
            val qualified = level==task.level && (task.coolBelow==null || snapshot.cabinTemperature?.let { it<=task.coolBelow }==true)
            val interval = source-task.lastSource
            val counted = if(qualified && task.previousQualified && interval in 1..MAX_SAMPLE_GAP_MS) interval else 0L
            task.copy(phase=if(level==task.level) SurfacePhase.RUNNING else task.phase,lastSource=source,
                previousQualified=qualified,confirmedMs=(task.confirmedMs+counted).coerceAtMost(task.requiredMs))
        }
        if(manual) return handover(observed.copy(thermal=thermal.copy(tasks=tasks)),now,"舒适设置已变化 · 已退出自动调节")
        val inBand = snapshot.acOn==true && snapshot.cabinTemperature?.let { abs(it-s.preferences.target)<=1 }==true
        val stableSince = if(inBand) thermal.airStableSince?.takeIf { source-(s.lastSource ?: source) in 1..MAX_SAMPLE_GAP_MS } ?: source else null
        val ready = stableSince!=null && source-stableSince>=AIR_STABLE_MS
        val updated = thermal.copy(tasks=tasks,airStableSince=stableSince,airReady=ready)
        val phase = when {
            updated.suspended -> PreparationPhase.DEGRADED
            snapshot.acOn!=true -> PreparationPhase.ACCEPTED
            ready && tasks.any { !it.settled } -> if(tasks.any { it.phase==SurfacePhase.RUNNING && it.previousQualified })
                PreparationPhase.SURFACE_FINISH else PreparationPhase.RUNNING
            ready && thermal.departureAt!=null -> PreparationPhase.HOLD
            ready -> PreparationPhase.READY
            snapshot.cabinTemperature!=null && s.initialTemperature!=null && snapshot.cabinTemperature>s.preferences.target+1 && snapshot.cabinTemperature<s.initialTemperature-0.3 -> PreparationPhase.COOLING
            snapshot.cabinTemperature!=null && s.initialTemperature!=null && snapshot.cabinTemperature<s.preferences.target-1 && snapshot.cabinTemperature>s.initialTemperature+0.3 -> PreparationPhase.WARMING
            else -> PreparationPhase.RUNNING
        }
        // A completed seat is no longer ours. A later user action on that seat must not be turned
        // off again when the cabin eventually finishes or the appointment reaches departure.
        val released = tasks.filter { it.settled && it.sentAt>0 && snapshot.level(it.channel)==0 }.map { it.channel }.toSet()
        return observed.copy(thermal=updated,phase=phase,status=PreparationProgress.title(phase),channels=s.channels-released)
    }

    fun handover(s: PreparationSession, now: Long, reason: String) = s.copy(phase=PreparationPhase.HANDED_OVER,
        finished=true,endedAt=now,status=reason,thermal=s.thermal?.copy(suspended=true,suspensionReason=reason))

    /** Called only after a fresh full vehicle read and again while owning the serial write lane. */
    fun evaluate(s: PreparationSession, snapshot: ClimateSnapshot, now: Long, elapsed: Long, parked: Boolean): ComfortDecision {
        val thermal = s.thermal ?: return ComfortDecision(s)
        if(s.finished || s.stopRequested || thermal.suspended || s.phase in setOf(PreparationPhase.READING,PreparationPhase.SENDING,PreparationPhase.UNKNOWN,PreparationPhase.FAILED)) return ComfortDecision(s)
        if(elapsed<thermal.startElapsed || abs((now-s.started)-(elapsed-thermal.startElapsed))>120_000)
            return ComfortDecision(s.copy(thermal=thermal.copy(suspended=true,suspensionReason="手机时间已变化，自动调节已暂停"),phase=PreparationPhase.DEGRADED))
        val effectiveNow = maxOf(now,s.started+elapsed-thermal.startElapsed)
        val end = endAt(s)
        if(!fresh(snapshot,now) || !parked || snapshot.sourceTime!!.toEpochMilli()<s.started) return ComfortDecision(s)
        // Stop decisions precede the minimum positive duration check and never imply physical success.
        if(!thermal.autoEndAttempted && (effectiveNow>=end ||
            (thermal.airReady && thermal.tasks.all { it.settled } && thermal.departureAt==null && s.preferences.finishWhenComfortable)))
            return ComfortDecision(s.copy(thermal=thermal.copy(autoEndAttempted=true)),end=true)
        if(effectiveNow>=end) return ComfortDecision(s)
        val overdue = thermal.tasks.any { task -> task.pending && effectiveNow-(if(task.phase==SurfacePhase.STOPPING) task.stopSentAt else task.sentAt)>CONFIRM_TIMEOUT_MS }
        if(overdue) return ComfortDecision(s.copy(thermal=thermal.copy(suspended=true,suspensionReason="舒适项目执行结果待核实，自动调节已暂停"),phase=PreparationPhase.DEGRADED))
        if(thermal.tasks.any { it.pending }) return ComfortDecision(s)
        if(snapshot.acOn!=true) return ComfortDecision(s)
        val targets = mutableListOf<ClimateTarget>()
        val tasks = thermal.tasks.map { task ->
            when {
                task.phase==SurfacePhase.RUNNING && (task.confirmedMs>=task.requiredMs || effectiveNow>=task.leaseEnd) -> {
                    targets.add(ClimateTarget(task.channel,0))
                    task.copy(phase=SurfacePhase.STOPPING,stopSentAt=now)
                }
                task.phase==SurfacePhase.WAITING && effectiveNow>=task.notBefore -> {
                    val available = surfaceMinutes(task,end-effectiveNow)
                    val checked = eligible(task,snapshot)
                    if(checked.settled) checked
                    else if(available==null) task.copy(phase=SurfacePhase.SKIPPED,reason="剩余时间不足，本次未启动舒适项目")
                    else {
                        targets.add(ClimateTarget(task.channel,task.level,available))
                        task.copy(phase=SurfacePhase.SENT,sentAt=now,leaseEnd=now+available*60_000L)
                    }
                }
                else -> task
            }
        }
        val changed = s.copy(thermal=thermal.copy(tasks=tasks),channels=(s.channels+targets.filter { it.value>0 }.map { it.channel }).distinct())
        return ComfortDecision(changed,targets.takeIf { it.isNotEmpty() }?.let(VehicleCommand::Comfort))
    }

    fun result(s: PreparationSession, command: VehicleCommand.Comfort, result: CommandResult): PreparationSession {
        if(s.finished) return s
        val thermal = s.thermal ?: return s
        return when(result) {
            CommandResult.UNKNOWN -> s.copy(thermal=thermal.copy(suspended=true,suspensionReason="控制结果待核实，自动调节已暂停"),phase=PreparationPhase.UNKNOWN,finished=true)
            CommandResult.REJECTED -> s.copy(thermal=thermal.copy(tasks=thermal.tasks.map { task ->
                if(command.targets.any { it.channel==task.channel }) task.copy(phase=SurfacePhase.LIMITED,reason="舒适项目操作未受理，本次未重试") else task
            },suspended=true,suspensionReason="舒适项目操作未受理，自动调节已暂停"),phase=PreparationPhase.DEGRADED)
            else -> s
        }
    }

    fun detail(s: PreparationSession): String {
        val thermal = s.thermal ?: return ""
        if(thermal.suspended) return thermal.suspensionReason
        if(thermal.tasks.any { !it.settled } && thermal.airReady) return when {
            thermal.tasks.any { it.phase==SurfacePhase.RUNNING && it.previousQualified } -> "舱温已达标 · 舒适项目继续处理，表面温度未直接测量"
            thermal.tasks.any { it.phase==SurfacePhase.STOPPING } -> "舱温已达标 · 等待舒适项目关闭回传"
            thermal.tasks.any { it.phase==SurfacePhase.SENT } -> "舱温已达标 · 等待舒适项目运行回传"
            thermal.tasks.any { it.phase==SurfacePhase.WAITING } -> "舱温已达标 · 临近出发再进行局部预热"
            else -> "舱温已达标 · 舒适项目状态待核实"
        }
        if(s.phase==PreparationPhase.HOLD) return "舱温已稳定 · 保持至 ${displayTime(thermal.departureAt?.let(java.time.Instant::ofEpochMilli))}"
        if(s.phase==PreparationPhase.READY) return if(thermal.tasks.any { it.phase==SurfacePhase.LIMITED || it.phase==SurfacePhase.SKIPPED })
            "舱温已达标 · 部分舒适项目未参与或未完全核实" else "舱温已稳定 · 本次舒适任务已完成规则评估"
        return thermal.note
    }
}
