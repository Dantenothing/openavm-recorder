package com.dante.zeekrcheck.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Collections
import okio.Buffer

class CloudClientTest {
    @Test fun bearerSchemeSurvivesLoginPersistenceAndColdStartWithoutPasswordReplay() = runBlocking {
        val credential = "Bearer SYNTHETIC.ACCESS_TOKEN-with-prefix"
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        var saved: SavedSession? = null
        val client = clientWith(sessionChanged = { saved = it }) { request ->
            requests.add(request)
            if (request.url.encodedPath == "/${RequestPolicy.BEARER}")
                """{"success":true,"data":{"accessToken":"$credential"}}""" else envelope(request)
        }
        assertEquals(1, client.login("synthetic@example.invalid", "synthetic-password") {}.size)
        assertEquals(credential, saved!!.accessToken)
        val restored = SavedSession.parse(saved!!.encode())
        client.clearSession()
        val next = clientWith(restoredSession = restored) { request -> requests.add(request); envelope(request) }
        assertEquals(1, next.resumeSession().size)
        assertEquals(7, requests.size)
        assertEquals(credential, requests[5].header("authorization"))
        assertEquals(credential, requests[6].header("authorization"))
        assertEquals("GET", requests[6].method)
        assertEquals(requests[5].header("x-device-id"), requests[6].header("x-device-id"))
        assertEquals(0, next.passwordLogins)
    }

    @Test fun opaqueHeaderCredentialAllowsSchemeSpaceButRejectsBlankAndHeaderInjection() {
        assertTrue(SavedSession.validToken("Bearer SYNTHETIC-CREDENTIAL"))
        assertTrue(SavedSession.validToken("SYNTHETIC-RAW-CREDENTIAL"))
        for (invalid in listOf("", "   ", "Bearer private\r\nInjected: secret", "token\u0000", "token\u007f", "密钥", "x".repeat(16_385))) {
            assertFalse(SavedSession.validToken(invalid))
        }
    }

