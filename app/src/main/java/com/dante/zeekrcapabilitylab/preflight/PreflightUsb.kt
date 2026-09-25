package com.dante.zeekrcapabilitylab.preflight

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import kotlinx.serialization.json.*

internal object PreflightFiles {
    /** Closed, app-owned files only. Container sample inspection is not a decoder test. */
    fun inspect(context: Context, uri: Uri, bytes: Long, cancelled: () -> Boolean): JsonObject {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri,"r").use { pfd ->
                requireNotNull(pfd); extractor.setDataSource(pfd.fileDescriptor)
                val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: error("NO_VIDEO_TRACK")
                val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
                fun integer(key: String): Int? = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
                var first: Long? = null; var last: Long? = null; var count = 0; var backwards = 0; var previousKey: Long? = null
                val keyGaps = mutableListOf<Long>(); val deadline = SystemClock.elapsedRealtime()+5_000
                while (count < 12_000 && SystemClock.elapsedRealtime() < deadline && !cancelled()) {
                    val pts = extractor.sampleTime; if (pts < 0) break
                    if (last != null && pts < last) backwards++
                    if (first == null) first = pts
                    last = pts; count++
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        previousKey?.let { if (keyGaps.size < 32) keyGaps += pts-it }; previousKey = pts
                    }
                    if (!extractor.advance()) break
                }
                val complete = extractor.sampleTime < 0
                val duration = if (first != null && last != null) last!!-first!! else null
                check(count > 1 && duration != null && duration > 0) { "INSUFFICIENT_VIDEO_SAMPLES" }
                return obj("status" to if (complete) "PASS" else "INCOMPLETE", "evidenceLevel" to "ENCODED", "decode" to "NOT_IMPLEMENTED",
                    "actualMime" to format.getString(MediaFormat.KEY_MIME), "width" to integer(MediaFormat.KEY_WIDTH), "height" to integer(MediaFormat.KEY_HEIGHT),
                    "rotation" to integer(MediaFormat.KEY_ROTATION),"crop" to listOf("crop-left","crop-top","crop-right","crop-bottom").associateWith(::integer),
                    "profile" to integer(MediaFormat.KEY_PROFILE),"level" to integer(MediaFormat.KEY_LEVEL),
                    "colorStandard" to integer(MediaFormat.KEY_COLOR_STANDARD),"colorRange" to integer(MediaFormat.KEY_COLOR_RANGE),
                    "colorTransfer" to integer(MediaFormat.KEY_COLOR_TRANSFER),
                    "trackDurationUs" to if(format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null,
                    "samples" to count,"completeSampleScan" to complete,"firstPtsUs" to first.toString(),"lastPtsUs" to last.toString(),
                    "nonMonotonicPts" to backwards,"keyFrameIntervalsUs" to keyGaps.map { it.toString() },
                    "encodedFps" to ((count-1)*1_000_000.0/duration),"fpsMethod" to "(sampleCount-1)*1e6/(lastPts-firstPts)",
                    "fileBytes" to bytes,"averageContainerBitrateBps" to if (complete) bytes*8_000_000.0/duration else null,
                    "codecComponent" to null,"codecComponentReason" to "MEDIAR​ECORDER_DOES_NOT_EXPOSE_COMPONENT".replace("​",""),
                    "crossFileContinuity" to "UNAVAILABLE_NO_GLOBAL_PTS_MAPPING")
            }
        } finally {
            try { extractor.release() } catch (_: Throwable) {
                synchronized(PreflightRuntime.retained) { PreflightRuntime.retained+=extractor }
                throw PreflightCleanupUnconfirmed()
            }
        }
    }
    fun asset(metadata: UsbMediaStoreBackend.Metadata, kind: UsbExportAssetKind = UsbExportAssetKind.VIDEO): UsbExportAsset = UsbExportAsset(
        kind=kind, requestedName=requireNotNull(metadata.displayName),expectedBytes=metadata.sizeBytes ?: 0,
        expectedSha256="",expectedMimeType=metadata.mimeType,itemUri=metadata.uri.toString(),
        observedName=metadata.displayName,observedOwnerPackage=metadata.ownerPackage,published=false)
}

