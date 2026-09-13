package com.dante.zeekrcapabilitylab.sentry

import java.nio.ByteBuffer

/** Bounded Annex-B CSD parser. Views are used only before the codec callback releases its buffer. */
data class AvcParameterSets(val sps: ByteBuffer, val pps: ByteBuffer) {
    companion object {
        fun read(first: ByteBuffer, second: ByteBuffer? = null): AvcParameterSets {
            require(first.remaining() + (second?.remaining() ?: 0) in 1..16_384) { "AVC_CONFIG_SIZE_INVALID" }
            var sps: ByteBuffer? = null
            var pps: ByteBuffer? = null
            for (input in listOfNotNull(first, second)) {
                val data = input.slice().asReadOnlyBuffer()
                fun prefix(at: Int): Int = when {
                    at + 3 <= data.limit() && data.get(at) == 0.toByte() && data.get(at + 1) == 0.toByte() && data.get(at + 2) == 1.toByte() -> 3
                    at + 4 <= data.limit() && data.get(at) == 0.toByte() && data.get(at + 1) == 0.toByte() && data.get(at + 2) == 0.toByte() && data.get(at + 3) == 1.toByte() -> 4
                    else -> 0
                }
                var at = 0
                while (at < data.limit()) {
                    val head = prefix(at)
                    require(head > 0 && at + head < data.limit()) { "AVC_CONFIG_NOT_ANNEX_B" }
                    val start = at + head
                    var end = start + 1
                    while (end < data.limit() && prefix(end) == 0) end++
                    val nal = data.duplicate().apply { position(start); limit(end) }.slice().asReadOnlyBuffer()
                    when (nal.get(0).toInt() and 31) {
                        7 -> { require(sps == null || sps == nal) { "MULTIPLE_DISTINCT_SPS_UNSUPPORTED" }; sps = nal }
                        8 -> { require(pps == null || pps == nal) { "MULTIPLE_DISTINCT_PPS_UNSUPPORTED" }; pps = nal }
                        6, 9 -> Unit
                        else -> error("UNEXPECTED_NAL_IN_CODEC_CONFIG")
                    }
                    at = end
                }
            }
            return AvcParameterSets(requireNotNull(sps) { "AVC_SPS_MISSING" }, requireNotNull(pps) { "AVC_PPS_MISSING" })
        }
    }
}
