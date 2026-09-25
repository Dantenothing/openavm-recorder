package com.dante.zeekrcheck.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class CloudOnlineRefreshTest {
    private val config = Fixture.config()
    private val base = 1_800_000_000_000L
    private val vehicle = Vehicle("L6T00000000000001", emptyList())
    private val saved = SavedSession("synthetic-user", "synthetic-access", "f7645262-0e95-4300-8ad3-4208e643930a", config.fingerprint())
    private class Memory : PresenceLeaseStorage {
        var lease: PresenceLease? = null
        override fun load() = lease
        override fun save(lease: PresenceLease) { this.lease = lease }
        override fun clear(lease: PresenceLease) { if (this.lease == lease) this.lease = null }
    }
    // Synthetic interceptor responses execute inline, so virtual test deadlines cannot race a real IO thread.
    private class DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
    private fun status(source: Long) = """{"additionalVehicleStatus":{"climateStatus":{"interiorTemp":19.9,"updateTime":$source}}}"""
    private fun baseline() = Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(base), Json.parseToJsonElement(status(base - 3_600_000)))
    private fun client(store: Memory, now: () -> Long, session: SavedSession = saved, reply: (Request) -> String): CloudClient {
        val http = OkHttpClient.Builder().dispatcher(Dispatcher(DirectExecutor())).retryOnConnectionFailure(false).addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(it.request()).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val clock = object : Clock() {
            override fun instant() = Instant.ofEpochMilli(now())
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        return CloudClient(config, Fixture.transport(http), clock, restoredSession = session, presenceStorage = store)
    }
    private fun type(request: Request) = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject["hbType"]!!.jsonPrimitive.int
    private fun ordinary(request: Request, source: Long) = """{"success":true,"data":${if (request.url.encodedPath == "/${RequestPolicy.VEHICLES}")
        """[{"vin":"${vehicle.vin}"}]""" else status(source)}}"""

    @Test fun manualFlowSignsOnlyExactHeartbeatRouteAndKeepsVehicleCountersZero() = runTest {
        val requests = mutableListOf<Request>(); val store = Memory()
        val client = client(store, { base + testScheduler.currentTime }) {
            requests += it
            if (it.method == "POST") """{"success":true}""" else ordinary(it, base + testScheduler.currentTime)
        }
        val result = client.refreshStatusOnline(vehicle, baseline())!!
        assertTrue(result.exitConfirmed)
        assertTrue(result.message.contains("新车况上报"))
        val posts = requests.filter { it.method == "POST" }
        assertEquals(listOf(1, 3, 3, 2), posts.map(::type))
        assertTrue(posts.all { it.url == PresenceHeartbeat.URL && !it.header("X-SIGNATURE").isNullOrBlank() &&
            it.header("X-VIN") == Signatures.encryptVin(vehicle.vin, config) && it.header("authorization") == saved.accessToken })
        assertTrue(posts.none { RequestPolicy.allowed(it.method, it.url) })
        assertNull(store.lease)
        assertEquals(0, client.vehicleRequestAttempts + client.climateRequestAttempts + client.passwordLogins)
        val count = requests.size
        client.refreshStatusOnline(vehicle, baseline())
        assertEquals(count, requests.size) // Stale cloud data cannot bypass the one-minute manual cooldown.
    }

    @Test fun passiveReadAndFreshManualResultNeverEnterOnline() = runTest {
        val requests = mutableListOf<Request>()
        val client = client(Memory(), { base }) { requests += it; ordinary(it, base - 10_000) }
        val read = client.probe(Endpoint.STATUS, vehicle)
        assertNull(client.refreshStatusOnline(vehicle, read))
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test fun failedExitIsRecoveredAfterRestartWithoutReenteringOrRelogging() = runTest {
        val store = Memory(); val calls = mutableListOf<Int>()
        val first = client(store, { base + testScheduler.currentTime }) {
            if (it.method == "POST") {
                calls += type(it)
                if (type(it) == 2) throw IOException("synthetic disconnect")
                """{"success":true,"data":{}}"""
            } else ordinary(it, base + testScheduler.currentTime)
        }
        assertFalse(first.refreshStatusOnline(vehicle, baseline())!!.exitConfirmed)
        assertNotNull(store.lease)
        assertEquals(2, calls.count { it == 2 })
        val recoveryCalls = mutableListOf<Int>()
        val restarted = client(store, { base + testScheduler.currentTime }) {
            if (it.method == "POST") { recoveryCalls += type(it); """{"success":true}""" }
            else ordinary(it, base)
        }
        assertEquals(1, restarted.resumeSession().size)
        assertEquals(listOf(2), recoveryCalls)
        assertNull(store.lease)
        assertEquals(0, restarted.passwordLogins)
    }

    @Test fun aDifferentAccountNeverSendsThePreviousAccountsExit() = runTest {
        val store = Memory()
        store.lease = PresenceLease("f".repeat(64), VehicleOverview.key(vehicle), base)
        val requests = mutableListOf<Request>()
        val client = client(store, { base }) { requests += it; ordinary(it, base) }
        client.resumeSession()
        assertTrue(requests.all { it.method == "GET" })
        assertNull(store.lease)
    }

    @Test fun logoutPreventsOnlineEntry() = runTest {
        val requests = mutableListOf<Request>()
        val client = client(Memory(), { base }) { requests += it; ordinary(it, base) }
        client.clearSession()
        assertNull(client.refreshStatusOnline(vehicle, baseline()))
        assertTrue(requests.isEmpty())
    }

    @Test fun heartbeatPolicyRejectsControlPathsBodiesAndOtherIdentities() {
        val heartbeat = PresenceHeartbeat(PresenceType.ENTER, saved.deviceId, base)
        val valid = Request.Builder().url(PresenceHeartbeat.URL).header("x-device-id", saved.deviceId)
            .post(heartbeat.body().toRequestBody("application/json".toMediaType())).build()
        assertTrue(heartbeat.allows(valid))
        assertFalse(heartbeat.allows(valid.newBuilder().url(ClimateRequestPolicy.URL).build()))
        assertFalse(heartbeat.allows(valid.newBuilder().url(PresenceHeartbeat.URL.newBuilder().addQueryParameter("x", "1").build()).build()))
        assertFalse(heartbeat.allows(valid.newBuilder().header("x-device-id", "another-device").build()))
        assertFalse(heartbeat.allows(valid.newBuilder().post("{}".toRequestBody()).build()))
        assertFalse(heartbeat.allows(valid.newBuilder().post("{".toRequestBody()).build()))
        assertFalse(heartbeat.allows(valid.newBuilder().get().build()))
    }
}
