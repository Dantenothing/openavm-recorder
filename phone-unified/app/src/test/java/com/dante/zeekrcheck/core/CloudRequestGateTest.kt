package com.dante.zeekrcheck.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class CloudRequestGateTest {
    private class FakeCall : Call {
        var sent = false; var cancelled = false
        override fun request() = Request.Builder().url(RequestPolicy.DISCOVERY).build()
        override fun enqueue(responseCallback: Callback) { sent = true }
        override fun execute(): Response = error("not used")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = sent
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }
    private val callback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) { response.close() }
    }
    @Test fun oldRequestsStayRevokedAfterAReplacementConfigurationOpens() {
        val gate = CloudRequestGate()
        val beforeImport = gate.permit()
        gate.open()
        assertThrows(CancellationException::class.java) { beforeImport.enqueue(FakeCall(), callback) }
        val old = gate.permit()
        val active = FakeCall()
        old.enqueue(active, callback)
        gate.revoke()
        assertTrue(active.cancelled)
        gate.open()
        val queued = FakeCall()
        assertThrows(CancellationException::class.java) { old.enqueue(queued, callback) }
        assertFalse(queued.sent)
        val current = FakeCall()
        gate.permit().enqueue(current, callback)
        assertTrue(current.sent)
    }
    @Test fun missingPermitNeverReachesTheNetworkInterceptor() = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { attempts.incrementAndGet(); error("must not reach transport") }.build()
        try {
            ReadOnlyTransport(http).execute(Request.Builder().url(RequestPolicy.DISCOVERY).build())
            fail("Unconfigured transport must be denied")
        } catch (_: CancellationException) { assertEquals(0, attempts.get()) }
    }
}
