package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly opted-in live GET probe; credentials never leave app memory and all POSTs are blocked. */
class IdleReadDiagnosticTest {
    @Test fun idleConnectionReadOnlyDiagnostic() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveReadOnly")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val config=ProtocolConfig.parse(SecureConfigStore(context).load() ?: error("Configuration unavailable"))
        val saved=SecureSessionStore(context).load() ?: error("Saved session unavailable")
        val posts=AtomicInteger(0)
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10,java.util.concurrent.TimeUnit.SECONDS).readTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(25,java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
                if(chain.request().method!="GET") { posts.incrementAndGet();throw IOException("Live diagnostic blocks writes") }
                chain.proceed(chain.request())
            }.build()
        val client=CloudClient(config,ReadOnlyTransport(http),restoredSession=saved)
        val selected=OverviewStore.get(context).state.value.vehicleKey
        val vehicle=client.resumeSession().firstOrNull { VehicleOverview.key(it)==selected } ?: error("Selected vehicle unavailable")
        val observations=mutableListOf<JsonObject>()
        fun save() {
            File(context.filesDir,"idle-read-diagnostic.json").writeText(buildJsonObject {
                put("schema","zeekr-idle-read/1");put("blockedPostAttempts",posts.get())
                putJsonArray("observations") { observations.forEach { add(it) } }
            }.toString())
        }
        for(pause in listOf(0L,35_000L,45_000L)) {
            delay(pause)
            val probe=client.probe(Endpoint.STATUS,vehicle)
            observations+=buildJsonObject { put("idleMs",pause);put("outcome",probe.outcome.name);put("at",System.currentTimeMillis()) }
            save()
            if(probe.outcome==ProbeOutcome.NETWORK) {
                val second=client.probe(Endpoint.STATUS,vehicle)
                observations+=buildJsonObject { put("immediateSecondRead",true);put("outcome",second.outcome.name);put("at",System.currentTimeMillis()) }
                save()
            }
        }
        assertEquals(0,posts.get())
        assertEquals(0,client.passwordLogins)
        assertEquals(0,client.vehicleRequestAttempts)
        assertEquals(0,client.climateRequestAttempts)
    }
}
