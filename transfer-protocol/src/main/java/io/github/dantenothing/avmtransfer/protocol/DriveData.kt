package io.github.dantenothing.avmtransfer.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Approximate segment-relative alignment, not a claim of an ECU sensor measurement timestamp. */
@Serializable
data class DriveDataDescriptor(
    val schemaVersion: Int = 1,
    val sizeBytes: Long,
    val sha256: String,
    val recordingSessionId: String,
    val segmentStartedElapsedMs: Long,
    val segmentEndedElapsedMs: Long,
    val alignment: String = "SEGMENT_ELAPSED_APPROX_V1",
)

@Serializable
data class DriveDataBundle(
    val schemaVersion: Int = 1,
    val recordingSessionId: String,
    val segmentStartedElapsedMs: Long,
    val segmentEndedElapsedMs: Long,
    val alignment: String = "SEGMENT_ELAPSED_APPROX_V1",
    val gps: List<DriveGpsSample> = emptyList(),
    val vehicle: List<DriveVehicleSample> = emptyList(),
)

@Serializable
data class DriveGpsSample(
    val elapsedMs: Long,
    val epochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val provider: String,
    /** GPS/location speed only; vehicle speed is stored in the vehicle channel. */
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val breakBefore: Boolean = false,
)

@Serializable
data class DriveVehicleValue(
    val readElapsedMs: Long,
    val number: Double? = null,
    val text: String? = null,
    val status: String = "OK",
)

@Serializable
data class DriveVehicleSample(
    val elapsedMs: Long,
    val epochMs: Long,
    val values: Map<String, DriveVehicleValue>,
    val breakBefore: Boolean = false,
)

