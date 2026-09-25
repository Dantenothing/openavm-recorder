package com.dante.zeekrcheck.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ChargePortProtocolTest {
    @Test fun openingUsesTheOfficialAppLidTargetAndWaitsForChangedReadback() = runTest { checkControl(false) }
    @Test fun closingUsesTheSameOfficialTargetWithTheCloseCommand() = runTest { checkControl(true) }

    private suspend fun checkControl(initiallyOpen: Boolean) {
        val now=Instant.parse("2026-09-20T02:00:00Z")
        val methods=mutableListOf<String>()
        val bodies=mutableListOf<String>()
        var commanded=false
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            val request=chain.request()
            methods+=request.method
            val data=if(request.method=="POST") {
                val buffer=Buffer();request.body!!.writeTo(buffer);bodies+=buffer.readUtf8()
                commanded=true
                """{"sessionId":"synthetic-receipt"}"""
            } else {
                val open=if(commanded) !initiallyOpen else initiallyOpen
                // The official button reads DcAc status even though its command target is named "front".
                """{"additionalVehicleStatus":{"electricVehicleStatus":{"chargeLidDcAcStatus":"${if(open) 1 else 0}","chargeLidAcStatus":"${if(open) 0 else 1}"}}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("""{"success":true,"data":$data}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val config=Fixture.config()
        val client=CloudClient(config,Fixture.transport(http),clock=Clock.fixed(now,ZoneOffset.UTC),
            restoredSession=SavedSession("synthetic-user","synthetic-access","d294932f-f97e-4b6d-a63c-41bfa80d83ca",config.fingerprint()))
        val vehicle=Vehicle("L6T00000000000001",emptyList())
        val shown=if(initiallyOpen) "打开" else "关闭"
        var result:CommandResult?=null
        var accepted=0
        var observed:Probe?=null
        CardControl.run("port",shown,{ client.probe(it,vehicle) },{}, { action ->
            result=client.controlVehicle(vehicle,VehicleCommand.Body(action),{ accepted++ },before=shown) { observed=it }
        },{ fail(it) })
        assertEquals(listOf("GET","POST","GET"),methods)
        assertEquals(1,accepted)
        val body=Json.parseToJsonElement(bodies.single())
        assertEquals(if(initiallyOpen) "RDC" else "RDO",body.at("serviceId").text())
        assertEquals(if(initiallyOpen) "stop" else "start",body.at("command").text())
        // AU 1.6.6 ControlCmdExtendKt common_lid_key -> FRONT_CHARGE_LID, then SNC Cmd target.
        assertEquals(Json.parseToJsonElement("""[{"key":"target","value":"front-charge-lid"}]"""),body.at("setting.serviceParameters"))
        assertEquals(CommandResult.MATCHED,result)
        assertEquals(if(initiallyOpen) "关闭" else "打开",Capabilities.parse(listOf(observed!!)).first { it.id=="port" }.value)
        assertEquals(1,client.vehicleRequestAttempts)
        assertEquals(0,client.passwordLogins)
    }
}
