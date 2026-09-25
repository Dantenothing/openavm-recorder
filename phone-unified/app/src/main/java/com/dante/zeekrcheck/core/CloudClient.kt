package com.dante.zeekrcheck.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CheckFailure(val outcome: ProbeOutcome, val httpStatus: Int? = null,
    val networkIssue: String? = null, val readRecoverable: Boolean = false) : Exception(outcome.label)

data class PhoneIdentity(val brand: String, val model: String, val release: String, val sdk: Int)

/** Exact origins + paths + method + query. No controllable base URL, redirect, or generic command API. */
object RequestPolicy {
    const val DISCOVERY = "https://gateway-pub-hw-em-sg.zeekrlife.com/overseas-app/region/url"
    const val USER_BASE = "https://gateway-pub-hw-em-sg.zeekrlife.com/zeekr-cuc-idaas-sea/"
    const val TSP_BASE = "https://sea-snc-tsp-api-gw.zeekrlife.com/"
    const val CLIENT_ID = "1JwLroFkFFIpgFGdTRrm4_nzkkwDkfHj7RxJQb7J8tc"
    const val VEHICLES = "ms-app-bff/api/v4.0/veh/vehicle-list"
    const val BEARER = "ms-user-auth/v1.0/auth/login"

    fun allowed(method: String, url: HttpUrl): Boolean {
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
        val path = url.encodedPath
        if (url.host == "gateway-pub-hw-em-sg.zeekrlife.com") return when (method to path) {
            "GET" to "/overseas-app/region/url" -> url.query == null
            "POST" to "/zeekr-cuc-idaas-sea/auth/checkUserV2",
            "POST" to "/zeekr-cuc-idaas-sea/auth/loginByEmailEncrypt" -> url.query == null
            "GET" to "/zeekr-cuc-idaas-sea/user/tspCode" -> url.query == "tspClientId=$CLIENT_ID"
            else -> false
        }
        if (url.host != "sea-snc-tsp-api-gw.zeekrlife.com") return false
        if (method == "POST") return path == "/$BEARER" && url.query == null
        if (method != "GET") return false
        if (path == "/$VEHICLES") return url.query == "needSharedCar=true"
        val endpoint = Endpoint.entries.firstOrNull { path == "/${it.path}" } ?: return false
        return if (endpoint == Endpoint.STATUS) url.query == "latest=false&target=new" else url.query == null
    }
    fun validateRegion(data: JsonElement) {
        val list = data as? JsonArray ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        val au = list.firstOrNull { it.at("countryCode").text()?.uppercase() == "AU" }
            ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        // A different discovery result must be reviewed before transmitting credentials to it.
        if (au.at("regionCode").text() != "SEA" || au.at("url.userCenterUrl").text()?.trimEnd('/') != USER_BASE.trimEnd('/')) {
            throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        }
    }
}

class ReadOnlyTransport(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS).build()) {

