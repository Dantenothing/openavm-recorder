package com.dante.zeekrcapabilitylab.sentry

import com.dante.zeekrcapabilitylab.sentry.canary.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CanaryEncoderSelectionTest {
    private fun candidate(name: String, supported: Boolean = true) = CanaryEncoderCandidate(name, true, true,
        sizeSupported = supported, sizeAndRateSupported = supported, formatSupported = supported)

    @Test fun unsupportedDefaultDoesNotHideAnotherDeclaredHardwareEncoder() {
        val opened = mutableListOf<String>()
        val result = CanaryEncoderSelection.open(listOf(candidate("default", false), candidate("other")), true) {
            opened += it.name; it.name
        }
        assertEquals("other", result)
        assertEquals(listOf("other"), opened)
    }

    @Test fun nativeCameraSizeCanBeConfiguredDespiteNegativeCodecDeclaration() {
        val attempts = mutableListOf<CanaryEncoderAttempt>()
        val result = CanaryEncoderSelection.open(listOf(candidate("vendor", false)), true, observe = attempts::add) { "configured" }
        assertEquals("configured", result)
        assertEquals("EXACT_SOURCE_PROBE", attempts.last().mode)
        assertEquals("STARTED", attempts.last().state)
        assertFalse(candidate("vendor", false).sizeSupported!!)
    }

    @Test fun rejectedConfigurationTriesNextEncoderAndRetainsFailureStage() {
        val attempts = mutableListOf<CanaryEncoderAttempt>()
        val opened = mutableListOf<String>()
        val result = CanaryEncoderSelection.open(listOf(candidate("first"), candidate("second")), true, observe = attempts::add) {
            opened += it.name
            if (it.name == "first") throw CanaryEncoderOpenFailure("CONFIGURE", "CodecException")
            it.name
        }
        assertEquals("second", result)
        assertEquals(listOf("first", "second"), opened)
        assertTrue(attempts.any { it.name == "first" && it.state == "FAILED" && it.error == "CONFIGURE_CodecException" })
        assertEquals("STARTED", attempts.last().state)
    }

    @Test fun retryCountIsBoundedEvenWhenEveryConfigurationIsRejected() {
        val opened = mutableListOf<String>()
        val failure = runCatching {
            CanaryEncoderSelection.open((1..30).map { candidate("encoder$it") }, true) {
                opened += it.name; throw CanaryEncoderOpenFailure("START", "CodecException")
            }
        }.exceptionOrNull()
        assertEquals("ENCODER_CONFIGURE_FAILED", failure?.message)
        assertEquals(CanaryEncoderSelection.MAX_ATTEMPTS, opened.size)
    }

    @Test fun softwareBufferOnlyAndUndeclaredCameraSizesAreNeverProbed() {
        var opened = 0
        val prohibited = listOf(candidate("software").copy(hardware = false), candidate("buffer").copy(surfaceInput = false))
        assertTrue(runCatching { CanaryEncoderSelection.open(prohibited, true) { opened++ } }.isFailure)
        assertTrue(runCatching { CanaryEncoderSelection.open(listOf(candidate("valid")), false) { opened++ } }.isFailure)
        assertEquals(0, opened)
    }

    @Test fun unconfirmedCleanupStopsBeforeOpeningAnotherEncoder() {
        val opened = mutableListOf<String>()
        val failure = runCatching {
            CanaryEncoderSelection.open(listOf(candidate("first"), candidate("second")), true) {
                opened += it.name; throw CanaryEncoderOpenFailure("RELEASE", "CodecException", cleanupUnconfirmed = true)
            }
        }.exceptionOrNull()
        assertEquals(listOf("first"), opened)
        assertTrue(failure is CanaryEncoderOpenFailure && failure.cleanupUnconfirmed)
    }

    @Test fun stopRequestPreventsOpeningAndPreventsAnotherRetry() {
        var cancelled = true
        var opened = 0
        val candidates = listOf(candidate("first"), candidate("second"))
        assertEquals("ENCODER_SELECTION_CANCELLED", runCatching {
            CanaryEncoderSelection.open(candidates, true, isCancelled = { cancelled }) { opened++ }
        }.exceptionOrNull()?.message)
        assertEquals(0, opened)
        cancelled = false
        assertEquals("ENCODER_SELECTION_CANCELLED", runCatching {
            CanaryEncoderSelection.open(candidates, true, isCancelled = { cancelled }) {
                opened++; cancelled = true; throw CanaryEncoderOpenFailure("CONFIGURE", "CodecException")
            }
        }.exceptionOrNull()?.message)
        assertEquals(1, opened)
    }

    @Test fun startupEvidenceKeepsNativeDimensionsNegativeDeclarationsAndSeparateMemoryBudgets() {
        val evidence = CanaryStartupDiagnostics(sourceWidth = 1280, sourceHeight = 5140, cameraSurfaceDeclared = true,
            systemAvailableBytes = 11L * 1024 * 1024 * 1024, systemTotalBytes = 16L * 1024 * 1024 * 1024,
            appHeapMaxBytes = 512L * 1024 * 1024, appHeapHeadroomBytes = 350L * 1024 * 1024,
            candidates = listOf(candidate("vendor", false)), attempts = listOf(CanaryEncoderAttempt("vendor", "EXACT_SOURCE_PROBE", "STARTED")))
        val text = CanaryEvidenceJson.format.encodeToString(evidence)
        assertEquals(evidence, CanaryEvidenceJson.format.decodeFromString<CanaryStartupDiagnostics>(text))
        assertTrue(evidence.systemAvailableBytes!! > evidence.appHeapMaxBytes)
        assertFalse(evidence.candidates.single().sizeSupported!!)
    }

    @Test fun currentStartupReportFitsOneCopyAndIncludesFailureDiagnosticsWithoutOldHistory() {
        val startup = CanaryStartupDiagnostics(sourceWidth = 1280, sourceHeight = 5140,
            candidates = (1..16).map { candidate("hardware$it", false) },
            attempts = (1..4).map { CanaryEncoderAttempt("hardware$it", "EXACT_SOURCE_PROBE", "FAILED", "CONFIGURE_failure") })
        val ram = CanarySnapshot(phase = "STOPPED", stopReason = "ENCODER_CONFIGURE_FAILED", startup = startup)
        val current = CombinedCanaryReport(build = "test", sdk = 32, ramAndClip = ram, usb = UsbCanaryJobSnapshot())
        val report = CanaryStartupReportText.prepare(current, 123)
        assertEquals(listOf(report.json), report.parts)
        assertTrue(report.json.toByteArray(Charsets.UTF_8).size < CanaryTextReportWriter.MAX_COPY_BYTES)
        val decoded = CanaryEvidenceJson.format.decodeFromString<CanaryStartupReport>(report.json)
        assertEquals(current, decoded.current)
        assertEquals("OPENAVM_SENTRY_STARTUP_REPORT", decoded.format)
        assertFalse(report.json.contains("\"archive\":"))
    }
}
