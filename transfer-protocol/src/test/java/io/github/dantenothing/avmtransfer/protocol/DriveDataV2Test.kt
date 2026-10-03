package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DriveDataV2Test {
    private val session = "00000000-0000-4000-8000-000000000001"
    private val anchor = VideoClockAnchor(elapsedRealtimeNs = 1_000_000_000, source = "CALLBACK_OBSERVED")
    private fun bundle() = DriveDataBundle(schemaVersion = 2, recordingSessionId = session,
        segmentStartedElapsedMs = 1_000, segmentEndedElapsedMs = 5_000,
        alignment = DriveDataCodec.ANCHORED_ALIGNMENT, videoClockAnchor = anchor,
        recorderReport = DriveRecorderReport(appVersion = "5.1.0-drive4", sessionCorrelation = DriveRecorderReport.correlation(session)),
        vehicle = listOf(DriveVehicleSample(1_000, 1_790_000_000_000, mapOf(
            "throttle_depth" to DriveVehicleValue(1_000, number = 12.0, status = "PROVISIONAL_RAW")))))

    @Test fun rawPedalAndClockRoundTripWithoutPretendingPercentage() {
        val data = bundle()
        val bytes = DriveDataCodec.encode(data)
        assertEquals(data, DriveDataCodec.decode(bytes))
        assertEquals(2, DriveDataCodec.descriptor(bytes)!!.schemaVersion)
        assertEquals("drive-data-v2", DriveDataCodec.capability(2))
        assertEquals("PROVISIONAL_RAW", DriveDataPlayback.vehicleAt(data.vehicle, 1_500)!!.values["throttle_depth"]!!.status)
        assertNull(DriveDataPlayback.vehicleAt(data.vehicle, 4_000))
    }

    @Test fun v1DoesNotAccidentallyAcquireNewFieldsAndUnknownVersionsStayRejected() {
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(bundle().copy(schemaVersion = 1)) }
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(bundle().copy(schemaVersion = 3)) }
    }

    @Test fun rawNeverAcceptedAsVerifiedPercentOrWithUnboundedValue() {
        listOf(DriveVehicleValue(1_000, number = 12.0), DriveVehicleValue(1_000, number = 1e9, status = "PROVISIONAL_RAW"),
            DriveVehicleValue(1_000, text = "12%", status = "PROVISIONAL_RAW")).forEach { field ->
            val data = bundle().copy(vehicle = listOf(bundle().vehicle.single().copy(values = mapOf("throttle_depth" to field))))
            assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(data) }
        }
    }

    @Test fun aggregateReportRejectsUnrelatedSessionAndFreeformPrivateDetails() {
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(bundle().copy(recorderReport = bundle().recorderReport!!.copy(sessionCorrelation = "a".repeat(16)))) }
        assertFalse(bundle().recorderReport!!.copy(cleanupOutcome = "C:/private/user/address").valid())
        val raw = Json.encodeToString(bundle()).replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"password\":\"private\"")
        assertNull(DriveDataCodec.decode(raw.toByteArray()))
        assertFalse(bundle().recorderReport!!.copy(appVersion = "private token value").valid())
    }

    @Test fun pedalReportPreservesRawBoundsButRejectsForeignKeysOrFabricatedPercentages() {
        val pedal = DrivePedalReport("throttle_depth", 4, 3, -1.0, 700.0)
        val data = bundle().copy(recorderReport = bundle().recorderReport!!.copy(pedals = listOf(pedal)))
        assertEquals(data, DriveDataCodec.decode(DriveDataCodec.encode(data)))
        assertFalse(pedal.copy(key = "private_identifier").valid())
        assertFalse(pedal.copy(scale = "PERCENT").valid())
        assertFalse(pedal.copy(rawAvailable = 0).valid())
        assertFalse(pedal.copy(rawMin = 800.0).valid())
        val quoted = DriveDataCodec.encode(data).toString(Charsets.UTF_8).replace("\"rawAvailable\":3", "\"rawAvailable\":\"3\"")
        assertNull(DriveDataCodec.decode(quoted.toByteArray()))
    }

    @Test fun anchorMustMatchSameSidecarAndNotGetRelabelledApproximate() {
        val data = bundle()
        val bytes = DriveDataCodec.encode(data)
        val descriptor = DriveDataCodec.descriptor(bytes)!!
        val prefix = """{"recordingSessionId":"$session","recordingMode":"NORMAL","startedAtElapsedRealtimeMs":1000,"stoppedAtElapsedRealtimeMs":5000"""
        assertNull(DriveDataCodec.verify(bytes, descriptor, "$prefix}"))
        assertEquals(data, DriveDataCodec.verify(bytes, descriptor, "$prefix,\"videoClockAnchor\":${Json.encodeToString(anchor)}}"))
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(data.copy(alignment = DriveDataCodec.ALIGNMENT)) }
        assertThrows(IllegalArgumentException::class.java) { DriveDataCodec.encode(data.copy(videoClockAnchor = null)) }
    }
}
