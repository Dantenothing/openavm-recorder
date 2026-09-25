package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class ContinuousEncoderSelectionTest {
    private val accepted = ContinuousCodecCandidate("hardware.avc", true, true, true, true, 8192, 8192, 2, 2)
    @Test fun cameraDeclarationRemainsMandatoryEvenWithAValidCodec() {
        val selection = ContinuousEncoderSelectionPolicy.choose(false, listOf(accepted))
        assertNull(selection.codecName)
        assertEquals("CAMERA_CODEC_SIZE_NOT_DECLARED", selection.reason)
    }
    @Test fun evidenceDistinguishesRejectedTallVideoFromAQueryFailure() {
        val tall = ContinuousEncoderSelectionPolicy.choose(true, listOf(accepted.copy(
            formatSupported = false, sizeAndRateSupported = false, maximumHeight = 4096)))
        assertEquals("CODEC_FORMAT_NOT_DECLARED", tall.reason)
        assertEquals(4096, tall.candidates.single().maximumHeight)
        assertNull(tall.codecName)
        val query = ContinuousEncoderSelectionPolicy.choose(true, listOf(accepted.copy(queryError = "IllegalArgumentException")))
        assertEquals("CODEC_QUERY_FAILED", query.reason)
        assertNull(query.codecName)
    }
    @Test fun diagnosticDetailDoesNotRelaxAnyOfTheThreeExistingGates() {
        for (surface in listOf(false, true)) for (format in listOf(false, true)) for (rate in listOf(false, true)) {
            val result = ContinuousEncoderSelectionPolicy.choose(true,
                listOf(accepted.copy(surfaceInput = surface, formatSupported = format, sizeAndRateSupported = rate)))
            assertEquals(surface && format && rate, result.codecName != null)
        }
    }
    @Test fun diagnosticTruncationDoesNotChangeWhichCodecIsSelected() {
        val checks = List(10) { accepted.copy(name = "rejected.$it", formatSupported = false) } + accepted
        val result = ContinuousEncoderSelectionPolicy.choose(true, checks)
        assertEquals("hardware.avc", result.codecName)
        assertEquals(8, result.candidates.size)
        assertEquals(3, result.omittedCandidates)
    }
    @Test fun noEncoderNoSurfaceAndNoRateAreDifferentFailures() {
        assertEquals("NO_HARDWARE_AVC_ENCODER", ContinuousEncoderSelectionPolicy.choose(true, emptyList()).reason)
        assertEquals("CODEC_SURFACE_INPUT_NOT_DECLARED", ContinuousEncoderSelectionPolicy.choose(true,
            listOf(accepted.copy(surfaceInput = false))).reason)
        assertEquals("CODEC_SIZE_OR_RATE_NOT_DECLARED", ContinuousEncoderSelectionPolicy.choose(true,
            listOf(accepted.copy(sizeAndRateSupported = false))).reason)
    }
}
