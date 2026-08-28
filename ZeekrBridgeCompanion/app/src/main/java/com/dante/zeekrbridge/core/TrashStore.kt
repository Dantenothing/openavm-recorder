package com.dante.zeekrbridge.core

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TrashEntry(
    val id: String,
    val deletedAtEpochMs: Long,
    val videoNames: List<String>,
    val sizeBytes: Long,
    val legacy: Boolean = false,
)

@Serializable
private data class TrashManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val deletedAtEpochMs: Long,
    val items: List<TrashItem>,
)

@Serializable
private data class TrashItem(
    val directory: String,
    val videoName: String,
    val metadataNames: List<String>,
)

/** File-only implementation kept separate from Android state so moves and restores are testable. */
internal class TrashRepository(
    private val receivedRoot: File,
    private val trashRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        receivedRoot.mkdirs()
        trashRoot.mkdirs()
    }

    fun list(): List<TrashEntry> {
        val modern = trashRoot.listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { dir -> readManifest(dir)?.toEntry(dir) }
        val legacyVideos = legacyVideos()
        val legacy = if (legacyVideos.isEmpty()) emptyList() else listOf(
            TrashEntry(
                id = LEGACY_ID,
                deletedAtEpochMs = legacyVideos.maxOfOrNull(File::lastModified) ?: 0L,
                videoNames = legacyVideos.map(File::getName),
                sizeBytes = legacyVideos.sumOf(File::length),
                legacy = true,
            ),
        )
        return (modern + legacy).sortedByDescending { it.deletedAtEpochMs }
    }

    fun moveToTrash(videos: Collection<File>): Int {
        val sources = videos.distinctBy { it.absoluteFile.normalize().path }
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) && insideReceived(it) }
        if (sources.isEmpty()) return 0
        val now = clock()
        val id = "$now-${UUID.randomUUID()}"
        val staging = File(trashRoot, ".pending-$id")
        val finalDir = File(trashRoot, id)
        staging.mkdirs()
        val moved = mutableListOf<Pair<File, File>>()
        return try {
            val items = sources.mapIndexed { index, video ->
                val itemDir = File(staging, "item-${index.toString().padStart(4, '0')}").apply { mkdirs() }
                val videoTarget = File(itemDir, video.name)
                move(video, videoTarget)
                moved += videoTarget to video
                val metadata = ReceivedFiles.relatedMetadataFiles(video).filter(File::isFile).map { source ->
                    val target = File(itemDir, source.name)
                    move(source, target)
                    moved += target to source
                    target.name
                }
                TrashItem(itemDir.name, videoTarget.name, metadata)
            }
            writeManifest(staging, TrashManifest(id = id, deletedAtEpochMs = now, items = items))
            check(staging.renameTo(finalDir)) { "Cannot commit trash entry" }
            sources.size
        } catch (error: Throwable) {
            moved.asReversed().forEach { (stored, original) ->
                if (stored.isFile && !original.exists()) runCatching { move(stored, original) }
            }
            staging.deleteRecursively()
            throw error
        }
    }

    fun restore(id: String): Int {
        if (id == LEGACY_ID) return restoreLegacy()
        val entryDir = File(trashRoot, id)
        val manifest = readManifest(entryDir) ?: return 0
        var restored = 0
        manifest.items.forEach { item ->
            val itemDir = File(entryDir, item.directory)
            val video = File(itemDir, item.videoName)
            if (!video.isFile) return@forEach
            val target = uniqueVideoTarget(item.videoName)
            val moved = mutableListOf<Pair<File, File>>()
            try {
                move(video, target)
                moved += target to video
                item.metadataNames.forEach { name ->
                    val source = File(itemDir, name)
                    if (!source.isFile) return@forEach
                    val metadataTarget = metadataTarget(target, item.videoName, name)
                    move(source, metadataTarget)
                    moved += metadataTarget to source
                }
                itemDir.deleteRecursively()
                restored++
            } catch (error: Throwable) {
                moved.asReversed().forEach { (destination, source) ->
                    if (destination.isFile && !source.exists()) runCatching { move(destination, source) }
                }
                throw error
            }
        }
        val remaining = manifest.items.filter { File(entryDir, it.directory).exists() }
        if (remaining.isEmpty()) entryDir.deleteRecursively()
        else writeManifest(entryDir, manifest.copy(items = remaining))
        return restored
    }

    fun deletePermanently(id: String): Boolean = when (id) {
        LEGACY_ID -> {
            val videos = legacyVideos()
            videos.forEach { video ->
                ReceivedFiles.relatedMetadataFiles(video).forEach(File::delete)
                video.delete()
            }
            videos.isNotEmpty()
        }
        else -> File(trashRoot, id).takeIf(File::isDirectory)?.deleteRecursively() == true
    }

    fun empty(): Boolean {
        val existed = trashRoot.listFiles().orEmpty().isNotEmpty()
        trashRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        return existed
    }

    private fun restoreLegacy(): Int {
        var restored = 0
        legacyVideos().forEach { video ->
            val target = uniqueVideoTarget(video.name)
            move(video, target)
            ReceivedFiles.relatedMetadataFiles(video).filter(File::isFile).forEach { metadata ->
                move(metadata, metadataTarget(target, video.name, metadata.name))
            }
            restored++
        }
        return restored
    }

    private fun TrashManifest.toEntry(dir: File): TrashEntry {
        val videos = items.mapNotNull { item -> File(File(dir, item.directory), item.videoName).takeIf(File::isFile) }
        val allFiles = dir.walkTopDown().filter(File::isFile).filterNot { it.name == MANIFEST }.toList()
        return TrashEntry(id, deletedAtEpochMs, videos.map(File::getName), allFiles.sumOf(File::length))
    }

    private fun readManifest(dir: File): TrashManifest? = runCatching {
        json.decodeFromString<TrashManifest>(File(dir, MANIFEST).readText())
    }.getOrNull()

    private fun writeManifest(dir: File, manifest: TrashManifest) {
        val target = File(dir, MANIFEST)
        val temp = File(dir, "$MANIFEST.tmp")
        temp.writeText(json.encodeToString(manifest))
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Cannot write trash manifest" }
    }

    private fun metadataTarget(restoredVideo: File, originalVideoName: String, metadataName: String): File = when {
        metadataName == "$originalVideoName.sidecar.json" -> File(receivedRoot, "${restoredVideo.name}.sidecar.json")
        metadataName == "${originalVideoName.substringBeforeLast('.')}.json" ->
            File(receivedRoot, "${restoredVideo.nameWithoutExtension}.json")
        else -> File(receivedRoot, metadataName)
    }

    private fun uniqueVideoTarget(name: String): File {
        val direct = File(receivedRoot, name)
        if (!direct.exists()) return direct
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "mp4")
        var suffix = 2
        while (true) {
            val candidate = File(receivedRoot, "$stem ($suffix).$extension")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun legacyVideos(): List<File> = trashRoot.listFiles().orEmpty()
        .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }

    private fun insideReceived(file: File): Boolean {
        val root = receivedRoot.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
        return file.canonicalFile.path.startsWith(root, ignoreCase = true)
    }

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        check(!target.exists()) { "Destination already exists: ${target.name}" }
        if (!source.renameTo(target)) {
            source.copyTo(target)
            check(source.delete()) { "Cannot remove original after copy: ${source.name}" }
        }
    }

    private companion object {
        const val MANIFEST = "trash.json"
        const val LEGACY_ID = "legacy-flat-trash"
    }
}

object TrashStore {
    private lateinit var repository: TrashRepository
    private val _entries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val entries: StateFlow<List<TrashEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        repository = TrashRepository(ReceivedStore.receivedDir(), File(app.filesDir, "trash"))
        refresh()
    }

    @Synchronized
    fun moveToTrash(files: Collection<File>): Int = repository.moveToTrash(files).also { refresh() }

    @Synchronized
    fun restore(id: String): Int = repository.restore(id).also {
        ReceivedStore.refresh()
        refresh()
    }

    @Synchronized
    fun deletePermanently(id: String): Boolean = repository.deletePermanently(id).also { refresh() }

    @Synchronized
    fun empty(): Boolean = repository.empty().also { refresh() }

    fun refresh() {
        _entries.value = repository.list()
    }
}
