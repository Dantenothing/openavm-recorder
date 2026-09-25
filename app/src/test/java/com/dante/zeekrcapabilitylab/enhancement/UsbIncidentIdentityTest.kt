package com.dante.zeekrcapabilitylab.enhancement
import com.dante.zeekrcapabilitylab.usbexport.*
import com.dante.zeekrcapabilitylab.product.RecordingQuality
import org.junit.Assert.*
import org.junit.Test
class UsbIncidentIdentityTest {
    private val manifest = UsbPortableSegmentManifest(bundleId = "a".repeat(64), contentKey = "b".repeat(64),
        sourceKind = UsbSegmentSourceKind.DIRECT_RECORDING, appVersion = "test", buildGitSha = "test", logicalId = "session:1",
        recordingSessionId = "1", segmentNumber = 3, recordingMode = "NORMAL", startedAtEpochMs = 1000, stoppedAtEpochMs = 60000, assets = emptyList())
    @Test fun markerCannotMigrateAcrossVideoOrSessionIdentities() {
        val marker = UsbIncidentMarker(bundleId = manifest.bundleId, contentKey = manifest.contentKey, recordingSessionId = "1", eventId = "event", requestedAtEpochMs = 50000)
        assertTrue(marker.matches(manifest))
        assertFalse(marker.copy(recordingSessionId = "2").matches(manifest))
        assertFalse(marker.copy(contentKey = "c".repeat(64)).matches(manifest))
        assertFalse(marker.copy(schemaVersion = 2).matches(manifest))
        assertFalse(marker.copy(role = "DELETE").matches(manifest))
        assertFalse(marker.copy(eventId = "").matches(manifest))
        assertFalse(marker.copy(requestedAtEpochMs = 0).matches(manifest))
    }
    @Test fun defaultQualityDoesNotChangeCurrentBitrates() {
        assertEquals(28000000, RecordingQuality.ORIGINAL.bitrate(28000000))
        assertEquals(14000000, RecordingQuality.ORIGINAL.bitrate(14000000))
        assertEquals(20000000, RecordingQuality.BALANCED.bitrate(28000000))
        assertEquals(14000000, RecordingQuality.ECONOMY.bitrate(28000000))
        assertEquals(7000000, RecordingQuality.ECONOMY.bitrate(14000000))
    }
}
