package com.dante.zeekrcheck.core

import java.time.Instant

enum class Airflow { NONE, COOLING, WARMING, HOLD, AIR }

/** A view of existing evidence, never a second comfort strategy or a compressor-mode claim. */
data class ThermalPresentation(val ambient: String, val airflow: Airflow, val label: String, val targeting: Boolean = false) {
    val compactLabel: String get() = when (airflow) {
        Airflow.HOLD -> "温度达标"
        Airflow.AIR -> "空调送风中"
        else -> label
    }
    fun progressOverride(s: PreparationSession, now: Long): String? {
        if (PreparationProgress.idle(s, now) || PreparationProgress.phase(s, now) !in setOf(
                PreparationPhase.RUNNING, PreparationPhase.COOLING, PreparationPhase.WARMING,
                PreparationPhase.READY, PreparationPhase.HOLD, PreparationPhase.SURFACE_FINISH)) return null
        if (targeting) return label
        return when (airflow) {
            Airflow.NONE -> "备车状态待核实"
            Airflow.AIR -> if (label == "正在备车") null else label
            else -> null
        }
    }
    fun preparationTitle(s: PreparationSession, now: Long) = progressOverride(s, now) ?: PreparationProgress.controlTitle(s, now)
    fun preparationDetail(s: PreparationSession, now: Long) = when {
        targeting && progressOverride(s, now) != null -> "空调已运行 · 等待温度变化"
        progressOverride(s, now) != null -> "等待新的车辆回传"
        else -> PreparationProgress.detail(s, now)
    }
    fun preparationButton(s: PreparationSession, now: Long, target: Int): String {
        val original = PreparationProgress.button(s, now, target)
        return progressOverride(s, now)?.let { "$it\n${original.substringAfter('\n')}" } ?: original
    }
    companion object {
        fun from(overview: VehicleOverview, preparation: PreparationSession?, now: Instant): ThermalPresentation {
            val millis = now.toEpochMilli()
            val ambient = overview.thermalState(now)
            val cabin = overview.readings["cabin_temperature"]
            val temperature = cabin?.takeIf { it.fresh(now) }?.value?.substringBefore(' ')?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in -60.0..100.0 }
            val session = preparation?.takeIf { it.vehicleKey == overview.vehicleKey }
            val phase = session?.let { PreparationProgress.phase(it, millis) }
            val driving = overview.motion?.let {
                it.state == "Driving" && OverviewReading("", it.source, it.fetched).fresh(now) &&
                    it.source!! >= (session?.started ?: 0)
            } == true
            val active = !driving && session != null && !PreparationProgress.idle(session, millis)
            fun still(label: String) = ThermalPresentation(ambient, Airflow.NONE, label)
            if (active && phase in setOf(PreparationPhase.READING, PreparationPhase.SENDING, PreparationPhase.STOPPING))
                return still(PreparationProgress.title(phase!!))
            if (active && phase in setOf(PreparationPhase.UNKNOWN, PreparationPhase.DEGRADED))
                return still("备车状态待核实")
            if (temperature == null || !overview.climateFresh(now))
                return still(if (active) "备车状态待核实" else overview.thermalLabel(now))
            if (active && phase == PreparationPhase.ACCEPTED) return still("等待空调运行回传")
            val air = overview.blowerActive == true || overview.acOn == true
            if (!air) return still(overview.thermalLabel(now))
            // A finished observer cannot keep its last 'on' sample animating indefinitely.
            if (session?.finished == true && (overview.climateSource ?: 0) <= (session.lastSource ?: session.endedAt))
                return still(overview.thermalLabel(now))
            val confirmedSession = active && overview.acOn == true && session!!.remoteRunningObserved &&
                overview.climateSource!! >= session.started && overview.climateSource >= (session.lastSource ?: Long.MAX_VALUE) &&
                cabin?.source == overview.climateSource
            if (confirmedSession) {
                // A newer sample may precede the session observer. It can establish the current
                // target direction, but cannot inherit the old sample's trend or ready status.
                val classified = overview.climateSource == session!!.lastSource
                val flow = when (if (classified) phase else null) {
                    PreparationPhase.COOLING -> Airflow.COOLING
                    PreparationPhase.WARMING -> Airflow.WARMING
                    PreparationPhase.HOLD, PreparationPhase.READY, PreparationPhase.SURFACE_FINISH -> Airflow.HOLD
                    else -> if (classified && session.thermal?.airReady == true) Airflow.HOLD else Airflow.AIR
                }
                // Color the requested preparation direction as soon as remote AC is confirmed.
                // 'Targeting' is presentation only: it never advances measured comfort progress.
                if (flow == Airflow.AIR) {
                    val target = session.preferences.target
                    if (temperature < target-1) return ThermalPresentation(ambient,Airflow.WARMING,"预热至 ${target}°C",targeting=true)
                    if (temperature > target+1) return ThermalPresentation(ambient,Airflow.COOLING,"预冷至 ${target}°C",targeting=true)
                }
                val label = when (flow) {
                    Airflow.COOLING -> "正在降温"
                    Airflow.WARMING -> "正在升温"
                    Airflow.HOLD -> if (phase == PreparationPhase.SURFACE_FINISH) "舱温达标 · 座椅继续处理" else "温度达标 · 保持中"
                    else -> "正在备车"
                }
                return ThermalPresentation(ambient, flow, label)
            }
            // A newer, unclassified sample or driver AC can prove airflow, not its heat direction.
            return ThermalPresentation(ambient, Airflow.AIR, if (driving) "空调以车机为准" else overview.climateLabel(now))
        }
    }
}