    suspend fun execute(request: Request): JsonElement {
        check(RequestPolicy.allowed(request.method, request.url)) { "Request is outside the capability-check allowlist" }
        return executeVerified(request)
    }
    internal suspend fun executeClimate(request: Request, target: ClimateTarget): JsonElement {
        check(ClimateRequestPolicy.allowed(request, target)) { "Request is outside the climate-test allowlist" }
        return executeVerified(request)
    }
    internal suspend fun executeVehicle(request: Request, command: VehicleCommand): JsonElement {
        check(VehicleCommandPolicy.allowed(request, command))
        return executeVerified(request)
    }
    internal suspend fun executePresence(request: Request, heartbeat: PresenceHeartbeat): JsonElement {
        check(heartbeat.allows(request)) { "Request is outside the online-presence allowlist" }
        return executeVerified(request)
    }
    /** Drop only idle sockets. This never cancels or replays an in-flight vehicle write. */
    internal fun discardIdleConnections() { client.connectionPool.evictAll() }
    private suspend fun executeVerified(request: Request): JsonElement {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val failure=networkFailure(e)
                    NetworkTrace.record(request,failure.outcome.name,failure.networkIssue)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            when (it.code) {
                                401 -> throw CheckFailure(ProbeOutcome.AUTH_REQUIRED, it.code)
                                403 -> throw CheckFailure(ProbeOutcome.REJECTED, it.code)
                                429 -> throw CheckFailure(ProbeOutcome.RATE_LIMITED, it.code)
                            }
                            if (!it.isSuccessful) throw CheckFailure(if (it.code >= 500) ProbeOutcome.NETWORK else ProbeOutcome.REJECTED, it.code)
                            val source = it.body?.source() ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
                            source.request(2_097_153) // Reads to the bound or EOF; readByteArray(count) would require that exact size.
                            if (source.buffer.size > 2_097_152) throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
                            val text = source.readByteArray().toString(Charsets.UTF_8)
                            decodeResponse(text, requireData = !request.url.encodedPath.endsWith("/auth/checkUserV2") && request.url != PresenceHeartbeat.URL)
                        }
                        NetworkTrace.record(request,"SUCCESS")
                        if (continuation.isActive) continuation.resume(data)
                    } catch (e: Exception) {
                        val safe = when (e) {
                            is CheckFailure -> e
                            is IOException -> networkFailure(e)
                            else -> CheckFailure(ProbeOutcome.INVALID_RESPONSE)
                        }
                        NetworkTrace.record(request,safe.outcome.name,safe.networkIssue,safe.httpStatus)
                        if (continuation.isActive) continuation.resumeWithException(safe)
                    }
                }
            })
        }
    }

    companion object {
        private fun networkFailure(error:IOException):CheckFailure {
            val issue=when(error) {
                is InterruptedIOException -> "TIMEOUT"
                is javax.net.ssl.SSLException -> "TLS"
                is java.net.ProtocolException -> "PROTOCOL"
                is java.net.UnknownHostException -> "DNS"
                is java.net.ConnectException -> "CONNECT"
                is java.net.SocketException -> "SOCKET"
                is java.io.EOFException -> "EOF"
                else -> "IO"
            }
            return CheckFailure(if(error is InterruptedIOException) ProbeOutcome.TIMEOUT else ProbeOutcome.NETWORK,
                networkIssue=issue,readRecoverable=issue in setOf("DNS","CONNECT","SOCKET","EOF","IO"))
        }
        internal fun decodeResponse(text: String, requireData: Boolean = true): JsonElement {
            val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
                ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
            if (root["success"] != JsonPrimitive(true)) {
                if (root["msg"].text() == "Token expired") throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                if (root["success"] == JsonPrimitive(false)) throw CheckFailure(ProbeOutcome.REJECTED)
                throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
            }
            if (requireData && !root.containsKey("data")) throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
            return root["data"] ?: JsonNull
        }
    }
}

