package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class UsbDirectAccessProbe(
    val attempted: Boolean = true,
    val rootExists: Boolean = false,
    val rootReadable: Boolean = false,
    val rootListSucceeded: Boolean = false,
    val sentryDirectoryExists: Boolean = false,
    val sentryDirectoryReadable: Boolean = false,
    val sentryListSucceeded: Boolean = false,
    val sampledEventDirectories: Int = 0,
    val infoReadSucceeded: Boolean = false,
    val videoOpenSucceeded: Boolean = false,
    val failures: List<String> = emptyList(),
)

@Serializable
data class UsbSentryInfoSummary(
    val timestamp: String? = null,
    val timestampMatchesDirectory: Boolean? = null,
    val locationPresent: Boolean = false,
    val locationValid: Boolean? = null,
    val rawR: Int? = null,
    val rawD: List<Long>? = null,
    val rawAz: Int? = null,
    val parseError: String? = null,
)

@Serializable
data class UsbSentryEvent(
    val stableKey: String,
    val eventId: String,
    val relativeDirectory: String,
    val totalBytes: Long,
    val modifiedEpochMs: Long,
    val videoRelativePath: String? = null,
    val thumbnailRelativePath: String? = null,
    val infoRelativePath: String? = null,
    val complete: Boolean,
    val missingFiles: List<String> = emptyList(),
    val unexpectedFileCount: Int = 0,
    val info: UsbSentryInfoSummary? = null,
)

internal data class UsbSentryScanResult(
    val attempted: Boolean,
    val completed: Boolean,
    val directoryFound: Boolean = false,
    val truncated: Boolean = false,
    val invalidDirectoryCount: Int = 0,
    val events: List<UsbSentryEvent> = emptyList(),
    val error: String? = null,
)

internal object ZeekrSentryInventory {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventName = Regex("""^\d{4}-\d{2}-\d{2}_\d{2}_\d{2}_\d{2}$""")

    fun probe(target: UsbExportTarget): UsbDirectAccessProbe {
        val failures = mutableListOf<String>()
        val root = target.directoryPath?.let(::File)
            ?: return UsbDirectAccessProbe(failures = listOf("TARGET_DIRECTORY_UNAVAILABLE"))
        val rootEntries = listDirectory(root, "ROOT", failures)
        val sentry = rootEntries?.firstOrNull {
            it.isDirectory && it.name == SENTRY_DIRECTORY
        }
        val eventDirectories = sentry?.let { listDirectory(it, "SENTRY", failures) }
            ?.filter { it.isDirectory && eventName.matches(it.name) }
            ?.sortedByDescending { it.name }
            .orEmpty()
        val sample = eventDirectories.firstOrNull()
        val info = sample?.resolve(INFO_NAME)
        val video = sample?.resolve("${sample.name}_alert_360.mp4")
        val infoReadable = info?.takeIf { it.isFile }?.let { file ->
            runCatching { readBoundedUtf8(file, MAX_INFO_BYTES) }
                .onFailure { failures += "INFO_READ:${describe(it)}" }
                .isSuccess
        } ?: false
        val videoReadable = video?.takeIf { it.isFile }?.let { file ->
            runCatching {
                file.inputStream().use { input ->
                    if (input.read() < 0) error("empty video")
                }
            }.onFailure { failures += "VIDEO_OPEN:${describe(it)}" }.isSuccess
        } ?: false
        return UsbDirectAccessProbe(
            rootExists = root.exists(),
            rootReadable = root.canRead(),
            rootListSucceeded = rootEntries != null,
            sentryDirectoryExists = sentry?.exists() == true,
            sentryDirectoryReadable = sentry?.canRead() == true,
            sentryListSucceeded = sentry != null && failures.none { it.startsWith("SENTRY_LIST:") },
            sampledEventDirectories = eventDirectories.size.coerceAtMost(MAX_PROBE_EVENTS),
            infoReadSucceeded = infoReadable,
            videoOpenSucceeded = videoReadable,
            failures = failures,
        )
    }

