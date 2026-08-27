package com.dante.zeekrbridge.server

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRequestAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sha = "a".repeat(64)

    @Test
    fun legacyRequestGetsStableIdAndAuthenticatedOwner() {
        val body = """{"fileName":"drive.mp4","mimeType":"video/mp4","sizeBytes":3,"sha256":"$sha","carId":"spoofed"}"""
        val result = UploadRequestAdapter.decode(json, body, "paired-car")
        assertTrue(result.legacy)
        assertEquals("legacy-$sha", result.request.clientTransferId)
        assertEquals("paired-car", result.request.carId)
        assertNull(result.request.sidecarJson)
    }

    @Test
    fun openAvmRequestKeepsTaskAndSidecarButUsesAuthenticatedOwner() {
        val body = """{"clientTransferId":"task-7","fileName":"drive.mp4","sizeBytes":3,"sha256":"$sha","carId":"spoofed","sidecarJson":"{}"}"""
        val result = UploadRequestAdapter.decode(json, body, "paired-car")
        assertFalse(result.legacy)
        assertEquals("task-7", result.request.clientTransferId)
        assertEquals("paired-car", result.request.carId)
        assertEquals("{}", result.request.sidecarJson)
    }

    @Test
    fun schemaFiveRecordingSessionMetadataPassesThroughUnchanged() {
        val sidecar = """{"schemaVersion":5,"recordingSessionId":"session-a"}"""
        val body = """{"clientTransferId":"task-8","fileName":"drive.mp4","sizeBytes":3,"sha256":"$sha","carId":"spoofed","sidecarJson":"${sidecar.replace("\"", "\\\"")}"}"""

        val result = UploadRequestAdapter.decode(json, body, "paired-car")

        assertEquals(sidecar, result.request.sidecarJson)
    }
}
