package com.dante.zeekrbridge.core

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SavedMediaOrigin {
    SENTRY,
}

@Serializable
data class SavedMediaRecord(
    val id: String = UUID.randomUUID().toString(),
    val outputUri: String? = null,
    val outputPath: String? = null,
    val displayName: String,
    val origin: SavedMediaOrigin,
    val sourceId: String? = null,
    val exportTarget: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val layoutKind: String = IndexedLayoutKind.UNKNOWN.name,
    val laneLabels: List<String> = emptyList(),
    val laneOrder: List<Int> = emptyList(),
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
) {
    val uri: Uri?
        get() = when {
            !outputUri.isNullOrBlank() -> Uri.parse(outputUri)
            !outputPath.isNullOrBlank() -> Uri.fromFile(File(outputPath))
            else -> null
        }

    val indexedLayoutKind: IndexedLayoutKind
        get() = runCatching { IndexedLayoutKind.valueOf(layoutKind) }.getOrDefault(IndexedLayoutKind.UNKNOWN)
}

@Serializable
private data class SavedMediaCatalog(
    val schemaVersion: Int = 1,
    val records: List<SavedMediaRecord> = emptyList(),
)

/** Durable provenance for completed app exports. Temporary/prepared inputs never enter this store. */
object SavedMediaStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val _records = MutableStateFlow<List<SavedMediaRecord>>(emptyList())
    val records: StateFlow<List<SavedMediaRecord>> = _records.asStateFlow()
    private lateinit var app: Context

    @Synchronized
    fun init(context: Context) {
        app = context.applicationContext
        _records.value = readCatalog().records
            .filter { it.outputUri != null || it.outputPath != null }
            .distinctBy(::locationKey)
            .sortedByDescending { it.createdAtEpochMs }
    }

    @Synchronized
    fun register(record: SavedMediaRecord) {
        check(::app.isInitialized) { "SavedMediaStore is not initialized" }
        val key = locationKey(record)
        if (_records.value.firstOrNull { locationKey(it) == key } == record) return
        val next = (_records.value.filterNot { locationKey(it) == key } + record)
            .sortedByDescending { it.createdAtEpochMs }
        writeCatalog(SavedMediaCatalog(records = next))
        _records.value = next
    }

    @Synchronized
    fun remove(id: String) {
        check(::app.isInitialized) { "SavedMediaStore is not initialized" }
        val next = _records.value.filterNot { it.id == id }
        if (next.size == _records.value.size) return
        writeCatalog(SavedMediaCatalog(records = next))
        _records.value = next
    }

    suspend fun reconcileMissing() {
        check(::app.isInitialized) { "SavedMediaStore is not initialized" }
        val snapshot = _records.value
        val valid = withContext(Dispatchers.IO) {
            snapshot.filter(::isReadable)
        }
        synchronized(this) {
            if (_records.value !== snapshot || valid.size == snapshot.size) return
            writeCatalog(SavedMediaCatalog(records = valid))
            _records.value = valid
        }
    }

    private fun readCatalog(): SavedMediaCatalog = runCatching {
        catalogFile().openRead().bufferedReader().use { reader ->
            json.decodeFromString(SavedMediaCatalog.serializer(), reader.readText())
        }
    }.getOrDefault(SavedMediaCatalog())

    private fun writeCatalog(catalog: SavedMediaCatalog) {
        val atomic = catalogFile()
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(catalog).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun catalogFile(): AtomicFile {
        val directory = File(app.filesDir, "media-index").apply { mkdirs() }
        return AtomicFile(File(directory, "saved-media-v1.json"))
    }

    private fun locationKey(record: SavedMediaRecord): String =
        record.outputUri ?: record.outputPath ?: record.id

    private fun isReadable(record: SavedMediaRecord): Boolean = when {
        !record.outputPath.isNullOrBlank() -> File(record.outputPath).isFile
        !record.outputUri.isNullOrBlank() -> runCatching {
            app.contentResolver.openFileDescriptor(Uri.parse(record.outputUri), "r")?.use { true } ?: false
        }.getOrDefault(false)
        else -> false
    }
}
