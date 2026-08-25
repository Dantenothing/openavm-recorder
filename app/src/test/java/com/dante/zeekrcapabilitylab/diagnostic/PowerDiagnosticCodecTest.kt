package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerDiagnosticCodecTest {
    @Test
    fun payloadUsesBoundedVersionedFormat() {
        val evidence = evidence((1..80).map { event(it) })
        val payload = evidence.qrPayload(900)

        assertTrue(payload.startsWith("AVMP1|"))
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 900)
        assertTrue(payload.endsWith("tr=1"))
    }

    @Test
    fun detailAllowlistDoesNotLeakUnknownPayloadValues() {
        val event = event(1).copy(payload = mapOf(
            "interactive" to "false",
            "password" to "do-not-export",
            "filePath" to "C:/private/video.mp4",
        ))

        val detail = PowerDiagnosticCodec.safeDetail(event)

        assertTrue(detail.contains("interactive=false"))
        assertFalse(detail.contains("do-not-export"))
        assertFalse(detail.contains("private"))
    }

    private fun evidence(events: List<ProbeEvent>) = PowerDiagnosticEvidence(
        ticket = "ABC123",
        version = "0.2.0-alpha7-probe",
        versionCode = 17,
        gitSha = "1234567",
        processId = "123-456",
        recorder = "RECORDING",
        source = "SURROUND",
        segment = 3,
        wakeLock = true,
        exitReason = "NONE",
        events = events,
    )

    private fun event(number: Int) = ProbeEvent(
        sequence = number.toLong(),
        sessionId = "app",
        epochMs = System.currentTimeMillis() - number * 1_000L,
        elapsedRealtimeMs = number * 1_000L,
        category = "LIFECYCLE",
        eventName = "POWER_DELAYED_SNAPSHOT_$number",
        severity = "INFO",
        payload = mapOf(
            "interactive" to "false",
            "displays" to "0:OFF",
            "recorder" to "RECORDING",
        ),
    )
}