/** Bounded, allowlisted data contract. It does not fetch URLs, inspect device clocks or infer routes. */
object DriveDataCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_SAMPLES_PER_SOURCE = 20_000
    const val CAPABILITY = "drive-data-v1"
    const val ALIGNMENT = "SEGMENT_ELAPSED_APPROX_V1"
    const val PLAYBACK_AGE_MS = 2_000L
    private const val MAX_EPOCH_MS = 253_402_300_799_999L
    private val uuid = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    private val hash = Regex("[a-f0-9]{64}")
    private val providers = setOf("gps", "network", "fused", "passive")
    private val vehicleKeys = setOf("speed", "gear", "indicator", "turn_left", "turn_right", "brake_pedal", "stop_lamp")
    private val statuses = setOf("OK", "NO_VALUE", "SENTINEL", "TINY_SENTINEL", "NON_FINITE", "OUT_OF_RANGE",
        "UNKNOWN_ENUM", "NON_INTEGER_ENUM", "UNAVAILABLE", "READ_FAILED", "INVALID_VALUE", "STALE")
    private val primitiveMembers = setOf("schemaVersion", "sizeBytes", "segmentStartedElapsedMs", "segmentEndedElapsedMs",
        "elapsedMs", "epochMs", "latitude", "longitude", "accuracyM", "speedMps", "speedAccuracyMps",
        "readElapsedMs", "number", "breakBefore")
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = false; isLenient = false }

    fun encode(bundle: DriveDataBundle): ByteArray {
        require(validBundle(bundle)) { "INVALID_DRIVE_DATA" }
        val bytes = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "DRIVE_DATA_TOO_LARGE" }
        return bytes
    }

    fun decode(bytes: ByteArray): DriveDataBundle? = runCatching {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val text = utf8(bytes)
        JsonShapeCheck(text).check()
        json.decodeFromString<DriveDataBundle>(text).takeIf(::validBundle)
    }.getOrNull()

    fun descriptor(bytes: ByteArray): DriveDataDescriptor? = decode(bytes)?.let { bundle ->
        DriveDataDescriptor(sizeBytes = bytes.size.toLong(), sha256 = sha256(bytes),
            recordingSessionId = bundle.recordingSessionId, segmentStartedElapsedMs = bundle.segmentStartedElapsedMs,
            segmentEndedElapsedMs = bundle.segmentEndedElapsedMs, alignment = bundle.alignment)
    }

    fun validDescriptor(descriptor: DriveDataDescriptor): Boolean = descriptor.schemaVersion == 1 &&
        descriptor.sizeBytes in 1..MAX_BYTES.toLong() && hash.matches(descriptor.sha256) &&
        validWindow(descriptor.recordingSessionId, descriptor.segmentStartedElapsedMs, descriptor.segmentEndedElapsedMs, descriptor.alignment)

    fun matches(bundle: DriveDataBundle, descriptor: DriveDataDescriptor, sidecarJson: String?): Boolean = runCatching {
        if (!validDescriptor(descriptor) || !validBundle(bundle) || bundle.schemaVersion != descriptor.schemaVersion ||
            bundle.recordingSessionId != descriptor.recordingSessionId ||
            bundle.segmentStartedElapsedMs != descriptor.segmentStartedElapsedMs ||
            bundle.segmentEndedElapsedMs != descriptor.segmentEndedElapsedMs || bundle.alignment != descriptor.alignment) return false
        val sidecar = sidecarJson ?: return false
        if (sidecar.isEmpty() || sidecar.toByteArray(Charsets.UTF_8).size > TransferProtocol.MAX_SIDECAR_BYTES) return false
        JsonShapeCheck(sidecar).check()
        val root = json.parseToJsonElement(sidecar) as? JsonObject ?: return false
        fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun integer(key: String) = (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        text("recordingSessionId") == bundle.recordingSessionId && text("recordingMode") == "NORMAL" &&
            integer("startedAtElapsedRealtimeMs") == bundle.segmentStartedElapsedMs &&
            integer("stoppedAtElapsedRealtimeMs") == bundle.segmentEndedElapsedMs &&
            ("timeLapseMultiplier" !in root || integer("timeLapseMultiplier") == 1L)
    }.getOrDefault(false)

    /** SHA is checked over the original bytes, never a re-encoded or normalized document. */
    fun verify(bytes: ByteArray, descriptor: DriveDataDescriptor, sidecarJson: String?): DriveDataBundle? {
        if (!validDescriptor(descriptor) || bytes.size.toLong() != descriptor.sizeBytes || sha256(bytes) != descriptor.sha256) return null
        return decode(bytes)?.takeIf { matches(it, descriptor, sidecarJson) }
    }

    internal fun validGps(sample: DriveGpsSample): Boolean = sample.elapsedMs >= 0 && sample.epochMs in 1..MAX_EPOCH_MS &&
        sample.latitude.isFinite() && sample.latitude in -90.0..90.0 &&
        sample.longitude.isFinite() && sample.longitude in -180.0..180.0 &&
        sample.accuracyM.isFinite() && sample.accuracyM in 0f..50f && sample.provider in providers &&
        (sample.speedMps == null || sample.speedMps.isFinite() && sample.speedMps in 0f..100f) &&
        (sample.speedAccuracyMps == null || sample.speedAccuracyMps.isFinite() && sample.speedAccuracyMps in 0f..3f)

    internal fun validVehicle(sample: DriveVehicleSample): Boolean = sample.elapsedMs >= 0 && sample.epochMs in 1..MAX_EPOCH_MS &&
        sample.values.size <= vehicleKeys.size && sample.values.all { (key, field) ->
            validVehicleValue(key, field) && field.readElapsedMs <= sample.elapsedMs &&
                (field.status != "OK" || sample.elapsedMs - field.readElapsedMs <= PLAYBACK_AGE_MS)
        }

    private fun validVehicleValue(key: String, field: DriveVehicleValue): Boolean {
        if (key !in vehicleKeys || field.readElapsedMs < 0 || field.status !in statuses) return false
        if (field.status != "OK") return field.number == null && field.text == null
        return when (key) {
            "speed" -> field.text == null && field.number?.let { it.isFinite() && it in 0.0..360.0 } == true
            "gear" -> field.number == null && field.text in setOf("P", "R", "N", "D")
            "indicator" -> field.number == null && field.text in setOf("off", "left", "right", "hazard")
            else -> field.text == null && field.number in setOf(0.0, 1.0)
        }
    }

    private fun validBundle(bundle: DriveDataBundle): Boolean {
        if (bundle.schemaVersion != 1 ||
            !validWindow(bundle.recordingSessionId, bundle.segmentStartedElapsedMs, bundle.segmentEndedElapsedMs, bundle.alignment) ||
            bundle.gps.size > MAX_SAMPLES_PER_SOURCE || bundle.vehicle.size > MAX_SAMPLES_PER_SOURCE) return false
        var previous = -1L
        for (point in bundle.gps) {
            if (!validGps(point) || point.elapsedMs < bundle.segmentStartedElapsedMs ||
                point.elapsedMs >= bundle.segmentEndedElapsedMs || point.elapsedMs <= previous) return false
            previous = point.elapsedMs
        }
        previous = -1L
        for (sample in bundle.vehicle) {
            if (!validVehicle(sample) || sample.elapsedMs < bundle.segmentStartedElapsedMs ||
                sample.elapsedMs >= bundle.segmentEndedElapsedMs || sample.elapsedMs <= previous ||
                sample.values.any { it.value.readElapsedMs < bundle.segmentStartedElapsedMs }) return false
            previous = sample.elapsedMs
        }
        return true
    }

    private fun validWindow(session: String, start: Long, end: Long, alignment: String) =
        uuid.matches(session) && start >= 0 && end > start && alignment == ALIGNMENT

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Preflight depth, collection counts and duplicate keys before kotlinx builds the document. */
    private class JsonShapeCheck(private val text: String) {
        private var at = 0
        fun check() { value(0); whitespace(); require(at == text.length) }
        private fun whitespace() { while (at < text.length && text[at] in " \r\n\t") at++ }
        private fun value(depth: Int, member: String? = null) {
            require(depth <= 16)
            whitespace(); require(at < text.length)
            if (member in primitiveMembers) {
                // kotlinx accepts some quoted numeric tokens. This wire contract does not.
                require(text[at] != '"' && text[at] != '{' && text[at] != '[')
            }
            when (text[at]) {
                '{' -> {
                    at++; whitespace()
                    val keys = HashSet<String>()
                    if (take('}')) return
                    while (true) {
                        whitespace(); val key = string()
                        require(keys.size < 256 && keys.add(key))
                        whitespace(); require(take(':')); value(depth + 1, key); whitespace()
                        if (take('}')) break
                        require(take(','))
                    }
                }
                '[' -> {
                    at++; whitespace()
                    if (take(']')) return
                    var count = 0
                    while (true) {
                        require(++count <= MAX_SAMPLES_PER_SOURCE)
                        value(depth + 1); whitespace()
                        if (take(']')) break
                        require(take(','))
                    }
                }
                '"' -> string()
                else -> {
                    val start = at
                    while (at < text.length && text[at] !in ",]} \r\n\t") at++
                    require(at > start)
                }
            }
        }
        private fun take(char: Char): Boolean = if (at < text.length && text[at] == char) { at++; true } else false
        private fun string(): String {
            val start = at
            require(take('"'))
            while (at < text.length) {
                val next = text[at++]
                if (next == '"') return json.decodeFromString<String>(text.substring(start, at))
                if (next == '\\') { require(at < text.length); at++ }
            }
            error("UNTERMINATED_STRING")
        }
    }
}