    suspend fun scan(root: File, storageUuid: String): UsbSentryScanResult {
        val sentry = File(root, SENTRY_DIRECTORY)
        if (!sentry.exists()) {
            return UsbSentryScanResult(
                attempted = true,
                completed = true,
                directoryFound = false,
            )
        }
        if (!sentry.isDirectory) {
            return UsbSentryScanResult(
                attempted = true,
                completed = false,
                directoryFound = true,
                error = "SENTRY_PATH_NOT_DIRECTORY",
            )
        }
        val entries = try {
            sentry.listFiles() ?: error("listFiles returned null")
        } catch (t: Throwable) {
            return UsbSentryScanResult(
                attempted = true,
                completed = false,
                directoryFound = true,
                error = "SENTRY_LIST:${describe(t)}",
            )
        }
        val validDirectories = entries.filter { it.isDirectory && eventName.matches(it.name) }
            .sortedByDescending { it.name }
        val truncated = validDirectories.size > MAX_SENTRY_EVENTS
        val events = validDirectories.take(MAX_SENTRY_EVENTS).map { directory ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            parseEvent(directory, storageUuid)
        }
        return UsbSentryScanResult(
            attempted = true,
            completed = !truncated,
            directoryFound = true,
            truncated = truncated,
            invalidDirectoryCount = entries.count { it.isDirectory && !eventName.matches(it.name) },
            events = events,
            error = if (truncated) "SENTRY_EVENT_LIMIT_REACHED" else null,
        )
    }

    private fun parseEvent(directory: File, storageUuid: String): UsbSentryEvent {
        val id = directory.name
        val expectedVideo = "${id}_alert_360.mp4"
        val expectedThumbnail = "${id}_alert_360.jpg"
        val expected = setOf(expectedVideo, expectedThumbnail, INFO_NAME)
        val files = runCatching { directory.listFiles()?.filter(File::isFile).orEmpty() }
            .getOrDefault(emptyList())
        val byName = files.associateBy { it.name }
        val missing = expected.filterNot(byName::containsKey).sorted()
        val infoFile = byName[INFO_NAME]
        val info = infoFile?.let(::parseInfo)
        return UsbSentryEvent(
            stableKey = "sentry:$storageUuid:$id",
            eventId = id,
            relativeDirectory = "$SENTRY_DIRECTORY/$id/",
            totalBytes = files.sumOf { runCatching(it::length).getOrDefault(0L) },
            modifiedEpochMs = files.maxOfOrNull { runCatching(it::lastModified).getOrDefault(0L) }
                ?: directory.lastModified(),
            videoRelativePath = byName[expectedVideo]?.let { "$SENTRY_DIRECTORY/$id/$expectedVideo" },
            thumbnailRelativePath = byName[expectedThumbnail]?.let {
                "$SENTRY_DIRECTORY/$id/$expectedThumbnail"
            },
            infoRelativePath = infoFile?.let { "$SENTRY_DIRECTORY/$id/$INFO_NAME" },
            complete = missing.isEmpty() && info?.parseError == null &&
                info?.timestampMatchesDirectory == true,
            missingFiles = missing,
            unexpectedFileCount = files.count { it.name !in expected },
            info = info,
        )
    }

    private fun parseInfo(file: File): UsbSentryInfoSummary {
        val raw = runCatching {
            json.decodeFromString<RawSentryInfo>(readBoundedUtf8(file, MAX_INFO_BYTES))
        }.getOrElse {
            return UsbSentryInfoSummary(parseError = describe(it))
        }
        return UsbSentryInfoSummary(
            timestamp = raw.t,
            timestampMatchesDirectory = raw.t?.let { it == file.parentFile?.name },
            locationPresent = !raw.l.isNullOrBlank(),
            locationValid = raw.l?.let(::isValidCoordinate),
            rawR = raw.r,
            rawD = raw.d,
            rawAz = raw.az,
        )
    }

    private fun listDirectory(
        directory: File,
        label: String,
        failures: MutableList<String>,
    ): Array<File>? = runCatching {
        directory.listFiles() ?: error("listFiles returned null")
    }.onFailure { failures += "${label}_LIST:${describe(it)}" }.getOrNull()

    private fun readBoundedUtf8(file: File, maxBytes: Int): String {
        require(file.length() in 0..maxBytes.toLong()) { "info_inner.txt exceeds $maxBytes bytes" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= maxBytes) {
                    "info_inner.txt exceeds $maxBytes bytes"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun isValidCoordinate(value: String): Boolean {
        val parts = value.split(',')
        if (parts.size != 2) return false
        val latitude = parts[0].trim().toDoubleOrNull() ?: return false
        val longitude = parts[1].trim().toDoubleOrNull() ?: return false
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}:${t.message.orEmpty().take(160)}"

    @Serializable
    private data class RawSentryInfo(
        val t: String? = null,
        val r: Int? = null,
        val l: String? = null,
        val d: List<Long>? = null,
        val az: Int? = null,
    )

    private const val SENTRY_DIRECTORY = "SentryMode"
    private const val INFO_NAME = "info_inner.txt"
    private const val MAX_INFO_BYTES = 64 * 1024
    private const val MAX_PROBE_EVENTS = 20
    private const val MAX_SENTRY_EVENTS = 5_000
}
