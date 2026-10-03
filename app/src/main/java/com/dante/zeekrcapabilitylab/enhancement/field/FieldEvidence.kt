package com.dante.zeekrcapabilitylab.enhancement.field

import kotlinx.serialization.json.*

/** Owned by the main dispatcher. Getter completion is never represented as sensor freshness. */
class FieldEvidence(
    val id: String, val beganMs: Long, val beganWallMs: Long, val durationSeconds: Int,
    val includeCoordinates: Boolean, val locationRequested: Boolean,
    private val specs: List<VehicleSignalSpec> = VehicleSignalCatalog.signals,
) {
    private class Signal {
        var reads = 0; var valid = 0; var available = 0; var changes = 0; var decodedChanges = 0
        var at = 0L; var maxLatency = 0L; var totalLatency = 0L
        var min: Double? = null; var max: Double? = null
        var last: VehicleSignalReading? = null
        var previousValidRaw: Double? = null
        var previousRaw: Double? = null
        var omittedChanges = 0
        val changesLog = mutableListOf<JsonObject>()
        val statuses = linkedMapOf<String, Int>()
        val uniqueRaw = linkedSetOf<Double>()
    }
    private val signals = specs.associate { it.key to Signal() }
    private val timeline = mutableListOf<JsonObject>()
    private val markers = mutableListOf<JsonObject>()
    private val gpsReasons = linkedMapOf<String, Int>()
    private var gpsSample: FieldGpsSample? = null
    private var gpsSpeedSample: FieldGpsSample? = null
    private val gpsProviders = linkedMapOf<String, Int>()
    private var gpsCount = 0; private var gpsValid = 0; private var gpsSpeedValid = 0
    private var firstValidGpsAfterMs: Long? = null
    private var lastTimeline = beganMs - 5_000
    private var lastTick = beganMs
    private var maxTickGap = 0L
    var sdkStatus = "NOT_STARTED"
    var gpsStatus = if (locationRequested) "STARTING" else "NOT_REQUESTED"
    var reason: String? = null
    var stoppedMs: Long? = null

    fun signal(reading: VehicleSignalReading, now: Long, latencyMs: Long) {
        if (stoppedMs != null) return
        val s = signals[reading.key] ?: return
        s.reads++; s.at = now; s.last = reading
        s.maxLatency = maxOf(s.maxLatency, latencyMs); s.totalLatency += latencyMs.coerceAtLeast(0)
        if (reading.sourceAvailable) s.available++
        s.statuses[reading.status] = (s.statuses[reading.status] ?: 0) + 1
        reading.raw?.takeIf(Double::isFinite)?.let { raw ->
            if (s.uniqueRaw.size < 12) s.uniqueRaw += raw
            if (s.previousRaw != null && s.previousRaw != raw) {
                s.changes++
                // Keep first two and latest eight changes independently for every signal.
                // Fast analog values cannot crowd discrete door/light transitions out.
                if (s.changesLog.size >= 10) { s.changesLog.removeAt(2); s.omittedChanges++ }
                s.changesLog += buildJsonObject {
                    put("ms", now - beganMs); put("key", reading.key); put("raw", raw)
                    put("valid", reading.valid); put("status", reading.status)
                    put("value", reading.value); put("text", reading.text)
                }
            }
            s.previousRaw = raw
        }
        if (reading.valid) {
            s.valid++
            reading.value?.takeIf(Double::isFinite)?.let {
                s.min = minOf(s.min ?: it, it); s.max = maxOf(s.max ?: it, it)
            }
            reading.raw?.takeIf(Double::isFinite)?.let { raw ->
                if (s.previousValidRaw != null && s.previousValidRaw != raw) {
                    s.decodedChanges++
                }
                s.previousValidRaw = raw
            }
        }
    }

    fun gps(sample: FieldGpsSample, now: Long) {
        if (stoppedMs != null) return
        val quality = FieldGpsQualityGate.evaluate(sample, now)
        gpsCount++; if (quality.positionValid) gpsValid++; if (quality.speedValid) gpsSpeedValid++
        if (quality.positionValid && firstValidGpsAfterMs == null) firstValidGpsAfterMs = now - beganMs
        gpsReasons[quality.reason] = (gpsReasons[quality.reason] ?: 0) + 1
        gpsProviders[sample.provider.take(32)] = (gpsProviders[sample.provider.take(32)] ?: 0) + 1
        if (quality.speedValid) gpsSpeedSample = sample
        // A poor network callback must not replace a still-fresh GPS fix.
        val previous = gpsSample
        if (previous == null || quality.positionValid || !FieldGpsQualityGate.evaluate(previous, now).positionValid) {
            gpsSample = sample
        }
    }

    fun gpsSpeed(now: Long): Double? = gpsSpeedSample?.takeIf {
        gpsStatus !in GPS_BLOCKED && FieldGpsQualityGate.evaluate(it, now).speedValid
    }?.speedMps?.times(3.6)

    fun speed(now: Long): Double? = signals["speed"]?.let { s ->
        s.last?.takeIf { it.valid && now - s.at in 0..2_000 }?.value
    }

    fun mark(label: String, now: Long) {
        if (stoppedMs == null && markers.size < 24) markers += buildJsonObject {
            put("ms", now - beganMs); put("label", label.take(64)); put("evidence", "USER_MARKER_NOT_VERIFIED")
        }
    }

    fun tick(now: Long, recorderRunning: Boolean, recorderContext: JsonObject? = null) {
        if (stoppedMs != null) return
        maxTickGap = maxOf(maxTickGap, now - lastTick); lastTick = now
        if (now - lastTimeline < 5_000 || timeline.size >= 361) return
        lastTimeline = now
        timeline += buildJsonObject {
            put("ms", now - beganMs); put("vehicleSpeedKmh", speed(now)); put("gpsSpeedKmh", gpsSpeed(now))
            put("recorderRunning", recorderRunning)
            recorderContext?.let { put("recorder", it) }
            put("gear", signals["gear"]?.last?.takeIf { it.valid }?.text)
            put("gps", gpsJson(now))
        }
    }

    private fun gpsJson(now: Long): JsonObject = buildJsonObject {
        val sample = gpsSample
        put("status", displayGpsStatus(now)); put("providerStatus", gpsStatus)
        put("fixQuality", sample?.let { FieldGpsQualityGate.evaluate(it, now).reason })
        if (sample != null) {
            put("provider", sample.provider.take(32)); put("ageMs", now - sample.elapsedMs)
            put("accuracyM", sample.accuracyM?.takeIf(Double::isFinite))
            put("cached", sample.cached); put("mock", sample.mock)
            put("speedMps", sample.speedMps?.takeIf(Double::isFinite))
            put("speedAccuracyMps", sample.speedAccuracyMps?.takeIf(Double::isFinite))
            if (includeCoordinates && FieldGpsQualityGate.evaluate(sample, now).positionValid) {
                put("latitude", sample.latitude); put("longitude", sample.longitude)
            }
        }
    }

    fun finish(now: Long, why: String) { if (stoppedMs == null) { stoppedMs = now; reason = why } }

    fun ui(now: Long, recorderRunning: Boolean): FieldTestUiState = FieldTestUiState(
        active = stoppedMs == null, phase = if (stoppedMs == null) "RUNNING" else "FINISHED",
        reason = reason, elapsedSeconds = ((stoppedMs ?: now) - beganMs).coerceAtLeast(0) / 1000,
        durationSeconds = durationSeconds, sampledSignals = signals.values.count { it.reads > 0 },
        validSignals = signals.values.count { it.valid > 0 }, changedSignals = signals.values.count { it.changes > 0 },
        vehicleSpeedKmh = if (stoppedMs == null) speed(now) else null,
        gpsSpeedKmh = if (stoppedMs == null) gpsSpeed(now) else null,
        gpsStatus = displayGpsStatus(now),
        sdkStatus = sdkStatus, recorderRunning = recorderRunning,
        signals = specs.map { spec -> val s = signals.getValue(spec.key); val r = s.last
            FieldSignalUi(spec.key, spec.nameEn, spec.nameZh,
                if (r?.valid == true) r.text ?: r.value?.toString() ?: "—" else "—",
                r?.status ?: "NOT_READ", s.at.takeIf { s.reads > 0 }?.let { now - it }, s.changes)
        },
    )

    fun json(now: Long): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("format", "OPENAVM_VEHICLE_FIELD_TEST"); put("run", id)
        put("startedAtEpochMs", beganWallMs); put("durationLimitSeconds", durationSeconds)
        put("elapsedMs", (stoppedMs ?: now) - beganMs); put("complete", stoppedMs != null); put("reason", reason)
        put("sdkStatus", sdkStatus); put("locationRequested", locationRequested)
        put("coordinatesIncludedByOptIn", includeCoordinates); put("readOnly", true)
        put("commandsSent", 0); put("cameraOpenedByFieldTest", false); put("wakeLockHeld", false)
        put("readTimestampMeaning", "GETTER_COMPLETION_NOT_SENSOR_MEASUREMENT_TIME")
        put("confidenceMeaning", "REFERENCE_CONFIDENCE_NOT_VALIDATION_ON_THIS_VEHICLE")
        put("sampling", buildJsonObject { put("fastTargetMs", 250); put("otherTargetMs", 3000)
            put("timelineMs", 5000); put("maxTickGapMs", maxTickGap) })
        put("signals", buildJsonArray { specs.forEach { spec -> val s = signals.getValue(spec.key)
            add(buildJsonObject {
                put("key", spec.key); put("idHex", "0x%08X".format(spec.id)); put("zone", spec.zone)
                put("getter", spec.kind.name); put("unit", spec.unit); put("confidence", spec.confidence)
                put("reads", s.reads); put("getterReturned", s.available); put("validReadings", s.valid)
                put("changes", s.changes); put("min", s.min); put("max", s.max)
                put("decodedChanges", s.decodedChanges); put("omittedChanges", s.omittedChanges)
                put("maxReadLatencyMs", s.maxLatency); put("totalReadLatencyMs", s.totalLatency)
                put("lastReadAgeMs", s.at.takeIf { s.reads > 0 }?.let { now - it })
                put("lastRaw", s.last?.raw?.takeIf(Double::isFinite)); put("lastValue", s.last?.value?.takeIf(Double::isFinite))
                put("lastText", s.last?.text); put("lastStatus", s.last?.status ?: "NOT_READ")
                put("rawExamples", JsonArray(s.uniqueRaw.map(::JsonPrimitive)))
                put("statuses", JsonObject(s.statuses.mapValues { JsonPrimitive(it.value) }))
            })
        } })
        put("gps", buildJsonObject {
            put("samples", gpsCount); put("validPositions", gpsValid); put("validSpeeds", gpsSpeedValid)
            put("firstValidFixAfterMs", firstValidGpsAfterMs)
            put("reasons", JsonObject(gpsReasons.mapValues { JsonPrimitive(it.value) })); put("latest", gpsJson(now))
            put("providerCallbacks", JsonObject(gpsProviders.mapValues { JsonPrimitive(it.value) }))
            put("speedProvider", gpsSpeedSample?.provider); put("speedAgeMs", gpsSpeedSample?.let { now - it.elapsedMs })
            put("validatedSpeedKmh", gpsSpeed(now))
        })
        put("markers", JsonArray(markers.toList()))
        put("changes", JsonArray(signals.values.flatMap { it.changesLog }.sortedBy { it["ms"]?.jsonPrimitive?.long ?: 0 }))
        put("changeRetention", "PER_SIGNAL_FIRST_2_AND_LATEST_8")
        put("omittedChanges", signals.values.sumOf { it.omittedChanges }); put("timeline", JsonArray(timeline.toList()))
    }
    private fun displayGpsStatus(now: Long): String = when {
        gpsStatus in GPS_BLOCKED -> gpsStatus
        locationRequested && gpsValid == 0 && now - beganMs > 30_000 -> "GPS_TIMEOUT"
        gpsSample != null -> FieldGpsQualityGate.evaluate(gpsSample!!, now).reason
        else -> gpsStatus
    }
    private companion object { val GPS_BLOCKED = setOf("NO_PERMISSION", "LOCATION_OFF", "NO_PROVIDER") }
}

