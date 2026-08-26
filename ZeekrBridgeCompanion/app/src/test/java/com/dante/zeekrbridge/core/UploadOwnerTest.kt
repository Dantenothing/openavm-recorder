package com.dante.zeekrbridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadOwnerTest {
    private fun session(carId: String) = UploadSession(
        uploadId = "u1",
        request = UploadCreateRequest(
            fileName = "a.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1,
            sha256 = "0".repeat(64),
            carId = carId,
        ),
        chunkSize = 4,
        totalChunks = 1,
    )

    @Test
    fun ownerMatchesOnlyExactAuthenticatedCar() {
        assertTrue(UploadOwner.isOwner(session("car-1"), "car-1"))
        assertFalse(UploadOwner.isOwner(session("car-1"), "car-2"))
        assertFalse(UploadOwner.isOwner(session("car-1"), null))
    }
}
