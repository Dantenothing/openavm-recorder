package com.dante.zeekrcapabilitylab.service.recorder

import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale

/** The decoder contract, independent of mutable MediaFormat / ByteBuffer cursor state. */
internal class ContinuousTrackFormatSpec private constructor(
    val mime: String,
    val width: Int,
    val height: Int,
    private val codecData: Map<String, String>,
    private val trackIntegers: Map<String, Int>,
    private val decoderHints: Map<String, Int>,
) {
    fun compatibleWith(other: ContinuousTrackFormatSpec): Boolean =
        mime == other.mime && width == other.width && height == other.height &&
            codecData == other.codecData && trackIntegers == other.trackIntegers &&
            decoderHints.all { (key, value) -> other.decoderHints[key]?.let { it == value } != false }

    /** Remember newly supplied hints without ever replacing the first MediaFormat used for MP4. */
    fun includingHintsFrom(other: ContinuousTrackFormatSpec): ContinuousTrackFormatSpec =
        ContinuousTrackFormatSpec(mime, width, height, codecData, trackIntegers, other.decoderHints + decoderHints)

    // Never accidentally put CSD bytes into an exception, log, or diagnostic report.
    override fun toString(): String = "ContinuousTrackFormatSpec($mime,$width,$height)"

    companion object {
        const val INCOMPLETE = "ENCODER_TRACK_FORMAT_INCOMPLETE"
        const val INCOMPATIBLE = "ENCODER_TRACK_FORMAT_INCOMPATIBLE"
        const val MAX_BUFFER_BYTES = 64 * 1024
        const val MAX_TOTAL_BUFFER_BYTES = 256 * 1024

        /** Only AVC is produced by these recorders. Android's muxer contract requires AVC SPS in
         * csd-0 and PPS in csd-1. Do not assume combined decoder-input CSD is a valid muxer format. */
        fun capture(fields: ContinuousTrackFormatFields): ContinuousTrackFormatSpec = try {
            val keys = fields.keys.toSet()
            require(keys.size <= 128 && keys.all { it.length <= 128 })
            val mime = requireNotNull(fields.string("mime")).lowercase(Locale.ROOT)
            require(mime == "video/avc")
            val width = fields.integer("width"); val height = fields.integer("height")
            require(width in 1..32_768 && height in 1..32_768)
            val csdKeys = keys.filter { it.startsWith("csd-") }.sorted()
            require("csd-0" in csdKeys && "csd-1" in csdKeys && csdKeys.all { it in (0..7).map { n -> "csd-$n" } })
            var total = 0
            val data = linkedMapOf<String, String>()
            for (key in csdKeys + listOf("hdr-static-info").filter { it in keys }) {
                val source = requireNotNull(fields.buffer(key)).duplicate()
                require(source.remaining() in 1..MAX_BUFFER_BYTES)
                total += source.remaining(); require(total <= MAX_TOTAL_BUFFER_BYTES)
                val bytes = ByteArray(source.remaining()).also { source.get(it) }
                data[key] = Base64.getEncoder().encodeToString(bytes)
                if (key == "csd-0") require(7 in annexBNalTypes(bytes))
                if (key == "csd-1") require(8 in annexBNalTypes(bytes))
            }
            fun optional(key: String) = if (key in keys) fields.integer(key) else null
            val integers = linkedMapOf<String, Int>()
            val hints = linkedMapOf<String, Int>()
            // Missing / zero colour means unspecified, not evidence for an actual colour change.
            // AVC CSD remains byte-identical; conflicting explicitly supplied hints still fail.
            for (key in listOf("color-standard", "color-range", "color-transfer", "profile", "level")) {
                optional(key)?.also { require(it >= 0) }?.takeIf { it != 0 }?.let { hints[key] = it }
            }
            integers["rotation-degrees"] = (optional("rotation-degrees") ?: 0).also {
                require(it in setOf(0, 90, 180, 270))
            }
            val left = optional("crop-left") ?: 0; val top = optional("crop-top") ?: 0
            val right = optional("crop-right") ?: width - 1; val bottom = optional("crop-bottom") ?: height - 1
            require(left in 0 until width && right in left until width && top in 0 until height && bottom in top until height)
            integers.putAll(mapOf("crop-left" to left, "crop-top" to top, "crop-right" to right, "crop-bottom" to bottom))
            val sarWidth = optional("sar-width"); val sarHeight = optional("sar-height")
            require((sarWidth == null) == (sarHeight == null))
            var a = sarWidth ?: 1; var b = sarHeight ?: 1
            require(a in 1..65_535 && b in 1..65_535)
            var x = a; var y = b
            while (y != 0) { val next = x % y; x = y; y = next }
            a /= x; b /= x
            integers["sar-width"] = a; integers["sar-height"] = b
            ContinuousTrackFormatSpec(mime, width, height, data.toMap(), integers.toMap(), hints.toMap())
        } catch (error: Exception) {
            throw IllegalStateException(INCOMPLETE, error)
        }

        /** Read header types only, not SPS interpretation. Exact bytes are compared separately. */
        private fun annexBNalTypes(bytes: ByteArray): Set<Int> {
            val result = mutableSetOf<Int>()
            for (index in 0 until (bytes.size - 3).coerceAtLeast(0)) {
                if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()) {
                    result += bytes[index + 3].toInt() and 31
                }
            }
            return result
        }
    }
}

internal interface ContinuousTrackFormatFields {
    val keys: Set<String>
    fun string(key: String): String?
    fun integer(key: String): Int
    fun buffer(key: String): ByteBuffer?
}

internal enum class ContinuousFormatDecision { INSTALL, KEEP }
internal data class ContinuousFormatObservation(val notificationCount: Long = 0, val compatibleRepeats: Long = 0,
                                                val decision: String = "NOT_RECEIVED")

/** Confined to the codec worker: reject a changed contract before accepting its following samples.
 * One first MediaFormat owns the run; later files retain that format, including omitted hints. */
internal class ContinuousTrackFormatState {
    private var first: ContinuousTrackFormatSpec? = null
    @Volatile private var observation = ContinuousFormatObservation()
    fun snapshot(): ContinuousFormatObservation = observation

    fun accept(spec: ContinuousTrackFormatSpec): ContinuousFormatDecision {
        observation = observation.copy(notificationCount = observation.notificationCount + 1)
        val previous = first
        if (previous == null) {
            first = spec
            observation = observation.copy(decision = "INSTALL")
            return ContinuousFormatDecision.INSTALL
        }
        if (!previous.compatibleWith(spec)) {
            observation = observation.copy(decision = "INCOMPATIBLE")
            throw IllegalStateException(ContinuousTrackFormatSpec.INCOMPATIBLE)
        }
        first = previous.includingHintsFrom(spec)
        observation = observation.copy(compatibleRepeats = observation.compatibleRepeats + 1, decision = "KEEP")
        return ContinuousFormatDecision.KEEP
    }

    fun incomplete() {
        observation = observation.copy(notificationCount = observation.notificationCount + 1, decision = "INCOMPLETE")
    }
}
