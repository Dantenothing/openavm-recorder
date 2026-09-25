package com.dante.zeekrcheck.core

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.ProtocolException
import javax.net.ssl.SSLPeerUnverifiedException
import java.util.concurrent.atomic.AtomicInteger

class ReadRecoveryTest {
    private fun cloud(statusCode:Int=200,body:(Request)->String):CloudClient {
        val client=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(statusCode).message("OK")
                .body(body(chain.request()).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudClient(Fixture.config(),ReadOnlyTransport(client),restoredSession=SavedSession("synthetic-user","synthetic-access",
            "d294932f-f97e-4b6d-a63c-41bfa80d83ca",Fixture.config().fingerprint()))
    }
    private val vehicle=Vehicle("L6T00000000000001",emptyList())
    private val status="""{"success":true,"data":{"additionalVehicleStatus":{"drivingSafetyStatus":{"centralLockingStatus":"1"}}}}"""

    @Test fun transientPreflightReadRecoversInsideTheSameTapAndChoosesOnlyOneCommand() = runTest {
        var reads=0
        val client=cloud { request -> assertEquals("GET",request.method); reads++; if(reads==1) throw EOFException("private server details");status }
        val sent=mutableListOf<BodyAction>()
        CardControl.run("lock","已锁",{client.probe(it,vehicle)},{},{sent+=it},{})
        assertEquals(listOf(BodyAction.UNLOCK),sent)
        assertEquals(2,reads)
        assertEquals(0,client.vehicleRequestAttempts)
        assertEquals(0,client.passwordLogins)
    }
    @Test fun persistentFailureStopsAfterOneReadRecoveryAndSendsNoCommand() = runTest {
        var reads=0
        val client=cloud { reads++;throw SocketException("private network address") }
        var message=""
        CardControl.run("lock","已锁",{client.probe(it,vehicle)},{},{fail("no write on failed reads")},{message=it})
        assertEquals(2,reads)
        assertTrue(message.contains("未发送操作"))
        assertFalse(message.contains("private"))
    }
    @Test fun recoveredReadDoesNotReverseTheIntentWhenVehicleStateChanged() = runTest {
        var reads=0;var message=""
        val client=cloud { reads++;if(reads==1) throw EOFException();status.replace("\"1\"","\"0\"") }
        CardControl.run("lock","已锁",{client.probe(it,vehicle)},{},{fail("changed state must not send opposite command")},{message=it})
        assertEquals(2,reads);assertTrue(message.contains("车况已变化"))
    }
    @Test fun networkFailureOnPhysicalWriteIsStillNeverReplayed() = runTest {
        val writes=AtomicInteger()
        val client=cloud { request -> assertEquals("POST",request.method);writes.incrementAndGet();throw SocketException("private endpoint") }
        val result=client.controlVehicle(vehicle,VehicleCommand.Body(BodyAction.LOCK),{fail("no receipt")}) {}
        assertEquals(CommandResult.UNKNOWN,result);assertEquals(1,writes.get());assertEquals(1,client.vehicleRequestAttempts)
    }
    @Test fun cancelledAccountCannotRetryUsingItsOldCredential() = runTest {
        var reads=0
        val client=cloud { reads++;throw EOFException("private token") }
        val result=client.probe(Endpoint.STATUS,vehicle) { client.clearSession() }
        assertEquals(ProbeOutcome.AUTH_REQUIRED,result.outcome);assertEquals(1,reads)
    }
    @Test fun permanentProtocolTlsTimeoutAndHttpFailuresDoNotReconnect() = runTest {
        for(error in listOf(SSLPeerUnverifiedException("private"),ProtocolException("private"),SocketTimeoutException("private"))) {
            var reads=0
            val client=cloud { reads++;throw error }
            val result=client.probe(Endpoint.STATUS,vehicle) { fail("must not reconnect") }
            assertNotEquals(ProbeOutcome.SUCCESS,result.outcome);assertEquals(1,reads)
        }
        for(code in listOf(403,429,500,502,503)) {
            var reads=0
            val client=cloud(code) { reads++;status }
            assertNotEquals(ProbeOutcome.SUCCESS,client.probe(Endpoint.STATUS,vehicle) { fail("must not reconnect") }.outcome)
            assertEquals(1,reads)
        }
    }
    @Test fun recoveryRebuildsSignedHeadersAndReportsOnlySanitizedIssueCategories() = runTest {
        val requests=mutableListOf<Request>()
        val client=cloud { request -> requests+=request;if(requests.size==1) throw EOFException("PRIVATE-secret-host-token");status }
        var reconnects=0
        assertEquals(ProbeOutcome.SUCCESS,client.probe(Endpoint.STATUS,vehicle) { reconnects++ }.outcome)
        assertEquals(1,reconnects);assertEquals(2,requests.size)
        assertNotEquals(requests[0].header("X-API-SIGNATURE-NONCE"),requests[1].header("X-API-SIGNATURE-NONCE"))
        assertTrue(requests.all { it.method=="GET" })
        val trace=NetworkTrace.export()
        for(privateValue in listOf("PRIVATE-secret","synthetic-access","synthetic-user",vehicle.vin,"zeekrlife.com")) assertFalse(trace.contains(privateValue))
        assertTrue(trace.contains("RECONNECTING"))
    }
}
