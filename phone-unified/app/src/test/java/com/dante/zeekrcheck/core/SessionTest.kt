package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionTest {
    private fun saved(access: String = "synthetic-old-access") = SavedSession("synthetic-user-token", access,
        "d294932f-f97e-4b6d-a63c-41bfa80d83ca", Fixture.config().fingerprint())
    private val vehicles = """[{"vin":"L6T00000000000001","isShared":true}]"""
    private fun ok(data: String) = """{"success":true,"data":$data}"""
    private fun client(session: SavedSession = saved(), changed: suspend (SavedSession?) -> Unit = {}, shared: SessionPersistence? = null,
        respond: (Request) -> Pair<Int, String>): CloudClient {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val (status, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudClient(Fixture.config(), ReadOnlyTransport(http), restoredSession = session, sessionChanged = changed,
            persistentSession = shared, persistenceEpoch = shared?.current())
    }
    private fun exchangeResponse(request: Request): Pair<Int, String> = 200 to when (request.url.encodedPath) {
        "/zeekr-cuc-idaas-sea/user/tspCode" -> ok("""{"code":"synthetic-code"}""")
        "/${RequestPolicy.BEARER}" -> ok("""{"accessToken":"synthetic-new-access"}""")
        else -> ok(vehicles)
    }
    @Test fun appAndWidgetClientsShareOneRenewalEvenWhenTheTokenTextIsReused() = runBlocking {
        for (reuseToken in listOf(false, true)) {
            var record: SavedSession? = saved()
            val persistence = SessionPersistence(object : SessionStorage {
                override fun load() = record
                override fun save(session: SavedSession) { record = session }
                override fun clear() { record = null }
            })
            val expired = CountDownLatch(2)
            val exchanges = AtomicInteger()
            val reads = AtomicInteger()
            val response: (Request) -> Pair<Int, String> = { request ->
                if (request.url.encodedPath.endsWith("/vehicle-list") && reads.incrementAndGet() <= 2) {
                    expired.countDown(); check(expired.await(5, TimeUnit.SECONDS)); 401 to "{}"
                } else if (request.url.encodedPath == "/${RequestPolicy.BEARER}") {
                    exchanges.incrementAndGet()
                    200 to ok("""{"accessToken":"${if (reuseToken) "synthetic-old-access" else "synthetic-new-access"}"}""")
                } else exchangeResponse(request)
            }
            val epoch = persistence.current()
            val app = client(changed = { persistence.write(epoch, it) }, shared = persistence, respond = response)
            val widget = client(changed = { persistence.write(epoch, it) }, shared = persistence, respond = response)
            assertTrue(awaitAll(async { app.resumeSession() }, async { widget.resumeSession() }).all { it.size == 1 })
            assertEquals(1, exchanges.get())
            assertEquals(1, app.renewalAttempts + widget.renewalAttempts)
            assertEquals(0, app.passwordLogins + widget.passwordLogins)
        }
    }
    @Test fun aClientFromBeforeLogoutCannotReadOrSendAControl() = runBlocking {
        var record: SavedSession? = saved()
        val persistence = SessionPersistence(object : SessionStorage {
            override fun load() = record
            override fun save(session: SavedSession) { record = session }
            override fun clear() { record = null }
        })
        var requests = 0
        val old = client(shared = persistence) { requests++; 200 to ok(vehicles) }
        persistence.write(persistence.advance(), null)
        try { old.resumeSession(); fail("Session was cleared") } catch (e: CheckFailure) { assertEquals(ProbeOutcome.AUTH_REQUIRED, e.outcome) }
        assertEquals(ClimateResult.REJECTED, old.controlClimate(Vehicle("L6T00000000000001", emptyList()), ClimateTarget(ClimateChannel.AC, 22), {}, {}))
        assertEquals(0, requests)
    }
    @Test fun restoreUsesOneReadWithStableDeviceAndNeverSendsCredentialsAgain() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val session = saved()
        val client = client(session) { requests.add(it); 200 to ok(vehicles) }
        assertEquals(1, client.resumeSession().size)
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals(session.deviceId, requests.single().header("x-device-id"))
        assertEquals(session.accessToken, requests.single().header("authorization"))
        assertEquals(0, client.passwordLogins)
        assertEquals(0, client.renewalAttempts)
    }
    @Test fun expiredAccessUsesSavedUserSessionAndPersistsTheReplacement() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        var updated: SavedSession? = null
        val client = client(changed = { updated = it }) { request ->
            requests.add(request)
            if (request.header("authorization") == saved().accessToken) 401 to "{}" else exchangeResponse(request)
        }
        assertEquals(1, client.resumeSession().size)
        assertEquals(4, requests.size)
        assertEquals(listOf("GET", "GET", "POST", "GET"), requests.map { it.method })
        assertEquals("synthetic-user-token", requests[1].header("authorization"))
        assertEquals("synthetic-new-access", updated!!.accessToken)
        assertEquals(saved().deviceId, updated!!.deviceId)
        assertEquals(1, client.renewalAttempts)
        assertEquals(0, client.passwordLogins)
    }
    @Test fun concurrentExpiredReadsShareOneExchange() = runBlocking {
        val expired = CountDownLatch(2)
        val exchanges = AtomicInteger()
        val client = client { request ->
            if (request.header("authorization") == "synthetic-old-access") {
                expired.countDown(); check(expired.await(5, TimeUnit.SECONDS)); 401 to "{}"
            } else {
                if (request.url.encodedPath == "/${RequestPolicy.BEARER}") exchanges.incrementAndGet()
                exchangeResponse(request)
            }
        }
        val results = awaitAll(async { client.resumeSession() }, async { client.resumeSession() })
        assertTrue(results.all { it.size == 1 })
        assertEquals(1, exchanges.get())
        assertEquals(1, client.renewalAttempts)
    }
    @Test fun offlineFailureRetainsSavedSessionAndDoesNotAttemptAuthentication() = runBlocking {
        var callbacks = 0
        var requests = 0
        val client = client(changed = { callbacks++ }) { request -> assertEquals("GET",request.method); requests++; throw IOException("synthetic private connection") }
        val error = try { client.resumeSession(); null } catch (e: CheckFailure) { e }
        assertEquals(ProbeOutcome.NETWORK, error!!.outcome)
        assertEquals(2, requests); assertEquals(0, callbacks) // One bounded reconnect, with no authentication replay.
        assertEquals(0, client.renewalAttempts); assertEquals(0, client.passwordLogins)
    }
    @Test fun forbiddenRateLimitAndServerErrorDoNotCauseAuthenticationOrEraseSession() = runBlocking {
        for (status in listOf(403, 429, 500)) {
            var callbacks = 0
            var requests = 0
            val client = client(changed = { callbacks++ }) { requests++; status to "{}" }
            try { client.resumeSession(); fail("Should reject") } catch (_: CheckFailure) { }
            assertEquals(1, requests); assertEquals(0, callbacks); assertEquals(0, client.renewalAttempts)
        }
    }
    @Test fun expiredUserSessionIsClearedWithoutPasswordFallback() = runBlocking {
        val changes = mutableListOf<SavedSession?>()
        var requests = 0
        val client = client(changed = { changes.add(it) }) { requests++; 401 to "{}" }
        try { client.resumeSession(); fail("Should require login") } catch (e: CheckFailure) { assertEquals(ProbeOutcome.AUTH_REQUIRED, e.outcome) }
        assertEquals(2, requests)
        assertEquals(listOf<SavedSession?>(null), changes)
        try { client.resumeSession(); fail("Should have no session") } catch (_: CheckFailure) { }
        assertEquals(2, requests); assertEquals(0, client.passwordLogins)
    }
    @Test fun transientRenewalFailureDoesNotSpinAndRetainsDiskCredentials() = runBlocking {
        var exchanges = 0
        var callbacks = 0
        val client = client(changed = { callbacks++ }) { request ->
            if (request.url.encodedPath.endsWith("/user/tspCode")) { exchanges++; 429 to "{}" } else 401 to "{}"
        }
        repeat(2) { try { client.resumeSession(); fail("Should reject") } catch (_: CheckFailure) { } }
        assertEquals(1, exchanges); assertEquals(0, callbacks)
    }
    @Test fun physicalPostAuthFailureIsNeverReplayedOrAutomaticallyRenewed() = runBlocking {
        var writes = 0
        var exchanges = 0
        val client = client { request ->
            when {
                request.url.toString() == ClimateRequestPolicy.URL -> { writes++; 401 to "{}" }
                request.method == "POST" -> { exchanges++; exchangeResponse(request) }
                else -> 200 to ok(vehicles)
            }
        }
        val vehicle = client.resumeSession().single()
        val result = client.controlClimate(vehicle, ClimateTarget(ClimateChannel.FRONT_LEFT, 3), { fail("No receipt") }, {})
        assertEquals(ClimateResult.REJECTED, result)
        assertEquals(1, writes); assertEquals(0, exchanges); assertEquals(0, client.renewalAttempts)
    }
    @Test fun clearingSessionWhileExchangeRunsCannotPublishItsLateResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var callbacks = 0
        val client = client(changed = { callbacks++ }) { request ->
            if (request.header("authorization") == "synthetic-old-access") 401 to "{}" else {
                if (request.url.encodedPath == "/${RequestPolicy.BEARER}") {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                exchangeResponse(request)
            }
        }
        val call = async { try { client.resumeSession(); false } catch (_: CancellationException) { true } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            client.clearSession()
        } finally { release.countDown() }
        assertTrue(call.await()); assertEquals(0, callbacks)
    }
    @Test fun sessionSchemaBindsRegionProtocolAndDeviceWithoutPasswordOrRawResponse() {
        val session = saved()
        val encoded = session.encode()
        assertTrue(SavedSession.parse(encoded).matches(Fixture.config()))
        assertFalse(session.toString().contains("synthetic"))
        assertFalse(encoded.contains("password")); assertFalse(encoded.contains("refreshToken"))
        val obj = Json.parseToJsonElement(encoded).jsonObject
        for ((key, value) in listOf("region" to "EU", "userOrigin" to "https://evil.invalid/", "deviceId" to "invalid")) {
            val modified = JsonObject(obj + (key to JsonPrimitive(value))).toString()
            val error = assertThrows(IllegalArgumentException::class.java) { SavedSession.parse(modified) }
            assertFalse(error.message!!.contains("synthetic"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedSession.parse(JsonObject(obj + ("password" to JsonPrimitive("private"))).toString())
        }
        val wrongConfig = SavedSession(session.userToken, session.accessToken, session.deviceId, "0".repeat(64))
        assertFalse(wrongConfig.matches(Fixture.config()))
        assertThrows(IllegalArgumentException::class.java) { client(wrongConfig) { 200 to ok(vehicles) } }
    }
    @Test fun logoutWaitsForInFlightSaveAndRejectsOldCallbacks() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var record: SavedSession? = null
        val storage = object : SessionStorage {
            override fun load() = record
            override fun save(session: SavedSession) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); record = session }
            override fun clear() { record = null }
        }
        val persistence = SessionPersistence(storage)
        val old = persistence.current()
        val saving = async { persistence.write(old, saved()) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        val logout = persistence.advance()
        val clearing = async { persistence.write(logout, null) }
        release.countDown()
        assertFalse(saving.await()); assertTrue(clearing.await())
        assertFalse(persistence.write(old, saved()))
        assertNull(persistence.load())
    }
}
