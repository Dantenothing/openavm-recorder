package com.dante.zeekrcapabilitylab.diagnostic

import kotlinx.serialization.Serializable
import com.dante.zeekrcapabilitylab.runtime.RuntimeIntentModel

@Serializable
internal data class AwayRow(val event: String, val wall: Long, val elapsed: Long, val uptime: Long,
                           val process: String, val facts: Map<String, String> = emptyMap())

/** Evidence classifications, never an assertion about physical vehicle locks or whole-vehicle sleep. */
internal object AwayTimeline {
    fun classify(rows: List<AwayRow>): String {
        if (rows.isEmpty()) return "NO_EVIDENCE"
        val start=rows.indexOfLast { it.event=="RECORDER_START" }
        fun lastRelevant(event: String): Int = rows.indices.lastOrNull { index ->
            val row = rows[index]
            row.event == event && (start < 0 || index < start || row.facts["recordingSessionId"] == null || sameRun(rows[start], row))
        } ?: -1
        val confirmed=lastRelevant("RECORDER_VEHICLE_AWAY_CONFIRMED")
        val stop=lastRelevant("RECORDER_STOP")
        val terminal=lastRelevant("RECORDER_SESSION_TERMINATED")
        return when {
            terminal>=0 && start>terminal -> "STOP_THEN_NEW_RECORDING_REQUEST"
            terminal>=0 -> "SESSION_TERMINATED_CHECK_RESOURCE_RESULT"
            stop>=0 && start>stop -> "STOP_THEN_NEW_RECORDING_REQUEST"
            stop>=0 && rows[stop].facts["authorityReason"]=="CONTINUOUS_FAILED" -> "RECORDING_ERROR_STOP_OBSERVED_CHECK_RESOURCE_RESULT"
            stop>=0 -> "STOP_REQUEST_OBSERVED_CHECK_RESOURCE_RESULT"
            confirmed>=0 -> "AWAY_CONFIRMED_WITHOUT_STOP_EVENT"
            rows.any { it.event=="RECORDER_VEHICLE_AWAY_PENDING" } -> "AWAY_CANDIDATE_WITHOUT_CONFIRMED_STOP"
            start<0 && rows.none { it.facts["recorder"] in setOf("RECORDING", "PREPARING", "FINALIZING") } &&
                rows.withIndex().any { (index, row) ->
                    row.facts["standalonePreview"]=="true" && rows.drop(index+1).any { later ->
                        later.process==row.process && later.elapsed>=row.elapsed && later.facts["interactive"]=="false" &&
                            later.facts["displays"]?.split(',')?.contains("0:OFF")==true &&
                            later.facts["standalonePreview"]=="false" && later.facts["floatingControls"]=="false"
                    }
                } -> "PREVIEW_EXIT_OBSERVED_RECORDING_NOT_TESTED"
            rows.any { it.event in setOf("APP_BACKGROUND","POWER_PASSIVE_SNAPSHOT") } -> "NO_CONFIRMED_AWAY_SIGNAL"
            else -> "INSUFFICIENT_BACKGROUND_EVIDENCE"
        }
    }
    internal fun sameRun(start: AwayRow, event: AwayRow): Boolean = start.process == event.process &&
        (start.facts["recordingSessionId"] == null || start.facts["recordingSessionId"] == event.facts["recordingSessionId"])
    fun sleepEvidence(rows: List<AwayRow>): Long = rows.zipWithNext().filter { (a,b) ->
        a.process==b.process && b.elapsed>=a.elapsed && b.uptime>=a.uptime
    }.sumOf { (a,b) -> ((b.elapsed-a.elapsed)-(b.uptime-a.uptime)).coerceAtLeast(0) }

    /** Replays observations without issuing a command, opening a camera or acquiring a lock. */
    fun shadow(rows: List<AwayRow>): Map<String, String> {
        var model:RuntimeIntentModel?=null; var process=""; var epoch:String?=null; var serial=0
        var start:AwayRow?=null
        for(r in rows) {
            if(process!=r.process) { process=r.process; model=RuntimeIntentModel(process); epoch=null; start=null }
            val m=model ?: continue
            when(r.event) {
                "RECORDER_START" -> {
                    start=r
                    epoch="record-${++serial}"; m.beginLocal(epoch!!)
                    m.start(process,RuntimeIntentModel.Task("task-$serial",epoch!!,RuntimeIntentModel.Kind.LOCAL_RECORD,1,Long.MAX_VALUE,"AVM","RECORD"),r.elapsed)
                }
                "RECORDER_STOP", "RECORDER_VEHICLE_AWAY_CONFIRMED" ->
                    if (r.facts["recordingSessionId"] == null || start?.let { sameRun(it,r) } == true) epoch?.let(m::endLocal)
                "RECORDER_SESSION_TERMINATED" -> if (start?.let { sameRun(it,r) } == true) epoch?.let(m::endLocal)
                // App foreground and screen ON deliberately grant no authority in this prototype.
            }
        }
        val target=model?.target(rows.lastOrNull()?.elapsed ?: 0)
        return mapOf("mode" to "SHADOW_ONLY_NO_COMMANDS", "expectedRecording" to (target?.recording?.toString() ?: "UNKNOWN"),
            "coverage" to "RECORDER_START_STOP_TERMINAL_PREVIEW_AND_PARKING_NOT_CONNECTED")
    }
}
