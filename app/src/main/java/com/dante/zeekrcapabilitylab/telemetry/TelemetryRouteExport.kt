package com.dante.zeekrcapabilitylab.telemetry

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit, local exports of recorded fixes. No map service or route inference is involved. */
object TelemetryRouteExport {
    private const val EXTENSION_NAMESPACE = "urn:openavm:telemetry:1"
    // Keep GPX dateTime values within four-digit years; malformed clocks are not route evidence.
    private const val MAX_EPOCH_MS = 253_402_300_799_999L
    private data class Segment(val points: List<TelemetryFix>, val breakReason: String)

    fun gpx(track: TelemetryTrack): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"OpenAVM\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:openavm=\"").append(EXTENSION_NAMESPACE).append("\">\n")
        append("<trk><name>OpenAVM ").append(xml(track.header.sessionId.take(128))).append("</name>\n")
        for (segment in segments(track, splitAntimeridian = false)) {
            append("<trkseg>\n")
            for (fix in segment.points) {
                append("<trkpt lat=\"").append(decimal(fix.latitude)).append("\" lon=\"")
                    .append(decimal(fix.longitude)).append("\"><time>")
                    .append(timestamp(fix)).append("</time><extensions>")
                append("<openavm:elapsedMs>").append(fix.elapsedMs).append("</openavm:elapsedMs>")
                append("<openavm:accuracyM>").append(decimal(fix.accuracyM)).append("</openavm:accuracyM>")
                append("<openavm:provider>").append(xml(fix.provider)).append("</openavm:provider>")
                gpsSpeed(fix)?.let { speed ->
                    append("<openavm:gpsSpeedMps>").append(decimal(speed)).append("</openavm:gpsSpeedMps>")
                    fix.speedAccuracyMps?.let { accuracy ->
                        append("<openavm:gpsSpeedAccuracyMps>").append(decimal(accuracy))
                            .append("</openavm:gpsSpeedAccuracyMps>")
                    }
                }
                append("</extensions></trkpt>\n")
            }
            append("</trkseg>\n")
        }
        append("</trk></gpx>\n")
    }

    fun geoJson(track: TelemetryTrack): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("source", "OpenAVM recorded GPS fixes")
        put("schemaVersion", 1)
        put("sessionId", track.header.sessionId.take(128))
        put("timeBasis", "recorded monotonic elapsedMs; time is UTC")
        put("inputLimit", TelemetryStore.MAX_POINTS)
        put("inputTruncated", track.points.size > TelemetryStore.MAX_POINTS)
        put("features", JsonArray(segments(track, splitAntimeridian = true).mapIndexed { index, segment ->
            buildJsonObject {
                put("type", "Feature")
                put("geometry", buildJsonObject {
                    put("type", if (segment.points.size == 1) "Point" else "LineString")
                    val coordinates = segment.points.map { fix ->
                        JsonArray(listOf(JsonPrimitive(fix.longitude), JsonPrimitive(fix.latitude)))
                    }
                    put("coordinates", if (coordinates.size == 1) coordinates.single() else JsonArray(coordinates))
                })
                put("properties", buildJsonObject {
                    put("segment", index)
                    put("breakReason", segment.breakReason)
                    put("pointCount", segment.points.size)
                    // Arrays have the same order as geometry coordinates, including one-item Point arrays.
                    put("time", JsonArray(segment.points.map { JsonPrimitive(timestamp(it)) }))
                    put("elapsedMs", JsonArray(segment.points.map { JsonPrimitive(it.elapsedMs) }))
                    put("accuracyM", JsonArray(segment.points.map { JsonPrimitive(it.accuracyM) }))
                    put("provider", JsonArray(segment.points.map { JsonPrimitive(it.provider) }))
                    put("gpsSpeedMps", JsonArray(segment.points.map { gpsSpeed(it)?.let(::JsonPrimitive) ?: JsonNull }))
                    put("gpsSpeedAccuracyMps", JsonArray(segment.points.map {
                        if (gpsSpeed(it) == null) JsonNull else it.speedAccuracyMps?.let(::JsonPrimitive) ?: JsonNull
                    }))
                })
            }
        }))
    }.toString()

    /** Call only after a user chooses to open this recorded location in an external map. */
    fun googleMapsPointUrl(fix: TelemetryFix): String? = fix.takeIf(::validFix)?.let {
        "https://www.google.com/maps/search/?api=1&query=${decimal(it.latitude)}%2C${decimal(it.longitude)}"
    }

    private fun segments(track: TelemetryTrack, splitAntimeridian: Boolean): List<Segment> {
        if (track.header.version != 1 || track.header.startedElapsedMs < 0) return emptyList()
        val result = ArrayList<Segment>()
        var current = ArrayList<TelemetryFix>()
        var previous: TelemetryFix? = null
        var pendingBreak: String? = "START"
        var currentReason = "START"
        fun finish() {
            if (current.isNotEmpty()) result += Segment(current, currentReason)
            current = ArrayList()
        }
        for (fix in track.points.take(TelemetryStore.MAX_POINTS)) {
            val prior = previous
            if (!validFix(fix) || fix.elapsedMs < track.header.startedElapsedMs) {
                pendingBreak = "INVALID_FIX"
                continue
            }
            if (prior != null && fix.elapsedMs <= prior.elapsedMs) {
                pendingBreak = "OUT_OF_ORDER"
                continue
            }
            val reason = pendingBreak ?: when {
                fix.breakBefore -> "RECORDED_GAP"
                prior == null -> "START"
                fix.provider != prior.provider -> "PROVIDER_CHANGE"
                fix.elapsedMs - prior.elapsedMs > TelemetryPolicy.MAX_GAP_MS -> "TIME_GAP"
                splitAntimeridian && kotlin.math.abs(fix.longitude - prior.longitude) > 180.0 -> "ANTIMERIDIAN"
                else -> null
            }
            if (reason != null) {
                finish()
                currentReason = reason
            }
            current += fix
            previous = fix
            pendingBreak = null
        }
        finish()
        return result
    }

    private fun validFix(fix: TelemetryFix): Boolean = TelemetryPolicy.validPosition(fix) &&
        fix.accuracyM <= TelemetryPolicy.MAX_ACCURACY_M && fix.elapsedMs >= 0 &&
        fix.epochMs in 1..MAX_EPOCH_MS

    private fun gpsSpeed(fix: TelemetryFix): Float? = fix.speedMps?.takeIf { speed ->
        speed.isFinite() && speed in 0f..100f &&
            (fix.speedAccuracyMps == null || (fix.speedAccuracyMps.isFinite() && fix.speedAccuracyMps in 0f..3f))
    }

    private fun timestamp(fix: TelemetryFix): String = Instant.ofEpochMilli(fix.epochMs).toString()
    private fun decimal(number: Number): String = BigDecimal(number.toString()).stripTrailingZeros().toPlainString()

    private fun xml(value: String): String = buildString {
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            when (val codePoint = iterator.nextInt()) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                9, 10, 13 -> appendCodePoint(codePoint)
                in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(codePoint)
                else -> append('\uFFFD')
            }
        }
    }
}
