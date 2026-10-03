package com.dante.zeekrbridge.player

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Select aggregate diagnostics explicitly; never serialize a bundle, point, file, or session identifier. */
internal fun recordedPlaybackReport(
    appVersion: String?, route: RecordedDriveRoute?, playlistSize: Int, mediaIndex: Int, positionMs: Long?,
    attachmentStatus: DriveAttachmentStatus, frame: RecordedDriveFrame, mapDiagnostics: Map<String, String>,
): String = buildJsonObject {
    put("format", "OPENAVM_PHONE_PLAYBACK_REPORT")
    put("schemaVersion", 1)
    put("appVersion", appVersion?.take(80))
    put("alignment", "SEGMENT_ELAPSED_APPROX_V1")
    put("coordinatesIncluded", false)
    put("filePathsIncluded", false)
    put("sessionIdsIncluded", false)
    put("playlistSegments", playlistSize)
    put("mappedSegments", route?.mappedSegments)
    put("mappedPoints", route?.points?.size)
    put("recordedGpsSamples", route?.gpsSampleCount)
    put("recordedVehicleSamples", route?.vehicleSampleCount)
    put("missingSegments", route?.missingSegments)
    put("timeLapseSegments", route?.unsupportedSegments)
    put("limitedSegments", route?.limitedSegments)
    put("timingIncomplete", route?.timingIncomplete)
    put("routeSimplified", route?.simplified)
    put("currentSegmentIndex", mediaIndex)
    put("currentPositionMs", positionMs?.takeIf { it >= 0L })
    put("currentAttachmentStatus", attachmentStatus.name)
    put("currentGpsReadingAvailable", frame.gps != null)
    put("currentVehicleReadingAvailable", frame.vehicle != null)
    put("map", buildJsonObject {
        setOf("module", "provider", "renderer", "sdkVersion", "mode", "loadOutcome", "loadCount", "failureCount", "errorCategory")
            .forEach { key -> mapDiagnostics[key]?.let { put(key, it.take(96)) } }
    })
}.toString()
