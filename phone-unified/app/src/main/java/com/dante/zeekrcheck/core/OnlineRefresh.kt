package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import java.time.Instant
import java.util.UUID

enum class PresenceType(val wire: Int) { ENTER(1), EXIT(2), POLL(3) }

/** Explicit, narrow route; general read and vehicle-control allowlists remain unchanged. */
class PresenceHeartbeat(val type: PresenceType, private val deviceId: String, private val at: Long) {
    init { require(UUID.fromString(deviceId).toString() == deviceId); require(at > 0) }
    fun body() = buildJsonObject {
        put("hbType", type.wire); put("deviceId", deviceId); put("deviceType", 1); put("ts", at)
    }.toString()
    fun allows(request: Request): Boolean = runCatching {
        if (request.method != "POST" || request.url != URL || request.header("x-device-id") != deviceId) return false
        val buffer = Buffer(); request.body?.writeTo(buffer)
        buffer.size in 1..1024 && Json.parseToJsonElement(buffer.readUtf8()) == Json.parseToJsonElement(body())
    }.getOrDefault(false)
    override fun toString() = "PresenceHeartbeat(${type.name})"
    companion object { val URL = (RequestPolicy.TSP_BASE + "ms-app-online-manager/api/v1.0/app/hb").toHttpUrl() }
}

data class PresenceLease(val identity: String, val vehicleKey: String, val started: Long)
interface PresenceLeaseStorage {
    fun load(): PresenceLease?
    fun save(lease: PresenceLease)
    fun clear(lease: PresenceLease)
}
internal object PresenceSessions { val mutex = Mutex() }

data class OnlineRefreshResult(val probe: Probe, val message: String,
    val outcome: ProbeOutcome = ProbeOutcome.SUCCESS, val exitConfirmed: Boolean = true)

/** Group update time proves an upload, never an independently verified thermometer sample. */
class OnlineRefreshFlow(
    private val send: suspend (PresenceType) -> Long?,
    private val read: suspend () -> Probe,
    private val now: () -> Instant = Instant::now,
    private val current: () -> Boolean = { true },
    private val publish: (Probe) -> Unit = {},
    private val progress: (String) -> Unit = {},
    private val beforeEnter: () -> Unit = {},
    private val afterExit: () -> Unit = {},
) {
    private class Superseded : Exception()
    suspend fun run(baseline: Probe): OnlineRefreshResult {
        var latest = baseline
        var message = "已查询云端 · 车辆暂无新上报"
        var outcome = ProbeOutcome.SUCCESS
        var entered = false
        var exited = true
        val requestedAt = now()
        val old = ClimateSnapshot.parse(baseline)
        fun checkCurrent() { if (!current()) throw Superseded() }
        try {
            checkCurrent()
            beforeEnter()
            entered = true // An interrupted ENTER can still have reached the server.
            progress("正在获取新车况…")
            val completed = withTimeoutOrNull(45_000) {
                send(PresenceType.ENTER)
                checkCurrent()
                send(PresenceType.POLL)
                progress("等待车辆新上报…")
                repeat(2) {
                    repeat(20) { delay(1_000); checkCurrent() }
                    send(PresenceType.POLL)
                    checkCurrent()
                    val next = read()
                    if (next.outcome != ProbeOutcome.SUCCESS) throw CheckFailure(next.outcome)
                    latest = next
                    publish(next)
                    val climate = ClimateSnapshot.parse(next)
                    val source = climate.sourceTime
                    if (climate.cabinTemperature != null && source != null &&
                        !source.isBefore(requestedAt) && !source.isAfter(now().plusSeconds(30)) &&
                        (old.sourceTime == null || source.isAfter(old.sourceTime))) {
                        message = "已收到新车况上报" + if (climate.cabinTemperature == old.cabinTemperature) " · 温度数值未变" else ""
                        return@withTimeoutOrNull true
                    }
                    progress("等待车辆新上报…")
                }
                true
            }
            if (completed == null) message = "等待新上报已结束 · 保留最新可用记录"
        } catch (_: Superseded) {
            outcome = ProbeOutcome.CANCELLED
            message = "刷新已结束 · 将使用车辆操作回传"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            outcome = (e as? CheckFailure)?.outcome ?: ProbeOutcome.NETWORK
            message = "新车况暂未取到 · 保留云端记录"
        } finally {
            if (entered) withContext(NonCancellable) {
                runCatching { progress("正在结束本次刷新…") }
                exited = false
                repeat(2) {
                    if (!exited) exited = withTimeoutOrNull(4_000) {
                        try { send(PresenceType.EXIT); afterExit(); true }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { false }
                    } == true
                }
            }
        }
        if (!exited) message += " · 在线连接待收尾"
        return OnlineRefreshResult(latest, message, outcome, exited)
    }

    companion object {
        val progressMessages = setOf("正在获取新车况…", "等待车辆新上报…", "正在结束本次刷新…")
        fun needed(baseline: Probe, now: Instant): Boolean {
            if (baseline.outcome != ProbeOutcome.SUCCESS) return false
            val source = ClimateSnapshot.parse(baseline).sourceTime ?: return true
            return now.toEpochMilli() - source.toEpochMilli() !in -30_000..60_000
        }
    }
}
