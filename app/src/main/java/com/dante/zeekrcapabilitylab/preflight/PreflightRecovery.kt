package com.dante.zeekrcapabilitylab.preflight

import android.content.Context
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import kotlinx.serialization.json.*

/** Reopening the app does not prove that an interrupted vendor camera owner has gone away. */
object PreflightRecovery {
    fun installInterlock(context: Context) {
        val reason=PreflightStore(context).blockingReason() ?: return
        val owner=CameraWorkCoordinator.claim("PREFLIGHT_INTERRUPTED") ?: return
        CameraWorkCoordinator.publish(owner,"上轮体检收尾未确认。请复制报告，然后重启车机。 / Restart the vehicle system after copying the report.",reason)
        PreflightRuntime.mutable.value=PreflightUi(blocked=true,phase="CLEANUP_UNCONFIRMED",message=reason)
    }

    /** Read-only collection after restart; preserves the frozen report before adding exit evidence. */
    internal fun refreshInterruptedReport(context: Context): Boolean {
        val store=PreflightStore(context)
        val prior=store.read() ?: return false
        if(PreflightRuntime.state.value.active) return true
        if(prior["cleanup"] !in listOf(JsonPrimitive("PENDING"),JsonPrimitive("UNCONFIRMED"))) return true
        store.archivePrevious()
        val revised=JsonObject(prior+mapOf("phase" to JsonPrimitive("INTERRUPTED"),
            "reason" to JsonPrimitive("PROCESS_INTERRUPTED"),
            "cleanup" to JsonPrimitive(PreflightPlan.interruptedCleanup((prior["mode"] as? JsonPrimitive)?.contentOrNull)),
            "afterRestartExitEvidence" to PreflightInventory.exits(context)))
        PreflightReport.validate(revised); store.save(revised); PreflightRuntime.report=revised
        PreflightRuntime.mutable.value=PreflightRuntime.state.value.copy(reportReady=true,message="上轮体检被中断，已保留现场并补充退出记录。 / Previous run interrupted.")
        return true
    }
}
