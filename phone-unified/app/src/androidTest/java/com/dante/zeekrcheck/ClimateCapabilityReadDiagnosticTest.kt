package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import java.util.UUID

/** Explicit HVAC investigation: existing login, GET only, bounded fields, no raw response or vehicle writes. */
class ClimateCapabilityReadDiagnosticTest {
    @Test fun inspectClimateFieldsWithoutVehicleWrites() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("climateReadOnly") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ProtocolConfig.parse(SecureConfigStore(context).load() ?: error("Configuration unavailable"))
        val saved = SecureSessionStore(context).load() ?: error("Saved session unavailable")
        val blocked = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val capabilityUrl = (RequestPolicy.TSP_BASE + "ms-vehicle-capability/api/v1.0/vehicle/function/model/info").toHttpUrl()
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (chain.request().method != "GET" ||
                    !(RequestPolicy.allowed("GET", chain.request().url) || chain.request().url == capabilityUrl)) {
                    blocked.incrementAndGet()
                    throw IOException("Climate inspection blocks writes, including session exchanges")
                }
                reads.incrementAndGet()
                chain.proceed(chain.request())
            }.build()
        // No persistence callback: this diagnostic cannot replace or clear the saved login.
        val client = CloudClient(config, ReadOnlyTransport(http), restoredSession = saved)
        val selected = OverviewStore.get(context).state.value.vehicleKey
        val vehicle = client.resumeSession().firstOrNull { VehicleOverview.key(it) == selected }
            ?: error("Selected vehicle unavailable")
        val probe = client.probe(Endpoint.STATUS, vehicle)
        val climate = probe.data.at("additionalVehicleStatus.climateStatus")
        val names = listOf("interiorTemp", "exteriorTemp", "preClimateActive", "airBlowerActive", "cdsClimateActive",
            "steerWhlHeatingSts", "steerWhlHeatgAvlSts", "drvHeatSts", "passHeatingSts", "drvVentSts", "drvVentDetail",
            "passVentSts", "passVentDetail", "ventilateStatus", "defrost", "defrostActive", "airCleanSts",
            "acTemp", "targetTemp", "targetTemperature", "setTemp", "fanSpeed", "fanLevel", "blowerSpeed", "windSpeed",
            "rapidCoolingActive", "rapidWarmingActive", "crSetTemp", "currentTemperature", "climateSts", "activeStatus",
            "cabinTempReductionStatus", "interiorTempValidity", "vtmTemperature", "vtmTsActive")
        var capabilityOutcome = "NOT_REQUESTED"
        val codes = sortedSetOf<String>()
        val capabilityFields = mutableListOf<JsonObject>()
        if (InstrumentationRegistry.getArguments().getString("climateReadCapabilities") == "true") {
            // This exact read endpoint is independently verified in official 1.6.6 VehicleRemoteControlApi.
            // Keep it test-only rather than broadening the production transport allowlist for research.
            val headers = mutableMapOf(
                "ACCEPT-LANGUAGE" to "en-AU", "AppId" to "ONEX97FB91F061405", "authorization" to saved.accessToken,
                "Content-Type" to "application/json; charset=utf-8", "user-agent" to "okhttp/4.12.0", "X-API-SIGNATURE-VERSION" to "2.0",
                "X-APP-ID" to "ZEEKRCNCH001M0001", "x-app-os-version" to "", "x-device-id" to saved.deviceId, "x-p" to "Android",
                "X-PLATFORM" to "APP", "X-PROJECT-ID" to "ZEEKR_SEA", "X-API-SIGNATURE-NONCE" to UUID.randomUUID().toString(),
                "X-TIMESTAMP" to Instant.now().toEpochMilli().toString(), "X-VIN" to Signatures.encryptVin(vehicle.vin, config),
            )
            headers["X-SIGNATURE"] = Signatures.appSignature("GET", capabilityUrl, headers, null, config.prodSecret)
            val request = Request.Builder().url(capabilityUrl).get().apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            try {
                val result = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw CheckFailure(ProbeOutcome.REJECTED, response.code)
                    val source = response.body?.source() ?: error("Empty capability response")
                    source.request(2_097_153)
                    check(source.buffer.size <= 2_097_152)
                    ReadOnlyTransport.decodeResponse(source.readByteArray().toString(Charsets.UTF_8))
                }
                fun visit(element: JsonElement) {
                    if (element is JsonObject) {
                        val code = element["functionCode"].text()?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
                        if (code != null) {
                            codes.add(code)
                            if (code.contains(Regex("ZAF|climate|air|heat|vent|cool|steer|wind|fan", RegexOption.IGNORE_CASE))) {
                                capabilityFields.add(buildJsonObject {
                                    put("functionCode", code)
                                    listOf("paramValueUse", "paramValueCode", "paramValue").forEach { key ->
                                        element[key].text()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }
                                            ?.let { put(key, it) }
                                    }
                                })
                            }
                        }
                        element.values.forEach(::visit)
                    } else if (element is JsonArray) element.forEach(::visit)
                }
                visit(result)
                capabilityOutcome = "SUCCESS"
            } catch (error: Exception) {
                capabilityOutcome = if (error is CheckFailure) error.outcome.name else error.javaClass.simpleName
            }
        }
        // Only allow numeric/boolean telemetry. Unexpected strings are described by type, never copied.
        fun boundedValue(value: JsonElement?): JsonElement {
            val raw = (value as? JsonPrimitive)?.contentOrNull ?: return JsonNull
            if (raw == "true" || raw == "false") return JsonPrimitive(raw == "true")
            val number = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in -100.0..1000.0 }
            return number?.let(::JsonPrimitive) ?: JsonNull
        }
        val report = buildJsonObject {
            put("schema", "zeekr-climate-read/1"); put("scope", "GET_ONLY_NO_VEHICLE_CONTROLS")
            put("outcome", probe.outcome.name); put("fetchedAt", probe.fetchedAt.toString())
            put("climateSourceTime", Capabilities.sourceTime(climate.at("updateTime"))?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("capabilityReadOutcome", capabilityOutcome)
            putJsonArray("functionCodes") { codes.forEach { add(it) } }
            putJsonArray("climateFunctionConfiguration") { capabilityFields.forEach { add(it) } }
            putJsonObject("fields") {
                names.forEach { name ->
                    val value = climate.at(name)
                    put(name, buildJsonObject {
                        put("present", value != null); put("nonNull", value != null && value != JsonNull)
                        put("value", boundedValue(value))
                    })
                }
            }
            // Key names only, scoped to the HVAC object; no identity/location/account objects.
            putJsonArray("climateFieldNames") {
                (climate as? JsonObject)?.keys?.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }
                    ?.sorted()?.forEach { add(it) }
            }
            put("httpGetAttempts", reads.get()); put("blockedWriteAttempts", blocked.get())
            put("passwordLogins", client.passwordLogins); put("vehicleControlAttempts", client.vehicleRequestAttempts)
            put("climateControlAttempts", client.climateRequestAttempts)
        }
        File(context.filesDir, "climate-capability-read-diagnostic.json").writeText(report.toString())
        assertEquals(ProbeOutcome.SUCCESS, probe.outcome)
        assertEquals(0, blocked.get()); assertEquals(0, client.passwordLogins)
        assertEquals(0, client.vehicleRequestAttempts); assertEquals(0, client.climateRequestAttempts)
    }
}
