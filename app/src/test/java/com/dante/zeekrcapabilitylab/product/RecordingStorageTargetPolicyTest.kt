package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStorageTargetPolicyTest {

    @Test
    fun internalTargetAlwaysResolvesInternal() {
        assertEquals(
            RecordingStorageTargetPolicy.Resolution(recordingsRootPath = null, fellBackToInternal = false),
            RecordingStorageTargetPolicy.resolve(RecordingStorageTargetPolicy.TARGET_INTERNAL, "/storage/USB/x"),
        )
    }

    @Test
    fun usbTargetUsesTheDriveWhenPresent() {
        assertEquals(
            RecordingStorageTargetPolicy.Resolution(recordingsRootPath = "/storage/USB/x", fellBackToInternal = false),
            RecordingStorageTargetPolicy.resolve(RecordingStorageTargetPolicy.TARGET_USB, "/storage/USB/x"),
        )
    }

    @Test
    fun usbTargetWithoutADriveFallsBackAndSaysSo() {
        assertEquals(
            RecordingStorageTargetPolicy.Resolution(recordingsRootPath = null, fellBackToInternal = true),
            RecordingStorageTargetPolicy.resolve(RecordingStorageTargetPolicy.TARGET_USB, null),
        )
        assertEquals(
            RecordingStorageTargetPolicy.Resolution(recordingsRootPath = null, fellBackToInternal = true),
            RecordingStorageTargetPolicy.resolve(RecordingStorageTargetPolicy.TARGET_USB, " "),
        )
    }

    @Test
    fun unknownTargetsAreTreatedAsInternalWithoutFallbackNoise() {
        assertEquals(
            RecordingStorageTargetPolicy.Resolution(recordingsRootPath = null, fellBackToInternal = false),
            RecordingStorageTargetPolicy.resolve("cloud", "/storage/USB/x"),
        )
    }
}
