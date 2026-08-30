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
        assertTrue(payload.contains("|test=VEHICLE_AWAY|"))
        assertTrue(payload.contains("|va=ACTIVE|"))
        assertTrue(payload.contains("|dark=1|"))
        assertTrue(payload.contains("|route=TERMINAL_STOP|"))
        assertTrue(payload.contains("|vr=CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF|"))
        assertTrue(payload.contains("|stop=VEHICLE_AWAY_CONFIRMED|"))
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

    @Test
    fun summaryPreservesCameraLossDecisionStateAfterForegroundClearsLiveLatch() {
        val route = event(1).copy(
            eventName = "RECORDER_CAMERA_LOSS_ROUTE",
            payload = mapOf(
                "phase" to "CONFIRMED",
                "appForeground" to "false",
                "screenOn" to "true",
                "mainDisplayOn" to "true",
                "backgroundPowerOffEvidence" to "true",
                "sawScreenOffWhileBackground" to "true",
                "sawMainDisplayOffWhileBackground" to "false",
                "route" to "TERMINAL_STOP",
                "reason" to "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF",
            ),
        )
        val laterForeground = event(2).copy(
            eventName = "RECORDER_VEHICLE_AWAY_SIGNAL",
            payload = mapOf(
                "phase" to "ACTIVE",
                "appForeground" to "true",
                "screenOn" to "true",
                "mainDisplayOn" to "true",
                "backgroundPowerOffEvidence" to "false",
            ),
        )

        val summary = VehicleAwayDiagnosticSummary.fromEvents(listOf(route, laterForeground))

        assertTrue(summary.backgroundPowerOffEvidence)
        assertFalse(summary.appForeground)
        assertTrue(summary.sawScreenOffWhileBackground)
        assertTrue(summary.cameraLossRoute == "TERMINAL_STOP")
        assertTrue(summary.lastReason == "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF")
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
        testType = "VEHICLE_AWAY",
        vehicleAway = VehicleAwayDiagnosticSummary(
            phase = "ACTIVE",
            appForeground = false,
            screenOn = false,
            mainDisplayOn = true,
            backgroundPowerOffEvidence = true,
            sawScreenOffWhileBackground = true,
            sawMainDisplayOffWhileBackground = false,
            cameraLossRoute = "TERMINAL_STOP",
            lastReason = "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF",
            lastStopReason = "VEHICLE_AWAY_CONFIRMED",
        ),
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