    @Test fun exactAllowlistRejectsWritesRedirectOriginsAndQueryInjection() {
        val denied = listOf(
            "https://sea-snc-tsp-api-gw.zeekrlife.com/ms-remote-control/v1.0/remoteControl/control",
            RequestPolicy.TSP_BASE + "ms-charge-manage/api/v1.0/charge/setTravelPlan",
            RequestPolicy.USER_BASE + "user/updateLanguage?language=en",
            "https://sea-snc-tsp-api-gw.zeekrlife.com.attacker.invalid/${Endpoint.STATUS.path}?latest=false&target=new",
            "http://sea-snc-tsp-api-gw.zeekrlife.com/${Endpoint.STATUS.path}?latest=false&target=new",
            "https://user:pass@sea-snc-tsp-api-gw.zeekrlife.com/${Endpoint.STATUS.path}?latest=false&target=new",
            "https://sea-snc-tsp-api-gw.zeekrlife.com:8443/${Endpoint.STATUS.path}?latest=false&target=new",
            RequestPolicy.TSP_BASE + RequestPolicy.VEHICLES + "?needSharedCar=true&control=1",
        )
        denied.forEach { url -> listOf("GET", "POST").forEach { method -> assertFalse("$method $url", RequestPolicy.allowed(method, url.toHttpUrl())) } }
        Endpoint.entries.forEach { endpoint ->
            val url = (RequestPolicy.TSP_BASE + endpoint.path + if (endpoint == Endpoint.STATUS) "?latest=false&target=new" else "").toHttpUrl()
            assertTrue(RequestPolicy.allowed("GET", url))
            assertFalse(RequestPolicy.allowed("POST", url))
        }
    }
    @Test fun regionDiscoveryCannotRedirectCredentialsElsewhere() {
        assertThrows(CheckFailure::class.java) { RequestPolicy.validateRegion(Json.parseToJsonElement("""[{"countryCode":"AU","regionCode":"SEA","url":{"userCenterUrl":"https://evil.invalid/"}}]""")) }
        assertThrows(CheckFailure::class.java) { RequestPolicy.validateRegion(Json.parseToJsonElement("""[{"countryCode":"AU","regionCode":"EU","url":{"userCenterUrl":"${RequestPolicy.USER_BASE}"}}]""")) }
    }
    @Test fun fullSyntheticFlowRequestsSharedCarsAndOnlySixReadEndpoints() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = clientWith { request ->
            requests.add(request)
            envelope(request)
        }
        val stages = mutableListOf<String>()
        val vehicles = client.login("synthetic@example.invalid", "synthetic-password", stages::add)
        assertEquals(6, stages.size)
        assertEquals(1, vehicles.size)
        val probes = Endpoint.entries.map { client.probe(it, vehicles.single()) }
        assertTrue(probes.all { it.outcome == ProbeOutcome.SUCCESS })
        assertEquals(12, requests.size)
        assertEquals("needSharedCar=true", requests[5].url.query)
        assertTrue(requests.drop(6).all { it.method == "GET" && !it.header("X-VIN").isNullOrBlank() })
        assertEquals(3, requests.count { it.method == "POST" }) // Account check, login, TSP login only.
        assertTrue(requests.all { RequestPolicy.allowed(it.method, it.url) })
        assertFalse(requests.any { it.url.toString().contains("updateLanguage") })
        assertNull(requests[0].header("authorization"))
        assertEquals("synthetic-user-token", requests[3].header("authorization"))
        assertEquals("", requests[4].header("authorization"))
        assertEquals("synthetic-bearer", requests[5].header("authorization"))
    }
    @Test fun expiredReadHasOneBoundedExchangeAndCannotLoopOnAnotherRejection() = runBlocking {
        var count = 0
        val client = clientWith { request -> count++; if (request.url.encodedPath == "/${Endpoint.STATUS.path}") """{"success":false,"msg":"Token expired","private":"must not leak"}""" else envelope(request) }
        val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
        val probe = client.probe(Endpoint.STATUS, vehicle)
        assertEquals(ProbeOutcome.AUTH_REQUIRED, probe.outcome)
        assertEquals(10, count) // One failed GET, code exchange + TSP login, one GET replay.
        assertEquals(1, client.passwordLogins)
        assertEquals(1, client.renewalAttempts)
        assertEquals(ProbeOutcome.AUTH_REQUIRED, client.probe(Endpoint.STATUS, vehicle).outcome)
        assertEquals(11, count) // A later rejection does not start another exchange in this client.
        assertFalse(probe.toString().contains("private"))
    }
    @Test fun serverRejectionIsNotPresentedAsBlanketGuestPermissionDenial() = runBlocking {
        val client = clientWith { request -> if (request.url.encodedPath == "/${Endpoint.SENTRY.path}") """{"success":false,"msg":"unknown private message"}""" else envelope(request) }
        val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
        val probe = client.probe(Endpoint.SENTRY, vehicle)
        assertEquals(ProbeOutcome.REJECTED, probe.outcome)
        assertNull(probe.data)
        assertTrue(probe.outcome.label.contains("待核对"))
    }
    @Test fun timeoutAndBadJsonHaveSanitizedErrors() = runBlocking {
        val timeoutClient = clientWith { throw SocketTimeoutException("private-host-and-token") }
        val timeout = try { timeoutClient.login("synthetic@example.invalid", "synthetic-password") {}; null } catch (e: CheckFailure) { e }
        assertEquals(ProbeOutcome.TIMEOUT, timeout!!.outcome)
        assertFalse(timeout.message!!.contains("private"))
        val error = assertThrows(CheckFailure::class.java) { ReadOnlyTransport.decodeResponse("private-malformed-json") }
        assertEquals(ProbeOutcome.INVALID_RESPONSE, error.outcome)
        assertFalse(error.message!!.contains("private"))
    }
    @Test fun oversizedResponseIsBoundedAndRejected() = runBlocking {
        val client = clientWith { " ".repeat(2_097_153) }
        val failure = try { client.login("synthetic@example.invalid", "synthetic-password") {}; null } catch (e: CheckFailure) { e }
        assertEquals(ProbeOutcome.INVALID_RESPONSE, failure!!.outcome)
    }
    @Test fun emptyPlanAndMissingDataAreDifferent() {
        assertEquals(JsonObject(emptyMap()), ReadOnlyTransport.decodeResponse("""{"success":true,"data":{}}"""))
        assertThrows(CheckFailure::class.java) { ReadOnlyTransport.decodeResponse("""{"success":true}""") }
        assertEquals(JsonNull, ReadOnlyTransport.decodeResponse("""{"success":true}""", requireData = false))
    }
    @Test fun temperatureAcceptanceObservesPowerWithoutClaimingSetpointConfirmationOrSendingOtherActions() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = clientWith { request ->
            requests.add(request)
            if (request.url.toString() == ClimateRequestPolicy.URL) """{"success":true,"data":{"sessionId":"synthetic-task"}}""" else envelope(request)
        }
        val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
        val target = ClimateTarget(ClimateChannel.AC, 22)
        var accepted = false
        val snapshots=mutableListOf<ClimateSnapshot>()
        val result = client.controlClimate(vehicle, target, { accepted = true }, { snapshots += it })
        assertTrue(accepted)
        assertEquals(ClimateResult.NEEDS_CHECK, result)
        assertEquals(10, requests.size)
        assertEquals(3,snapshots.size)
        assertTrue(snapshots.all { it.acOn==null && it.cabinTemperature==null })
        assertTrue(requests.takeLast(3).all { it.method=="GET" && it.url.encodedPath=="/${Endpoint.STATUS.path}" })
        assertEquals(1, client.climateRequestAttempts)
        val sent = requests.single { it.url.toString()==ClimateRequestPolicy.URL }
        assertTrue(ClimateRequestPolicy.allowed(sent, target))
        val body = Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
        assertEquals(target.body(), body)
        assertEquals(Signatures.appSignature(sent.method, sent.url, sent.headers.toMap(), body, Fixture.config().prodSecret), sent.header("X-SIGNATURE"))
    }
    @Test fun acceptedPreparationOrBodyReadFailureRetainsReceiptWithoutRetryingWrite() = runTest {
        for(command in listOf(VehicleCommand.Comfort(listOf(ClimateTarget(ClimateChannel.AC,22))),VehicleCommand.Body(BodyAction.PORT_OPEN))) {
            var writes=0; var reads=0; var received=false
            val client=clientWith { request -> when(request.url.encodedPath) {
                ClimateRequestPolicy.URL.toHttpUrl().encodedPath -> { writes++; """{"success":true,"data":{"sessionId":"synthetic-task"}}""" }
                "/${Endpoint.STATUS.path}" -> { reads++; throw SocketTimeoutException("synthetic-timeout") }
                else -> envelope(request)
            } }
            val vehicle=client.login("synthetic@example.invalid","synthetic-password") {}.single()
            assertEquals(CommandResult.ACCEPTED,client.controlVehicle(vehicle,command,{received=true}) {})
            assertTrue(received); assertEquals(1,writes); assertEquals(1,reads)
        }
    }
    @Test fun ambiguousControlNetworkFailureIsNeverRetried() = runBlocking {
        var writes = 0
        val client = clientWith { request ->
            if (request.url.toString() == ClimateRequestPolicy.URL) { writes++; throw SocketTimeoutException("private response") }
            envelope(request)
        }
        val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
        val result = client.controlClimate(vehicle, ClimateTarget(ClimateChannel.FRONT_LEFT, 3), {}, {})
        assertEquals(ClimateResult.UNKNOWN, result)
        assertEquals(1, writes)
        assertEquals(1, client.climateRequestAttempts)
    }
    @Test fun missingTaskIdIsNotTreatedAsCompletion() = runBlocking {
        val client = clientWith { request -> if (request.url.toString() == ClimateRequestPolicy.URL) """{"success":true,"data":{}}""" else envelope(request) }
        val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
        assertEquals(ClimateResult.UNKNOWN, client.controlClimate(vehicle, ClimateTarget(ClimateChannel.FRONT_LEFT, 2), { fail("Missing receipt") }, {}))
    }

    @Test fun tailgateAcceptanceSendsOnlyTheOfficialLatchRequestWithoutWaitingForOpening() = runTest {
        val requests = mutableListOf<Request>()
        val client = clientWith { request ->
            requests.add(request)
            if (request.url.toString() == ClimateRequestPolicy.URL) """{"success":true,"data":{"sessionId":"synthetic-tailgate-task"}}""" else envelope(request)
        }
        val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
        requests.clear()
        var accepted = false
        val command = VehicleCommand.Body(BodyAction.TRUNK_UNLOCK)
        assertEquals(CommandResult.ACCEPTED, client.controlVehicle(vehicle, command, { accepted = true }) { fail("Latch receipt must not wait for physical opening") })
        assertTrue(accepted)
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals("RDU", body.at("serviceId").text())
        assertEquals("stop", body.at("command").text())
        val params = body.at("setting.serviceParameters") as JsonArray
        assertEquals(1, params.size)
        assertEquals("target", params.single().at("key").text())
        assertEquals("trunk", params.single().at("value").text())
    }

    @Test fun uncertainTailgateWriteIsNotReplayedAndNextExplicitHornRequestIsAllowed() = runTest {
        for (failure in listOf("missing-receipt", "timeout", "malformed-response")) {
            val writes = mutableListOf<String>()
            val client = clientWith { request ->
                if (request.url.toString() != ClimateRequestPolicy.URL) envelope(request) else {
                    writes += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    if (writes.size > 1) """{"success":true,"data":{"sessionId":"synthetic-horn-task"}}"""
                    else when (failure) {
                        "timeout" -> throw SocketTimeoutException("synthetic timeout")
                        "malformed-response" -> "not-json"
                        else -> """{"success":true,"data":{}}"""
                    }
                }
            }
            val vehicle = client.login("synthetic@example.invalid", "synthetic-password") {}.single()
            val unlock = VehicleCommand.Body(BodyAction.TRUNK_UNLOCK)
            val result = client.controlVehicle(vehicle, unlock, { fail("No valid receipt") }) { fail("No physical-state polling") }
            assertEquals(CommandResult.UNKNOWN, result)
            assertFalse(OperationFeedback.requiresAcknowledgement(unlock, result))
            assertEquals(listOf(unlock.body()), writes)
            val nextExplicitAction = VehicleCommand.Body(BodyAction.HORN)
            assertEquals(CommandResult.ACCEPTED, client.controlVehicle(vehicle, nextExplicitAction, {}) {})
            assertEquals(listOf(unlock.body(), nextExplicitAction.body()), writes)
        }
    }

    private fun clientWith(restoredSession: SavedSession? = null, sessionChanged: suspend (SavedSession?) -> Unit = {},
        body: (Request) -> String): CloudClient {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(request).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudClient(Fixture.config(), ReadOnlyTransport(http), restoredSession = restoredSession, sessionChanged = sessionChanged)
    }
    private fun envelope(request: Request): String = when (request.url.encodedPath) {
        "/overseas-app/region/url" -> """{"success":true,"data":[{"countryCode":"AU","regionCode":"SEA","url":{"userCenterUrl":"${RequestPolicy.USER_BASE}"}}]}"""
        "/zeekr-cuc-idaas-sea/auth/checkUserV2" -> """{"success":true}"""
        "/zeekr-cuc-idaas-sea/auth/loginByEmailEncrypt" -> """{"success":true,"data":{"tokenName":"Authorization","tokenValue":"synthetic-user-token"}}"""
        "/zeekr-cuc-idaas-sea/user/tspCode" -> """{"success":true,"data":{"code":"synthetic-code"}}"""
        "/${RequestPolicy.BEARER}" -> """{"success":true,"data":{"accessToken":"synthetic-bearer"}}"""
        "/${RequestPolicy.VEHICLES}" -> """{"success":true,"data":[{"vin":"L6T00000000000001","isShared":true}]}"""
        else -> """{"success":true,"data":{}}"""
    }
}
