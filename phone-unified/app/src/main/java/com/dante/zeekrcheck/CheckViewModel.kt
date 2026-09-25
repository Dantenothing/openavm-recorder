package com.dante.zeekrcheck

import android.os.Build
import android.os.SystemClock
import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

data class ClimateUiState(
    val enabled: Boolean = false,
    val refreshing: Boolean = false,
    val snapshot: ClimateSnapshot? = null,
    val queue: ClimateQueueState = ClimateQueueState(),
    val requestAttempts: Int = 0,
    val message: String? = null,
)

data class CheckUiState(
    val configReady: Boolean = false,
    val configSaved: Boolean = false,
    val sessionSaved: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val stage: String = "登录极氪账号，连接你的车辆",
    val message: String? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val selected: Int = 0,
    val report: Report? = null,
)

class CheckViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(CheckUiState(busy = true, stage = "正在读取本机连接配置"))
    val state = mutable.asStateFlow()
    private var config: ProtocolConfig? = null
    private var client: CloudClient? = null
    private var job: Job? = null
    private var generation = 0
    private val store = SecureConfigStore(application)
    private val sessions = AppSessions.get(application)
    private val overview = OverviewStore.get(application)
    val assistant = AssistantStore.get(application)
    private val mutableOperating = MutableStateFlow(false)
    val operating = mutableOperating.asStateFlow()
    private var controlJob: Job? = null
    private var lastHorn = 0L
    private var preparationReading=false
    private var temperatureSending = false
    private var temperatureWorker = false
    private var climateAfterTemperature: ClimateTarget? = null
    private var preparationProbe: Probe? = null
    private var locationEvidence: JsonObject = buildJsonObject { put("read", false) }
    private var installationId = UUID.randomUUID().toString()
    private val mutableClimate = MutableStateFlow(ClimateUiState())
    val climateState = mutableClimate.asStateFlow()
    private var climateQueue: ClimateQueue? = null
    private var climateCollector: Job? = null
    private var climateRead: Job? = null
    private var climateGeneration = 0
    private val overviewReads = OverviewReadCoordinator<Report?>()

    private fun clearClimate() {
        ++climateGeneration
        climateQueue?.close(); climateQueue = null
        climateCollector?.cancel(); climateRead?.cancel()
        mutableClimate.value = ClimateUiState()
    }
    private fun bindClimate() {
        clearClimate()
        val active = client ?: return
        val vehicle = mutable.value.vehicles.getOrNull(mutable.value.selected) ?: return
        overview.select(vehicle)
        val epoch = climateGeneration
        val queue = ClimateQueue(viewModelScope) { target, accepted ->
            try {
                assistant.edit { it.copy(operationPending = true, pendingBodyAction = null, operationMessage = "正在发送：${target.label}") }
                val result = active.controlClimate(vehicle, target, {
                    accepted()
                    if (epoch == climateGeneration) mutableClimate.update { it.copy(requestAttempts = active.climateRequestAttempts) }
                }) { snapshot ->
                    if (epoch == climateGeneration) { mutableClimate.update { it.copy(snapshot = snapshot) }; overview.publishClimate(vehicle, snapshot) }
                }
                if (epoch == climateGeneration) {
                    assistant.edit { it.copy(operationPending = result == ClimateResult.UNKNOWN,
                        operationMessage = "${target.label} · ${when (result) { ClimateResult.MATCHED -> "车况已确认"; ClimateResult.REJECTED -> "未受理"; ClimateResult.NEEDS_CHECK -> "已受理 · 设定温度不回传"; else -> "实际结果待核实" }}") }
                    assistant.record(target.label, result.name)
                }
                result
            } finally { if (epoch == climateGeneration) mutableClimate.update { it.copy(requestAttempts = active.climateRequestAttempts) } }
        }
        climateQueue = queue
        climateCollector = viewModelScope.launch { queue.state.collect { value ->
            if (epoch == climateGeneration) mutableClimate.update { it.copy(queue = value) }
        } }
    }
    fun enableClimate(enabled: Boolean) {
        if (!mutable.value.connected || mutable.value.busy) return
        if (!enabled) climateQueue?.discardPending()
        mutableClimate.update { it.copy(enabled = enabled, message = null) }
        if (enabled && mutableClimate.value.snapshot == null) refreshClimate()
    }
    fun climateTarget(target: ClimateTarget) {
        if (mutableClimate.value.enabled && mutable.value.connected && finishTemperatureBeforeClimate()) {
            climateAfterTemperature = target
            return
        }
        if (!mutableClimate.value.enabled || mutable.value.busy || !mutable.value.connected || mutableOperating.value || (assistant.state.value.operationPending && !mutableClimate.value.queue.active)) return
        assistant.state.value.activePreparation?.takeIf { it.thermal!=null && !it.finished && it.vehicleKey==overview.state.value.vehicleKey }?.let {
            assistant.updatePreparation(it,ComfortPolicy.handover(it,System.currentTimeMillis(),"已手动调节舒适功能 · 已退出自动调节"))
        }
        if (climateQueue?.submit(target) == false) mutableClimate.update { it.copy(message = "上一轮结果尚未确认，请核对后再开始。") }
    }
    fun refreshClimate() {
        if (mutable.value.busy || !mutable.value.connected || mutableClimate.value.refreshing) return
        val active = client ?: return
        val vehicle = mutable.value.vehicles.getOrNull(mutable.value.selected) ?: return
        val epoch = climateGeneration
        mutableClimate.update { it.copy(refreshing = true, message = null) }
        climateRead = viewModelScope.launch {
            try { val snapshot = active.climate(vehicle); if (epoch == climateGeneration) { mutableClimate.update { it.copy(snapshot = snapshot) }; overview.publishClimate(vehicle, snapshot) } }
            catch (e: CancellationException) { throw e }
            catch (e: CheckFailure) { if (epoch == climateGeneration) mutableClimate.update { it.copy(message = "读取空调状态：${e.outcome.label}。") } }
            catch (_: Exception) { if (epoch == climateGeneration) mutableClimate.update { it.copy(message = "空调状态格式尚需核对。") } }
            finally { if (epoch == climateGeneration) mutableClimate.update { it.copy(refreshing = false) } }
        }
    }
    fun cancelPendingClimate() { climateQueue?.discardPending() }
    fun acknowledgeClimate() {
        climateQueue?.acknowledgeOutcome(); mutableClimate.update { it.copy(message = null) }
        if (!mutableOperating.value && !mutableClimate.value.queue.active) assistant.edit { it.copy(operationPending = false) }
        refreshClimate()
    }
    fun onBackground() {
        climateQueue?.discardPending()
        mutableClimate.update { it.copy(enabled = false) }
    }
    fun climateExport(): JsonObject = buildJsonObject {
        val climate = mutableClimate.value
        put("requestAttempts", client?.climateRequestAttempts ?: climate.requestAttempts)
        put("vehicleRequestAttempts", client?.vehicleRequestAttempts ?: 0)
        put("locationEvidence", locationEvidence)
        putJsonObject("background") {
            put("widgetSyncEnabled", assistant.state.value.widgetSyncEnabled)
            put("guardEnabled", assistant.state.value.guardEnabled); put("rulesPaused", assistant.state.value.paused)
            put("parkingPhase", assistant.state.value.parkingGuard.phase.name)
            put("parkingPaused", assistant.state.value.parkingGuard.paused)
            put("bluetoothBound", assistant.state.value.carBluetoothAddress.isNotBlank())
            put("sync", overview.state.value.sync.json())
        }
        put("queue", climate.queue.export())
        put("operationPending", assistant.state.value.operationPending)
        assistant.state.value.activePreparation?.let { session ->
            putJsonObject("preparation") {
                put("phase",PreparationProgress.phase(session,System.currentTimeMillis()).name)
                put("finished",session.finished); put("stopRequested",session.stopRequested)
                put("target",session.preferences.target); put("started",session.started); put("deadline",session.deadline)
                put("resumable",PreparationProgress.resumable(session,System.currentTimeMillis()))
                put("endedAt",session.endedAt); put("buttonIdle",PreparationProgress.idle(session,System.currentTimeMillis()))
                put("initialTemperature",session.initialTemperature?.let(::JsonPrimitive) ?: JsonNull)
                put("lastTemperature",session.lastTemperature?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSource",session.lastSource?.let(::JsonPrimitive) ?: JsonNull)
                put("remoteRunningObserved",session.remoteRunningObserved)
                put("airReady",session.thermal?.airReady ?: false)
                put("minutes",session.preferences.minutes)
            }
        }
        climate.snapshot?.let { put("readback", it.export()) }
        put("positionMapping", "SV.11 / SV.19; AU physical seat correspondence requires first-car confirmation")
    }
    fun sessionDiagnostic(): JsonObject = buildJsonObject {
        put("saved", mutable.value.sessionSaved)
        put("passwordLogins", client?.passwordLogins ?: 0)
        put("restoredChecks", client?.restoredChecks ?: 0)
        put("renewalAttempts", client?.renewalAttempts ?: 0)
    }

    private fun newClient(configuration: ProtocolConfig, epoch: Int, saved: SavedSession? = null) = CloudClient(
        configuration, phone = PhoneIdentity(Build.BRAND, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
        restoredSession = saved, deviceId = saved?.deviceId ?: installationId,
        persistentSession = sessions, persistenceEpoch = epoch,
        presenceStorage = OnlinePresenceStore(getApplication()),
        sessionChanged = { value ->
            try {
                if (sessions.write(epoch, value)) mutable.update { it.copy(sessionSaved = value != null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (sessions.current() == epoch) mutable.update { it.copy(sessionSaved = false,
                    message = "本机会话保存失败；本次连接仍可使用，但重开 App 可能需要重新登录。") }
            }
        })

    init {
        assistant.recoverInterrupted()
        job = viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val identity = application.getSharedPreferences("installation", 0)
                    val storedId = identity.getString("deviceId", null)
                    if (storedId != null && runCatching { UUID.fromString(storedId).toString() == storedId }.getOrDefault(false)) {
                        installationId = storedId
                    } else check(identity.edit().putString("deviceId", installationId).commit())
                    val debug = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    val pending = if (debug) PendingProtocol.consume(application.filesDir) else null
                    if (pending != null) { sessions.write(sessions.advance(), null); store.save(pending); pending } else store.loadOrDefault()
                }
                if (text != null) {
                    config = ProtocolConfig.parse(text)
                    mutable.value = CheckUiState(configReady = true, configSaved = true, busy = true, stage = "正在读取本机登录状态")
                    restoreSaved(sessions.current())
                } else mutable.value = CheckUiState()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = CheckUiState(message = "连接配置未能载入，请重启应用；仍有问题可在高级连接设置中恢复默认配置。")
            }
        }
    }

    fun reconnect() {
        if (mutable.value.busy || config == null || mutableClimate.value.queue.active) return
        discardRuntime()
        val epoch = sessions.advance()
        mutable.update { it.copy(busy = true, connected = false, report = null, vehicles = emptyList(), stage = "正在恢复登录", message = null) }
        job = viewModelScope.launch { restoreSaved(epoch) }
    }

    private suspend fun restoreSaved(epoch: Int) {
        try {
            val saved = try { sessions.load() } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                sessions.write(epoch, null)
                mutable.update { it.copy(sessionSaved = false, stage = "需要重新登录", message = "本机会话未能解密，连接配置已保留。") }
                return
            }
            if (saved == null) {
                mutable.update { it.copy(sessionSaved = false, stage = "连接配置已就绪", message = "登录后会加密保存会话，下次自动恢复；密码不会保存。") }
                return
            }
            if (!saved.matches(config ?: return)) {
                sessions.write(epoch, null)
                mutable.update { it.copy(sessionSaved = false, stage = "需要重新登录", message = "连接配置已变化，请登录以建立新的会话。") }
                return
            }
            val active = newClient(config ?: return, epoch, saved)
            client = active
            mutable.update { it.copy(sessionSaved = true, stage = "正在验证已保存的登录") }
            val vehicles = withTimeout(90_000) { active.resumeSession() }
            if (sessions.current() == epoch) {
                val remembered = vehicles.indexOfFirst { VehicleOverview.key(it) == overview.state.value.vehicleKey }.coerceAtLeast(0)
                mutable.update { it.copy(connected = true, vehicles = vehicles, selected = remembered, stage = "已恢复登录",
                    message = if (vehicles.isEmpty()) "会话有效，当前没有返回可访问车辆。" else "已复用本机登录状态，找到 ${vehicles.size} 辆车。") }
                bindClimate()
            }
        } catch (_: TimeoutCancellationException) { restoreFailed(epoch, "连接超时") }
        catch (e: CancellationException) { restoreFailed(epoch, "已停止连接"); throw e }
        catch (e: CheckFailure) { restoreFailed(epoch, e.outcome.label) }
        catch (_: Exception) { restoreFailed(epoch, "会话恢复未完成") }
        finally { if (sessions.current() == epoch) mutable.update { it.copy(busy = false) } }
    }

    private fun restoreFailed(epoch: Int, reason: String) {
        if (sessions.current() == epoch) mutable.update { it.copy(connected = false, stage = "尚未连接车辆服务",
            message = if (it.sessionSaved) "$reason。已保留加密会话，可以稍后点‘重新连接’，无需立即输入密码。"
                else "$reason。服务端已不再接受保存的登录，请重新登录。") }
    }

    fun importConfig(text: String) {
        if (mutable.value.busy) return
        try {
            val parsed = ProtocolConfig.parse(text)
            overview.clear()
            discardRuntime()
            val sessionEpoch = sessions.advance()
            config = parsed
            mutable.value = CheckUiState(configReady = true, busy = true, stage = "正在加密保存配置")
            job = viewModelScope.launch {
                val saved = try { sessions.write(sessionEpoch, null); withContext(Dispatchers.IO) { store.save(text) }; true } catch (_: Exception) { false }
                mutable.update { it.copy(busy = false, configSaved = saved, stage = "配置格式通过，等待登录",
                    message = if (saved) "配置已加密保存在本机，重启 App 后自动载入。密码不会保存。"
                    else "配置可在本次会话使用，但加密保存失败；重启后请重新导入。") }
            }
        } catch (e: IllegalArgumentException) {
            // Parser messages contain only fixed text and fixed field names, never supplied values.
            mutable.update { it.copy(message = e.message ?: "配置格式无效") }
        }
    }
    fun fileError() { mutable.update { it.copy(message = "文件无法读取，或文件超过 64 KB。请选择本地协议 JSON。") } }
    fun exportResult(success: Boolean) { mutable.update { it.copy(message = if (success) "诊断报告已保存（已去除身份凭据、VIN 和精确位置）。" else "报告未保存，请重新选择保存位置。") } }

    fun login(email: String, password: String) {
        if (mutable.value.busy) return
        val currentConfig = config ?: return
        if (!email.contains('@') || email.length > 254 || password.isBlank()) {
            mutable.update { it.copy(message = "请填写 guest 账号的邮箱和密码。") }; return
        }
        val epoch = ++generation
        val sessionEpoch = sessions.advance()
        overview.clear()
        clearClimate()
        client?.clearSession()
        val activeClient = newClient(currentConfig, sessionEpoch)
        client = activeClient
        mutable.value = CheckUiState(configReady = true, configSaved = mutable.value.configSaved, busy = true, stage = "正在连接")
        job = viewModelScope.launch {
            try {
                sessions.write(sessionEpoch, null)
                val vehicles = withTimeout(180_000) {
                    activeClient.login(email.trim(), password) { stage -> if (generation == epoch) mutable.update { it.copy(stage = stage) } }
                }
                if (generation == epoch) mutable.update { it.copy(connected = true, vehicles = vehicles,
                    stage = "登录完成", message = if (!it.sessionSaved) "登录成功，但加密保存会话失败；重开 App 可能需要重新登录。"
                    else if (vehicles.isEmpty()) "已保持登录；账号当前没有返回可访问车辆，请在原厂 App 核对共享有效期。"
                    else "已保持登录，找到 ${vehicles.size} 辆车。重开 App 会自动恢复。") }
                if (generation == epoch) bindClimate()
            } catch (_: TimeoutCancellationException) {
                failLogin(epoch, activeClient, "连接总时限已到，请稍后手动重试。")
            } catch (_: CancellationException) {
                failLogin(epoch, activeClient, "已停止连接。")
            } catch (e: CheckFailure) {
                failLogin(epoch, activeClient, e.outcome.label + "。可先核对协议配置；若需要验证码，请先在原厂 App 完成验证。")
            } catch (_: Exception) {
                failLogin(epoch, activeClient, "连接未完成，需要核对协议或响应格式。")
            } finally {
                if (generation == epoch) mutable.update { it.copy(busy = false) }
            }
        }
    }
    private fun failLogin(epoch: Int, activeClient: CloudClient, message: String) {
        activeClient.clearSession()
        if (generation == epoch) mutable.update { it.copy(connected = false, message = "${it.stage}：$message") }
    }
    fun selectVehicle(index: Int) {
        if (!mutable.value.busy && !mutableOperating.value && !mutableClimate.value.queue.active && index in mutable.value.vehicles.indices && index != mutable.value.selected) {
            mutable.update { it.copy(selected = index, report = null) }; bindClimate()
        }
    }
    fun checkVehicle() {
        val current = mutable.value
        if (current.busy || !current.connected || mutableClimate.value.queue.active || mutableOperating.value) {
            overview.edit { it.copy(message = if (!current.connected) "请先恢复保存的登录" else "已有任务处理中 · 稍后可刷新") }
            return
        }
        val vehicle = current.vehicles.getOrNull(current.selected) ?: return
        val activeClient = client ?: return
        val epoch = ++generation
        val report = Report(false, Instant.now(), null, vehicle.label, vehicle.roleFieldNames, emptyList())
        mutable.update { it.copy(busy = true, report = report, message = null) }
        job = viewModelScope.launch {
            try {
                withTimeout(180_000) {
                    for ((index, endpoint) in Endpoint.entries.withIndex()) {
                        mutable.update { it.copy(stage = "${index + 1}/${Endpoint.entries.size} · ${endpoint.title}") }
                        val probe = activeClient.probe(endpoint, vehicle)
                        if (generation == epoch) {
                            mutable.update { it.copy(report = it.report?.copy(probes = it.report.probes + probe)) }
                            mutable.value.report?.let { overview.publish(vehicle, it) }
                        }
                        if (generation == epoch && endpoint == Endpoint.STATUS && probe.outcome == ProbeOutcome.SUCCESS) {
                            mutableClimate.update { it.copy(snapshot = ClimateSnapshot.parse(probe)) }
                            updateLocation(probe)
                        }
                        if (probe.outcome == ProbeOutcome.AUTH_REQUIRED || probe.outcome == ProbeOutcome.RATE_LIMITED) {
                            if (probe.outcome == ProbeOutcome.AUTH_REQUIRED) {
                                activeClient.clearSession()
                                mutable.update { it.copy(connected = false) }
                            }
                            markRemaining(probe.outcome)
                            break
                        }
                    }
                }
                if (generation == epoch) mutable.update { it.copy(stage = "本轮检查结束", message = "请按每项证据判断；接口失败或空字段不等于账号无权限。") }
            } catch (_: TimeoutCancellationException) {
                if (generation == epoch) { markRemaining(ProbeOutcome.TIMEOUT); mutable.update { it.copy(stage = "检查超时", message = "已保留完成的检查结果。") } }
            } catch (_: CancellationException) {
                if (generation == epoch) { markRemaining(ProbeOutcome.CANCELLED); mutable.update { it.copy(stage = "检查已停止", message = "已保留完成的检查结果。") } }
            } catch (_: Exception) {
                if (generation == epoch) { markRemaining(ProbeOutcome.INVALID_RESPONSE); mutable.update { it.copy(stage = "检查中断", message = "需要核对本车返回的数据格式。") } }
            } finally {
                if (generation == epoch) mutable.update { it.copy(busy = false, report = it.report?.copy(completedAt = Instant.now())) }
            }
        }
    }
    private fun markRemaining(outcome: ProbeOutcome) {
        mutable.update { state -> state.copy(report = state.report?.let { report ->
            val already = report.probes.map { it.endpoint }.toSet()
            report.copy(probes = report.probes + Endpoint.entries.filterNot { it in already }.map { Probe(it, outcome, Instant.now(), attempted = false) })
        }) }
    }
    fun refreshOverview() {
        job = viewModelScope.launch { syncOverview(SyncReason.WIDGET, interactive = true) }
    }

    /** Quick refresh only reads cloud state. Explicit temperature tasks own any online/AC work. */
    suspend fun syncOverview(reason: SyncReason, interactive: Boolean = false): Report? {
        val requestedKey = "${sessions.current()}/${overview.epoch()}"
        fun stillRequested() = requestedKey == "${sessions.current()}/${overview.epoch()}"
        return overviewReads.read(requestedKey, interactive && reason == SyncReason.WIDGET) {
            if (!stillRequested()) return@read null
            if (withTimeoutOrNull(65_000) { state.first { !it.busy } } == null) {
                if (!stillRequested()) return@read null
                overview.edit { it.copy(message = "连接尚未完成 · 本次查询超时", sync = it.sync.started(System.currentTimeMillis(), reason)
                    .finished(System.currentTimeMillis(), ProbeOutcome.TIMEOUT, "连接尚未完成 · 本次查询超时")) }
                return@read null
            }
            if (!stillRequested()) return@read null
            if (!state.value.connected && state.value.sessionSaved && config != null) {
                mutable.update { it.copy(busy = true) }
                restoreSaved(sessions.current())
            }
            if (!stillRequested()) return@read null
            val vehicle = state.value.vehicles.getOrNull(state.value.selected)
            val active = client
            if (!state.value.connected || vehicle == null || active == null) {
                overview.edit { it.copy(sync = it.sync.started(System.currentTimeMillis(), reason)
                    .finished(System.currentTimeMillis(), ProbeOutcome.AUTH_REQUIRED, "尚未连接 · 请打开应用恢复登录")) }
                return@read null
            }
            val now = System.currentTimeMillis()
            if (!overview.state.value.sync.permits(now, interactive)) {
                if (interactive) overview.edit { it.copy(message = "服务暂时限流 · 请稍后再刷新") }
                return@read state.value.report?.takeIf { !it.demo && it.completedAt?.let { t -> now - t.toEpochMilli() in 0..60_000 } == true }
            }
            if ((mutableOperating.value && !temperatureSending) || mutableClimate.value.queue.active || preparationReading) {
                if (interactive) overview.edit { it.copy(message = "正在处理车辆操作 · 将使用操作回传更新") }
                return@read null
            }
            val epoch = generation; val cacheEpoch = overview.epoch()
            val previousTemperature = overview.state.value.readings["cabin_temperature"]
            overview.edit { it.copy(refreshingAt = now, message = if (interactive) null else it.message, sync = it.sync.started(now, reason)) }
            val probes = mutableListOf<Probe>()
            val started = Instant.now()
            fun publish(probe: Probe) {
                if (generation != epoch || cacheEpoch != overview.epoch()) return
                probes.removeAll { it.endpoint == probe.endpoint }
                probes.add(probe)
                val report = Report(false, started, Instant.now(), "selected", emptyList(), probes.toList())
                mutable.update { it.copy(report = report) }
                overview.publish(vehicle, report, cacheEpoch)
                if (probe.endpoint == Endpoint.STATUS) updateLocation(probe)
                if (probe.endpoint == Endpoint.STATUS && probe.outcome == ProbeOutcome.SUCCESS)
                    mutableClimate.update { it.copy(snapshot = ClimateSnapshot.parse(probe)) }
            }
            try {
                withTimeout(65_000) {
                    for (endpoint in listOf(Endpoint.STATUS, Endpoint.SENTRY)) {
                        val probe = active.probe(endpoint, vehicle)
                        if (generation != epoch || cacheEpoch != overview.epoch()) break
                        publish(probe)
                        if (probe.outcome == ProbeOutcome.AUTH_REQUIRED) mutable.update { it.copy(connected = false) }
                        if (probe.outcome in listOf(ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.RATE_LIMITED, ProbeOutcome.REJECTED)) break
                    }
                }
                if (epoch != generation || cacheEpoch != overview.epoch()) return@read null
                val failure = probes.firstOrNull { it.outcome != ProbeOutcome.SUCCESS }
                val outcome = failure?.outcome ?: if (probes.size == 2) ProbeOutcome.SUCCESS else ProbeOutcome.CANCELLED
                overview.edit { old ->
                    val result = failure?.let { "${it.endpoint.title}：${it.outcome.label} · 保留可用记录" }
                        ?: old.copy(message = null).refreshResult(previousTemperature, Instant.now())
                    old.copy(message = if (interactive) result + if (old.pendingBody != null) " · 操作结果仍待回传" else "" else old.message,
                        sync = old.sync.finished(System.currentTimeMillis(), outcome, result))
                }
                Report(false, started, Instant.now(), "selected", emptyList(), probes.toList())
            } catch (_: TimeoutCancellationException) {
                if (epoch == generation && cacheEpoch == overview.epoch()) overview.edit { it.copy(
                    sync = it.sync.finished(System.currentTimeMillis(), ProbeOutcome.TIMEOUT, "查询超时 · 保留上次记录"), message = "查询超时 · 保留上次记录") }
                null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (epoch == generation && cacheEpoch == overview.epoch()) overview.edit { it.copy(
                    sync = it.sync.finished(System.currentTimeMillis(), ProbeOutcome.NETWORK, "查询未完成 · 保留上次记录"), message = "查询未完成 · 保留上次记录") }
                null
            }
            finally {
                if (epoch == generation && cacheEpoch == overview.epoch()) overview.edit { it.copy(refreshingAt = null,
                    message = if (it.message in OnlineRefreshFlow.progressMessages) "本次刷新已结束 · 保留最新可用记录" else it.message,
                    sync = if (it.sync.completed < it.sync.started) it.sync.finished(System.currentTimeMillis(), ProbeOutcome.CANCELLED, "本次查询中断 · 等待下次检查") else it.sync) }
            }
        }
    }
    fun stop() { if (mutable.value.report != null || client != null) job?.cancel() }
    fun demo() {
        if (mutable.value.busy) return
        clearClimate()
        client?.clearSession(); client = null
        mutable.value = CheckUiState(configReady = config != null, configSaved = mutable.value.configSaved, sessionSaved = mutable.value.sessionSaved,
            stage = "示例报告 · 未连接车辆", message = if (mutable.value.sessionSaved) "登录状态仍保存在本机，点‘重新连接’可回到本车。" else null,
            report = Demo.report(Instant.now()))
    }
    private fun discardRuntime() {
        ++generation
        clearClimate()
        controlJob?.cancel(); controlJob = null
        job?.cancel(); job = null
        client?.clearSession(); client = null
    }
    fun reset() {
        if (mutable.value.busy) return
        discardRuntime()
        overview.clear()
        val epoch = sessions.advance()
        mutable.value = CheckUiState(configReady = config != null, configSaved = mutable.value.configSaved, busy = true, stage = "正在退出账号")
        job = viewModelScope.launch {
            val cleared = try { sessions.write(epoch, null) } catch (_: Exception) { false }
            mutable.update { it.copy(busy = false, stage = "已退出本机账号", message = if (cleared)
                "已删除本机保存的登录状态，连接配置保留。自行导出的文件不会被删除。"
                else "保存的登录状态未能完整删除，请在系统设置中清除本应用数据。") }
        }
    }
    fun forgetConfig() {
        if (mutable.value.busy) return
        discardRuntime(); config = null
        overview.clear()
        val epoch = sessions.advance()
        mutable.value = CheckUiState(busy = true, stage = "正在恢复默认连接配置")
        job = viewModelScope.launch {
            try {
                val bundled = withContext(Dispatchers.IO) { BundledProtocol.load(getApplication()) }
                sessions.write(epoch, null)
                withContext(Dispatchers.IO) { store.save(bundled) }
                config = ProtocolConfig.parse(bundled)
                mutable.value = CheckUiState(configReady = true, configSaved = true, stage = "连接配置已就绪",
                    message = "已恢复默认连接配置并退出登录。重新登录即可使用。")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = CheckUiState(message = "默认连接配置未能恢复，请重启或更新应用。")
            }
        }
    }
    override fun onCleared() { clearClimate(); client?.clearSession(); config = null; super.onCleared() }

    fun acknowledgeOperation() {
        if (mutableOperating.value || mutableClimate.value.queue.active) return
        overview.edit { it.copy(pendingBody = null) }
        assistant.edit { it.copy(operationPending = false, pendingBodyAction = null, operationMessage = "已核对；旧操作不会重发",
            activePreparation = PreparationControl.acknowledge(it.activePreparation)) }
        acknowledgeClimate()
    }

    fun bodyAction(action: BodyAction) {
        if (action == BodyAction.SENTRY_OFF) overview.state.value.vehicleKey?.let(assistant::pauseGuard)
        if (action == BodyAction.SENTRY_ON) overview.state.value.vehicleKey?.let(assistant::holdHomeGuard)
        if (action == BodyAction.HORN && System.currentTimeMillis() - lastHorn < 5_000) return
        if (!canOperate()) return
        if (action == BodyAction.HORN) lastHorn = System.currentTimeMillis()
        mutableOperating.value = true
        controlJob = viewModelScope.launch { try { sendCommand(VehicleCommand.Body(action)) } finally { mutableOperating.value = false } }
    }

    /** Shared by home-screen widgets and the in-app card. No Activity or choice dialog is required. */
    suspend fun cardAction(action: String, key: String, shown: String?, stillBound: () -> Boolean) {
        fun feedback(text: String) {
            if (overview.state.value.vehicleKey == key && stillBound()) {
                overview.edit { it.copy(message = text) }
                assistant.edit { it.copy(operationMessage = text) }
            }
        }
        if (action !in CardControl.actions || overview.state.value.vehicleKey != key || !stillBound()) return
        if (action == "prepare") {
            // Resolve the fixed button intent before any wait. A delayed stop must never start a new session.
            when (PreparationControl.decide(shown, assistant.state.value.activePreparation, key, System.currentTimeMillis())) {
                PreparationTap.STOP -> stopPreparation(assistant.state.value.activePreparation!!, stillBound)
                PreparationTap.START -> {
                    if (withTimeoutOrNull(15_000) { state.first { !it.busy } } == null) feedback("连接尚未完成 · 本次未发送操作")
                    else if (stillBound() && overview.state.value.vehicleKey == key &&
                        PreparationControl.decide(shown, assistant.state.value.activePreparation, key, System.currentTimeMillis()) == PreparationTap.START) prepareNow()
                }
                PreparationTap.OBSERVE -> {
                    feedback(if(assistant.state.value.activePreparation?.stopRequested==true) "停止操作已记录 · 未重复发送" else "正在核实备车状态 · 未发送新操作")
                    resumePreparation(); observePreparation()
                }
                PreparationTap.STALE -> feedback("备车状态已变化 · 已更新卡片，请重新选择")
            }
            return
        }
        val manualOff = action == "guard" && shown == "开启"
        val manualOn = action == "guard" && shown == "关闭"
        if (manualOff) assistant.pauseGuard(key)
        if (manualOn) assistant.holdHomeGuard(key)
        if (withTimeoutOrNull(15_000) { state.first { !it.busy } } == null) { feedback("连接尚未完成 · 本次未发送操作"); return }
        if (withTimeoutOrNull(70_000) { overview.state.first { !it.refreshing() }; if (manualOff || manualOn) operating.first { !it } } == null) {
            feedback(if (manualOff) "本次自动守护已暂停 · 关闭请求尚未发送" else "车况仍在读取 · 本次未发送操作"); return
        }
        if (overview.state.value.vehicleKey != key || !stillBound()) return
        if (!canOperate()) { feedback(assistant.state.value.operationMessage); return }
        if (action == "find" && System.currentTimeMillis() - lastHorn < 5_000) return
        val active = client ?: return
        val vehicle = state.value.vehicles.getOrNull(state.value.selected) ?: return
        val epoch = generation; val cacheEpoch = overview.epoch()
        mutableOperating.value = true
        var attempted: BodyAction? = null
        try {
            feedback(if (action == "find") "正在发送找车指令…" else "正在核对车况…")
            CardControl.run(action, shown,
                fetch = { endpoint -> withTimeout(20_000) { active.probe(endpoint, vehicle) { feedback("连接已断开 · 正在重新连接…") } } },
                publish = { probe -> if (epoch == generation && stillBound()) overview.publish(vehicle,
                    Report(false, probe.fetchedAt, probe.fetchedAt, "selected", emptyList(), listOf(probe)), cacheEpoch) },
                send = { target ->
                    if (epoch == generation && overview.state.value.vehicleKey == key && stillBound()) {
                        if (target == BodyAction.HORN) lastHorn = System.currentTimeMillis()
                        attempted = target
                        sendCommand(VehicleCommand.Body(target))
                    }
                }, feedback = ::feedback)
        } catch (e: CancellationException) {
            feedback(attempted?.let { OperationFeedback.message(VehicleCommand.Body(it), CommandResult.UNKNOWN) }
                ?: "读取中断 · 本次未发送操作")
            throw e
        } catch (_: Exception) {
            feedback(attempted?.let { OperationFeedback.message(VehicleCommand.Body(it), CommandResult.UNKNOWN) }
                ?: "读取失败 · 本次未发送操作")
        } finally { mutableOperating.value = false }
    }

    private fun finishTemperatureBeforeClimate(prepareAfter: Int? = null): Boolean {
        val task = assistant.state.value.temperatureUpdate?.takeIf {
            it.vehicleKey == overview.state.value.vehicleKey && (it.active || it.ownsAc)
        } ?: return false
        // An old unresolved task cannot keep ownership over a later, explicit climate command.
        if (!task.active && System.currentTimeMillis() - task.startSent > 420_000) {
            assistant.edit { it.copy(temperatureUpdate = task.copy(ownsAc = false,
                phase = TemperaturePhase.HANDED_OVER, message = "已交给手动空调操作", finishedAt = System.currentTimeMillis())) }
            return false
        }
        assistant.edit { it.copy(temperatureUpdate = task.copy(cancelRequested = true,
            prepareAfter = prepareAfter, message = "正在结束车温更新 · 随后处理舒适操作")) }
        if (!temperatureWorker) TemperatureUpdateService.request(getApplication(), stop = true)
        return true
    }

    /** One explicit foreground task; neither widget polling nor presence recovery can start HVAC. */
    suspend fun updateTemperature(key: String, expectedId: Long, stopOnly: Boolean, bound: () -> Boolean) {
        if (temperatureWorker) return
        val prior = assistant.state.value.temperatureUpdate
        if ((prior?.id ?: 0) != expectedId || key != overview.state.value.vehicleKey || !bound()) return
        if (stopOnly && prior?.ownsAc != true) return
        if (!stopOnly && prior?.let { it.active || it.ownsAc } == true) return
        val epoch = generation; val cacheEpoch = overview.epoch(); val accountEpoch = sessions.current()
        fun current() = epoch == generation && cacheEpoch == overview.epoch() && accountEpoch == sessions.current() &&
            key == overview.state.value.vehicleKey && bound()
        val task = if (stopOnly) prior!!.copy(phase = TemperaturePhase.CHECKING, cancelRequested = false,
            finishedAt = 0, message = "正在核对临时空调是否停止") else
            TemperatureUpdate(key, System.currentTimeMillis(), overview.state.value.target)
        temperatureWorker = true
        var completed = false
        assistant.edit { it.copy(temperatureUpdate = task) }
        fun save(next: TemperatureUpdate) {
            if (current()) assistant.edit { old ->
                if (old.temperatureUpdate?.id != task.id) old else old.copy(temperatureUpdate = next,
                    temperatureReceipt = TemperatureReceipt.from(next) ?: old.temperatureReceipt)
            }
        }
        try {
            withTimeout(245_000) {
                // The original tap already started the foreground service, before this network wait.
                withTimeout(65_000) { state.first { !it.busy } }
                if (!current()) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                if (!state.value.connected && state.value.sessionSaved && config != null) {
                    mutable.update { it.copy(busy = true) }
                    restoreSaved(sessions.current())
                }
                val active = client ?: throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                val vehicle = state.value.vehicles.getOrNull(state.value.selected) ?: throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                if (!current() || !state.value.connected) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                if (!stopOnly) assistant.state.value.temperatureReadDelay(key, System.currentTimeMillis())?.let { message ->
                    save(task.copy(phase = TemperaturePhase.DONE, message = message, finishedAt = System.currentTimeMillis()))
                    completed = true
                    return@withTimeout
                }
                fun preparing() = assistant.state.value.activePreparation?.let {
                    it.vehicleKey == key && PreparationProgress.inProgress(it, System.currentTimeMillis())
                } == true
                fun publish(probe: Probe) {
                    if (!current()) return
                    overview.publish(vehicle, Report(false, probe.fetchedAt, probe.fetchedAt, "selected", emptyList(), listOf(probe)), cacheEpoch)
                    if (probe.outcome == ProbeOutcome.SUCCESS) {
                        mutableClimate.update { it.copy(snapshot = ClimateSnapshot.parse(probe)) }
                        updateLocation(probe)
                    }
                }
                suspend fun readTemperature(initialProbe: Probe? = null) {
                TemperatureUpdateFlow(task, { assistant.state.value.temperatureUpdate }, ::save,
                    read = { active.probe(Endpoint.STATUS, vehicle) },
                    refreshEvidence = { baseline ->
                        active.refreshStatusOnline(vehicle, baseline,
                            current = { current() && assistant.state.value.temperatureUpdate?.cancelRequested != true },
                            published = ::publish)?.probe ?: baseline
                    }, send = { target, dispatch, observed ->
                        // Reserve the same control lane as body commands and preparation before persisting dispatch.
                        withTimeout(12_000) {
                            while (mutableOperating.value || mutableClimate.value.queue.active || preparationReading) delay(150)
                        }
                        if (!current() || preparing() || assistant.state.value.operationPending || mutableClimate.value.queue.halted)
                            throw CheckFailure(ProbeOutcome.REJECTED)
                        mutableOperating.value = true; temperatureSending = true
                        try {
                            dispatch()
                            active.controlVehicle(vehicle, VehicleCommand.Comfort(listOf(target)), accepted = {}, observed = observed)
                        } finally { temperatureSending = false; mutableOperating.value = false }
                    }, publish = ::publish, current = ::current, preparationRunning = ::preparing, initialProbe = initialProbe).run(stopOnly)
                }
                readTemperature()
                completed = true
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            assistant.state.value.temperatureUpdate?.takeIf { it.id == task.id }?.let { save(it.interrupted(System.currentTimeMillis())) }
        } finally {
            temperatureWorker = false
            if (current()) {
                assistant.state.value.temperatureUpdate?.takeIf { it.id == task.id }?.let { save(it.interrupted(System.currentTimeMillis())) }
                val finished = assistant.state.value.temperatureUpdate
                if (finished?.id == task.id) {
                    assistant.record("更新车温", finished.message)
                    if (finished.phase == TemperaturePhase.FAILED && !finished.cancelRequested && finished.source == null)
                        assistant.edit { it.copy(temperatureRetryAfter = System.currentTimeMillis() + 60_000) }
                    else if (finished.source != null) assistant.edit { it.copy(temperatureRetryAfter = 0) }
                    val prepare = finished.prepareAfter
                    val climate = climateAfterTemperature
                    climateAfterTemperature = null
                    if (completed && !finished.ownsAc && finished.phase != TemperaturePhase.HANDED_OVER && state.value.connected) {
                        save(finished.copy(prepareAfter = null))
                        if (prepare != null) prepareNow(prepare.takeIf { it == 15 })
                        else if (climate != null) { enableClimate(true); climateTarget(climate) }
                    } else if (prepare != null || climate != null) {
                        if (!finished.ownsAc) save(finished.copy(prepareAfter = null))
                        overview.edit { it.copy(message = if (finished.ownsAc) "临时空调停止待确认 · 舒适操作尚未启动"
                            else finished.message + " · 舒适操作尚未启动") }
                    }
                }
            } else climateAfterTemperature = null
        }
    }

    private fun canOperate(): Boolean {
        val message = when {
            !mutable.value.connected -> "请先恢复车辆连接"
            mutable.value.busy || mutableOperating.value || mutableClimate.value.queue.active -> "已有操作正在处理，请稍等"
            assistant.state.value.operationPending || mutableClimate.value.queue.halted -> "请先核对上一条操作结果"
            else -> null
        }
        if (message != null) {
            assistant.edit { it.copy(operationMessage = message) }
            overview.edit { it.copy(message=message) }
            return false
        }
        return true
    }

    fun prepareNow(departureAfterMinutes: Int? = null) {
        if (finishTemperatureBeforeClimate(departureAfterMinutes?.takeIf { it == 15 } ?: 0)) return
        val existing=assistant.state.value.activePreparation
        if(existing?.vehicleKey==overview.state.value.vehicleKey && PreparationProgress.inProgress(existing,System.currentTimeMillis())) {
            overview.edit { it.copy(message="${PreparationProgress.title(PreparationProgress.phase(existing!!,System.currentTimeMillis()))} · 未重复发送") }
            resumePreparation()
            viewModelScope.launch { observePreparation() }
            return
        }
        if (!canOperate()) return
        val key = overview.state.value.vehicleKey ?: return
        val preferences = assistant.state.value.preferences.copy(target = overview.state.value.target)
        overview.edit { it.copy(message="正在准备备车 · 目标 ${preferences.target}°C") }
        // Starting the foreground observer is part of this explicit user operation.
        val departureAt = departureAfterMinutes?.takeIf { it==15 }?.let { System.currentTimeMillis()+it*60_000L }
        runCatching { PreparationService.start(getApplication(), key, preferences, departureAt) }.onFailure {
            assistant.edit { it.copy(operationMessage = "系统未允许启动备车任务，请保持应用在前台再试") }
        }
    }

    suspend fun prepareFromService(key: String, preferences: ComfortPreferences, departureAt: Long? = null, appointmentId: String? = null): Boolean {
        withTimeoutOrNull(60_000) { state.first { !it.busy } } ?: return false
        if (assistant.state.value.temperatureUpdate?.let { it.vehicleKey == key && (it.active || it.ownsAc) } == true) {
            overview.edit { it.copy(message = "车温更新尚未结束 · 本次备车未启动") }
            return false
        }
        if (overview.state.value.vehicleKey != key || !canOperate()) return false
        mutableOperating.value = true
        var started = 0L
        try {
            val active = client ?: return false
            val vehicle = mutable.value.vehicles.getOrNull(mutable.value.selected) ?: return false
            val epoch = generation
            val preparingAt=System.currentTimeMillis()
            started = preparingAt
            assistant.edit { it.copy(activePreparation=PreparationSession(key,preparingAt,preparingAt+preferences.minutes*60_000L,
                preferences,listOf(ClimateChannel.AC),phase=PreparationPhase.READING,status="正在读取车温")) }
            val probe = active.probe(Endpoint.STATUS, vehicle)
            if (epoch != generation) return false
            if (probe.outcome != ProbeOutcome.SUCCESS) throw CheckFailure(probe.outcome)
            val snapshot = publishPreparationStatus(vehicle, probe)
            val reading = assistant.state.value.activePreparation?.takeIf { it.createdAt==preparingAt && !it.finished } ?: return false
            if (PreparationProgress.driving(probe, System.currentTimeMillis())) {
                val current = assistant.state.value.activePreparation ?: return false
                assistant.updatePreparation(current, current.copy(phase=PreparationPhase.HANDED_OVER,finished=true,
                    status="车辆处于行驶模式 · 本次未发送备车",endedAt=System.currentTimeMillis()))
                overview.edit { it.copy(message="车辆处于行驶模式 · 本次未发送备车") }
                return false
            }
            val now = System.currentTimeMillis()
            val planned = ComfortPolicy.start(preferences,snapshot,now,SystemClock.elapsedRealtime(),departureAt,appointmentId)
            val command = planned.command
            if (command == null) {
                val fresh = snapshot.sourceTime?.let { Instant.now().epochSecond - it.epochSecond in 0..300 } == true
                val message = if(planned.thermal.note.isNotBlank()) planned.thermal.note else if (fresh && snapshot.cabinTemperature != null && kotlin.math.abs(snapshot.cabinTemperature - preferences.target) <= 1)
                    "已接近目标温度，本次无需启动" else "没有新鲜车温，未猜测冷热或发送备车"
                assistant.edit { it.copy(operationMessage = message,activePreparation=it.activePreparation?.copy(phase=if(planned.thermal.note.isBlank()) PreparationPhase.READY else PreparationPhase.FAILED,
                    status=message,finished=true,endedAt=System.currentTimeMillis(),lastTemperature=snapshot.cabinTemperature,lastSource=snapshot.sourceTime?.toEpochMilli())) }
                overview.edit { it.copy(message=message) }; assistant.record("智能备车", message)
                return false
            }
            started = now
            assistant.updatePreparation(reading,reading.copy(started=now,deadline=now+preferences.minutes*60_000L,
                channels=command.targets.map { it.channel },phase=PreparationPhase.SENDING,status="正在发送备车请求",
                thermal=planned.thermal,
                initialTemperature=snapshot.cabinTemperature?.takeIf { snapshot.sourceTime?.let { t -> now-t.toEpochMilli() in 0..300_000 }==true }))
            val result = sendCommand(command, preparation=true)
            if (result == CommandResult.ACCEPTED || result == CommandResult.MATCHED) {
                return true
            }
            assistant.state.value.activePreparation?.takeIf { it.started==started && !it.finished }?.let { current ->
                assistant.updatePreparation(current,current.copy(phase=if(result==CommandResult.REJECTED) PreparationPhase.FAILED else PreparationPhase.UNKNOWN,
                    status=if(result==CommandResult.REJECTED) "请求未受理" else "请求结果未确认 · 未重复发送",finished=true,endedAt=System.currentTimeMillis()))
            }
            return false
        } catch (e: CancellationException) {
            assistant.edit { current -> current.copy(activePreparation=current.activePreparation?.let {
                if(it.vehicleKey==key && it.started==started) PreparationProgress.interrupted(it) else it
            }) }
            throw e
        }
        catch (_: Exception) {
            assistant.state.value.activePreparation?.takeIf { it.vehicleKey==key && it.started==started && !it.finished }?.let { current ->
                val unsent = current.phase == PreparationPhase.READING
                val message = if(unsent) "读取失败 · 本次未发送备车" else "执行未完成 · 结果待核实，未自动重试"
                assistant.updatePreparation(current,current.copy(phase=if(unsent) PreparationPhase.FAILED else PreparationPhase.UNKNOWN,
                    status=message,finished=true,endedAt=System.currentTimeMillis()))
                assistant.edit { it.copy(operationMessage=message) }
                overview.edit { it.copy(message=message) }
            }
            return false
        }
        finally { mutableOperating.value = false }
    }

    private suspend fun sendCommand(command: VehicleCommand, preparation: Boolean = false, title: String = command.title): CommandResult {
        val active = client ?: return CommandResult.REJECTED
        val vehicle = mutable.value.vehicles.getOrNull(mutable.value.selected) ?: return CommandResult.REJECTED
        val epoch = generation; val cacheEpoch = overview.epoch()
        val body = (command as? VehicleCommand.Body)?.action
        val before = body?.observation?.first?.let { overview.state.value.readings[it]?.value }
        val pending = body?.takeIf { it.observation != null }?.let { PendingBody(it, System.currentTimeMillis(), before) }
        assistant.edit { it.copy(operationPending = true, pendingBodyAction = body, operationMessage = "正在发送：${title}".take(180)) }
        overview.edit { it.copy(message = "正在发送：${title}", pendingBody = pending) }
        val result = try {
            active.controlVehicle(vehicle, command, {
                if (generation == epoch) {
                    val receiptFeedback = if (OperationFeedback.receiptOnly(command)) OperationFeedback.message(command, CommandResult.ACCEPTED) else null
                    assistant.edit { it.copy(operationPending=false,operationMessage = receiptFeedback ?: "请求已受理，等待车辆回传") }
                    overview.edit { it.copy(message = receiptFeedback ?: "${title} · 等待车辆回传") }
                    if(preparation) assistant.edit { current -> current.copy(activePreparation=current.activePreparation?.let {
                        if(it.finished) it else it.copy(phase=if(it.stopRequested) PreparationPhase.STOPPING else PreparationPhase.ACCEPTED,
                            status=if(it.stopRequested) "停止操作已记录 · 等待车辆回传" else "备车已受理，等待运行回传")
                    }) }
                }
            }, before = before) { probe ->
                if (generation == epoch) overview.publish(vehicle, Report(false, probe.fetchedAt, probe.fetchedAt, "selected", emptyList(), listOf(probe)), cacheEpoch)
            }
        } catch (e: CancellationException) {
            if (OperationFeedback.receiptOnly(command) && generation == epoch) {
                val message = OperationFeedback.message(command, CommandResult.UNKNOWN)
                assistant.edit { it.copy(operationPending = false, pendingBodyAction = null, operationMessage = message) }
                overview.edit { it.copy(message = message) }
                assistant.record(title, CommandResult.UNKNOWN.label)
            }
            throw e
        } catch (_: Exception) { CommandResult.UNKNOWN }
        if (generation == epoch) {
            val completedPreparation = assistant.state.value.activePreparation?.takeIf { preparation && it.finished && it.phase != PreparationPhase.UNKNOWN }
            val needsAcknowledgement = OperationFeedback.requiresAcknowledgement(command, result)
            val feedback = OperationFeedback.message(command, result, title)
            assistant.edit { it.copy(operationPending = needsAcknowledgement, pendingBodyAction = body.takeIf { needsAcknowledgement },
                operationMessage = completedPreparation?.let { s -> PreparationProgress.detail(s,System.currentTimeMillis()) } ?: feedback) }
            assistant.record(title, result.label)
            overview.edit { it.copy(message = if(completedPreparation != null) null else feedback,
                pendingBody = if (result == CommandResult.MATCHED || result == CommandResult.REJECTED) null else it.pendingBody) }
        }
        return result
    }

    private suspend fun stopPreparation(session: PreparationSession, stillBound: () -> Boolean, automatic: Boolean = false) {
        if(session.finished || session.stopRequested || session.vehicleKey!=overview.state.value.vehicleKey || !stillBound()) return
        val epoch = generation
        fun current() = assistant.state.value.activePreparation?.takeIf { generation==epoch && stillBound() &&
            overview.state.value.vehicleKey==session.vehicleKey && it.vehicleKey==session.vehicleKey && it.createdAt==session.createdAt && !it.finished }
        fun feedback(message: String) {
            if(generation==epoch && overview.state.value.vehicleKey==session.vehicleKey) {
                assistant.edit { it.copy(operationMessage=message) }; overview.edit { it.copy(message=message) }
            }
        }
        val requested = PreparationControl.requestStop(session,System.currentTimeMillis())
        assistant.updatePreparation(session,requested)
        feedback(if(requested.finished) requested.status else "收到停止操作 · 正在结束备车")
        if(requested.finished) return // The initial temperature read has not sent anything yet.
        var ownsLane=false
        var attempted=false
        try {
            // A second tap can arrive during the start POST/readback. Keep its intent, then use the same lane.
            val available=withTimeoutOrNull(65_000) {
                combine(state,operating,climateState) { account,working,climate -> !account.busy && !working && !climate.queue.active }.first { it }
            } != null
            current() ?: return
            if(!available || !state.value.connected || state.value.busy || mutableOperating.value || mutableClimate.value.queue.active ||
                assistant.state.value.operationPending || mutableClimate.value.queue.halted) {
                feedback("停止尚未发送 · 请先核实当前操作，再点停止")
                return
            }
            val active=client ?: return
            val vehicle=state.value.vehicles.getOrNull(state.value.selected) ?: return
            mutableOperating.value=true; ownsLane=true
            val probe=withTimeout(20_000) { active.probe(Endpoint.STATUS,vehicle) }
            if(current()==null) return
            if(probe.outcome!=ProbeOutcome.SUCCESS) { feedback("读取失败 · 未发送结束备车指令"); return }
            publishPreparationStatus(vehicle,probe)
            val checked=current() ?: return
            if(PreparationProgress.driving(probe,System.currentTimeMillis())) {
                assistant.updatePreparation(checked,checked.copy(phase=PreparationPhase.HANDED_OVER,finished=true,
                    status="车辆进入行驶模式 · 已退出远程备车",endedAt=System.currentTimeMillis()))
                return
            }
            if(automatic && (ParkingEvidence.from(probe)?.let { it.parked && it.recent(System.currentTimeMillis()) }!=true ||
                !ComfortPolicy.fresh(ClimateSnapshot.parse(probe),System.currentTimeMillis()))) {
                feedback("车况暂不可核实 · 自动结束未发送，可手动停止")
                return
            }
            assistant.updatePreparation(checked,checked.copy(phase=PreparationPhase.STOPPING,stopSentAt=System.currentTimeMillis()))
            attempted=true
            val result=sendCommand(PreparationControl.stopCommand(checked),preparation=true,title="停止备车")
            current()?.let { pending ->
                val next=when(result) {
                    CommandResult.UNKNOWN -> pending.copy(phase=PreparationPhase.UNKNOWN,finished=true,endedAt=System.currentTimeMillis(),status="结束请求结果待核实 · 未重复发送")
                    CommandResult.REJECTED -> pending.copy(phase=PreparationPhase.ACCEPTED,stopRequested=false,stopSentAt=0,status="结束请求未受理 · 继续观察备车")
                    else -> pending.copy(status="停止请求：${result.label}")
                }
                assistant.updatePreparation(pending,next)
            }
            resumePreparation()
        } catch(e:CancellationException) {
            if(attempted) current()?.let { assistant.updatePreparation(it,it.copy(phase=PreparationPhase.UNKNOWN,finished=true,
                endedAt=System.currentTimeMillis(),status="结束请求结果待核实 · 未重复发送")) }
            feedback(if(attempted) "结束请求结果待核实 · 未重复发送" else "停止操作已中断 · 未发送结束指令")
            throw e
        } catch(_:Exception) {
            feedback(if(attempted) "结束备车未完成 · 请刷新核实，未自动重试" else "读取失败 · 未发送结束备车指令")
        } finally {
            // Even a removed widget or changed connection must release this unsent local intent.
            if(!attempted) assistant.state.value.activePreparation?.takeIf { it.vehicleKey==session.vehicleKey &&
                it.createdAt==session.createdAt && !it.finished && it.stopRequested && it.stopSentAt==0L }?.let { pending ->
                assistant.updatePreparation(pending,pending.copy(stopRequested=false,
                    phase=if(pending.phase==PreparationPhase.STOPPING) PreparationPhase.ACCEPTED else pending.phase))
            }
            if(ownsLane && generation==epoch) mutableOperating.value=false
        }
    }

    suspend fun observePreparation(): ClimateSnapshot? {
        if(mutable.value.busy || mutableOperating.value || mutableClimate.value.queue.active || preparationReading) return null
        val active = client ?: return null
        val vehicle = mutable.value.vehicles.getOrNull(mutable.value.selected) ?: return null
        val key = assistant.state.value.activePreparation?.vehicleKey ?: return null
        if (VehicleOverview.key(vehicle) != key) return null
        val epoch = generation
        preparationReading=true
        return try {
            active.probe(Endpoint.STATUS,vehicle).takeIf { epoch==generation && key==overview.state.value.vehicleKey && it.outcome==ProbeOutcome.SUCCESS }
                ?.let { publishPreparationStatus(vehicle,it) }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { null } finally { preparationReading=false }
    }

    private fun publishPreparationStatus(vehicle: Vehicle, probe: Probe): ClimateSnapshot {
        preparationProbe=probe
        val snapshot = ClimateSnapshot.parse(probe)
        mutableClimate.update { it.copy(snapshot=snapshot) }
        overview.publish(vehicle,Report(false,probe.fetchedAt,probe.fetchedAt,"selected",emptyList(),listOf(probe)))
        updateLocation(probe)
        return snapshot
    }

    /** Only the foreground preparation worker calls this; ordinary refreshes remain read-only. */
    suspend fun advancePreparation() {
        val snapshot = observePreparation() ?: return
        val session = assistant.state.value.activePreparation ?: return
        if(session.thermal==null || session.finished || session.vehicleKey!=overview.state.value.vehicleKey ||
            mutable.value.busy || mutableOperating.value || mutableClimate.value.queue.active || mutableClimate.value.queue.halted ||
            assistant.state.value.operationPending || !mutable.value.connected) return
        val now=System.currentTimeMillis()
        val parked=ParkingEvidence.from(preparationProbe)?.let { it.parked && it.recent(now) }==true
        val decision=ComfortPolicy.evaluate(session,snapshot,now,SystemClock.elapsedRealtime(),parked)
        val epoch=generation
        // No suspension between deciding, rechecking the lane, and persisting the write intent.
        if(assistant.state.value.activePreparation!=session || mutableOperating.value || mutableClimate.value.queue.active) return
        assistant.updatePreparation(session,decision.session)
        if(decision.end) {
            stopPreparation(decision.session,{ generation==epoch && overview.state.value.vehicleKey==session.vehicleKey },automatic=true)
            return
        }
        val command=decision.command ?: return
        mutableOperating.value=true
        try {
            val result=sendCommand(command,title="智能备车 · 座椅阶段调整")
            assistant.state.value.activePreparation?.takeIf { generation==epoch && it.createdAt==session.createdAt && !it.finished }?.let {
                assistant.updatePreparation(it,ComfortPolicy.result(it,command,result))
            }
        } catch(e:CancellationException) {
            assistant.state.value.activePreparation?.takeIf { generation==epoch && it.createdAt==session.createdAt && !it.finished }?.let {
                assistant.updatePreparation(it,ComfortPolicy.result(it,command,CommandResult.UNKNOWN))
            }
            throw e
        } finally { if(generation==epoch) mutableOperating.value=false }
    }

    fun resumePreparation() {
        val session=assistant.state.value.activePreparation ?: return
        if(!state.value.connected || session.vehicleKey!=overview.state.value.vehicleKey || !PreparationProgress.resumable(session,System.currentTimeMillis())) return
        runCatching { PreparationService.resume(getApplication(),session) }
    }

    fun updateLocation(probe: Probe) {
        val raw = probe.data.at("basicVehicleStatus.position")
        val latitude = raw.at("latitude").text()?.toDoubleOrNull()
        val longitude = raw.at("longitude").text()?.toDoubleOrNull()
        locationEvidence = buildJsonObject {
            put("read", probe.outcome == ProbeOutcome.SUCCESS)
            put("coordinateShape", when { latitude == null || longitude == null -> "MISSING"; latitude == 0.0 && longitude == 0.0 -> "ZERO";
                latitude in -90.0..90.0 && longitude in -180.0..180.0 -> "DECIMAL_RANGE"; else -> "OTHER_SCALE" })
            put("trustedFlag", when (raw.at("posCanBeTrusted").text()) { "true", "1" -> "true"; "false", "0" -> "false"; else -> "UNKNOWN" })
            put("sourceKnown", Capabilities.sourceTime(raw.at("updateTime")) != null)
            put("speedSourceKnown", Capabilities.sourceTime(probe.data.at("basicVehicleStatus.updateTime")) != null)
            put("aggregateStatusTimeKnown", Capabilities.sourceTime(probe.data.at("updateTime")) != null)
            ParkingEvidence.from(probe)?.let { parking ->
                put("parkingState", parking.brake.name)
                put("speedAvailable", parking.speed != null)
                put("drivingMode", parking.drivingMode)
                put("parkingEvidenceRecent", parking.recent(System.currentTimeMillis()))
                put("aggregateParkingTime", parking.aggregateMotionTime)
            }
        }
        assistant.updateLocation(probe)
    }

    suspend fun checkAwayGuard(report: Report?, dryRun: Boolean = false): GuardDecision {
        val settings = assistant.state.value
        if ((!settings.guardEnabled && !settings.homeGuardEnabled) || settings.paused) return GuardDecision(false, "规则已暂停")
        val vehicle = mutable.value.vehicles.getOrNull(mutable.value.selected) ?: return GuardDecision(false, "车辆尚未连接")
        val epoch = generation
        fun note(message: String) { if (generation == epoch) assistant.edit { it.copy(guardMessage = message) } }
        val guard = settings.parkingGuard
        if (guard.vehicleKey != VehicleOverview.key(vehicle)) return GuardDecision(false, "等待当前车辆状态")
        val status = report?.probes?.lastOrNull { it.endpoint == Endpoint.STATUS }
        val sentry = report?.probes?.lastOrNull { it.endpoint == Endpoint.SENTRY }
        val now = System.currentTimeMillis()
        if (guard.journey.pending(now)) {
            val pending = GuardDecision(false, "里程变化待复查 · 暂不改变本次哨兵选择", journeyFollowUp = guard.journey.needsFollowUp(now))
            if (!dryRun) note(pending.reason)
            return pending
        }
        val atHome = HomeZone.area(ParkingEvidence.from(status)?.position, settings.home, settings.homeRadius) in
            setOf(HomeZone.Area.HOME, HomeZone.Area.NEAR_HOME)
        val decision = if (settings.homeGuardEnabled && (atHome || !settings.guardEnabled)) {
            if (settings.homeGuard.vehicleKey != VehicleOverview.key(vehicle)) return GuardDecision(false, "等待当前车辆到家记录")
            settings.homeGuard.decision(status, sentry, settings.home, settings.homeRadius, settings.location?.verified == true, now)
        } else if (settings.guardEnabled) guard.decision(status, sentry, settings.home, settings.homeRadius, settings.location?.verified == true, now)
        else GuardDecision(false, "离家开启未启用 · 到家关闭等待有效位置")
        if (dryRun) return decision
        if (!decision.enable && !decision.disable) { note(decision.reason); return decision }
        if (!canOperate()) { note("已有操作正在处理 · 本次未自动操作哨兵"); return GuardDecision(false, "已有操作正在处理", followUp = decision.disable) }
        val claimed = if (decision.disable) assistant.claimHomeGuard(settings.homeGuard, settings.home, settings.homeRadius)
            else assistant.claimGuard(guard, settings.home, settings.homeRadius)
        if (!claimed) return GuardDecision(false, "规则或本次手动选择已变化")
        mutableOperating.value = true
        try {
            val result = sendCommand(VehicleCommand.Body(if (decision.disable) BodyAction.SENTRY_OFF else BodyAction.SENTRY_ON),
                title = if (decision.disable) "到家自动关闭哨兵" else "离家自动开启哨兵")
            if (decision.disable) {
                val message = if (result == CommandResult.MATCHED) "已确认到家 · 哨兵已自动关闭" else "到家关闭：${result.label} · 不重复发送"
                if (!assistant.state.value.homeGuard.held) note(message)
            } else if (!assistant.state.value.parkingGuard.paused) note("离家停车：${result.label}")
        } finally { mutableOperating.value = false }
        return decision
    }
}
