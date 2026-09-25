package com.dante.zeekrcheck.core

enum class PreparationTap { START, STOP, OBSERVE, STALE }

/** A tap means what the displayed button offered, even if a read or a service start is delayed. */
object PreparationControl {
    fun shown(session: PreparationSession?, key: String?, now: Long): String {
        val current = session?.takeIf { it.vehicleKey == key }
        val action = when {
            current?.stopRequested == true && !current.finished -> "observe"
            PreparationProgress.inProgress(current, now) -> "stop"
            current != null && !PreparationProgress.idle(current, now) -> "observe"
            else -> "start"
        }
        return "$action:${current?.createdAt ?: 0L}"
    }

    fun decide(shown: String?, session: PreparationSession?, key: String, now: Long): PreparationTap {
        val current = session?.takeIf { it.vehicleKey == key }
        val id = shown?.substringAfter(':', "")?.toLongOrNull() ?: return PreparationTap.STALE
        val currentId: Long = current?.createdAt ?: 0L
        if (id != currentId) return PreparationTap.STALE
        return when (shown.substringBefore(':')) {
            "start" -> if (current == null || PreparationProgress.idle(current, now)) PreparationTap.START else PreparationTap.STALE
            "stop" -> if (current?.stopRequested == true) PreparationTap.OBSERVE
                else if (PreparationProgress.inProgress(current, now)) PreparationTap.STOP else PreparationTap.STALE
            "observe" -> PreparationTap.OBSERVE
            else -> PreparationTap.STALE
        }
    }

    fun requestStop(session: PreparationSession, now: Long): PreparationSession =
        if (session.phase == PreparationPhase.READING) session.copy(phase = PreparationPhase.FAILED,
            finished = true, endedAt = now, status = "已取消备车 · 本次未发送")
        else session.copy(stopRequested = true, status = "收到停止操作 · 正在等待当前请求结束")

    /** Only the comfort channels started by this preparation belong to its stop command. */
    fun stopCommand(session: PreparationSession) = VehicleCommand.Comfort(
        session.channels.map { ClimateTarget(it, 0, session.preferences.minutes) })

    fun needsCheck(session: PreparationSession?) = session?.phase==PreparationPhase.UNKNOWN && session.finished
    // An explicit acknowledgement retires the unresolved local task; it never sends or resumes a command.
    fun acknowledge(session: PreparationSession?) = session?.takeUnless(::needsCheck)

    fun label(session: PreparationSession?, now: Long): String = when {
        session?.stopRequested == true && !session.finished -> "正在结束…"
        PreparationProgress.inProgress(session, now) -> "停止备车"
        session != null && !PreparationProgress.idle(session, now) -> "核实备车状态"
        else -> "一键备车"
    }
}
