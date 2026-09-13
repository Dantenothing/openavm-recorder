package com.dante.zeekrcapabilitylab.sentry.canary

import kotlinx.serialization.Serializable

@Serializable
data class CanaryTelemetryPoint(
    val elapsedMs: Long, val pssKiB: Int, val thermal: Int?, val historySeconds: Double,
    val liveBytes: Long, val fps: Double,
    val payloadBytes: Long = 0, val bitrateBps: Long = 0, val completedHistorySeconds: Double = 0.0,
    val budgetEvictedGops: Long = 0, val droppedGops: Long = 0,
)

@Serializable
data class CanaryTelemetry(
    val elapsedMs: Long = 0,
    val ramReadyObserved: Boolean = false,
    val maximumHistorySeconds: Double = 0.0,
    val peakPssKiB: Int = 0,
    val peakThermal: Int? = null,
    val samples: List<CanaryTelemetryPoint> = emptyList(),
)

data class CanaryClipHistory(val retained: List<CanaryClipResult>, val omitted: Long) {
    fun append(result: CanaryClipResult): CanaryClipHistory {
        val next = retained + result
        // Repeated failed attempts must not evict the clips needed for tomorrow's review.
        val indices = (next.withIndex().filter { it.value.fileName != null }.takeLast(12) +
            next.withIndex().filter { it.value.fileName == null }.takeLast(4)).map { it.index }.toSet()
        val kept = next.filterIndexed { index, _ -> index in indices }
        return CanaryClipHistory(kept, omitted + next.size - kept.size)
    }
}

/** Called on the service's main handler. No files, frames, camera IDs or USB identities. */
class CanaryTelemetryAccumulator(private val capacity: Int = 120) {
    init { require(capacity in 1..120) }
    private val samples = ArrayDeque<CanaryTelemetryPoint>()
    private var summary = CanaryTelemetry()
    private var lastSampleMs = -5_000L
    fun observe(snapshot: CanarySnapshot, elapsedMs: Long): CanaryTelemetry {
        if (elapsedMs < summary.elapsedMs || elapsedMs < 0) return snapshot()
        summary = summary.copy(
            elapsedMs = elapsedMs,
            ramReadyObserved = summary.ramReadyObserved || snapshot.ramReady,
            maximumHistorySeconds = maxOf(summary.maximumHistorySeconds, snapshot.historySeconds),
            peakPssKiB = maxOf(summary.peakPssKiB, snapshot.processPssKiB),
            peakThermal = listOfNotNull(summary.peakThermal, snapshot.thermalStatus).maxOrNull(),
        )
        if (elapsedMs - lastSampleMs >= 5_000 || !snapshot.running) {
            if (samples.size == capacity) samples.removeFirst()
            samples.addLast(CanaryTelemetryPoint(elapsedMs, snapshot.processPssKiB, snapshot.thermalStatus,
                snapshot.historySeconds, snapshot.encodedLiveBytes, snapshot.actualFps,
                snapshot.encodedPayloadLiveBytes, snapshot.actualBitrateBps, snapshot.completedHistorySeconds,
                snapshot.budgetEvictedGops, snapshot.droppedGops))
            lastSampleMs = elapsedMs
        }
        return snapshot()
    }
    fun snapshot() = summary.copy(samples = samples.toList())
}

@Serializable
enum class CanaryEvidenceKind { RAM, USB }

@Serializable
data class CanaryEvidenceRecord(
    val id: String,
    val kind: CanaryEvidenceKind,
    val build: String,
    val sdk: Int,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val ram: CanarySnapshot? = null,
    val usb: UsbCanaryJobSnapshot? = null,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        require((kind == CanaryEvidenceKind.RAM && ram != null && usb == null && ram.runId == id) ||
            (kind == CanaryEvidenceKind.USB && usb != null && ram == null && usb.jobId == id))
    }
    val key get() = "${kind.name}:$id"
}

