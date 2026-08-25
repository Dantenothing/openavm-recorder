package com.dante.zeekrcapabilitylab.diagnostic

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import java.nio.charset.StandardCharsets

data class PowerDiagnosticEvidence(
    val ticket: String,
    val version: String,
    val versionCode: Int,
    val gitSha: String,
    val processId: String,
    val recorder: String,
    val source: String,
    val segment: Int,
    val wakeLock: Boolean,
    val exitReason: String,
    val events: List<ProbeEvent>,
) {
    fun humanText(nowMs: Long = System.currentTimeMillis()): String = buildString {
        appendLine("诊断编号：$ticket")
        appendLine("版本：$version ($versionCode) · $gitSha")
        appendLine("进程：$processId")
        appendLine("录像：$recorder · $source · segment $segment · WakeLock $wakeLock")
        appendLine("最近系统退出：$exitReason")
        appendLine("本次测试时间线：")
        if (events.isEmpty()) appendLine("- 暂无关键事件")
        events.takeLast(36).forEach { event ->
            val age = ((nowMs - event.epochMs).coerceAtLeast(0L) / 1_000L)
            val detail = PowerDiagnosticCodec.safeDetail(event)
            appendLine("- ${age}s ${event.eventName}${if (detail.isBlank()) "" else " · $detail"}")
        }
    }.trimEnd()

    fun qrPayload(maxBytes: Int = 1_100): String = PowerDiagnosticCodec.encode(this, maxBytes)
}

object PowerDiagnosticRepository {
    private val keyNames = setOf(
        "PROCESS_STARTED", "APP_FOREGROUND", "APP_BACKGROUND", "SCREEN_ON", "SCREEN_OFF",
        "SYSTEM_SHUTDOWN", "DISPLAY_ADDED", "DISPLAY_REMOVED", "DISPLAY_CHANGED",
        "POWER_DELAYED_SNAPSHOT", "VEHICLE_AWAY_TEST_MARKER", "VEHICLE_POWER_CAPABILITY",
        "RECORDER_START", "RECORDER_SEGMENT_START", "RECORDER_SEGMENT_FINALIZE_BEGIN",
        "RECORDER_MEDIA_RECORDER_STOP_RESULT", "RECORDER_CAMERA_DISCONNECTED",
        "RECORDER_CAMERA_UNAVAILABLE", "RECORDER_CAMERA_RECOVERY_WAITING",
        "RECORDER_CAMERA_RECOVERY_ATTEMPT", "RECORDER_CAMERA_RECOVERY_SUCCEEDED",
        "RECORDER_CAMERA_RECOVERY_AVAILABILITY_TRACKED", "RECORDER_WAKE_LOCK_ACQUIRED",
        "RECORDER_WAKE_LOCK_RELEASED", "RECORDER_STOP", "RECORDER_STOPPED",
        "RECORDER_CAMERA_RESUME_GATE_ARMED", "RECORDER_CAMERA_RESUME_GATE_DISARMED",
        "SURROUND_PREVIEW_BACKGROUND_STALE", "SURROUND_PREVIEW_FOREGROUND_REBUILD",
    )

    fun collect(context: Context): PowerDiagnosticEvidence {
        val all = EventLogger.readAllEvents()
            .sortedWith(compareBy<ProbeEvent> { it.epochMs }.thenBy { it.sequence })
        val markerIndex = all.indexOfLast { it.eventName == "VEHICLE_AWAY_TEST_MARKER" }
        val scoped = all.drop(if (markerIndex >= 0) markerIndex else (all.size - 120).coerceAtLeast(0))
            .filter(::isRelevant)
            .takeLast(120)
        val marker = scoped.firstOrNull { it.eventName == "VEHICLE_AWAY_TEST_MARKER" }
            ?.payload?.get("detail")
        val state = CameraRecordingService.state.value
        return PowerDiagnosticEvidence(
            ticket = marker?.let { PowerDiagnosticCodec.safeToken(it, 12) }
                ?: ZeekrApp.processStartId.takeLast(6).uppercase(),
            version = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            gitSha = BuildConfig.GIT_SHA,
            processId = ZeekrApp.processStartId,
            recorder = state.status,
            source = state.sourceRole?.name ?: "NONE",
            segment = state.segmentNumber,
            wakeLock = state.wakeLockHeld,
            exitReason = latestExitReason(context),
            events = scoped,
        )
    }

    private fun isRelevant(event: ProbeEvent): Boolean = event.eventName in keyNames ||
        event.eventName.startsWith("ACTIVITY_") ||
        event.eventName.startsWith("RECORDER_SEGMENT_") ||
        event.eventName.startsWith("RECORDER_CAMERA_RECOVERY_")

    private fun latestExitReason(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "UNAVAILABLE_API_${Build.VERSION.SDK_INT}"
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return@runCatching "NONE"
            "${exitReasonName(exit.reason)} status=${exit.status} pss=${exit.pss / 1024}MB age=${((System.currentTimeMillis() - exit.timestamp).coerceAtLeast(0L) / 1000L)}s"
        }.getOrDefault("UNAVAILABLE")
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "NATIVE_CRASH"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        else -> "REASON_$reason"
    }
}

object PowerDiagnosticCodec {
    fun encode(evidence: PowerDiagnosticEvidence, maxBytes: Int): String {
        require(maxBytes >= 180)
        val fixed = listOf(
            "AVMP1", "id=${safeToken(evidence.ticket, 12)}", "v=${safeToken(evidence.version, 30)}",
            "vc=${evidence.versionCode}", "sha=${safeToken(evidence.gitSha, 10)}",
            "p=${safeToken(evidence.processId, 30)}", "st=${safeToken(evidence.recorder, 20)}",
            "src=${safeToken(evidence.source, 12)}", "seg=${evidence.segment}",
            "wl=${if (evidence.wakeLock) 1 else 0}", "exit=${safeToken(evidence.exitReason, 70)}",
        )
        val now = System.currentTimeMillis()
        val tokens = evidence.events.asReversed().map { event ->
            val age = ((now - event.epochMs).coerceAtLeast(0L) / 1000L)
            "${age}s:${safeToken(event.eventName, 44)}:${safeToken(safeDetail(event), 80)}"
        }
        val included = mutableListOf<String>()
        tokens.forEach { token ->
            val candidate = (fixed + "e=${(included + token).joinToString(",")}" + "tr=0").joinToString("|")
            if (candidate.utf8Size() <= maxBytes) included += token
        }
        val fields = fixed + if (included.isEmpty()) emptyList() else listOf("e=${included.joinToString(",")}")
        return (fields + "tr=${if (included.size < tokens.size) 1 else 0}").joinToString("|")
    }

    fun safeDetail(event: ProbeEvent): String = listOf(
        "detail", "probeId", "interactive", "displays", "appForeground", "activity",
        "recorder", "source", "camera", "segment", "wakeLock", "reason", "result",
        "attempt", "processStartId",
    ).mapNotNull { key -> event.payload[key]?.let { "$key=$it" } }.joinToString(" ")

    fun safeToken(value: String, maxLength: Int): String = value
        .map { if (it.isLetterOrDigit() || it in "._-+=:,/") it else '_' }
        .joinToString("")
        .take(maxLength)

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
}