object DriveDataPlayback {
    /** Lookup in the car's recorded elapsed time domain, never the receiving phone's elapsed time. */
    fun gpsAt(samples: List<DriveGpsSample>, atElapsedMs: Long?): DriveGpsSample? {
        if (atElapsedMs == null || atElapsedMs < 0) return null
        val index = samples.binarySearchBy(atElapsedMs) { it.elapsedMs }.let { if (it >= 0) it else -it - 2 }
        val sample = samples.getOrNull(index) ?: return null
        if (!DriveDataCodec.validGps(sample) || atElapsedMs - sample.elapsedMs !in 0..DriveDataCodec.PLAYBACK_AGE_MS) return null
        val next = samples.getOrNull(index + 1)
        if (atElapsedMs > sample.elapsedMs && next != null &&
            (next.breakBefore || next.provider != sample.provider || next.elapsedMs - sample.elapsedMs > 5_000)) return null
        return sample
    }

    fun vehicleAt(samples: List<DriveVehicleSample>, atElapsedMs: Long?): DriveVehicleSample? {
        if (atElapsedMs == null || atElapsedMs < 0) return null
        val index = samples.binarySearchBy(atElapsedMs) { it.elapsedMs }.let { if (it >= 0) it else -it - 2 }
        val sample = samples.getOrNull(index) ?: return null
        if (!DriveDataCodec.validVehicle(sample) || atElapsedMs - sample.elapsedMs !in 0..DriveDataCodec.PLAYBACK_AGE_MS) return null
        if (atElapsedMs > sample.elapsedMs && samples.getOrNull(index + 1)?.breakBefore == true) return null
        return sample.copy(values = sample.values.mapValues { (_, field) ->
            if (atElapsedMs - field.readElapsedMs in 0..DriveDataCodec.PLAYBACK_AGE_MS) field
            else DriveVehicleValue(field.readElapsedMs, status = "STALE")
        })
    }
}
