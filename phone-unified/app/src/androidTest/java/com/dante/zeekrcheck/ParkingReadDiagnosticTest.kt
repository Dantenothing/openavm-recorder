package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit live GET only. Existing encrypted credentials stay on the phone; no raw payload is saved. */
class ParkingReadDiagnosticTest {
    @Test fun inspectOfficialParkingSignalsWithoutVehicleWrites() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("parkingReadOnly") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CloudAccess.loaded(context)
        val config = ProtocolConfig.parse(SecureConfigStore(context).load() ?: error("Configuration unavailable"))
        val saved = SecureSessionStore(context).load() ?: error("Saved session unavailable")
        val blocked = AtomicInteger(0)
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (chain.request().method != "GET") { blocked.incrementAndGet(); throw IOException("Parking inspection blocks writes") }
                chain.proceed(chain.request())
            }.build()
        val client = CloudClient(config, ReadOnlyTransport(http, CloudAccess.permit()), restoredSession = saved)
        val selected = OverviewStore.get(context).state.value.vehicleKey
        val vehicle = client.resumeSession().firstOrNull { VehicleOverview.key(it) == selected } ?: error("Selected vehicle unavailable")
        val probe = client.probe(Endpoint.STATUS, vehicle)
        val sentry = client.probe(Endpoint.SENTRY, vehicle)
        val settings = AssistantStore.get(context).state.value
        val time = System.currentTimeMillis()
        val evidence = ParkingEvidence.from(probe)
        val beforeGuard = settings.parkingGuard.takeIf { it.vehicleKey == selected } ?: ParkingGuard(selected ?: "")
        val observed = beforeGuard.observe(probe, time).observe(sentry, time)
        val decision = observed.decision(probe, sentry, settings.home, settings.homeRadius, settings.location?.verified == true, time)
        fun code(path: String) = probe.data.at(path).text()?.takeIf { it.matches(Regex("[0-9A-Za-z_/-]{1,24}")) }
        val fields = listOf("basicVehicleStatus.speed", "basicVehicleStatus.speedUnit", "basicVehicleStatus.speedValidity",
            "basicVehicleStatus.usageMode", "basicVehicleStatus.carMode", "basicVehicleStatus.engineStatus", "basicVehicleStatus.keyStatus",
            "additionalVehicleStatus.drivingSafetyStatus.electricParkBrakeStatus", "additionalVehicleStatus.drivingSafetyStatus.centralLockingStatus",
            "additionalVehicleStatus.electricVehicleStatus.chargerState", "basicVehicleStatus.position.posCanBeTrusted")
        val times = listOf("updateTime", "basicVehicleStatus.updateTime", "basicVehicleStatus.position.updateTime",
            "additionalVehicleStatus.drivingSafetyStatus.updateTime", "additionalVehicleStatus.climateStatus.updateTime", "additionalVehicleStatus.maintenanceStatus.updateTime")
        val report = buildJsonObject {
            put("schema", "zeekr-parking-read/1"); put("outcome", probe.outcome.name); put("fetchedAt", probe.fetchedAt.toString())
            putJsonObject("fields") { fields.forEach { put(it, code(it)?.let(::JsonPrimitive) ?: JsonNull) } }
            putJsonObject("sourceTimes") { times.forEach { put(it, Capabilities.sourceTime(probe.data.at(it))?.toString()?.let(::JsonPrimitive) ?: JsonNull) } }
            put("blockedWriteAttempts", blocked.get()); put("passwordLogins", client.passwordLogins)
            put("vehicleControlAttempts", client.vehicleRequestAttempts); put("climateControlAttempts", client.climateRequestAttempts)
            put("odometerAvailable", evidence?.odometer != null)
            put("odometerPositive", evidence?.odometer?.let { it > 0 } == true)
            put("odometerInteger", evidence?.odometer?.let { it % 1.0 == 0.0 } == true)
            put("odometerRecent", ParkingEvidence.fresh(evidence?.odometerTime, time))
            put("parkingMileageUsable", ParkedMileage.from(evidence, time) != null)
            putJsonObject("parkingCheck") {
                put("state", evidence?.brake?.name ?: "UNKNOWN"); put("recent", evidence?.recent(time) == true)
                put("aggregateTime", evidence?.aggregateMotionTime == true); put("phase", observed.phase.name)
                put("guardEnabled", settings.guardEnabled); put("rulesPaused", settings.paused)
                put("parkingPaused", observed.paused); put("homeConfigured", settings.home?.verified == true)
                put("positionVerified", settings.location?.verified == true)
                put("wouldEnable", decision.enable); put("reason", decision.reason)
            }
        }
        File(context.filesDir, "parking-read-diagnostic.json").writeText(report.toString())
        assertEquals(ProbeOutcome.SUCCESS, probe.outcome)
        assertEquals(ProbeOutcome.SUCCESS, sentry.outcome)
        assertNotNull(evidence)
        assertEquals(0, blocked.get()); assertEquals(0, client.passwordLogins)
        assertEquals(0, client.vehicleRequestAttempts); assertEquals(0, client.climateRequestAttempts)
    }
}