@Serializable
data class CanaryEvidenceArchive(
    val schemaVersion: Int = 1,
    val records: List<CanaryEvidenceRecord> = emptyList(),
    val evictedRecords: Long = 0,
) {
    init {
        require(schemaVersion == 1 && records.size <= MAX_RECORDS && evictedRecords >= 0)
        require(records.map { it.key }.distinct().size == records.size)
    }
    fun append(record: CanaryEvidenceRecord): CanaryEvidenceArchive {
        val existing = records.firstOrNull { it.key == record.key }
        // A delayed START or a second completion may not rewrite a completed observation.
        if (existing?.finishedAtEpochMs != null) return this
        val next = records.filterNot { it.key == record.key } + record
        return copy(records = next.takeLast(MAX_RECORDS), evictedRecords = evictedRecords + (next.size - MAX_RECORDS).coerceAtLeast(0))
    }
    companion object { const val MAX_RECORDS = 24 }
}

@Serializable
enum class CanaryCheckStatus { PASS, FAIL, PENDING }
@Serializable
data class CanaryCheck(val code: String, val status: CanaryCheckStatus, val explanation: String)
@Serializable
data class CanaryAssessment(
    val checks: List<CanaryCheck>,
    val hardwareAcceptance: String = "PENDING_VEHICLE_REVIEW",
)

/** Interprets recorded measurements only. This has no authority to enable a feature. */
object CanaryEvidenceEvaluator {
    fun ram(value: CanarySnapshot): CanaryAssessment {
        val checks = mutableListOf<CanaryCheck>()
        fun add(code: String, status: CanaryCheckStatus, text: String) { checks += CanaryCheck(code, status, text) }
        val frames = value.outputFrames > 1
        val pending = CanaryCheckStatus.PENDING
        val pass = CanaryCheckStatus.PASS
        val fail = CanaryCheckStatus.FAIL
        val target = value.historyTargetSeconds
        val knownBudget = value.encodedBudgetBytes in setOf(64L * 1024 * 1024,
            com.dante.zeekrcapabilitylab.sentry.EncodedBufferPool.CANARY_CAPACITY_BYTES.toLong())
        add("ENCODED_BUDGET", when {
            value.encodedLiveBytes < 0 || value.encodedHighWaterBytes < value.encodedLiveBytes -> fail
            value.encodedCapacityBytes == 0L -> pending
            !knownBudget || value.encodedCapacityBytes != value.encodedBudgetBytes || value.encodedHighWaterBytes > value.encodedCapacityBytes -> fail
            frames -> pass
            else -> pending
        }, "检查本轮 ${value.encodedBudgetBytes / 1048576} MiB 编码池预算；不代表整个进程内存上限。")
        add("RAM_HISTORY", when {
            target !in setOf(30, CanaryRamPolicy.TARGET_SECONDS) -> fail
            value.historyTargetTimedOut -> fail
            value.telemetry?.ramReadyObserved == true && value.telemetry.maximumHistorySeconds >= target -> pass
            value.ramReady && value.historySeconds >= target -> pass
            value.stopReason == "RAM_HISTORY_TARGET_NOT_REACHED" -> fail
            else -> pending
        }, if (value.historyTargetTimedOut) "启动 ${value.historyStartupDeadlineSeconds} 秒时仍未达到 $target 秒连续历史。本轮未通过；有可用历史时可继续保存诊断短片。"
            else "需要实际观察到 RAM 就绪且历史达到 $target 秒；可保存诊断短片不代表本项通过。早停或旧版缺少观测时待验证。")
        add("OUTPUT_MEASURED", when {
            !value.actualFps.isFinite() || value.actualFps < 0 || value.actualBitrateBps < 0 -> fail
            frames && value.actualFps > 0 && value.actualBitrateBps > 0 && value.largestSyncIntervalUs > 0 -> pass
            else -> pending
        }, "记录实际帧率、码率和关键帧间隔，画质与候选参数仍需实车评估。")
        add("GOP_CONTINUITY", when { value.droppedGops != 0L -> fail; frames -> pass; else -> pending },
            "丢弃过 GOP 的运行不能按连续覆盖验收。")
        add("SHUTDOWN_RELEASE", when {
            value.running || !frames -> pending
            value.phase == "STOPPED" && value.encodedLiveBytes == 0L &&
                (value.bufferStorage != "ANONYMOUS_SHARED_MEMORY" || value.bufferStorageReleased) -> pass
            else -> fail
        }, "结束报告需证明停止且编码引用归零；运行中或未留下结束报告时待验证。")
        if (value.stopReason != null && value.stopReason !in setOf("MANUAL_STOP", "CANARY_TWO_HOUR_LIMIT")) {
            add("RUN_END_REASON", fail, "停止原因：${value.stopReason}")
        }
        val clips = (value.clips + listOfNotNull(value.clip)).distinctBy { it.fileName ?: it.reason }
        if (clips.isEmpty()) add("MANUAL_CLIP", pending, "尚无手动短片。")
        clips.forEachIndexed { index, clip ->
            val verified = clip.containerVerified && clip.decodedFrameVerified && clip.byteCount > 0 &&
                clip.sha256?.matches(Regex("[0-9a-fA-F]{64}")) == true
            add("CLIP_${index + 1}", if (verified && !clip.partial && clip.preRollAchievedUs >= target * 1_000_000L &&
                clip.postRollAchievedUs >= 10_000_000) pass else fail,
                "短片 ${index + 1}：${clip.reason}；前 ${clip.preRollAchievedUs / 1_000_000.0} 秒 / 后 ${clip.postRollAchievedUs / 1_000_000.0} 秒；partial=${clip.partial}。")
        }
        add("CLOCK_CALIBRATION", pending, "当前回调到达时间只是估计，尚未完成时钟校准。")
        add("FULL_PLAYBACK", pending, "首帧解码不证明全片连续可播；需核对车机和 Companion 完整播放。")
        add("NORMAL_REGRESSION", pending, "普通/延时录像回归需要实车确认。")
        value.evidenceArchiveError?.let { add("ARCHIVE_WRITE", fail, "归档失败：$it；请另行导出当前报告。") }
        return CanaryAssessment(checks)
    }

