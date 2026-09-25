package com.dante.zeekrcheck

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/** Test APK only. Explicit opt-in, paired presence/exit, bounded duration, no vehicle controls.
 * Credentials remain in target-app memory; the report contains allowlisted observations only.
 */
class HeartbeatRefreshDiagnosticTest {
    private class Stop(val reason: String) : Exception()

    @Test fun policyRejectsNonDiagnosticTraffic() {
        val device = "f7645262-0e95-4300-8ad3-4208e643930a"
        val now = 1_800_000_000_000L
        fun request(type: Int = 1, id: String = device, at: Long = now, extra: Boolean = false): Request =
            Request.Builder().url(PresenceProbePolicy.url).post(buildJsonObject {
                put("hbType", type); put("deviceId", id); put("deviceType", 1); put("ts", at)
                if (extra) put("control", "not_allowed")
            }.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
        for (type in 1..3) assertTrue(PresenceProbePolicy.allowed(request(type), device, now))
        assertFalse(PresenceProbePolicy.allowed(request(4), device, now))
        assertFalse(PresenceProbePolicy.allowed(request(id = "another-device"), device, now))
        assertFalse(PresenceProbePolicy.allowed(request(at = now - 60_000), device, now))
        assertFalse(PresenceProbePolicy.allowed(request(extra = true), device, now))
        assertFalse(PresenceProbePolicy.allowed(request().newBuilder().url(PresenceProbePolicy.url.newBuilder()
            .addQueryParameter("control", "1").build()).build(), device, now))
        assertFalse(PresenceProbePolicy.allowed(request().newBuilder()
            .url(RequestPolicy.TSP_BASE + "ms-remote-control/v1.0/remoteControl/control").build(), device, now))
        assertFalse(PresenceProbePolicy.allowed(request().newBuilder()
            .url(PresenceProbePolicy.url.newBuilder().host("example.com").build()).build(), device, now))
        assertFalse(PresenceProbePolicy.allowed(request().newBuilder().get().build(), device, now))
        assertTrue(PresenceProbePolicy.allowed(Request.Builder().url(RequestPolicy.TSP_BASE +
            Endpoint.STATUS.path + "?latest=false&target=new").get().build(), device, now))
        assertFalse(PresenceProbePolicy.allowed(Request.Builder().url(RequestPolicy.TSP_BASE +
            Endpoint.STATUS.path + "?latest=true&target=new").get().build(), device, now))
    }

    @Test fun compareIdleReadsWithBoundedOnlinePresence() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOnlinePresence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ProtocolConfig.parse(SecureConfigStore(context).load() ?: error("Configuration unavailable"))
        val saved = SecureSessionStore(context).load() ?: error("Saved session unavailable")
        check(saved.matches(config))
        val blocked = AtomicInteger()
        val gets = AtomicInteger()
        val heartbeats = AtomicIntegerArray(4)
        val events = mutableListOf<JsonObject>()
        val started = System.currentTimeMillis()
        var result = "RUNNING"
        var cleanup = "NOT_NEEDED"
        var needsExit = false
        var baselineSource: Long? = null
        var onlineSource: Long? = null
        var latestRvs: Long? = null
        var vinHeader: String? = null
        val file = AtomicFile(File(context.filesDir, "heartbeat-refresh-diagnostic.json"))
        fun save() {
            val report = buildJsonObject {
                put("schema", "zeekr-online-presence-trial/1")
                put("scope", "STATUS_GET_AND_BOUNDED_ONLINE_PRESENCE_ONLY")
                put("started", started); put("updated", System.currentTimeMillis())
                put("result", result); put("cleanup", cleanup)
                put("baselineSource", baselineSource?.let(::JsonPrimitive) ?: JsonNull)
                put("onlineSource", onlineSource?.let(::JsonPrimitive) ?: JsonNull)
                put("blockedAttempts", blocked.get()); put("getAttempts", gets.get())
                putJsonObject("heartbeatAttempts") { for (type in 1..3) put(type.toString(), heartbeats.get(type)) }
                putJsonArray("events") { events.forEach { add(it) } }
            }.toString()
            val out = file.startWrite()
            try { out.write(report.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
            catch (e: Exception) { file.failWrite(out); throw e }
        }
        fun event(value: JsonObject) { events.add(value); save() }
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (!PresenceProbePolicy.allowed(request, saved.deviceId, System.currentTimeMillis())) {
                    blocked.incrementAndGet(); throw IOException("Diagnostic traffic rejected")
                }
                if (request.method == "GET") {
                    if (gets.incrementAndGet() > 14) throw IOException("Read limit reached")
                } else {
                    val type = PresenceProbePolicy.body(request)?.get("hbType")?.jsonPrimitive?.intOrNull
                        ?: throw IOException("Invalid heartbeat type")
                    val max = when (type) { 1 -> 1; 2 -> 2; else -> 6 }
                    if (heartbeats.incrementAndGet(type) > max) throw IOException("Presence limit reached")
                }
                chain.proceed(request)
            }.build()
        // No persistence callback and no allowed credential exchange; this probe cannot replace login.
        val client = CloudClient(config, ReadOnlyTransport(http), restoredSession = saved)
        suspend fun heartbeat(type: Int): Boolean {
            val at = System.currentTimeMillis()
            val body = buildJsonObject {
                put("hbType", type); put("deviceId", saved.deviceId); put("deviceType", 1); put("ts", at)
            }.toString()
            val headers = mutableMapOf(
                "ACCEPT-LANGUAGE" to "en-AU", "AppId" to "ONEX97FB91F061405", "authorization" to saved.accessToken,
                "Content-Type" to "application/json; charset=UTF-8", "user-agent" to "okhttp/4.12.0",
                "X-API-SIGNATURE-VERSION" to "2.0", "X-APP-ID" to "ZEEKRCNCH001M0001",
                "x-app-os-version" to "", "x-device-id" to saved.deviceId, "x-p" to "Android",
                "X-PLATFORM" to "APP", "X-PROJECT-ID" to "ZEEKR_SEA",
                "X-API-SIGNATURE-NONCE" to UUID.randomUUID().toString(), "X-TIMESTAMP" to at.toString(),
                // Official 1.6.6 oj.a supplies the selected encrypted VIN to heartbeat requests too.
                "X-VIN" to checkNotNull(vinHeader) { "Selected vehicle required" },
            )
            headers["X-SIGNATURE"] = Signatures.appSignature("POST", PresenceProbePolicy.url, headers, body, config.prodSecret)
            val request = Request.Builder().url(PresenceProbePolicy.url)
                .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            var outcome = "NOT_SENT"
            var code: Int? = null
            var rvs: Long? = null
            try {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        code = response.code
                        if (!response.isSuccessful) throw CheckFailure(ProbeOutcome.REJECTED, response.code)
                        val source = response.body?.source() ?: throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
                        source.request(65_537)
                        if (source.buffer.size > 65_536) throw CheckFailure(ProbeOutcome.INVALID_RESPONSE)
                        val data = ReadOnlyTransport.decodeResponse(source.readByteArray().toString(Charsets.UTF_8), requireData = false)
                        rvs = Capabilities.sourceTime(data.at("rvsVehicleStatusTs"))?.toEpochMilli()
                    }
                }
                outcome = "SUCCESS"
                latestRvs = rvs ?: latestRvs
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { outcome = if (e is CheckFailure) e.outcome.name else "NETWORK" }
            finally {
                event(buildJsonObject {
                    put("kind", "heartbeat"); put("type", type); put("at", at); put("outcome", outcome)
                    put("http", code?.let(::JsonPrimitive) ?: JsonNull)
                    put("rvsSource", rvs?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
            return outcome == "SUCCESS"
        }
        fun localIdle() {
            val state = AssistantStore.get(context).state.value
            if (state.activePreparation?.finished == false || state.operationPending) throw Stop("APP_OPERATION_ACTIVE")
        }
        suspend fun status(vehicle: Vehicle, phase: String): ClimateSnapshot {
            localIdle()
            val probe = client.probe(Endpoint.STATUS, vehicle)
            if (probe.outcome != ProbeOutcome.SUCCESS) throw Stop("STATUS_${probe.outcome.name}")
            val climate = ClimateSnapshot.parse(probe)
            val parking = ParkingEvidence.from(probe)
            val charging = probe.data.at("additionalVehicleStatus.electricVehicleStatus.chargerState").text()?.toIntOrNull()
            event(buildJsonObject {
                put("kind", "status"); put("phase", phase); put("at", probe.fetchedAt.toEpochMilli())
                put("source", climate.sourceTime?.toEpochMilli()?.let(::JsonPrimitive) ?: JsonNull)
                put("temperature", climate.cabinTemperature?.let(::JsonPrimitive) ?: JsonNull)
                put("acOn", climate.acOn?.let(::JsonPrimitive) ?: JsonNull)
                put("blowerActive", climate.blowerActive?.let(::JsonPrimitive) ?: JsonNull)
                put("parked", parking?.parked?.let(::JsonPrimitive) ?: JsonNull)
                put("locked", parking?.locked?.let(::JsonPrimitive) ?: JsonNull)
                put("chargerState", charging?.takeIf { it in 0..255 }?.let(::JsonPrimitive) ?: JsonNull)
            })
            if (climate.acOn != false || climate.blowerActive == true || parking?.parked != true || charging in setOf(2, 15, 24))
                throw Stop("VEHICLE_NOT_IDLE_OR_UNKNOWN")
            val sourceTime = climate.sourceTime
            if (sourceTime == null || sourceTime.isAfter(Instant.now().plusSeconds(30)))
                throw Stop("INVALID_CLIMATE_SOURCE")
            return climate
        }
        save()
        try {
            withTimeout(240_000) {
                localIdle()
                val selected = OverviewStore.get(context).state.value.vehicleKey
                val vehicle = client.resumeSession().firstOrNull { VehicleOverview.key(it) == selected }
                    ?: throw Stop("SELECTED_VEHICLE_UNAVAILABLE")
                vinHeader = Signatures.encryptVin(vehicle.vin, config)
                val first = status(vehicle, "ordinary_start")
                if (Instant.now().toEpochMilli() - first.sourceTime!!.toEpochMilli() < 300_000)
                    throw Stop("BASELINE_NOT_STALE")
                delay(40_000)
                val baseline = status(vehicle, "ordinary_end")
                baselineSource = baseline.sourceTime!!.toEpochMilli()
                if (baseline.sourceTime != first.sourceTime) throw Stop("ORDINARY_SOURCE_ALREADY_ADVANCING")
                val onlineAt = System.currentTimeMillis()
                needsExit = true // An ambiguous response can still have registered presence.
                cleanup = "PENDING"
                if (!heartbeat(1)) throw Stop("ONLINE_ENTRY_NOT_CONFIRMED")
                var lastNotice: Long? = null
                for (index in 0..5) {
                    if (index > 0) delay(20_000)
                    localIdle()
                    if (!heartbeat(3)) throw Stop("HEARTBEAT_NOT_CONFIRMED")
                    if (index == 0 || index == 2 || index == 4 || latestRvs != lastNotice) {
                        val reading = status(vehicle, "online_${index * 20}s")
                        onlineSource = reading.sourceTime!!.toEpochMilli()
                        if (onlineSource!! > baselineSource!! && onlineSource!! >= onlineAt) {
                            result = "NEW_CLIMATE_REPORT_DURING_ONLINE_WINDOW"
                            break
                        }
                    }
                    lastNotice = latestRvs
                }
                if (result == "RUNNING") {
                    delay(20_000)
                    onlineSource = status(vehicle, "online_deadline").sourceTime!!.toEpochMilli()
                    result = if (onlineSource!! > baselineSource!! && onlineSource!! >= onlineAt)
                        "NEW_CLIMATE_REPORT_DURING_ONLINE_WINDOW" else "NO_NEW_CLIMATE_REPORT_IN_ONLINE_WINDOW"
                }
            }
        } catch (e: Stop) { result = e.reason }
        catch (e: TimeoutCancellationException) { result = "TRIAL_DEADLINE" }
        catch (e: Exception) { result = if (e is CheckFailure) "TRIAL_${e.outcome.name}" else "TRIAL_ABORTED" }
        finally {
            if (needsExit) withContext(NonCancellable) {
                cleanup = if (heartbeat(2)) "CONFIRMED" else "UNCONFIRMED"
                if (cleanup != "CONFIRMED") {
                    delay(1_000)
                    cleanup = if (heartbeat(2)) "CONFIRMED_AFTER_RETRY" else "UNCONFIRMED"
                }
            }
            event(buildJsonObject {
                put("kind", "checks"); put("at", System.currentTimeMillis())
                put("passwordLogins", client.passwordLogins)
                put("vehicleControlAttempts", client.vehicleRequestAttempts)
                put("climateControlAttempts", client.climateRequestAttempts)
                val sessionAfter = SecureSessionStore(context).load()
                put("savedSessionUnchanged", sessionAfter?.accessToken == saved.accessToken &&
                    sessionAfter?.userToken == saved.userToken && sessionAfter?.deviceId == saved.deviceId)
            })
            http.connectionPool.evictAll()
        }
        assertEquals(0, client.passwordLogins)
        assertEquals(0, client.vehicleRequestAttempts)
        assertEquals(0, client.climateRequestAttempts)
        assertEquals(0, blocked.get())
        assertTrue(!needsExit || cleanup.startsWith("CONFIRMED"))
    }
}

/** Nothing outside this test APK can use the additional POST route. */
private object PresenceProbePolicy {
    val url = (RequestPolicy.TSP_BASE + "ms-app-online-manager/api/v1.0/app/hb").toHttpUrl()
    fun body(request: Request): JsonObject? = runCatching {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        if (buffer.size !in 1..1_024) return null
        Json.parseToJsonElement(buffer.readUtf8()) as? JsonObject
    }.getOrNull()
    fun allowed(request: Request, device: String, now: Long): Boolean {
        if (request.method == "GET") return RequestPolicy.allowed("GET", request.url) && request.url.encodedPath in
            setOf("/${RequestPolicy.VEHICLES}", "/${Endpoint.STATUS.path}")
        if (request.method != "POST" || request.url != url) return false
        val data = body(request) ?: return false
        return data.keys == setOf("hbType", "deviceId", "deviceType", "ts") &&
            data["hbType"] in (1..3).map(::JsonPrimitive) && data["deviceType"] == JsonPrimitive(1) &&
            data["deviceId"] == JsonPrimitive(device) && data["ts"]?.jsonPrimitive?.longOrNull
                ?.let { now - it in -30_000..30_000 } == true
    }
}
