package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DriveDataTest {
    private val session = "00000000-0000-4000-8000-000000000001"
    private val epoch = 1_791_000_000_000L
    private val json = Json { encodeDefaults = true }
    private fun gps(at: Long = 1_000) = DriveGpsSample(at, epoch + at, -34.9, 138.6, 4f, "gps")
    private fun vehicle(at: Long = 1_000) = DriveVehicleSample(at, epoch + at, mapOf(
        "speed" to DriveVehicleValue(at, number = 0.0), "gear" to DriveVehicleValue(at, text = "P"),
    ))
    private fun bundle(gps: List<DriveGpsSample> = listOf(gps()), vehicle: List<DriveVehicleSample> = listOf(vehicle())) =
        DriveDataBundle(recordingSessionId = session, segmentStartedElapsedMs = 1_000, segmentEndedElapsedMs = 20_000,
            gps = gps, vehicle = vehicle)
    private fun sidecar(start: Long = 1_000, end: Long = 20_000, mode: String = "NORMAL", multiplier: String = "1") =
        """{"recordingSessionId":"$session","startedAtElapsedRealtimeMs":$start,"stoppedAtElapsedRealtimeMs":$end,"recordingMode":"$mode","timeLapseMultiplier":$multiplier}"""
    private fun invalid(data: DriveDataBundle) {
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(data) }
        // The decoder must independently validate callers which bypass encode().
        runCatching { json.encodeToString(data).toByteArray() }.getOrNull()?.let { assertNull(DriveDataCodec.decode(it)) }
    }

    @Test fun roundTripPreservesBothIndependentSourcesAndApproximateClockContract() {
        val data = bundle(listOf(gps().copy(speedMps = 2f)), listOf(vehicle().copy(values = mapOf(
            "speed" to DriveVehicleValue(1_000, number = 9.0), "brake_pedal" to DriveVehicleValue(1_000, number = 0.0),
        ))))
        val encoded = DriveDataCodec.encode(data)
        assertEquals(data, DriveDataCodec.decode(encoded))
        val descriptor = DriveDataCodec.descriptor(encoded)!!
        assertEquals(encoded.size.toLong(), descriptor.sizeBytes)
        assertEquals(64, descriptor.sha256.length)
        assertEquals(DriveDataCodec.ALIGNMENT, descriptor.alignment)
        assertTrue(DriveDataCodec.validDescriptor(descriptor))
        assertEquals(data, DriveDataCodec.verify(encoded, descriptor, sidecar()))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("readElapsedMs"))
    }

    @Test fun zeroAndMissingSpeedAndUnknownControlsRemainDistinct() {
        val data = bundle(listOf(gps().copy(speedMps = 0f), gps(2_000)), listOf(vehicle().copy(values = mapOf(
            "speed" to DriveVehicleValue(1_000, number = 0.0),
            "turn_left" to DriveVehicleValue(1_000, number = 0.0),
            "brake_pedal" to DriveVehicleValue(1_000, status = "UNAVAILABLE"),
        ))))
        val decoded = DriveDataCodec.decode(DriveDataCodec.encode(data))!!
        assertEquals(0f, decoded.gps[0].speedMps!!, 0f)
        assertNull(decoded.gps[1].speedMps)
        assertEquals(0.0, decoded.vehicle.single().values.getValue("turn_left").number!!, 0.0)
        assertNull(decoded.vehicle.single().values.getValue("brake_pedal").number)
    }

    @Test fun eachSourceIsStrictlyMonotonicAndSegmentWindowIsHalfOpen() {
        invalid(bundle(listOf(gps(2_000), gps(1_000))))
        invalid(bundle(listOf(gps(), gps())))
        invalid(bundle(listOf(gps(999))))
        invalid(bundle(listOf(gps(20_000))))
        invalid(bundle(vehicle = listOf(vehicle(2_000), vehicle(1_000))))
        invalid(bundle(vehicle = listOf(vehicle(), vehicle())))
        invalid(bundle(vehicle = listOf(vehicle(20_000))))
        val accepted = bundle(listOf(gps(1_000), gps(19_999)), listOf(vehicle(1_000), vehicle(19_999)))
        assertNotNull(DriveDataCodec.decode(DriveDataCodec.encode(accepted)))
    }

    @Test fun onlyAllowlistedFiniteGpsValuesAndReasonableTimesAreAccepted() {
        val bad = listOf(gps().copy(latitude = Double.NaN), gps().copy(longitude = Double.POSITIVE_INFINITY),
            gps().copy(latitude = 90.1), gps().copy(longitude = -180.1), gps().copy(accuracyM = 51f),
            gps().copy(accuracyM = -1f), gps().copy(provider = "unknown"), gps().copy(speedMps = -1f),
            gps().copy(speedMps = 101f), gps().copy(speedMps = Float.NaN), gps().copy(speedAccuracyMps = 3.1f),
            gps().copy(epochMs = 0), gps().copy(epochMs = Long.MAX_VALUE))
        bad.forEach { invalid(bundle(listOf(it))) }
    }

    @Test fun readTimestampsMustBeInsideWindowNotInFutureOrStaleForAnOkValue() {
        invalid(bundle(vehicle = listOf(vehicle().copy(values = mapOf("speed" to DriveVehicleValue(999, number = 0.0))))))
        invalid(bundle(vehicle = listOf(vehicle().copy(values = mapOf("speed" to DriveVehicleValue(1_001, number = 0.0))))))
        invalid(bundle(vehicle = listOf(vehicle(4_000).copy(values = mapOf("speed" to DriveVehicleValue(1_000, number = 0.0))))))
        assertNotNull(DriveDataCodec.decode(DriveDataCodec.encode(bundle(vehicle = listOf(
            vehicle(4_000).copy(values = mapOf("speed" to DriveVehicleValue(1_000, status = "STALE"))),
        )))))
    }

    @Test fun vehicleDataAllowsOnlyKnownEnumsRangesAndRedactedStatuses() {
        val bad = listOf(
            "vin" to DriveVehicleValue(1_000, text = "not allowed"),
            "speed" to DriveVehicleValue(1_000, number = 361.0),
            "speed" to DriveVehicleValue(1_000, number = Double.NaN),
            "gear" to DriveVehicleValue(1_000, text = "DRIVE"),
            "indicator" to DriveVehicleValue(1_000, text = "unknown"),
            "turn_left" to DriveVehicleValue(1_000, number = 2.0),
            "brake_pedal" to DriveVehicleValue(1_000, number = 1.0, status = "UNAVAILABLE"),
            "stop_lamp" to DriveVehicleValue(1_000, status = "READ_FAILED:private detail"),
        )
        bad.forEach { invalid(bundle(vehicle = listOf(vehicle().copy(values = mapOf(it))))) }
        invalid(bundle(vehicle = listOf(vehicle().copy(epochMs = Long.MAX_VALUE))))
    }

    @Test fun unsupportedVersionAlignmentAndSessionAreRejected() {
        invalid(bundle().copy(schemaVersion = 3))
        invalid(bundle().copy(alignment = "EXACT_ECU_TIME"))
        invalid(bundle().copy(recordingSessionId = "../file"))
        invalid(bundle().copy(segmentStartedElapsedMs = -1))
        invalid(bundle().copy(segmentEndedElapsedMs = 1_000))
    }

    @Test fun descriptorAndPayloadMustMatchSameSessionWindowAndSchema() {
        val data = bundle()
        val bytes = DriveDataCodec.encode(data)
        val descriptor = DriveDataCodec.descriptor(bytes)!!
        assertFalse(DriveDataCodec.matches(data, descriptor.copy(recordingSessionId = "00000000-0000-4000-8000-000000000002"), sidecar()))
        assertFalse(DriveDataCodec.matches(data, descriptor.copy(segmentStartedElapsedMs = 999), sidecar()))
        assertFalse(DriveDataCodec.matches(data, descriptor.copy(segmentEndedElapsedMs = 20_001), sidecar()))
        assertFalse(DriveDataCodec.validDescriptor(descriptor.copy(schemaVersion = 3)))
        assertFalse(DriveDataCodec.validDescriptor(descriptor.copy(sizeBytes = 0)))
        assertFalse(DriveDataCodec.validDescriptor(descriptor.copy(sizeBytes = DriveDataCodec.MAX_BYTES + 1L)))
        assertFalse(DriveDataCodec.validDescriptor(descriptor.copy(sha256 = descriptor.sha256.uppercase())))
        assertFalse(DriveDataCodec.validDescriptor(descriptor.copy(sha256 = "00")))
    }

    @Test fun verificationChecksOriginalBytesShaAndLengthBeforeTrustingPayload() {
        val bytes = DriveDataCodec.encode(bundle())
        val descriptor = DriveDataCodec.descriptor(bytes)!!
        assertNull(DriveDataCodec.verify(bytes, descriptor.copy(sha256 = "0".repeat(64)), sidecar()))
        assertNull(DriveDataCodec.verify(bytes + ' '.code.toByte(), descriptor, sidecar()))
        assertNull(DriveDataCodec.verify(bytes.copyOf().also { it[it.lastIndex] = ']'.code.toByte() }, descriptor, sidecar()))
    }

    @Test fun sidecarMustBindExactElapsedWindowAndNormalRecordingWithoutClockGuessing() {
        val data = bundle()
        val descriptor = DriveDataCodec.descriptor(DriveDataCodec.encode(data))!!
        for (bad in listOf(null, "{}", sidecar(start = 999), sidecar(end = 20_001), sidecar(mode = "TIME_LAPSE"),
            sidecar(multiplier = "2"), sidecar(multiplier = "null"), sidecar(multiplier = "\"1\""),
            sidecar().replace(session, "00000000-0000-4000-8000-000000000002"),
            sidecar().replace("startedAtElapsedRealtimeMs", "startedAtEpochMs"))) {
            assertFalse("Invalid sidecar was accepted", DriveDataCodec.matches(data, descriptor, bad))
        }
        assertTrue(DriveDataCodec.matches(data, descriptor, sidecar().replace(",\"timeLapseMultiplier\":1", "")))
        assertFalse(DriveDataCodec.matches(data, descriptor, " ".repeat(TransferProtocol.MAX_SIDECAR_BYTES) + sidecar()))
    }

    @Test fun duplicateAndEscapedDuplicateKeysAreRejectedAtAnyDepth() {
        val bytes = DriveDataCodec.encode(bundle())
        val text = bytes.toString(Charsets.UTF_8)
        assertNull(DriveDataCodec.decode(text.replaceFirst("{", "{\"schemaVersion\":1,\"schemaVersion\":1,").toByteArray()))
        assertNull(DriveDataCodec.decode(text.replace("\"values\":{", "\"values\":{\"speed\":{},").toByteArray()))
        assertNull(DriveDataCodec.decode(text.replaceFirst("{", "{\"schemaVersion\":1,\"schema\\u0056ersion\":1,").toByteArray()))
        val descriptor = DriveDataCodec.descriptor(bytes)!!
        assertFalse(DriveDataCodec.matches(bundle(), descriptor, sidecar().replaceFirst("{", "{\"recordingMode\":\"NORMAL\",")))
    }

    @Test fun malformedUtf8UnknownFieldsDeepDocumentsAndOversizeBytesAreRejected() {
        assertNull(DriveDataCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(DriveDataCodec.decode(ByteArray(DriveDataCodec.MAX_BYTES + 1)))
        assertNull(DriveDataCodec.decode(byteArrayOf()))
        assertNull(DriveDataCodec.decode(("[".repeat(32) + "0" + "]".repeat(32)).toByteArray()))
        val text = DriveDataCodec.encode(bundle()).toString(Charsets.UTF_8)
        assertNull(DriveDataCodec.decode(text.replaceFirst("{", "{\"token\":\"not accepted\",").toByteArray()))
        assertNull(DriveDataCodec.decode(text.replace("\"elapsedMs\":1000", "\"elapsedMs\":\"1000\"").toByteArray()))
        assertNull(DriveDataCodec.decode((text + "{}").toByteArray()))
    }

    @Test fun listsAndSerializedBytesAreIndependentlyBounded() {
        invalid(bundle().copy(segmentEndedElapsedMs = 50_000,
            gps = List(DriveDataCodec.MAX_SAMPLES_PER_SOURCE + 1) { gps(1_000L + it) }))
        val big = bundle(gps = emptyList(), vehicle = List(DriveDataCodec.MAX_SAMPLES_PER_SOURCE) { index ->
            val at = 1_000L + index
            DriveVehicleSample(at, epoch + at, mapOf(
                "speed" to DriveVehicleValue(at, number = 10.0), "gear" to DriveVehicleValue(at, text = "D"),
                "indicator" to DriveVehicleValue(at, text = "left"), "turn_left" to DriveVehicleValue(at, number = 1.0),
                "turn_right" to DriveVehicleValue(at, number = 0.0), "brake_pedal" to DriveVehicleValue(at, number = 0.0),
                "stop_lamp" to DriveVehicleValue(at, number = 0.0),
            ))
        }).copy(segmentEndedElapsedMs = 50_000)
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(big) }
    }

    @Test fun gpsPlaybackUsesOnlyEarlierFreshRecordedFixAndRespectsGaps() {
        val points = listOf(gps(1_000), gps(2_000).copy(speedMps = 0f), gps(3_000).copy(breakBefore = true))
        assertEquals(1_000L, DriveDataPlayback.gpsAt(points, 1_500)!!.elapsedMs)
        assertEquals(0f, DriveDataPlayback.gpsAt(points, 2_000)!!.speedMps!!, 0f)
        assertNull(DriveDataPlayback.gpsAt(points, 2_500))
        assertEquals(3_000L, DriveDataPlayback.gpsAt(points, 3_000)!!.elapsedMs)
        assertNull(DriveDataPlayback.gpsAt(points, 5_001))
        assertNull(DriveDataPlayback.gpsAt(points, 999))
        assertNull(DriveDataPlayback.gpsAt(points, null))
        assertNull(DriveDataPlayback.gpsAt(listOf(gps(), gps(2_000).copy(provider = "network")), 1_500))
        assertNull(DriveDataPlayback.gpsAt(listOf(gps(), gps(10_000)), 1_500))
    }

    @Test fun vehiclePlaybackDoesNotReuseDataAcrossGapAndExpiresEachGetterSeparately() {
        val old = vehicle(2_000).copy(values = mapOf("speed" to DriveVehicleValue(1_000, number = 20.0),
            "gear" to DriveVehicleValue(2_000, text = "D")))
        val read = DriveDataPlayback.vehicleAt(listOf(old), 3_001)!!
        assertEquals("STALE", read.values.getValue("speed").status)
        assertNull(read.values.getValue("speed").number)
        assertEquals("D", read.values.getValue("gear").text)
        assertNull(DriveDataPlayback.vehicleAt(listOf(old, vehicle(3_000).copy(breakBefore = true)), 2_500))
        assertNotNull(DriveDataPlayback.vehicleAt(listOf(old, vehicle(3_000).copy(breakBefore = true)), 2_000))
        val stop = vehicle(2_100).copy(values = emptyMap(), breakBefore = true)
        assertTrue(DriveDataPlayback.vehicleAt(listOf(old, stop), 2_200)!!.values.isEmpty())
        assertNull(DriveDataPlayback.vehicleAt(listOf(old), 4_001))
    }

    @Test fun oldProtocolDocumentsDecodeWithDriveDataAbsent() {
        val health = Json.decodeFromString<HealthResponse>("""{"deviceName":"Phone","phoneDeviceId":"phone-id"}""")
        assertTrue(health.driveDataFormats.isEmpty())
        val request = Json.decodeFromString<UploadCreateRequest>("""{"clientTransferId":"upload-id","fileName":"video.mp4","sizeBytes":100,"sha256":"${"0".repeat(64)}","carId":"car-id"}""")
        assertNull(request.driveData)
        val status = Json.decodeFromString<UploadStatusResponse>("""{"uploadId":"upload-id","totalChunks":1,"status":"UPLOADING","chunkSize":100,"sha256":"${"0".repeat(64)}"}""")
        assertFalse(status.driveDataReceived)
    }

    @Test fun emptyDataAndMissingSourceAreValidButNeverInventSamples() {
        val data = bundle(emptyList(), emptyList())
        assertEquals(data, DriveDataCodec.decode(DriveDataCodec.encode(data)))
        assertNull(DriveDataPlayback.gpsAt(data.gps, 1_000))
        assertNull(DriveDataPlayback.vehicleAt(data.vehicle, 1_000))
        assertNotNull(DriveDataCodec.decode(DriveDataCodec.encode(bundle(vehicle = emptyList()))))
    }
}
