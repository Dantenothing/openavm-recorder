package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class ContinuousProbeSpecTest {
    private val full = ContinuousProbeSpec.surroundRepack()
    private fun declaration(encoding: ProbeVideoEncoding = full.encoding) = ProbeCodecDeclaration(
        "hardware.avc", encoding, surfaceInput = true, formatSupported = true, sizeAndRateSupported = true,
    )

    @Test fun cameraInputAndEncoderOutputAreSeparateAndNoImplicitScaleIsAllowed() {
        assertEquals(RepackRaster(1280, 5140), full.layout.input)
        assertEquals(RepackRaster(3840, 1728), full.encoding.raster)
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousProbeSpec(full.layout, full.encoding.copy(raster = full.layout.input))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousProbeSpec(full.layout, full.encoding.copy(raster = RepackRaster(1920, 1080)))
        }
    }

    @Test fun earlierP0QueryWithoutExplicitProfileAndBFrameRequestCannotAuthorizeThisFormat() {
        val p0Encoding = full.encoding.copy(profile = null, maxBFrames = null)
        assertFalse(declaration(p0Encoding).accepts(full.encoding))
        assertTrue(declaration().accepts(full.encoding))
        assertEquals(ProbeAvcProfile.BASELINE, full.encoding.profile)
        assertNull(full.encoding.maxBFrames)
    }

    @Test fun everyRequestedEncodingParameterParticipatesInDeclarationMatching() {
        for (other in listOf(
            full.encoding.copy(raster = RepackRaster(1920, 1080)),
            full.encoding.copy(frameRate = 20), full.encoding.copy(bitrateBps = 20_000_000),
            full.encoding.copy(iFrameIntervalSeconds = 2), full.encoding.copy(profile = null),
            full.encoding.copy(maxBFrames = 0), full.encoding.copy(maxBFrames = 1),
        )) assertFalse(declaration(other).accepts(full.encoding))
    }

    @Test fun unknownFalseAndFailedDeclarationsNeverAuthorizeAProbe() {
        val accepted = declaration()
        for (value in listOf(false, null)) {
            assertFalse(accepted.copy(surfaceInput = value).accepts(full.encoding))
            assertFalse(accepted.copy(formatSupported = value).accepts(full.encoding))
            assertFalse(accepted.copy(sizeAndRateSupported = value).accepts(full.encoding))
        }
        assertFalse(accepted.copy(queryError = "QUERY_FAILED").accepts(full.encoding))
        assertFalse(accepted.copy(codecName = "").accepts(full.encoding))
    }

    @Test fun healthCheckIsDistinctAndMustPassItsOwnDeclaration() {
        val health = ContinuousProbeSpec.healthCheck()
        assertEquals(RepackRaster(256, 1028), health.layout.input)
        assertEquals(RepackRaster(768, 352), health.encoding.raster)
        assertFalse(declaration(health.encoding).accepts(full.encoding))
        assertTrue(declaration(health.encoding).accepts(health.encoding))
    }

    @Test fun probeRejectsUnsupportedOrderingPolicyAndInvalidEncodingParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousProbeSpec(full.layout, full.encoding.copy(profile = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousProbeSpec(full.layout, full.encoding.copy(maxBFrames = 1))
        }
        assertThrows(IllegalArgumentException::class.java) { full.encoding.copy(frameRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { full.encoding.copy(bitrateBps = 0) }
        assertThrows(IllegalArgumentException::class.java) { full.encoding.copy(raster = RepackRaster(3839, 1728)) }
    }

    @Test fun baselineOmitsBFrameAndLatencyKeysInsteadOfExplicitlyWritingZero() {
        assertNull(full.encoding.maxBFrames)
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousProbeSpec(full.layout, full.encoding.copy(maxBFrames = 0))
        }
    }

    @Test fun highNoBIsAnIndependentExplicitCandidate() {
        val high = ContinuousProbeSpec.surroundRepack(ProbeAvcProfile.HIGH)
        assertEquals("AVC_HIGH_NO_B", high.encoding.candidateId)
        assertEquals(0, high.encoding.maxBFrames)
        assertFalse(declaration().accepts(high.encoding))
        assertThrows(IllegalArgumentException::class.java) {
            ContinuousProbeSpec(high.layout, high.encoding.copy(maxBFrames = null))
        }
    }
}
