package com.dante.zeekrcapabilitylab.preflight.continuous

internal enum class ProbeAvcProfile { BASELINE, HIGH }
internal enum class ProbeBitrateMode { VBR, CBR }

/** Exact requested parameters. Null means the key was omitted, not a verified runtime default. */
internal data class ProbeVideoEncoding(
    val raster: RepackRaster,
    val frameRate: Int = 30,
    val bitrateBps: Int = 28_000_000,
    val iFrameIntervalSeconds: Int = 1,
    val profile: ProbeAvcProfile? = ProbeAvcProfile.BASELINE,
    val maxBFrames: Int? = null,
    val bitrateMode: ProbeBitrateMode? = null,
) {
    val mime: String get() = "video/avc"
    val candidateId: String get() = when (profile) {
        ProbeAvcProfile.BASELINE -> "AVC_BASELINE_DEFAULT_NO_B"
        ProbeAvcProfile.HIGH -> "AVC_HIGH_NO_B"
        null -> "UNSPECIFIED_PROFILE"
    }
    init {
        require(raster.width % 2 == 0 && raster.height % 2 == 0)
        require(frameRate in setOf(15, 20, 30) && bitrateBps > 0 && iFrameIntervalSeconds > 0)
        require(maxBFrames == null || maxBFrames >= 0)
    }
}

/** No camera API, recorder routing or capability bypass is authorized by constructing a spec. */
internal data class ContinuousProbeSpec(val layout: StripRepackLayout, val encoding: ProbeVideoEncoding) {
    init {
        require(layout.encoded == encoding.raster) { "ENCODING_MUST_MATCH_REPACK_OUTPUT" }
        require(layout.input.width % 2 == 0 && layout.input.height % 2 == 0 && layout.stripHeight % 2 == 0)
        require((encoding.profile == ProbeAvcProfile.BASELINE && encoding.maxBFrames == null) ||
            (encoding.profile == ProbeAvcProfile.HIGH && encoding.maxBFrames == 0)) {
            "PROBE_REQUIRES_NAMED_NO_REORDER_POLICY"
        }
    }

    companion object {
        fun surroundRepack(profile: ProbeAvcProfile = ProbeAvcProfile.BASELINE): ContinuousProbeSpec {
            val layout = StripRepackLayout(RepackRaster(1280, 5140), 1728)
            return ContinuousProbeSpec(layout, ProbeVideoEncoding(layout.encoded, profile = profile,
                maxBFrames = if (profile == ProbeAvcProfile.HIGH) 0 else null))
        }

        fun healthCheck(profile: ProbeAvcProfile = ProbeAvcProfile.BASELINE): ContinuousProbeSpec {
            // Four luma pixels per barcode bit, sufficient for the same lossy decode verifier.
            val layout = StripRepackLayout(RepackRaster(256, 1028), 352)
            return ContinuousProbeSpec(layout, ProbeVideoEncoding(layout.encoded, bitrateBps = 3_000_000,
                profile = profile, maxBFrames = if (profile == ProbeAvcProfile.HIGH) 0 else null))
        }
    }
}

/** Positive declaration is only permission to consider a probe, never evidence it encoded. */
internal data class ProbeCodecDeclaration(
    val codecName: String,
    val queriedEncoding: ProbeVideoEncoding,
    val surfaceInput: Boolean?,
    val formatSupported: Boolean?,
    val sizeAndRateSupported: Boolean?,
    val queryError: String? = null,
    val bitrateModeSupported: Boolean? = null,
    val rateControlCapabilities: kotlinx.serialization.json.JsonObject? = null,
) {
    fun accepts(request: ProbeVideoEncoding): Boolean = codecName.isNotBlank() && queryError == null &&
        request == queriedEncoding && surfaceInput == true && formatSupported == true && sizeAndRateSupported == true &&
            (request.bitrateMode==null || bitrateModeSupported==true)
}
