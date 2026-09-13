package com.dante.zeekrcapabilitylab.sentry

import com.dante.zeekrcapabilitylab.sentry.canary.*
import org.junit.Assert.*
import org.junit.Test

class CanaryRamPolicyTest {
    @Test fun fullCurrentTelemetryAndStartupDiagnosticsStillFitOneJsonCopy() {
        val points = (1..120).map { CanaryTelemetryPoint(it * 5000L, 300_000, 1, 31.9, 20_000_000, 30.0,
            payloadBytes = 18_000_000, bitrateBps = 4_000_000, completedHistorySeconds = 29.9, budgetEvictedGops = 10, droppedGops = 1) }
        val startup = CanaryStartupDiagnostics(candidates = (1..16).map {
            CanaryEncoderCandidate("hardware$it", true, true, false, false, false, "[128, 4096]", "[128, 4096]")
        }, attempts = (1..4).map { CanaryEncoderAttempt("hardware$it", "EXACT_SOURCE_PROBE", "STARTED") })
        val snapshot = CanarySnapshot(startup = startup, telemetry = CanaryTelemetry(samples = points))
        val current = CombinedCanaryReport(build = "test", sdk = 32, ramAndClip = snapshot, usb = UsbCanaryJobSnapshot())
        val prepared = CanaryStartupReportText.prepare(current, 1)
        assertEquals(1, prepared.parts.size)
        assertTrue(prepared.json.toByteArray(Charsets.UTF_8).size <= CanaryTextReportWriter.MAX_COPY_BYTES)
        assertEquals(current, CanaryEvidenceJson.format.decodeFromString(CanaryStartupReport.serializer(), prepared.json).current)
    }

    private fun history(seconds: Long, completeSeconds: Long = seconds - 2) = RingSnapshot(
        10_000_000, 12_000_000, 64L * 1024 * 1024, seconds * 1_000_000, 7, 500, 0,
        seconds * 1_000_000, 1, completedHistoryUs = completeSeconds * 1_000_000)

    @Test fun seventeenSecondHistoryCanSaveDiagnosticsAndDoesNotStopAtSixtySeconds() {
        val result = CanaryRamPolicy.assess(history(17), true, true)
        assertTrue(result.clipReady)
        assertFalse(result.targetReady)
        assertEquals(CanaryHistoryDeadline.CONTINUE_DIAGNOSTIC, CanaryRamPolicy.deadline(result))
        val report = CanarySnapshot(running = true, clipReady = true, historySeconds = 17.0, historyTargetTimedOut = true)
        assertEquals(CanaryCheckStatus.FAIL, CanaryEvidenceEvaluator.ram(report).checks.first { it.code == "RAM_HISTORY" }.status)
    }

    @Test fun incompleteGopsAndContinuityFailuresCannotEnableSaving() {
        for (ring in listOf(history(30, 4), history(30).copy(completedGops = 1), history(30).copy(droppedGops = 1))) {
            val result = CanaryRamPolicy.assess(ring, true, true)
            assertFalse(result.clipReady)
            assertFalse(result.targetReady)
            assertEquals(CanaryHistoryDeadline.STOP_UNUSABLE, CanaryRamPolicy.deadline(result))
        }
        assertFalse(CanaryRamPolicy.assess(history(30), false, true).clipReady)
        assertFalse(CanaryRamPolicy.assess(history(30), true, false).clipReady)
    }

    @Test fun fullHistoryRequiresThreeMinutesAndAllowsEnoughStartupTime() {
        val ready = CanaryRamPolicy.assess(history(180), true, true)
        assertTrue(ready.clipReady)
        assertTrue(ready.targetReady)
        assertEquals(CanaryHistoryDeadline.TARGET_REACHED, CanaryRamPolicy.deadline(ready))
        assertFalse(CanaryRamPolicy.assess(history(180).copy(historyUs = 179_999_999), true, true).targetReady)
        assertFalse(CanaryRamPolicy.assess(history(30), true, true).targetReady)
        assertEquals(240_000L, CanaryRamPolicy.STARTUP_DEADLINE_MS)
    }

    @Test fun diagnosticClipCannotBeReportedAsCompleteOrPassEvidence() {
        val coverage = CanaryRamPolicy.coverage(17_000_000, 10_000_000, null)
        assertTrue(coverage.partial)
        assertEquals("PRE_ROLL_SHORT", coverage.reason)
        val clip = CanaryClipResult(containerVerified = true, decodedFrameVerified = true, partial = coverage.partial,
            reason = coverage.reason, preRollAchievedUs = 17_000_000, postRollAchievedUs = 10_000_000,
            byteCount = 1024, sha256 = "ab".repeat(32))
        assertEquals(CanaryCheckStatus.FAIL, CanaryEvidenceEvaluator.ram(CanarySnapshot(clip = clip)).checks.first { it.code == "CLIP_1" }.status)
        assertFalse(CanaryRamPolicy.coverage(180_000_000, 10_000_000, null).partial)
        assertEquals("POST_ROLL_SHORT", CanaryRamPolicy.coverage(180_000_000, 9_999_999, null).reason)
        assertEquals("PRE_AND_POST_ROLL_SHORT", CanaryRamPolicy.coverage(179_999_999, 9_999_999, null).reason)
        assertEquals("GOP_BUDGET_GAP", CanaryRamPolicy.coverage(180_000_000, 10_000_000, "GOP_BUDGET_GAP").reason)
        assertTrue(CanaryRamPolicy.coverage(180_000_000, 10_000_000, "GOP_BUDGET_GAP").partial)
    }

    @Test fun diagnosticsSurviveStopAndJsonRoundTripWithoutMakingOldReportsReady() {
        val tracker = CanaryTelemetryAccumulator()
        val running = CanarySnapshot(running = true, historySeconds = 17.0, completedHistorySeconds = 15.0,
            clipReady = true, historyTargetTimedOut = true, encodedLiveBytes = 10_000_000, encodedPayloadLiveBytes = 8_000_000,
            encodedPayloadHighWaterBytes = 9_000_000, actualBitrateBps = 4_000_000, budgetEvictedGops = 3, bufferAllocator = "PAGED_4096_BYTES")
        tracker.observe(running, 60_000)
        val stopped = running.copy(running = false, clipReady = false, encodedLiveBytes = 0, encodedPayloadLiveBytes = 0)
        val telemetry = tracker.observe(stopped, 61_000)
        assertEquals(8_000_000L, telemetry.samples.first().payloadBytes)
        assertEquals(4_000_000L, telemetry.samples.first().bitrateBps)
        assertEquals(3L, telemetry.samples.first().budgetEvictedGops)
        val json = CanaryEvidenceJson.format
        val decoded = json.decodeFromString(CanarySnapshot.serializer(), json.encodeToString(CanarySnapshot.serializer(), stopped.copy(telemetry = telemetry)))
        assertTrue(decoded.historyTargetTimedOut)
        assertEquals(9_000_000L, decoded.encodedPayloadHighWaterBytes)
        assertFalse(json.decodeFromString(CanarySnapshot.serializer(), "{\"historySeconds\":17.0}").clipReady)
        assertEquals(CanaryCheckStatus.FAIL, CanaryEvidenceEvaluator.ram(decoded).checks.first { it.code == "RAM_HISTORY" }.status)
    }
}
