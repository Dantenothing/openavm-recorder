package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.service.recorder.*
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Append-only annotation: existing video, sidecar and content hashes are never rewritten. */
@Serializable
data class UsbIncidentMarker(
    val schemaVersion: Int = 1,
    val kind: String = "OPENAVM_INCIDENT",
    val bundleId: String,
    val contentKey: String,
    val recordingSessionId: String,
    val eventId: String,
    val requestedAtEpochMs: Long,
    val role: String = IncidentProtectionStore.ROLE_PREVIOUS,
) {
    fun matches(manifest: UsbPortableSegmentManifest): Boolean = schemaVersion == 1 && kind == "OPENAVM_INCIDENT" &&
        bundleId == manifest.bundleId && contentKey == manifest.contentKey && recordingSessionId == manifest.recordingSessionId &&
        bundleId.matches(Regex("[a-f0-9]{64}")) && eventId.length in 1..120 && requestedAtEpochMs > 0 &&
        role in setOf(IncidentProtectionStore.ROLE_PREVIOUS, IncidentProtectionStore.ROLE_CURRENT, IncidentProtectionStore.ROLE_NEXT)
    fun tag() = IncidentTag(eventId, requestedAtEpochMs, role)
}

data class UsbIncidentProtection(val protected: Boolean, val marker: UsbIncidentMarker?)
data class UsbIncidentSave(val protectedCount: Int, val portableCount: Int)