    fun usb(value: UsbCanaryJobSnapshot): CanaryAssessment {
        val result = value.result
        val verified = result?.verification
        val checks = mutableListOf<CanaryCheck>()
        val status = when {
            value.busy || result == null || value.operation != "RUN" -> CanaryCheckStatus.PENDING
            result.passed && result.phase == com.dante.zeekrcapabilitylab.sentry.UsbCanaryPhase.PUBLISHED &&
                result.stopDurationMs?.let { it in 0..5_000 } == true && verified != null && verified.decodable &&
                verified.samples > 1 && verified.bytes > 0 && verified.longestWriteSampleMs in 0..500 &&
                verified.sha256.matches(Regex("[0-9a-fA-F]{64}")) -> CanaryCheckStatus.PASS
            else -> CanaryCheckStatus.FAIL
        }
        checks += CanaryCheck("USB_FD_CANARY", status,
            "${value.operation}：${result?.reason ?: "未完成"}。只有完整 RUN 可用于本项；恢复或清理不会替代原始测试结果。")
        checks += CanaryCheck("USB_INTERRUPTION_MATRIX", CanaryCheckStatus.PENDING, "真实进程死亡、拔盘与重挂载各阶段仍需硬件验证。")
        value.evidenceArchiveError?.let { checks += CanaryCheck("ARCHIVE_WRITE", CanaryCheckStatus.FAIL, "归档失败：$it") }
        return CanaryAssessment(checks)
    }
    fun record(value: CanaryEvidenceRecord) = value.ram?.let(::ram) ?: usb(value.usb!!)
    fun text(value: CanaryAssessment) = value.checks.joinToString("\n") {
        val label = when (it.status) { CanaryCheckStatus.PASS -> "已观察通过"; CanaryCheckStatus.FAIL -> "未通过"; CanaryCheckStatus.PENDING -> "待验证" }
        "[$label] ${it.code}：${it.explanation}"
    }
}
