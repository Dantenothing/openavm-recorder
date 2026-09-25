package com.dante.zeekrbridge.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Defense-in-depth checks for obsolete credential paths; socket behavior is tested on Android. */
class PhoneSecurityBoundaryTest {
    private fun source(path: String) = File("src/main/java/com/dante/zeekrbridge/$path").readText()

    @Test fun discoveryNeverReadsTheActivePairingCode() {
        assertFalse(source("server/BridgeServer.kt").contains("pairingId = PairingManager.currentCode()"))
    }
    @Test fun receiverStartupDoesNotOpenPairingWindow() {
        assertFalse(source("server/BridgeServer.kt").contains("PairingManager.newPairingCode()"))
    }
    @Test fun obsoleteQrClientCannotSendCredentialsOverHttp() {
        val client = source("core/PhonePairingClient.kt")
        assertFalse(client.contains("/api/pair/finalize"))
        assertFalse(client.contains("HttpURLConnection"))
    }
    @Test fun exportedDiagnosticsNeverInterpolateThePairingCode() {
        assertFalse(source("MainActivity.kt").contains("code=\u0024{PairingManager.code.value}"))
        assertTrue(source("MainActivity.kt").contains("ConnectionLogRedactor.redact(text)"))
    }
}