/** Local durable intent protects against quota cleanup even if USB is removed during marker publication. */
class UsbIncidentMarkers(context: Context) {
    private val context = context.applicationContext
    private val backend = UsbMediaStoreBackend(this.context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun name(id: String) = "OpenAVM_${id}.incident.json"
    private fun local(target: UsbExportTarget, id: String): AtomicFile {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val volume = UsbExportPolicy.sha256(target.storageUuid.lowercase().toByteArray())
        return AtomicFile(File(context.filesDir, "recordings/usb-incidents/${volume}_$id.json"))
    }
    fun read(target: UsbExportTarget, manifest: UsbPortableSegmentManifest,
        byName: Map<String, UsbMediaStoreBackend.Metadata>): UsbIncidentProtection {
        val stored = local(target, manifest.bundleId)
        val metadata = byName[name(manifest.bundleId)]
        if (!stored.baseFile.exists() && metadata == null) return UsbIncidentProtection(false, null)
        val localMarker = runCatching { stored.openRead().use { input ->
            require(input.channel.size() <= MAX_BYTES)
            json.decodeFromString<UsbIncidentMarker>(input.readBytes().toString(Charsets.UTF_8))
        } }.getOrNull()?.takeIf { it.matches(manifest) }
        val portable = metadata?.takeIf { it.pending == 0 && it.mimeType == "application/json" &&
            it.volumeName.equals(target.volumeName, true) && (it.sizeBytes ?: Long.MAX_VALUE) <= MAX_BYTES }?.let {
            runCatching { json.decodeFromString<UsbIncidentMarker>(backend.readBytes(it.uri, MAX_BYTES).toString(Charsets.UTF_8)) }
                .getOrNull()?.takeIf { marker -> marker.matches(manifest) }
        }
        // A damaged annotation never grants deletion permission. Unknown metadata is not an event tag.
        return UsbIncidentProtection(true, portable ?: localMarker)
    }
    fun protectPrevious(target: UsbExportTarget, sessionId: String, currentSegment: Int,
        eventId: String, requestedAt: Long, count: Int = 2): UsbIncidentSave = UsbMutationCoordinator.withTarget(target.storageUuid) {
        val mounted = UsbExportVolumeResolver.resolveExact(context, target) ?: error("USB_NOT_MOUNTED")
        val bundles = UsbSegmentCatalog(context, backend).snapshot(mounted).segments
        val selected = IncidentSelection.previous(bundles.map {
            IncidentCandidate(it.manifestData.bundleId, it.manifestData.recordingSessionId, it.manifestData.segmentNumber, it.incident?.eventId)
        }, sessionId, currentSegment, eventId, count)
        var saved = 0; var portable = 0
        bundles.filter { it.manifestData.bundleId in selected }.forEach { bundle ->
            val manifest = bundle.manifestData
            val original = runCatching { json.decodeFromString<SegmentSidecar>(backend.readBytes(bundle.sidecar.uri).toString(Charsets.UTF_8)) }.getOrNull()
            if (!original?.eventId.isNullOrBlank() && original?.eventId != eventId) return@forEach
            val marker = UsbIncidentMarker(bundleId = manifest.bundleId, contentKey = manifest.contentKey,
                recordingSessionId = sessionId, eventId = eventId, requestedAtEpochMs = requestedAt)
            val bytes = json.encodeToString(marker).toByteArray(Charsets.UTF_8)
            val file = local(mounted, marker.bundleId)
            file.baseFile.parentFile?.mkdirs()
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) } catch (t: Throwable) { file.failWrite(out); throw t }
            saved++
            runCatching { publish(mounted, manifest, marker); portable++ }
        }
        UsbIncidentSave(saved, portable)
    }
    private fun publish(target: UsbExportTarget, manifest: UsbPortableSegmentManifest, marker: UsbIncidentMarker) {
        require(marker.matches(manifest))
        val bytes = json.encodeToString(marker).toByteArray(Charsets.UTF_8)
        val existing = backend.findPublished(target, name(marker.bundleId))
        if (existing != null) {
            val saved = json.decodeFromString<UsbIncidentMarker>(backend.readBytes(existing.uri, MAX_BYTES).toString(Charsets.UTF_8))
            require(saved.matches(manifest) && saved == marker) { "INCIDENT_CONFLICT" }
            return
        }
        val item = backend.findOwnedPending(target, name(marker.bundleId)) ?: backend.insertPending(target, name(marker.bundleId), "application/json")
        require(item.displayName == name(marker.bundleId) && item.volumeName.equals(target.volumeName, true)) { "INCIDENT_NAME_CONFLICT" }
        backend.replaceOwnedPendingBytes(item.uri, bytes)
        require(backend.hashUri(item.uri) == (bytes.size.toLong() to UsbExportPolicy.sha256(bytes))) { "INCIDENT_VERIFY_FAILED" }
        require(backend.publish(item.uri) > 0) { "INCIDENT_PUBLISH_FAILED" }
    }
    /** Reconnect recovery; the catalog itself remains read-only. Caller excludes active auxiliary camera work. */
    fun synchronizePending(target: UsbExportTarget) = UsbMutationCoordinator.withTarget(target.storageUuid) {
        UsbSegmentCatalog(context, backend).snapshot(target).segments.forEach { bundle ->
            if (!local(target, bundle.manifestData.bundleId).baseFile.exists()) return@forEach
            val marker = runCatching { json.decodeFromString<UsbIncidentMarker>(local(target, bundle.manifestData.bundleId).openRead().use { stream ->
                require(stream.channel.size() <= MAX_BYTES); stream.readBytes().toString(Charsets.UTF_8)
            }) }.getOrNull() ?: return@forEach
            runCatching { publish(target, bundle.manifestData, marker) }
        }
    }
    fun removeAfterVideoDeletion(target: UsbExportTarget, bundle: UsbOwnedSegmentBundle) {
        if (backend.metadata(bundle.video.uri) != null) return
        val manifest = bundle.manifestData
        val item = backend.findPublished(target, name(manifest.bundleId))
        if (item != null && item.ownerPackage == context.packageName) {
            val marker = runCatching { json.decodeFromString<UsbIncidentMarker>(backend.readBytes(item.uri, MAX_BYTES).toString(Charsets.UTF_8)) }.getOrNull()
            if (marker?.matches(manifest) == true) backend.deleteOwned(target, UsbExportAsset(
                kind = UsbExportAssetKind.SIDECAR, requestedName = name(manifest.bundleId), expectedBytes = item.sizeBytes ?: 0,
                expectedSha256 = "", expectedMimeType = "application/json", itemUri = item.uri.toString(), published = true))
        }
        local(target, manifest.bundleId).delete()
    }
    fun enrich(sidecar: SegmentSidecar, incident: IncidentTag?): SegmentSidecar = incident?.let {
        sidecar.copy(protected = true, eventId = it.eventId, eventRequestedAtEpochMs = it.requestedAtEpochMs, eventRole = it.role)
    } ?: sidecar
    fun sidecarBytes(bundle: UsbOwnedSegmentBundle): ByteArray {
        val original = backend.readBytes(bundle.sidecar.uri)
        val enriched = enrich(json.decodeFromString<SegmentSidecar>(original.toString(Charsets.UTF_8)), bundle.incident)
        val markers = com.dante.zeekrcapabilitylab.player.ManualBookmarkTimeline.from(enriched)
        if (bundle.incident == null && markers.isEmpty()) return original
        return json.encodeToString(enriched.copy(triggerMarkers = (enriched.triggerMarkers + markers).distinct())) .toByteArray(Charsets.UTF_8)
    }
    companion object { const val MAX_BYTES = 8192 }
}
