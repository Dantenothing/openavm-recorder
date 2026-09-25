package com.dante.zeekrcapabilitylab.sharing

import android.content.Context
import com.dante.zeekrcapabilitylab.product.VehicleSentryRecording
import com.dante.zeekrcapabilitylab.product.VehicleUsbRecording
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStorageLock
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.transfer.FactorySentryTransferSource
import com.dante.zeekrcapabilitylab.usbexport.*
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed interface ShareSelection {
    val files: List<File>
    data class Internal(override val files: List<File>) : ShareSelection
    data class Usb(val recording: VehicleUsbRecording) : ShareSelection {
        override val files get() = recording.files
    }
    data class Factory(val recording: VehicleSentryRecording) : ShareSelection {
        override val files get() = listOfNotNull(recording.videoFile)
    }
}

/** A lease covers the selection, not individual HTTP requests, and outlives cancelled readers. */
class PreparedShare(val assets: List<ShareAsset>, private val pins: List<File>) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) pins.forEach(PlaybackPinRegistry::release)
    }
}

object ShareSources {
    private const val MAX_JSON_BYTES = 64 * 1024
    private data class Source(val file: File, val metadata: () -> ByteArray?, val mounted: () -> Boolean)

    fun prepare(context: Context, selection: ShareSelection, chosen: List<File>, includeJson: Boolean,
                limits: ShareLimits): PreparedShare {
        require(chosen.isNotEmpty() && chosen.size <= limits.maxFiles)
        require(chosen.distinctBy { it.absolutePath }.size == chosen.size)
        require(chosen.all { it in selection.files })
        val pins = mutableListOf<File>()
        try {
            fun pin(sources: List<Source>): PreparedShare {
                require(limits.accepts(sources.map { it.file.length() }))
                val assets = sources.flatMapIndexed { index, source ->
                    val file = source.file.canonicalFile
                    require(file.isFile && file.length() > 0 && file.canRead())
                    val original = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    fun valid(): Boolean = source.mounted() && runCatching {
                        val fresh = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                        fresh.isRegularFile && fresh.size() == original.size() &&
                            fresh.lastModifiedTime() == original.lastModifiedTime() && fresh.fileKey() == original.fileKey()
                    }.getOrDefault(false)
                    PlaybackPinRegistry.acquire(file)
                    pins += file
                    val identity = hash("${file.name}|${original.size()}|${original.lastModifiedTime()}|${original.fileKey()}".toByteArray())
                    val video = ShareAsset("v$index", file.name, original.size(), "W/\"$identity\"", valid = ::valid,
                        open = {
                            check(valid())
                            Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
                        })
                    // Missing, oversized or malformed metadata never prevents full-frame MP4 download.
                    val bytes = if (includeJson) runCatching { source.metadata() }.getOrNull()?.takeIf {
                        it.size <= MAX_JSON_BYTES && runCatching { Json.parseToJsonElement(it.toString(Charsets.UTF_8)) is JsonObject }.getOrDefault(false)
                    } else null
                    listOfNotNull(video, bytes?.let {
                        ShareAsset("j$index", file.name.removeSuffix(".mp4") + ".sidecar.json", it.size.toLong(),
                            "\"${hash(it)}\"", metadata = true, valid = ::valid, open = { MemoryChannel(it) })
                    })
                }
                require(limits.accepts(assets.filterNot { it.metadata }.map { it.size }))
                return PreparedShare(assets, pins.toList())
            }
            return when (selection) {
                is ShareSelection.Internal -> synchronized(RecorderStorageLock.lock) {
                    val root = File(context.filesDir, "recordings/segments").canonicalFile
                    pin(chosen.map { file ->
                        require(file.canonicalFile.parentFile == root && RecorderLibrary.isManaged(file))
                        Source(file, { readSmall(SegmentSidecarIO.sidecarFileFor(file)) }, { true })
                    })
                }
                is ShareSelection.Usb -> UsbMutationCoordinator.withTarget(selection.recording.storageUuid) {
                    val recording = selection.recording
                    val target = target(context, recording.storageUuid)
                    val root = File(requireNotNull(target.directoryPath)).canonicalFile
                    val backend = UsbMediaStoreBackend(context)
                    val segments = UsbSegmentCatalog(context).snapshot(target).segments
                        .filter { OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.SEGMENT_BUNDLE, it.manifestData.bundleId) in recording.ownedUnits }
                    val legacy = UsbExportCatalog(context, backend).snapshot(target).completeExports
                        .filter { OpenAvmOwnedUnitRef(OpenAvmOwnedUnitKind.LEGACY_EXPORT, it.exportKey) in recording.ownedUnits }
                    val verified = segments.map { it.video } + legacy.flatMap { it.assets }.filter { it.mimeType == "video/mp4" }
                    pin(chosen.map { file ->
                        val metadata = verified.single { rawFile(root, it).canonicalFile == file.canonicalFile }
                        require(file.length() == metadata.sizeBytes && metadata.pending == 0)
                        val bundle = segments.firstOrNull { it.video.uri == metadata.uri }
                        Source(file, {
                            if (bundle != null) com.dante.zeekrcapabilitylab.usbexport.UsbIncidentMarkers(context).sidecarBytes(bundle)
                            else readSmall(File(file.parentFile, file.name.removeSuffix(".mp4") + ".sidecar.json"))
                        }, { mounted(context, target) })
                    })
                }
                is ShareSelection.Factory -> {
                    val recording = selection.recording
                    require(chosen.size == 1)
                    UsbMutationCoordinator.withTarget(recording.storageUuid) {
                        val target = target(context, recording.storageUuid)
                        val snapshot = FactorySentryTransferSource.snapshotForQueue(context, recording.storageUuid,
                            recording.eventId, recording.startedAtEpochMs, chosen.single()).getOrThrow()
                        pin(listOf(Source(snapshot.file, { snapshot.sidecarJson.toByteArray(Charsets.UTF_8) }, { mounted(context, target) })))
                    }
                }
            }
        } catch (error: Throwable) {
            pins.forEach(PlaybackPinRegistry::release)
            throw error
        }
    }

    private fun target(context: Context, uuid: String): UsbExportTarget =
        UsbExportVolumeResolver.mountedTargets(context).single { it.storageUuid.equals(uuid, ignoreCase = true) }
    private fun mounted(context: Context, expected: UsbExportTarget): Boolean = runCatching {
        // No MediaStore scans or free-space probes in the streaming loop.
        val volumes = context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
        volumes.any { volume -> volume.isRemovable && volume.state == android.os.Environment.MEDIA_MOUNTED &&
            volume.uuid.equals(expected.storageUuid, ignoreCase = true) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            volume.directory?.absolutePath == expected.directoryPath }
    }.getOrDefault(false)
    private fun rawFile(root: File, metadata: UsbMediaStoreBackend.Metadata): File {
        val file = File(File(root, metadata.relativePath.orEmpty().trim('/', '\\')), requireNotNull(metadata.displayName)).canonicalFile
        require(file.path.startsWith(root.path + File.separator))
        return file
    }
    private fun readSmall(file: File): ByteArray? {
        if (!file.isFile || file.length() !in 1..MAX_JSON_BYTES.toLong()) return null
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_JSON_BYTES) return null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class MemoryChannel(private val bytes: ByteArray) : SeekableByteChannel {
        private var offset = 0
        private var opened = true
        override fun read(dst: ByteBuffer): Int {
            check(opened)
            if (offset >= bytes.size) return -1
            val size = minOf(dst.remaining(), bytes.size - offset)
            dst.put(bytes, offset, size); offset += size
            return size
        }
        override fun position() = offset.toLong()
        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition in 0..bytes.size.toLong()); offset = newPosition.toInt(); return this
        }
        override fun size() = bytes.size.toLong()
        override fun isOpen() = opened
        override fun close() { opened = false }
        override fun write(src: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw java.nio.channels.NonWritableChannelException()
    }
}
