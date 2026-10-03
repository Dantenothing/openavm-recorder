package com.dante.zeekrcapabilitylab.telemetry

import java.io.StringReader
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource

class TelemetryRouteExportTest {
    private val epoch = 1_791_006_000_000L
    private fun fix(ms: Long, provider: String = "gps") = TelemetryFix(
        elapsedMs = ms, epochMs = epoch + ms, latitude = -34.9, longitude = 138.6,
        accuracyM = 4f, provider = provider,
    )
    private fun track(vararg points: TelemetryFix) = TelemetryTrack(
        TelemetryHeader(sessionId = "00000000-0000-0000-0000-000000000001", startedElapsedMs = 1_000), points.toList(),
    )
    private fun xml(track: TelemetryTrack): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(InputSource(StringReader(TelemetryRouteExport.gpx(track))))
    private fun features(track: TelemetryTrack): JsonArray =
        Json.parseToJsonElement(TelemetryRouteExport.geoJson(track)).jsonObject.getValue("features").jsonArray

    @Test fun consecutiveFixesExportOneSegmentWithRecordedCoordinatesAndUtcTimes() {
        val data = track(fix(1_000), fix(2_000).copy(latitude = -34.9001))
        val document = xml(data)
        assertEquals(1, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        val times = document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "time")
        assertEquals(Instant.ofEpochMilli(epoch + 1_000).toString(), times.item(0).textContent)
        val geometry = features(data).single().jsonObject.getValue("geometry").jsonObject
        assertEquals("LineString", geometry.getValue("type").jsonPrimitive.content)
        assertEquals(138.6, geometry.getValue("coordinates").jsonArray[0].jsonArray[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(-34.9001, geometry.getValue("coordinates").jsonArray[1].jsonArray[1].jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun gapAndProviderChangesProduceSeparateSegments() {
        val data = track(fix(1_000), fix(2_000), fix(8_001), fix(9_001, "network"), fix(10_001, "network"))
        assertEquals(3, xml(data).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        val result = features(data)
        assertEquals(3, result.size)
        assertEquals("TIME_GAP", result[1].jsonObject.getValue("properties").jsonObject.getValue("breakReason").jsonPrimitive.content)
        assertEquals("PROVIDER_CHANGE", result[2].jsonObject.getValue("properties").jsonObject.getValue("breakReason").jsonPrimitive.content)
        assertEquals("Point", result[1].jsonObject.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun exactlyFiveSecondsRemainsConnectedButRecordedBreakDoesNot() {
        val result = features(track(fix(1_000), fix(6_000), fix(7_000).copy(breakBefore = true)))
        assertEquals(2, result.size)
        assertEquals(2, result[0].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.size)
        assertEquals("RECORDED_GAP", result[1].jsonObject.getValue("properties").jsonObject.getValue("breakReason").jsonPrimitive.content)
    }

    @Test fun invalidFixBreaksRouteInsteadOfJoiningSurroundingPoints() {
        val data = track(fix(1_000), fix(2_000).copy(latitude = Double.NaN), fix(3_000))
        assertEquals(2, features(data).size)
        val document = xml(data)
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
    }

    @Test fun outOfOrderOrDuplicatePointDoesNotResetMonotonicFence() {
        val result = features(track(fix(1_000), fix(4_000), fix(3_000), fix(2_000), fix(4_000), fix(5_000)))
        assertEquals(2, result.size)
        assertEquals(2, result[0].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.size)
        val lastProperties = result[1].jsonObject.getValue("properties").jsonObject
        assertEquals("OUT_OF_ORDER", lastProperties.getValue("breakReason").jsonPrimitive.content)
        assertEquals("5000", lastProperties.getValue("elapsedMs").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun malformedTimesCoordinatesAndProviderAreNotExported() {
        val invalid = listOf(
            fix(1_000).copy(epochMs = 0), fix(2_000).copy(epochMs = Long.MAX_VALUE),
            fix(3_000).copy(epochMs = Long.MIN_VALUE), fix(4_000).copy(latitude = 91.0),
            fix(5_000).copy(longitude = Double.POSITIVE_INFINITY), fix(6_000).copy(longitude = -181.0),
            fix(7_000).copy(provider = "<script>"), fix(8_000).copy(accuracyM = 51f),
            fix(9_000).copy(accuracyM = Float.NaN), fix(-1), fix(500),
        )
        val data = track(*invalid.toTypedArray())
        assertTrue(features(data).isEmpty())
        assertEquals(0, xml(data).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
    }

    @Test fun zeroGpsSpeedRemainsZeroMissingAndInvalidSpeedRemainAbsent() {
        val data = track(
            fix(1_000).copy(speedMps = 0f, speedAccuracyMps = 0f),
            fix(2_000), fix(3_000).copy(speedMps = Float.NaN), fix(4_000).copy(speedMps = -1f),
            fix(5_000).copy(speedMps = 101f), fix(6_000).copy(speedMps = 2f, speedAccuracyMps = 4f),
            fix(7_000).copy(speedMps = 2f, speedAccuracyMps = Float.NaN),
        )
        val props = features(data).single().jsonObject.getValue("properties").jsonObject
        val speeds = props.getValue("gpsSpeedMps").jsonArray
        assertEquals(7, speeds.size)
        assertEquals(0f, speeds[0].jsonPrimitive.content.toFloat(), 0f)
        assertTrue(speeds.drop(1).all { it == JsonNull })
        assertFalse(props.containsKey("vehicleSpeedMps"))
        val document = xml(data)
        assertEquals(1, document.getElementsByTagNameNS("urn:openavm:telemetry:1", "gpsSpeedMps").length)
        assertEquals("0", document.getElementsByTagNameNS("urn:openavm:telemetry:1", "gpsSpeedMps").item(0).textContent)
        assertEquals(0, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "speed").length)
    }

    @Test fun validSpeedWithoutReportedSpeedAccuracyIsPreserved() {
        val data = track(fix(1_000).copy(speedMps = 2.25f))
        val props = features(data).single().jsonObject.getValue("properties").jsonObject
        assertEquals("2.25", props.getValue("gpsSpeedMps").jsonArray.single().jsonPrimitive.content)
        assertEquals(JsonNull, props.getValue("gpsSpeedAccuracyMps").jsonArray.single())
        assertEquals("2.25", xml(data).getElementsByTagNameNS("urn:openavm:telemetry:1", "gpsSpeedMps").item(0).textContent)
    }

    @Test fun xmlEscapesLabelsAndReplacesInvalidXmlCharacters() {
        val label = "<&\"'>\u0001\uD800"
        val data = track(fix(1_000)).let { it.copy(header = it.header.copy(sessionId = label)) }
        val name = xml(data).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "name").item(0).textContent
        assertEquals("OpenAVM <&\"'>\uFFFD\uFFFD", name)
        val json = Json.parseToJsonElement(TelemetryRouteExport.geoJson(data)).jsonObject
        assertEquals(label, json.getValue("sessionId").jsonPrimitive.content)
    }

    @Test fun localeDoesNotChangeCoordinatesAndGpxDecimalsDoNotUseExponentNotation() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val point = fix(1_000).copy(latitude = 0.0000001, longitude = -0.0000001)
            val data = track(point)
            val gpx = TelemetryRouteExport.gpx(data)
            assertTrue(gpx.contains("lat=\"0.0000001\" lon=\"-0.0000001\""))
            assertEquals(1, xml(data).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
            assertEquals(1, features(data).size)
            assertEquals("https://www.google.com/maps/search/?api=1&query=0.0000001%2C-0.0000001",
                TelemetryRouteExport.googleMapsPointUrl(point))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun antimeridianPreservesGpxFixesAndDoesNotDrawWorldCrossingGeoJsonLine() {
        val data = track(fix(1_000).copy(longitude = 179.999), fix(2_000).copy(longitude = -179.999),
            fix(3_000).copy(longitude = -179.998))
        val document = xml(data)
        assertEquals(1, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        assertEquals(3, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        val result = features(data)
        assertEquals(2, result.size)
        assertEquals("Point", result[0].jsonObject.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("ANTIMERIDIAN", result[1].jsonObject.getValue("properties").jsonObject.getValue("breakReason").jsonPrimitive.content)
    }

    @Test fun exportExaminesAtMostStorePointLimitWithoutDownsamplingAndBridging() {
        val points = (0 until TelemetryStore.MAX_POINTS + 2).map { fix(1_000L + it * 1_000L) }
        val data = TelemetryTrack(track().header, points)
        val result = Json.parseToJsonElement(TelemetryRouteExport.geoJson(data)).jsonObject
        assertEquals("true", result.getValue("inputTruncated").jsonPrimitive.content)
        val props = result.getValue("features").jsonArray.single().jsonObject.getValue("properties").jsonObject
        assertEquals(TelemetryStore.MAX_POINTS, props.getValue("time").jsonArray.size)
    }

    @Test fun pointUrlUsesOnlyCoordinatesAndRejectsInvalidFixes() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=-34.9%2C138.6", TelemetryRouteExport.googleMapsPointUrl(fix(1_000)))
        assertNull(TelemetryRouteExport.googleMapsPointUrl(fix(1_000).copy(latitude = Double.NaN)))
        assertNull(TelemetryRouteExport.googleMapsPointUrl(fix(1_000).copy(epochMs = Long.MAX_VALUE)))
        assertNull(TelemetryRouteExport.googleMapsPointUrl(fix(1_000).copy(accuracyM = 51f)))
    }

    @Test fun unknownHeaderAndEmptyTrackProduceValidEmptyDocuments() {
        for (data in listOf(track(), track(fix(1_000)).let { it.copy(header = it.header.copy(version = 999)) },
            track(fix(1_000)).let { it.copy(header = it.header.copy(startedElapsedMs = -1)) })) {
            assertTrue(features(data).isEmpty())
            assertEquals("gpx", xml(data).documentElement.localName)
        }
    }
}
