package com.dante.zeekrcheck.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.*

class AssistantFeaturesTest {
    private val now = Instant.parse("2026-09-19T08:00:00Z")
    private fun snapshot(temp: Double, age: Long = 30) = ClimateSnapshot(cabinTemperature = temp, sourceTime = now.minusSeconds(age), fetchedAt = now)
    private val all = ComfortPreferences(steeringHeat = true, seatHeat = true, seatVentilation = true, bothSeats = true)
    @Test fun allAllowedIsPersistentAndDoesNotMeanAllActivated() {
        val decoded = AssistantState.parse(AssistantState(preferences = all).encode())
        assertEquals(all, decoded.preferences)
        assertEquals(setOf(ClimateChannel.AC, ClimateChannel.FRONT_LEFT, ClimateChannel.FRONT_RIGHT), PreparationPlanner.plan(all, snapshot(34.0), now)!!.targets.map { it.channel }.toSet())
        assertEquals(setOf(ClimateChannel.AC, ClimateChannel.HEAT_LEFT, ClimateChannel.HEAT_RIGHT, ClimateChannel.STEERING), PreparationPlanner.plan(all, snapshot(8.0), now)!!.targets.map { it.channel }.toSet())
        assertTrue(decoded.history.isEmpty()); assertTrue(decoded.plans.isEmpty()); assertNull(decoded.activePreparation)
    }
    @Test fun staleUnknownOrFutureTemperatureUsesOnlyTheExplicitThermostatTarget() {
        for (sample in listOf(snapshot(34.0, 301), snapshot(34.0, -31), snapshot(34.0).copy(sourceTime = null), snapshot(34.0).copy(cabinTemperature = null))) {
            assertEquals(listOf(ClimateTarget(ClimateChannel.AC, all.target, all.minutes)), PreparationPlanner.plan(all, sample, now)!!.targets)
        }
        assertNull(PreparationPlanner.plan(all, snapshot(22.0), now))
    }
    @Test fun warmPlanNeverEnablesAForbiddenAdjunctAndEveryStartHasNativeDuration() {
        val command = PreparationPlanner.plan(ComfortPreferences(minutes = 30), snapshot(34.0), now)!!
        assertEquals(1, command.targets.size)
        val parameters = Json.parseToJsonElement(command.body()).at("setting.serviceParameters") as JsonArray
        assertTrue(parameters.any { it.at("key").text() == "AC.duration" && it.at("value").text() == "30" })
        assertFalse(command.body().contains("SV."))
        assertThrows(IllegalArgumentException::class.java) { VehicleCommand.Comfort(listOf(ClimateTarget(ClimateChannel.HEAT_LEFT, 1), ClimateTarget(ClimateChannel.FRONT_LEFT, 1))) }
    }
    @Test fun changingDefaultsDoesNotMutateAnExistingPlanSnapshot() {
        val plan = DeparturePlan(vehicleKey = "synthetic-car", departure = now.plusSeconds(3600).toEpochMilli(), preferences = all)
        val next = AssistantState(preferences = all, plans = listOf(plan)).copy(preferences = all.copy(target = 26, seatHeat = false))
        assertEquals(all, AssistantState.parse(next.encode()).plans.single().preferences)
    }
    @Test fun recurrenceHonorsPlanTimezoneAndHandledOccurrence() {
        val departure = ZonedDateTime.of(2026,9,21,8,0,0,0,ZoneId.of("Australia/Adelaide")).toInstant()
        val plan = DeparturePlan(vehicleKey = "synthetic-car", departure = departure.toEpochMilli(), zone = "Australia/Adelaide", days = setOf(1,2,3,4,5), preferences = all)
        assertEquals(departure, plan.next(now))
        assertEquals(departure.plusSeconds(86400), plan.copy(handled = departure.toEpochMilli()).next(now))
        assertNull(plan.copy(enabled = false).next(now))
    }
    @Test fun dstUsesLocalDepartureTimeAndNeverDuplicatesAnOccurrence() {
        val zone = ZoneId.of("Australia/Adelaide")
        val anchor = ZonedDateTime.of(2026,9,27,8,0,0,0,zone).toInstant()
        val plan = DeparturePlan(vehicleKey = "synthetic", departure = anchor.toEpochMilli(), zone = zone.id, days = setOf(7), preferences = all, handled = anchor.toEpochMilli())
        val next = plan.next(Instant.parse("2026-10-03T00:00:00Z"))!!
        assertEquals(8, next.atZone(zone).hour)
        assertEquals(ZoneOffset.ofHoursMinutes(10,30), next.atZone(zone).offset)
    }
    @Test fun missedPreparationWindowSkipsToNextRecurrenceWithoutLateCatchup() {
        val departure = now.plusSeconds(600)
        val plan = DeparturePlan(vehicleKey = "synthetic", departure = departure.toEpochMilli(), zone = "UTC", leadMinutes = 15, preferences = all)
        assertNull(plan.next(now))
        val recurring = plan.copy(days = (1..7).toSet())
        assertEquals(departure.plusSeconds(86400), recurring.next(now))
        assertEquals(departure, plan.next(now.minusSeconds(301)))
    }
    @Test fun stopIntentPersistsWithoutReconstructingACommand() {
        val session = PreparationSession("synthetic", now.toEpochMilli(), now.plusSeconds(1800).toEpochMilli(), all, listOf(ClimateChannel.AC), stopRequested = true)
        val restored = AssistantState.parse(AssistantState(activePreparation = session, operationPending = true).encode())
        assertTrue(restored.activePreparation!!.stopRequested)
        assertTrue(restored.operationPending)
    }
    @Test fun positionMustBeNonzeroDecimalAndHaveItsOwnFreshness() {
        fun position(json: String) = CarLocation.from(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("""{"basicVehicleStatus":{"position":$json}}""")))
        assertNull(position("""{"latitude":0,"longitude":0}"""))
        assertNull(position("""{"latitude":-126000000,"longitude":498600000}"""))
        assertNull(position("""{"latitude":-35,"longitude":138.5,"posCanBeTrusted":"0"}"""))
        assertNull(position("""{"latitude":-35,"longitude":138.5,"marsCoordinates":"1"}"""))
        assertNotNull(position("""{"latitude":-35,"longitude":138.5,"posCanBeTrusted":"1","marsCoordinates":"0"}"""))
        assertFalse(position("""{"latitude":-35,"longitude":138.5}""")!!.fresh(now.toEpochMilli()))
        assertTrue(position("""{"latitude":-35,"longitude":138.5,"updateTime":${now.toEpochMilli()}}""")!!.fresh(now.toEpochMilli()))
    }
    @Test fun distanceHandlesTheDateLineAndKnownSmallSeparation() {
        assertTrue(CarLocation(0.0,179.999,null).distance(CarLocation(0.0,-179.999,null)) in 200.0..230.0)
        assertEquals(0.0, CarLocation(-35.0,138.5,null).distance(CarLocation(-35.0,138.5,null)), 0.01)
    }
    @Test fun typedAllowlistRejectsOtherTargetsOrExtraParameters() {
        val command = VehicleCommand.Body(BodyAction.HORN)
        val request = Request.Builder().url(ClimateRequestPolicy.URL).header("X-VIN","synthetic").header("authorization","synthetic")
            .post(command.body().toRequestBody("application/json".toMediaType())).build()
        assertTrue(VehicleCommandPolicy.allowed(request, command))
        assertFalse(VehicleCommandPolicy.allowed(request.newBuilder().url(ClimateRequestPolicy.URL+"?replay=1").build(),command))
        assertFalse(VehicleCommandPolicy.allowed(request, VehicleCommand.Body(BodyAction.UNLOCK)))
        assertFalse(RequestPolicy.allowed(request.method, request.url))
        assertEquals("horn", (Json.parseToJsonElement(command.body()).at("setting.serviceParameters") as JsonArray).single().at("value").text())
    }
    @Test fun acceptedReceiptIsNotPhysicalSuccessAndNoNetworkFailureReplaysControl() = runBlocking {
        for (failure in listOf(false, true)) {
            var requests = 0
            val http = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                ++requests
                if (failure) throw IOException("synthetic interruption")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("""{"success":true,"data":{"sessionId":"synthetic"}}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val client = CloudClient(Fixture.config(), Fixture.transport(http), restoredSession = SavedSession("synthetic-user", "synthetic-access", "d294932f-f97e-4b6d-a63c-41bfa80d83ca", Fixture.config().fingerprint()))
            val result = client.controlVehicle(Vehicle("L6T00000000000001", emptyList()), VehicleCommand.Body(BodyAction.HORN), {}) {}
            assertEquals(if (failure) CommandResult.UNKNOWN else CommandResult.ACCEPTED, result)
            assertEquals(1, requests); assertEquals(0, client.passwordLogins); assertEquals(0, client.renewalAttempts)
        }
    }
    @Test fun unresolvedOperationSurvivesRestartWithoutAReplayQueue() {
        val recovered = AssistantState.parse(AssistantState(operationPending = true, operationMessage = "结果待核实").encode())
        assertTrue(recovered.operationPending)
        assertTrue(recovered.plans.isEmpty())
        assertEquals(0, recovered.history.size)
    }
    @Test fun serviceFailureRetainsOnlyTheHttpCodeAndNeverTheServerBody() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("synthetic")
                .body("PRIVATE-SERVER-BODY".toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val client = CloudClient(Fixture.config(), Fixture.transport(http), restoredSession = SavedSession("synthetic-user", "synthetic-access", "d294932f-f97e-4b6d-a63c-41bfa80d83ca", Fixture.config().fingerprint()))
        val probe = client.probe(Endpoint.STATUS, Vehicle("L6T00000000000001", emptyList()))
        assertEquals(ProbeOutcome.NETWORK, probe.outcome); assertEquals(503, probe.httpStatus)
        val report = Report(false, now, now, "synthetic", emptyList(), listOf(probe)).export(now)
        assertTrue(report.contains("503")); assertFalse(report.contains("PRIVATE-SERVER-BODY"))
    }
}