/** Produces valid bounded JSON for Android clipboard IPC, retaining summaries first. */
object FieldReportBudget {
    const val MAX_BYTES = 240 * 1024
    fun combine(metadata: JsonObject, sessions: List<JsonObject>, cameras: List<JsonObject>, maxBytes: Int = MAX_BYTES): String {
        fun strip(report: JsonObject, timeline: Boolean, changes: Boolean) = JsonObject(report.filterKeys {
            (!timeline || it != "timeline") && (!changes || it != "changes")
        })
        fun sparse(report: JsonObject): JsonObject = JsonObject(report.toMutableMap().apply {
            val points = report["timeline"] as? JsonArray
            if (points != null) {
                put("timeline", JsonArray(points.filterIndexed { index, _ -> index % 6 == 0 || index == points.lastIndex }))
                put("exportTimelineStride", JsonPrimitive(6))
            } else remove("timeline")
        })
        for (level in 0..3) {
            val result = buildJsonObject {
                metadata.forEach { (k, v) -> put(k, v) }
                put("format", "OPENAVM_FIELD_BUNDLE"); put("schemaVersion", 1)
                put("trimLevel", level); put("historyAvailable", sessions.size)
                put("vehicleTests", JsonArray(sessions.take(if (level == 3) 1 else 4).mapIndexed { i, report ->
                    strip(if (i == 0 && level >= 1) sparse(report) else report, i > 0, i > 0 || level >= 2)
                }))
                put("cameraTests", JsonArray(cameras.take(if (level == 3) 2 else 8)))
            }.toString()
            if (result.toByteArray(Charsets.UTF_8).size <= maxBytes) return result
        }
        return buildJsonObject {
            put("format", "OPENAVM_FIELD_BUNDLE"); put("schemaVersion", 1)
            put("error", "REPORT_EXCEEDS_EXPORT_BUDGET"); put("historyAvailable", sessions.size)
        }.toString()
    }
}
