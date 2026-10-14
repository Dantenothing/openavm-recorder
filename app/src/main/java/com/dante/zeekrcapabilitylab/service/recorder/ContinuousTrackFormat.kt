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
    private val presentKeys: Set<String>,
    private val bufferSizes: Map<String, Int>,
) {
    fun compatibleWith(other: ContinuousTrackFormatSpec): Boolean =
        mime == other.mime && width == other.width && height == other.height &&
            codecData == other.codecData && trackIntegers == other.trackIntegers &&
            decoderHints.all { (key, value) -> other.decoderHints[key]?.let { it == value } != false }

    /** Remember newly supplied hints without ever replacing the first MediaFormat used for MP4. */
    fun includingHintsFrom(other: ContinuousTrackFormatSpec): ContinuousTrackFormatSpec =
        ContinuousTrackFormatSpec(mime, width, height, codecData, trackIntegers, other.decoderHints + decoderHints,
            presentKeys + other.presentKeys, bufferSizes)

    /** A new decoder contract can only use the same displayed geometry at an existing file cut. */
    fun sameLayoutAs(other: ContinuousTrackFormatSpec): Boolean =
        mime == other.mime && width == other.width && height == other.height && trackIntegers == other.trackIntegers

    fun differences(other: ContinuousTrackFormatSpec): List<ContinuousTrackFieldEvidence> {
        val result = mutableListOf<ContinuousTrackFieldEvidence>()
        fun changed(key: String) {
            result += ContinuousTrackFieldEvidence(label(key), "CHANGED", key in presentKeys, key in other.presentKeys,
                bufferSizes[key], other.bufferSizes[key])
        }
        if (mime != other.mime) changed("mime")
        if (width != other.width) changed("width")
        if (height != other.height) changed("height")
        (codecData.keys + other.codecData.keys).sorted().filter { codecData[it] != other.codecData[it] }.forEach(::changed)
        trackIntegers.keys.sorted().filter { trackIntegers[it] != other.trackIntegers[it] }.forEach(::changed)
        decoderHints.keys.sorted().filter { key -> other.decoderHints[key]?.let { it != decoderHints[key] } == true }
            .forEach(::changed)
        return result.take(24)
    }

    // Never accidentally put CSD bytes into an exception, log, or diagnostic report.
    override fun toString(): String = "ContinuousTrackFormatSpec($mime,$width,$height)"

    companion object {
        const val INCOMPLETE = "ENCODER_TRACK_FORMAT_INCOMPLETE"
        const val INCOMPATIBLE = "ENCODER_TRACK_FORMAT_INCOMPATIBLE"
        const val MAX_BUFFER_BYTES = 64 * 1024
        const val MAX_TOTAL_BUFFER_BYTES = 256 * 1024

        /** Only AVC is produced by these recorders. Android's muxer contract requires AVC SPS in
         * csd-0 and PPS in csd-1. Do not assume combined decoder-input CSD is a valid muxer format. */
        fun capture(fields: ContinuousTrackFormatFields): ContinuousTrackFormatSpec {
            val keys = try { fields.keys.toSet() } catch (error: Exception) {
                throw incompleteField("FORMAT_KEYS", "INVALID", null, cause = error)
            }
            if (keys.size > 128 || keys.any { it.length > 128 }) throw incompleteField("FORMAT_KEYS", "OUT_OF_RANGE", true)
            fun requirePresent(key: String) {
                if (key !in keys) throw incompleteField(label(key), "MISSING", false)
            }
            fun integer(key: String): Int {
                requirePresent(key)
                return try { fields.integer(key) } catch (error: Exception) {
                    throw incompleteField(label(key), "TYPE_MISMATCH", true, cause = error)
                }
            }
            fun range(key: String, value: Int, valid: Boolean): Int {
                if (!valid) throw incompleteField(label(key), "OUT_OF_RANGE", key in keys)
                return value
            }
            requirePresent("mime")
            val rawMime = try { fields.string("mime") } catch (error: Exception) {
                throw incompleteField("MIME", "TYPE_MISMATCH", true, cause = error)
            } ?: throw incompleteField("MIME", "MISSING", true)
            val mime = rawMime.lowercase(Locale.ROOT)
            if (mime != "video/avc") throw incompleteField("MIME", "INVALID", true)
            val width = integer("width").let { range("width", it, it in 1..32_768) }
            val height = integer("height").let { range("height", it, it in 1..32_768) }
            val csdKeys = keys.filter { it.startsWith("csd-") }.sorted()
            requirePresent("csd-0"); requirePresent("csd-1")
            if (csdKeys.any { it !in (0..7).map { n -> "csd-$n" } }) throw incompleteField("FORMAT_KEYS", "INVALID", true)
            var total = 0
            val data = linkedMapOf<String, String>()
            val sizes = linkedMapOf<String, Int>()
            for (key in csdKeys + listOf("hdr-static-info").filter { it in keys }) {
                val source = try { fields.buffer(key)?.duplicate() } catch (error: Exception) {
                    throw incompleteField(label(key), "TYPE_MISMATCH", true, cause = error)
                } ?: throw incompleteField(label(key), "MISSING", true)
                val size = source.remaining()
                if (size == 0) throw incompleteField(label(key), "EMPTY", true, size)
                if (size !in 1..MAX_BUFFER_BYTES) throw incompleteField(label(key), "OUT_OF_RANGE", true, size)
                total += size
                if (total > MAX_TOTAL_BUFFER_BYTES) throw incompleteField(label(key), "OUT_OF_RANGE", true, size)
                val bytes = ByteArray(source.remaining()).also { source.get(it) }
                sizes[key] = size
                data[key] = Base64.getEncoder().encodeToString(bytes)
                if ((key == "csd-0" && 7 !in annexBNalTypes(bytes)) ||
                    (key == "csd-1" && 8 !in annexBNalTypes(bytes))) throw incompleteField(label(key), "INVALID", true, size)
            }
            fun optional(key: String) = if (key in keys) integer(key) else null
            val integers = linkedMapOf<String, Int>()
            val hints = linkedMapOf<String, Int>()
            // Missing / zero colour means unspecified, not evidence for an actual colour change.
            // AVC CSD remains byte-identical; conflicting explicitly supplied hints still fail.
            for (key in listOf("color-standard", "color-range", "color-transfer", "profile", "level")) {
                optional(key)?.also { range(key, it, it >= 0) }?.takeIf { it != 0 }?.let { hints[key] = it }
            }
            integers["rotation-degrees"] = (optional("rotation-degrees") ?: 0).also {
                range("rotation-degrees", it, it in setOf(0, 90, 180, 270))
            }
            val left = optional("crop-left") ?: 0; val top = optional("crop-top") ?: 0
            val right = optional("crop-right") ?: width - 1; val bottom = optional("crop-bottom") ?: height - 1
            range("crop-left", left, left in 0 until width); range("crop-right", right, right in left until width)
            range("crop-top", top, top in 0 until height); range("crop-bottom", bottom, bottom in top until height)
            integers.putAll(mapOf("crop-left" to left, "crop-top" to top, "crop-right" to right, "crop-bottom" to bottom))
            val sarWidth = optional("sar-width"); val sarHeight = optional("sar-height")
            if ((sarWidth == null) != (sarHeight == null)) {
                throw incompleteField(if (sarWidth == null) "SAR_WIDTH" else "SAR_HEIGHT", "MISSING", false)
            }
            var a = sarWidth ?: 1; var b = sarHeight ?: 1
            range("sar-width", a, a in 1..65_535); range("sar-height", b, b in 1..65_535)
            var x = a; var y = b
            while (y != 0) { val next = x % y; x = y; y = next }
            a /= x; b /= x
            integers["sar-width"] = a; integers["sar-height"] = b
            return ContinuousTrackFormatSpec(mime, width, height, data.toMap(), integers.toMap(), hints.toMap(), keys, sizes.toMap())
        }

        internal fun incompleteField(field: String, kind: String, present: Boolean?, bytes: Int? = null,
                                     cause: Throwable? = null): ContinuousTrackFormatException =
            ContinuousTrackFormatException(INCOMPLETE,
                listOf(ContinuousTrackFieldEvidence(field, kind, null, present, null,
                    bytes?.takeIf { it in 0..MAX_TOTAL_BUFFER_BYTES })))
                .also { if (cause != null) it.initCause(cause) }

        private fun label(key: String): String = when (key) {
            "hdr-static-info" -> "HDR_STATIC"
            "rotation-degrees" -> "ROTATION"
            else -> key.replace('-', '_').uppercase(Locale.ROOT)
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

internal enum class ContinuousFormatDecision { INSTALL, KEEP, STAGE_ROLLOVER, DISCARD_TERMINAL }
internal data class ContinuousTrackFieldEvidence(val field: String, val kind: String,
    val beforePresent: Boolean?, val afterPresent: Boolean?, val beforeBytes: Int? = null, val afterBytes: Int? = null)
internal class ContinuousTrackFormatException(val errorCode: String, val fields: List<ContinuousTrackFieldEvidence>) :
    IllegalStateException(errorCode)
internal data class ContinuousFormatObservation(val notificationCount: Long = 0, val compatibleRepeats: Long = 0,
                                                val decision: String = "NOT_RECEIVED",
                                                val fields: List<ContinuousTrackFieldEvidence> = emptyList(),
                                                val discardedTransitionFrames: Long = 0)

/** Codec-worker state. Strict by default; direct cabin recording can request its own safe cut. */
internal class ContinuousTrackFormatState {
    private var first: ContinuousTrackFormatSpec? = null
    @Volatile private var pending: ContinuousTrackFormatSpec? = null
    private var independentRollover = false
    val hasPendingBoundary: Boolean get() = pending != null
    var discardTerminalMedia = false; private set
    @Volatile private var observation = ContinuousFormatObservation()
    fun snapshot(): ContinuousFormatObservation = observation

    fun accept(spec: ContinuousTrackFormatSpec, cutPending: Boolean = false, terminal: Boolean = false,
               allowIndependentRollover: Boolean = false): ContinuousFormatDecision {
        observation = observation.copy(notificationCount = observation.notificationCount + 1)
        if (terminal) cancelPending()
        pending?.let { candidate ->
            if ((!cutPending && !independentRollover) || !candidate.compatibleWith(spec)) reject(candidate.differences(spec))
            pending = candidate.includingHintsFrom(spec)
            observation = observation.copy(compatibleRepeats = observation.compatibleRepeats + 1, decision = "ROLLOVER_PENDING")
            return ContinuousFormatDecision.KEEP
        }
        val previous = first
        if (previous == null) {
            if (terminal && discardTerminalMedia) return ContinuousFormatDecision.DISCARD_TERMINAL
            first = spec
            observation = observation.copy(decision = "INSTALL", fields = emptyList())
            return ContinuousFormatDecision.INSTALL
        }
        if (!previous.compatibleWith(spec)) {
            val differences = previous.differences(spec)
            if (terminal) {
                discardAtEnd()
                observation = observation.copy(decision = "INCOMPATIBLE", fields = differences)
                return ContinuousFormatDecision.DISCARD_TERMINAL
            }
            if ((!cutPending && !allowIndependentRollover) || !previous.sameLayoutAs(spec)) reject(differences)
            independentRollover = allowIndependentRollover
            pending = spec
            observation = observation.copy(decision = "ROLLOVER_PENDING", fields = differences, discardedTransitionFrames = 0)
            return ContinuousFormatDecision.STAGE_ROLLOVER
        }
        first = previous.includingHintsFrom(spec)
        observation = observation.copy(compatibleRepeats = observation.compatibleRepeats + 1, decision = "KEEP", fields = emptyList())
        return ContinuousFormatDecision.KEEP
    }

    /** Must run before a planner could drop a non-keyframe or mutate its cut request. */
    fun beforeMediaSample(keyframe: Boolean, boundaryReady: Boolean = true): Boolean {
        if (pending != null && (!keyframe || !boundaryReady)) {
            // The new contract is already in effect. These frames cannot enter the old track,
            // nor start an independently decodable new file. The owner bounds this wait.
            observation = observation.copy(discardedTransitionFrames = observation.discardedTransitionFrames + 1)
            return false
        }
        return true
    }
    fun commitBoundary(cutBefore: Boolean): Boolean {
        val candidate = pending ?: return false
        if (!cutBefore) rejectTransition()
        first = candidate; pending = null
        return true
    }
    fun cancelPending() {
        if (pending != null) {
            discardAtEnd()
            observation = observation.copy(decision = "ROLLOVER_CANCELLED")
        }
    }
    /** Terminal-only latch. A changed or unreadable final contract must never write into the old track. */
    fun discardAtEnd() { pending = null; discardTerminalMedia = true }

    fun incomplete(error: Throwable? = null, terminal: Boolean = false) {
        if (terminal) discardAtEnd()
        var current = error
        var fields = emptyList<ContinuousTrackFieldEvidence>()
        repeat(4) {
            if (current is ContinuousTrackFormatException) { fields = (current as ContinuousTrackFormatException).fields; current = null }
            else current = current?.cause
        }
        observation = observation.copy(notificationCount = observation.notificationCount + 1, decision = "INCOMPLETE", fields = fields.take(24))
    }
    private fun rejectTransition(): Nothing = reject(listOf(ContinuousTrackFieldEvidence("FORMAT_TRANSITION", "INVALID", true, true)))
    private fun reject(fields: List<ContinuousTrackFieldEvidence>): Nothing {
        val safeFields = fields.ifEmpty { listOf(ContinuousTrackFieldEvidence("FORMAT_TRANSITION", "INVALID", true, true)) }.take(24)
        observation = observation.copy(decision = "INCOMPATIBLE", fields = safeFields)
        throw ContinuousTrackFormatException(ContinuousTrackFormatSpec.INCOMPATIBLE, safeFields)
    }
}