/** Only operations allocated by this run enter the private manifest. It is never exported. */
internal class PreflightUsbScope(
    private val context: Context, override val target: UsbExportTarget, private val runId: String,
    private val store: PreflightStore, private val cancelled: () -> Boolean,
    private val emit: (JsonObject) -> Unit,
) : RecorderDiagnosticScope {
    private val backend = UsbMediaStoreBackend(context)
    private val owned = linkedMapOf<String, UsbMediaStoreRecordingOutputHandle>()
    private val complete = linkedMapOf<String, UsbOwnedSegmentBundle>()
    private val deleted = mutableSetOf<String>()
    private val analyses = mutableListOf<JsonObject>()
    private var reserved = 0L
    private var written = 0L
    private var probeBytes = 0L
    private var admitted = 0L
    private val reservations = mutableMapOf<String, Long>()
    private var session: String? = null
    @Volatile var failed = false; private set
    private fun ledger(stage: String) = store.write("owned-$runId.json", obj("runId" to runId,"stage" to stage,
        "owned" to owned.values.map { obj("operation" to it.pendingVideo.operationId,"uri" to it.pendingVideo.itemUri,
            "bundle" to it.pendingVideo.bundleId,"session" to it.pendingVideo.recordingSessionId,
            "closedAndCommitted" to (it.pendingVideo.operationId in complete),"deleted" to (it.pendingVideo.operationId in deleted)) }))
    @Synchronized override fun admit(incomingBytes: Long) {
        check(!cancelled() && !failed) { "TEST_CANCELLED" }
        while (retained() + reserved + incomingBytes > PreflightPlan.MAX_RETAINED) {
            val oldest = complete.keys.firstOrNull { it !in deleted } ?: break
            delete(oldest)
        }
        val fresh = UsbExportVolumeResolver.resolveExact(context,target) ?: error("USB_REMOVED")
        val reason = PreflightPlan.budgetReason(written+reserved,retained()+reserved,incomingBytes,fresh.freeBytes)
        check(reason == null) { reason.orEmpty() }
        ledger("BEFORE_OUTPUT_OPEN"); admitted = incomingBytes
    }
    @Synchronized override fun opened(output: UsbMediaStoreRecordingOutputHandle) {
        val p = output.pendingVideo
        check(admitted > 0 && p.target.storageUuid == target.storageUuid)
        check(session == null || session == p.recordingSessionId); session = p.recordingSessionId
        owned[p.operationId] = output; reservations[p.operationId] = admitted; reserved += admitted; admitted = 0
        ledger("OUTPUT_OWNED")
        emit(obj("event" to "OUTPUT_OPENED","segment" to p.segmentNumber,"resourceId" to "output-${p.segmentNumber}"))
    }
    @Synchronized override fun committed(output: UsbMediaStoreRecordingOutputHandle) {
        try {
            val p = output.pendingVideo
            check(owned[p.operationId] === output && p.recordingSessionId == session)
            val bundle = UsbSegmentCatalog(context,backend).snapshot(target).segments.singleOrNull {
                it.manifestData.bundleId == p.bundleId && it.manifestData.recordingSessionId == session && it.video.uri.toString() == p.itemUri
            } ?: error("COMMITTED_BUNDLE_NOT_VISIBLE")
            complete[p.operationId] = bundle
            written += bundle.existingBytes
            // At most two product commits can be in flight. Reservations conservatively cover both.
            reserved = (reserved - (reservations.remove(p.operationId) ?: 0L)).coerceAtLeast(0)
            val analysis = PreflightFiles.inspect(context,bundle.video.uri,bundle.video.sizeBytes ?: 0) { false }
            analyses += obj("segment" to p.segmentNumber,"file" to analysis)
            ledger("CLOSED_VERIFIED")
            emit(obj("event" to "FILE_INSPECTED","segment" to p.segmentNumber,"analysis" to analysis))
        } catch (t: Throwable) { failed = true; throw t }
    }
    @Synchronized private fun retained() = complete.filterKeys { it !in deleted }.values.sumOf { it.existingBytes } + probeBytes
    @Synchronized fun summary(): JsonObject = obj("writtenBytes" to written,"retainedBytes" to retained(),"reservedBytes" to reserved,
        "completedFiles" to complete.size,"deletedBundles" to deleted.size,"failed" to failed,"files" to analyses.toList())
    private fun delete(id: String) {
        val original = requireNotNull(complete[id]); val output = requireNotNull(owned[id]); val p = output.pendingVideo
        check(p.operationId == id && p.recordingSessionId == session && original.video.uri.toString() == p.itemUri)
        val fresh = UsbSegmentCatalog(context,backend).snapshot(target).segments.singleOrNull { it.manifestData.bundleId == p.bundleId }
            ?: error("OWNED_BUNDLE_CHANGED")
        check(fresh.manifestData == original.manifestData && fresh.video.uri == original.video.uri && fresh.sidecar.uri == original.sidecar.uri && fresh.manifest.uri == original.manifest.uri)
        ledger("BEFORE_OWNED_DELETE")
        listOf(fresh.video,fresh.sidecar,fresh.manifest).forEach { metadata ->
            check(backend.deleteOwned(target,PreflightFiles.asset(metadata)).deletionConfirmed) { "OWNED_DELETE_UNCONFIRMED" }
        }
        deleted += id; ledger("OWNED_DELETE_CONFIRMED")
    }
    @Synchronized fun finishFiles() {
        // This method runs only after both recorder and GL have acknowledged disposal.
        if (!failed) complete.keys.filter { it !in deleted }.toList().forEach(::delete)
        ledger(if (failed) "FAILED_FILES_RETAINED" else "RUN_CLOSED")
    }
    override fun event(name: String, segment: Int, generation: Long, revision: Long) = emit(obj("event" to name,"segment" to segment,
        "cameraResourceId" to "camera-$generation","captureResourceId" to "capture-$generation-$revision"))

    fun probe(): JsonObject {
        check(!cancelled())
        val name = "OpenAVM_Preflight_${runId.take(8)}.bin"
        store.write("probe-$runId.json",obj("name" to name,"stage" to "BEFORE_INSERT"))
        val created = backend.insertPending(target,name,"application/octet-stream")
        var descriptor: android.os.ParcelFileDescriptor? = null
        var closeConfirmed = true
        var completed = false
        try {
            require(created.displayName == name && created.volumeName.equals(target.volumeName,true))
            store.write("probe-$runId.json",obj("uri" to created.uri.toString(),"name" to name,"stage" to "CREATED"))
            descriptor = backend.openRecordingDescriptor(created.uri); closeConfirmed = false
            val fd = descriptor.fileDescriptor; val payload = ByteArray(256*1024) { (it*31+17).toByte() }
            val chunkMs = mutableListOf<Long>(); val begin = SystemClock.elapsedRealtime()
            repeat(16) {
                check(!cancelled()) { "CANCELLED" }; val at = SystemClock.elapsedRealtime()
                var offset = 0; while(offset < payload.size) { val n=Os.write(fd,payload,offset,payload.size-offset); check(n>0); offset+=n }
                chunkMs += SystemClock.elapsedRealtime()-at
            }
            probeBytes = 16L*payload.size; written += probeBytes
            val length = Os.fstat(fd).st_size; check(length == probeBytes)
            check(Os.lseek(fd,12345,OsConstants.SEEK_SET) == 12345L)
            val patch = byteArrayOf(4,9,2,7); check(Os.write(fd,patch,0,patch.size)==patch.size)
            Os.lseek(fd,12345,OsConstants.SEEK_SET); val reread=ByteArray(4); check(Os.read(fd,reread,0,4)==4 && reread.contentEquals(patch))
            val syncStart = SystemClock.elapsedRealtime(); backend.sync(descriptor); val syncMs = SystemClock.elapsedRealtime()-syncStart
            descriptor.close(); descriptor = null; closeConfirmed = true
            context.contentResolver.openFileDescriptor(created.uri,"r").use { pfd ->
                requireNotNull(pfd); Os.lseek(pfd.fileDescriptor,12345,OsConstants.SEEK_SET)
                check(Os.read(pfd.fileDescriptor,reread,0,4)==4 && reread.contentEquals(patch))
            }
            check(backend.publish(created.uri)==1 && backend.metadata(created.uri)?.pending==0)
            completed = true
            return obj("backend" to "UsbMediaStoreBackend","mode" to "rw","bytesWritten" to probeBytes+4,"fstatBytes" to length,
                "seekAndPatch" to true,"closedReopenVerified" to true,"published" to true,
                "writeAndSyncDurationMs" to SystemClock.elapsedRealtime()-begin,"chunkWriteMs" to chunkMs,"syncMs" to syncMs,
                "durability" to "SYSCALL_RETURN_ONLY_NOT_POWER_LOSS_PROOF")
        } finally {
            if (descriptor != null) {
                closeConfirmed = runCatching { descriptor.close() }.isSuccess
            }
            if (!closeConfirmed) {
                descriptor?.let { synchronized(PreflightRuntime.retained) { PreflightRuntime.retained+=it } }
                throw PreflightCleanupUnconfirmed()
            }
            // Even a cancelled/failed probe is test-owned and safe to remove after its descriptor closed.
            val deletedProbe = backend.deleteOwned(target,PreflightFiles.asset(created)).deletionConfirmed
            if (deletedProbe) probeBytes=0
            store.write("probe-$runId.json",obj("uri" to created.uri.toString(),"name" to name,"stage" to "CLOSED",
                "operationPassed" to completed,"deletionConfirmed" to deletedProbe))
            check(deletedProbe) { "PROBE_DELETE_UNCONFIRMED" }
        }
    }
}