class CloudClient(
    private val config: ProtocolConfig,
    private val transport: ReadOnlyTransport = ReadOnlyTransport(),
    private val clock: Clock = Clock.systemUTC(),
    private val phone: PhoneIdentity = PhoneIdentity("Android", "Android", "unknown", 26),
    restoredSession: SavedSession? = null,
    private val deviceId: String = restoredSession?.deviceId ?: UUID.randomUUID().toString(),
    private val sessionChanged: suspend (SavedSession?) -> Unit = {},
    private val persistentSession: SessionPersistence? = null,
    private val persistenceEpoch: Int? = null,
    private val presenceStorage: PresenceLeaseStorage? = null,
) {
    init { require(restoredSession == null || (restoredSession.matches(config) && restoredSession.deviceId == deviceId)) }
    @Volatile private var session: SavedSession? = restoredSession
    private val accountVersion = AtomicInteger()
    private val renewal = persistentSession?.renewalMutex ?: Mutex()
    private var persistedRevision = persistentSession?.revision()
    private fun epochCurrent() = persistentSession == null || persistenceEpoch == persistentSession.current()
    private suspend fun saveChanged(value: SavedSession?) {
        if (!epochCurrent()) throw CancellationException("Session changed")
        sessionChanged(value)
        persistedRevision = persistentSession?.revision()
    }
    // One failed renewal is terminal for this client. A user can explicitly reconnect later.
    private var renewalFailure: ProbeOutcome? = null
    var passwordLogins = 0
        private set
    var restoredChecks = 0
        private set
    var renewalAttempts = 0
        private set
    var climateRequestAttempts = 0
        private set
    var vehicleRequestAttempts = 0
        private set
    private val jsonType = "application/json; charset=UTF-8".toMediaType()
    private val authHeaders = mapOf(
        "accept-language" to "en-AU", "app-authorization" to "1003", "app-code" to "32816dbd-ff17-47b7-e250-5dae7d9f8cd4",
        "appcode" to "eu-app", "appid" to "TSP", "appsecret" to "zeekr_tis", "appversion" to "1.6.6",
        "call-source" to "android", "client-id" to RequestPolicy.CLIENT_ID, "Content-Type" to jsonType.toString(),
        "country" to "AU", "device-name" to phone.model, "device-type" to "app", "language" to "en",
        "msgappid" to "11002", "msgclientid" to "1003", "registcountry" to "AU", "tmp-tenant-code" to "3300743799505195008",
        "user-agent" to "Device/${phone.brand}AppName/com.zeekr.globalAppVersion/1.6.6Platform/androidOSVersion/${phone.release}Ditto/true",
    )

    suspend fun login(email: String, password: String, stage: (String) -> Unit): List<Vehicle> {
        clearSession()
        val version = accountVersion.get()
        climateRequestAttempts = 0
        stage("1/6 · 确认澳洲区域")
        RequestPolicy.validateRegion(auth("GET", RequestPolicy.DISCOVERY))
        stage("2/6 · 检查账号")
        auth("POST", RequestPolicy.USER_BASE + "auth/checkUserV2", buildJsonObject { put("email", email); put("checkType", "1") }.toString())
        stage("3/6 · 登录 guest 账号")
        ++passwordLogins
        val encrypted = try { Signatures.encryptPassword(password, config.passwordKey) }
            catch (_: Exception) { throw CheckFailure(ProbeOutcome.INVALID_RESPONSE) }
        val login = auth("POST", RequestPolicy.USER_BASE + "auth/loginByEmailEncrypt", buildJsonObject {
            put("code", ""); put("codeId", ""); put("email", email); put("password", encrypted)
        }.toString())
        if (login.at("tokenName").text() != "Authorization") throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        val userToken = login.at("tokenValue").text()?.takeIf(SavedSession::validToken) ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        val next = exchange(userToken) { step -> stage(if (step == 1) "4/6 · 获取车辆服务登录码" else "5/6 · 连接车辆服务") }
        if (accountVersion.get() != version) throw CancellationException("Session changed")
        session = next
        saveChanged(next)
        stage("6/6 · 获取车辆（包含共享车辆）")
        return vehicles(allowRenewal = false)
    }

    /** First try the saved access token. No password, account check or discovery call is needed. */
    suspend fun resumeSession(): List<Vehicle> {
        if (session == null) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
        ++restoredChecks
        return vehicles(allowRenewal = true).also { recoverPresence(it) }
    }

    private suspend fun vehicles(allowRenewal: Boolean): List<Vehicle> = Vehicle.parseList(
        read(RequestPolicy.TSP_BASE + RequestPolicy.VEHICLES + "?needSharedCar=true", allowRenewal = allowRenewal))

    /** These two endpoints are verified in 1.6.6. No assumed refresh-token endpoint or password fallback. */
    private suspend fun exchange(userToken: String, stage: (Int) -> Unit = {}): SavedSession {
        stage(1)
        val code = auth("GET", RequestPolicy.USER_BASE + "user/tspCode?tspClientId=${RequestPolicy.CLIENT_ID}", userToken = userToken)
            .at("code").text()?.takeIf(String::isNotBlank) ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        stage(2)
        val response = appRequest("POST", RequestPolicy.TSP_BASE + RequestPolicy.BEARER, buildJsonObject {
            put("identifier", code); put("identityType", 10); put("loginDeviceId", "${phone.brand}-${phone.model}-${phone.sdk}-${phone.release}")
            put("loginDeviceJgId", ""); put("loginDeviceType", 1); put("loginPhoneBrand", phone.brand)
            put("loginPhoneModel", phone.model); put("loginSystem", "Android")
        }.toString(), accessToken = "")
        val bearer = response.at("accessToken").text()?.takeIf(SavedSession::validToken) ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
        return SavedSession(userToken, bearer, deviceId, config.fingerprint())
    }

    private suspend fun read(url: String, vin: String? = null, allowRenewal: Boolean = true, reconnecting: () -> Unit = {}): JsonElement {
        if (!epochCurrent()) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
        val original = session ?: throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
        val version = accountVersion.get()
        suspend fun connectedRead(token:String):JsonElement {
            try { return appRequest("GET",url,vin=vin,accessToken=token) }
            catch(e:CheckFailure) {
                if(!e.readRecoverable) throw e
                if(accountVersion.get()!=version || !epochCurrent() || session==null) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                NetworkTrace.record(Request.Builder().url(url).get().build(),"RECONNECTING",e.networkIssue)
                reconnecting()
                delay(350)
                if(accountVersion.get()!=version || !epochCurrent() || session==null) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                transport.discardIdleConnections()
                // Rebuild the signature and nonce, and retry this GET only once. Never retry the card action or POST.
                return appRequest("GET",url,vin=vin,accessToken=token)
            }
        }
        try { return connectedRead(original.accessToken) }
        catch (e: CheckFailure) { if (e.outcome != ProbeOutcome.AUTH_REQUIRED || !allowRenewal) throw e }
        renewal.withLock {
            if (accountVersion.get() != version || session == null) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
            if (!epochCurrent()) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
            persistentSession?.let { shared ->
                val latest = shared.load() ?: throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                if (!latest.matches(config) || latest.deviceId != deviceId) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
                if (persistedRevision != shared.revision() || latest.accessToken != original.accessToken || latest.userToken != original.userToken) {
                    session = latest
                    persistedRevision = shared.revision()
                    renewalFailure = null
                }
            }
            // Concurrent expired reads share a single exchange, even when a server reissues the same token text.
            if (session === original) {
                renewalFailure?.let { throw CheckFailure(it) }
                ++renewalAttempts
                try {
                    val next = exchange(original.userToken)
                    if (accountVersion.get() != version) throw CancellationException("Session changed")
                    session = next
                    saveChanged(next)
                } catch (e: CheckFailure) {
                    renewalFailure = e.outcome
                    if (e.outcome == ProbeOutcome.AUTH_REQUIRED) {
                        session = null
                        saveChanged(null)
                    }
                    throw e
                }
            }
        }
        if (accountVersion.get() != version || !epochCurrent()) throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)
        // Exactly one replay, and GET only. A second rejection cannot start a renewal loop.
        return try { connectedRead(session?.accessToken ?: throw CheckFailure(ProbeOutcome.AUTH_REQUIRED)) }
        catch (e: CheckFailure) {
            if (e.outcome == ProbeOutcome.AUTH_REQUIRED) renewalFailure = e.outcome
            throw e
        }
    }

    private val probeReads = ReadCoalescer<String, Probe>()
    suspend fun probe(endpoint: Endpoint, vehicle: Vehicle, reconnecting: () -> Unit = {}): Probe =
        probeReads.read("${accountVersion.get()}/${vehicle.vin}/${endpoint.name}") { try {
            val query = if (endpoint == Endpoint.STATUS) "?latest=false&target=new" else ""
            val data = read(RequestPolicy.TSP_BASE + endpoint.path + query, vin = vehicle.vin, reconnecting=reconnecting)
            Probe(endpoint, ProbeOutcome.SUCCESS, clock.instant(), data)
        } catch (e: CheckFailure) { Probe(endpoint, e.outcome, clock.instant(), httpStatus = e.httpStatus) } }
    fun clearSession() { accountVersion.incrementAndGet(); session = null; renewalFailure = null }
    override fun toString() = "CloudClient(redacted)"

    private var lastOnlineAttempt = 0L
    private fun presenceIdentity(saved: SavedSession): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((saved.userToken + "\u0000" + saved.deviceId).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Recovery can only send EXIT. Account/vehicle mismatch never redirects a pending lease. */
    suspend fun recoverPresence(vehicles: List<Vehicle>): Boolean = PresenceSessions.mutex.withLock {
        val lease = presenceStorage?.load() ?: return@withLock true
        val saved = session ?: return@withLock false
        if (!epochCurrent()) return@withLock false
        if (lease.identity != presenceIdentity(saved)) { presenceStorage.clear(lease); return@withLock true }
        val vehicle = vehicles.firstOrNull { VehicleOverview.key(it) == lease.vehicleKey } ?: return@withLock false
        withTimeoutOrNull(4_000) {
            try { presence(PresenceType.EXIT, vehicle, saved.accessToken); presenceStorage.clear(lease); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        } == true
    }

    private suspend fun presence(type: PresenceType, vehicle: Vehicle, token: String): Long? {
        val heartbeat = PresenceHeartbeat(type, deviceId, clock.millis())
        val response = appRequest("POST", PresenceHeartbeat.URL.toString(), heartbeat.body(), vehicle.vin,
            accessToken = token, presenceHeartbeat = heartbeat)
        return Capabilities.sourceTime(response.at("rvsVehicleStatusTs"))?.toEpochMilli()
    }

    /** Called only by an explicit refresh. Ordinary probes and automations never enter presence. */
    suspend fun refreshStatusOnline(vehicle: Vehicle, baseline: Probe, current: () -> Boolean = { true },
        published: (Probe) -> Unit = {}, progress: (String) -> Unit = {}): OnlineRefreshResult? {
        if (!OnlineRefreshFlow.needed(baseline, clock.instant()) || !current()) return null
        if (!recoverPresence(listOf(vehicle))) return OnlineRefreshResult(baseline, "上次在线连接待收尾 · 请稍后再试", ProbeOutcome.NETWORK, false)
        return PresenceSessions.mutex.withLock {
            if (!current() || !epochCurrent()) return@withLock null
            val saved = session ?: return@withLock null
            if (lastOnlineAttempt > 0 && clock.millis() - lastOnlineAttempt in 0..59_999)
                return@withLock OnlineRefreshResult(baseline, "刚尝试获取新车况 · 车辆暂无新上报")
            val version = accountVersion.get()
            val lease = PresenceLease(presenceIdentity(saved), VehicleOverview.key(vehicle), clock.millis())
            OnlineRefreshFlow(
                send = { type ->
                    val valid = accountVersion.get() == version && epochCurrent()
                    if (type != PresenceType.EXIT && (!valid || !current())) throw CancellationException("Refresh superseded")
                    // EXIT retains its original account binding even when logout cancels the request.
                    presence(type, vehicle, if (valid) session?.accessToken ?: saved.accessToken else saved.accessToken)
                }, read = { probe(Endpoint.STATUS, vehicle) }, now = clock::instant,
                current = { current() && accountVersion.get() == version && epochCurrent() },
                publish = published, progress = progress,
                beforeEnter = { presenceStorage?.save(lease); lastOnlineAttempt = clock.millis() },
                afterExit = { presenceStorage?.clear(lease) },
            ).run(baseline)
        }
    }

    suspend fun climate(vehicle: Vehicle): ClimateSnapshot {
        val probe = probe(Endpoint.STATUS, vehicle)
        if (probe.outcome != ProbeOutcome.SUCCESS) throw CheckFailure(probe.outcome)
        return ClimateSnapshot.parse(probe)
    }

    /** Each invocation sends exactly once. A missing receipt is not a reason to replay. */
    suspend fun controlVehicle(vehicle: Vehicle, command: VehicleCommand, accepted: () -> Unit,
        before: String? = null, observed: (Probe) -> Unit): CommandResult {
        if (session == null || !epochCurrent()) return CommandResult.REJECTED
        val sentAt = clock.instant()
        try {
            val receipt = appRequest("POST", ClimateRequestPolicy.URL, command.body(), vehicle.vin, vehicleCommand = command)
            if (receipt.at("sessionId").text()?.takeIf { it.isNotBlank() && it.length <= 256 } == null) return CommandResult.UNKNOWN
            accepted()
        } catch (e: CheckFailure) {
            return if (e.outcome in setOf(ProbeOutcome.REJECTED, ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.RATE_LIMITED)) CommandResult.REJECTED else CommandResult.UNKNOWN
        }
        if (command is VehicleCommand.Comfort) {
            // Keep the single-command lane while obtaining initial running feedback. Never call this a setpoint confirmation.
            repeat(3) {
                delay(5_000)
                val probe=probe(Endpoint.STATUS,vehicle); observed(probe)
                if(probe.outcome!=ProbeOutcome.SUCCESS) return CommandResult.ACCEPTED
                val snapshot=ClimateSnapshot.parse(probe)
                val ac=command.targets.firstOrNull { it.channel==ClimateChannel.AC }
                if(snapshot.sourceTime?.let { !it.isBefore(sentAt) && !it.isAfter(clock.instant().plusSeconds(30)) }==true &&
                    ac!=null && snapshot.acOn==(ac.value>0)) return CommandResult.ACCEPTED
            }
            return CommandResult.ACCEPTED
        }
        val expected = (command as? VehicleCommand.Body)?.action?.observation ?: return CommandResult.ACCEPTED
        val pending = PendingBody(command.action, sentAt.toEpochMilli(), before)
        val endpoint = if (expected.first == "sentry") Endpoint.SENTRY else Endpoint.STATUS
        repeat(8) {
            delay(5_000)
            val probe = probe(endpoint, vehicle)
            observed(probe)
            if (probe.outcome != ProbeOutcome.SUCCESS) return CommandResult.ACCEPTED
            if (pending.matches(probe)) return CommandResult.MATCHED
        }
        return CommandResult.ACCEPTED
    }

    /** A transport success is acceptance only. Never retry an ambiguous physical command. */
    suspend fun controlClimate(vehicle: Vehicle, target: ClimateTarget, accepted: () -> Unit,
        observed: (ClimateSnapshot) -> Unit): ClimateResult {
        if (session == null || !epochCurrent()) return ClimateResult.REJECTED
        val sentAt = clock.instant()
        try {
            val receipt = appRequest("POST", ClimateRequestPolicy.URL, target.body(), vehicle.vin, target)
            val session = receipt.at("sessionId").text()
            if (session.isNullOrBlank() || session.length > 256) return ClimateResult.UNKNOWN
            accepted()
        } catch (e: CheckFailure) {
            return if (e.outcome in setOf(ProbeOutcome.REJECTED, ProbeOutcome.AUTH_REQUIRED, ProbeOutcome.RATE_LIMITED))
                ClimateResult.REJECTED else ClimateResult.UNKNOWN
        }
        // 1.6.6's known status model exposes cabin temperature, not the AC setpoint.
        // Do not infer a setpoint confirmation from a temperature or power-state change.
        if (target.channel == ClimateChannel.AC && target.value > 0) {
            repeat(3) {
                delay(5_000)
                val snapshot=try { climate(vehicle) } catch (_: CheckFailure) { return ClimateResult.NEEDS_CHECK }
                observed(snapshot)
                if(snapshot.acOn==true && snapshot.sourceTime?.let { !it.isBefore(sentAt) && !it.isAfter(clock.instant().plusSeconds(30)) }==true)
                    return ClimateResult.NEEDS_CHECK
            }
            return ClimateResult.NEEDS_CHECK
        }
        return withTimeoutOrNull(75_000) {
            repeat(15) {
                delay(4_000)
                val snapshot = try { climate(vehicle) } catch (_: CheckFailure) { return@withTimeoutOrNull ClimateResult.UNKNOWN }
                observed(snapshot)
                if (snapshot.confirms(target, sentAt, clock.instant())) return@withTimeoutOrNull ClimateResult.MATCHED
            }
            ClimateResult.UNKNOWN
        } ?: ClimateResult.UNKNOWN
    }

    private suspend fun auth(method: String, urlText: String, body: String? = null, userToken: String? = null): JsonElement {
        val url = urlText.toHttpUrl()
        val headers = authHeaders + Signatures.authHeaders(method, url, config, clock.instant(), body) +
            (userToken?.let { mapOf("authorization" to it) } ?: emptyMap())
        return send(method, url, headers, body)
    }
    private suspend fun appRequest(method: String, urlText: String, body: String? = null, vin: String? = null,
        climateTarget: ClimateTarget? = null, accessToken: String = session?.accessToken.orEmpty(), vehicleCommand: VehicleCommand? = null,
        presenceHeartbeat: PresenceHeartbeat? = null): JsonElement {
        val url = urlText.toHttpUrl()
        val headers = mutableMapOf(
            "ACCEPT-LANGUAGE" to "en-AU", "AppId" to "ONEX97FB91F061405", "authorization" to accessToken,
            "Content-Type" to jsonType.toString(), "user-agent" to "okhttp/4.12.0", "X-API-SIGNATURE-VERSION" to "2.0",
            "X-APP-ID" to "ZEEKRCNCH001M0001", "x-app-os-version" to "", "x-device-id" to deviceId, "x-p" to "Android",
            "X-PLATFORM" to "APP", "X-PROJECT-ID" to "ZEEKR_SEA", "X-API-SIGNATURE-NONCE" to UUID.randomUUID().toString(),
            "X-TIMESTAMP" to clock.millis().toString(),
        )
        if (vin != null) headers["X-VIN"] = Signatures.encryptVin(vin, config)
        headers["X-SIGNATURE"] = Signatures.appSignature(method, url, headers, body, config.prodSecret)
        return send(method, url, headers, body, climateTarget, vehicleCommand, presenceHeartbeat)
    }
    private suspend fun send(method: String, url: HttpUrl, headers: Map<String, String>, body: String?,
        climateTarget: ClimateTarget? = null, vehicleCommand: VehicleCommand? = null, presenceHeartbeat: PresenceHeartbeat? = null): JsonElement {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, if (method == "POST") (body ?: "").toRequestBody(jsonType) else null).build()
        return if (presenceHeartbeat != null) {
            check(vehicleCommand == null && climateTarget == null)
            transport.executePresence(request, presenceHeartbeat)
        } else if (vehicleCommand != null) {
            check(VehicleCommandPolicy.allowed(request, vehicleCommand)); ++vehicleRequestAttempts
            transport.executeVehicle(request, vehicleCommand)
        } else if (climateTarget == null) transport.execute(request) else {
            check(ClimateRequestPolicy.allowed(request, climateTarget))
            ++climateRequestAttempts
            transport.executeClimate(request, climateTarget)
        }
    }
}
