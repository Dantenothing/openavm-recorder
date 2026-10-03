package com.dante.zeekrbridge.player

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import io.github.dantenothing.avmtransfer.protocol.DriveRecorderReport
import io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor

/** Select aggregate diagnostics explicitly; never serialize a bundle, point, file, or session identifier. */
internal fun recordedPlaybackReport(
    appVersion: String?, route: RecordedDriveRoute?, playlistSize: Int, mediaIndex: Int, positionMs: Long?,
    attachmentStatus: DriveAttachmentStatus, frame: RecordedDriveFrame, mapDiagnostics: Map<String, String>,
    recorder: DriveRecorderReport? = null, alignment: String? = null, seekCount: Int = 0,
    renderedFrames: Int = 0, playbackErrors: Int = 0, channelCount: Int = 1,
    anchor: VideoClockAnchor? = null,
    recorderFromCurrentPosition: Boolean = true,
    quantizedBoundaries: Int = 0,
): String = buildJsonObject {
    put("format", "OPENAVM_COMBINED_TEST_REPORT")
    put("schemaVersion", 2)
    put("appVersion", appVersion?.take(80))
    put("alignment", alignment?.takeIf { it in setOf("SEGMENT_ELAPSED_APPROX_V1", "VIDEO_CLOCK_ANCHOR_V2") } ?: "NOT_AVAILABLE")
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
    put("verifiedCurrentAttachment", attachmentStatus == DriveAttachmentStatus.AVAILABLE)
    put("transferIntegrity", if (attachmentStatus == DriveAttachmentStatus.AVAILABLE) "HASH_AND_SIDECAR_BINDING_VERIFIED" else "NOT_VERIFIED_FOR_CURRENT_POSITION")
    put("seekRequests", seekCount.coerceAtLeast(0))
    put("renderedFirstFrameCallbacks", renderedFrames.coerceAtLeast(0))
    put("playbackErrors", playbackErrors.coerceAtLeast(0))
    put("playbackChannels", channelCount.coerceIn(0, 3))
    put("hardwareAcceptance", "NOT_ASSERTED")
    val checkedAnchor = anchor?.takeIf { it.valid() }
    put("videoClockSource", checkedAnchor?.source ?: "SEGMENT_START_APPROXIMATE_OR_UNAVAILABLE")
    put("videoClockUncertaintyMs", checkedAnchor?.uncertaintyMs)
    put("timingUncertaintyKnown", checkedAnchor?.uncertaintyMs != null)
    put("sensorTiming", "GETTER_OBSERVATION_NOT_ECU_MEASUREMENT")
    put("quantizedBoundaries", quantizedBoundaries.coerceIn(0, 2_000))
    put("boundaryAdjustment", if (quantizedBoundaries > 0) "INTEGER_MILLISECOND_QUANTIZATION" else "NONE")
    val checkedRecorder = recorder?.takeIf { it.valid() }
    put("recorderSnapshotAvailable", checkedRecorder != null)
    put("recorderSnapshotScope", checkedRecorder?.snapshotScope ?: "ABSENT")
    put("recorderSnapshotSource", if (checkedRecorder == null) "ABSENT" else if (recorderFromCurrentPosition)
        "CURRENT_VERIFIED_ATTACHMENT" else "VERIFIED_SESSION_ATTACHMENT")
    if (checkedRecorder != null) put("recorder", Json.encodeToJsonElement(checkedRecorder))
    put("map", buildJsonObject {
        setOf("module", "provider", "renderer", "sdkVersion", "mode", "loadOutcome", "loadCount", "failureCount", "errorCategory")
            .forEach { key -> mapDiagnostics[key]?.let { put(key, it.take(96)) } }
    })
}.toString()
