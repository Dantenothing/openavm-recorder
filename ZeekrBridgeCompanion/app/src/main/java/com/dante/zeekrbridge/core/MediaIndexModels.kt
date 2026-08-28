package com.dante.zeekrbridge.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class IndexedSourceRole { SURROUND, CABIN, IR, UNKNOWN }

enum class IndexedLayoutKind { FOUR_LANE_V1, SINGLE_V1, UNKNOWN }

data class IndexedLane(
    val label: String,
    val x0: Int,
    val x1: Int,
    val y0: Int,
    val y1: Int,
    val displayOrder: Int,
    /** One-based position inside the raw composite file. */
    val lane: Int = 0,
)

data class IndexedMediaSegment(
    val id: String,
    val filePath: String,
    val fileName: String,
    val sidecarPath: String?,
    val sizeBytes: Long,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long?,
    val durationMs: Long,
    val segmentNumber: Int?,
    val recordingSessionId: String?,
    val eventId: String?,
    val eventRole: String?,
    val protected: Boolean,
    val sourceRole: IndexedSourceRole,
    val layoutKind: IndexedLayoutKind,
    val cameraId: String?,
    val lanes: List<IndexedLane>,
    val originalWidth: Int?,
    val originalHeight: Int?,
) {
    val file: File get() = File(filePath)
    val playbackLabels: List<String>
        get() = lanes.sortedBy { it.displayOrder }.map { it.label }.takeIf { it.size == 4 }
            ?: if (layoutKind == IndexedLayoutKind.FOUR_LANE_V1) {
                listOf("Front", "Rear", "Left", "Right")
            } else {
                emptyList()
            }
    val playbackLaneOrder: List<Int>
        get() = lanes.sortedBy { it.displayOrder }
            .map { it.lane }
            .takeIf { it.size == 4 && it.toSet() == setOf(1, 2, 3, 4) }
            ?: if (layoutKind == IndexedLayoutKind.FOUR_LANE_V1) {
                listOf(1, 2, 3, 4)
            } else {
                emptyList()
            }
}

data class IndexedRecordingSession(
    val id: String,
    val segments: List<IndexedMediaSegment>,
    val legacy: Boolean,
) {
    val startedAtEpochMs: Long = segments.minOf { it.startedAtEpochMs }
    val stoppedAtEpochMs: Long? = segments.mapNotNull { it.stoppedAtEpochMs }.maxOrNull()
    val durationMs: Long = segments.sumOf { it.durationMs }.takeIf { it > 0L }
        ?: ((stoppedAtEpochMs ?: startedAtEpochMs) - startedAtEpochMs).coerceAtLeast(0L)
    val sizeBytes: Long = segments.sumOf { it.sizeBytes }
    val sourceRole: IndexedSourceRole = segments.firstOrNull()?.sourceRole ?: IndexedSourceRole.UNKNOWN
    val layoutKind: IndexedLayoutKind = segments.firstOrNull()?.layoutKind ?: IndexedLayoutKind.UNKNOWN
    val hasIncident: Boolean = segments.any { it.protected || !it.eventId.isNullOrBlank() }
    val cover: IndexedMediaSegment get() = segments.first()
}

data class IndexedRecordingEvent(
    val id: String,
    val segments: List<IndexedMediaSegment>,
    val legacy: Boolean,
) {
    val startedAtEpochMs: Long = segments.minOf { it.startedAtEpochMs }
    val durationMs: Long = segments.sumOf { it.durationMs }
    val sizeBytes: Long = segments.sumOf { it.sizeBytes }
    val sourceRole: IndexedSourceRole = segments.firstOrNull()?.sourceRole ?: IndexedSourceRole.UNKNOWN
    val layoutKind: IndexedLayoutKind = segments.firstOrNull()?.layoutKind ?: IndexedLayoutKind.UNKNOWN
    val cover: IndexedMediaSegment get() = segments.first()
}

data class MediaIndexSnapshot(
    val segments: List<IndexedMediaSegment> = emptyList(),
    val sessions: List<IndexedRecordingSession> = emptyList(),
    val events: List<IndexedRecordingEvent> = emptyList(),
)

object MediaIndexScanner {
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(files: Collection<File>): MediaIndexSnapshot {
        val segments = files.asSequence()
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .map(::readSegment)
            .sortedByDescending { it.startedAtEpochMs }
            .toList()
        return MediaIndexGrouper.build(segments)
    }

    internal fun readSegment(file: File): IndexedMediaSegment {
        val sidecar = sidecarCandidates(file).firstOrNull(File::isFile)
        val obj = sidecar?.let {
            runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull()
        }
        val source = resolveSource(obj)
        val layout = resolveLayout(obj, source)
        val lanes = readLanes(obj)
        val startedAt = obj.long("startedAtEpochMs")
            ?: obj.long("requestedAtEpochMs")
            ?: file.lastModified().takeIf { it > 0L }
            ?: 0L
        val duration = obj.obj("actualTrack")?.long("durationMs")
            ?: durationBetween(startedAt, obj.long("stoppedAtEpochMs"))
        val profileSize = obj.obj("profile")?.obj("size")
        val laneLayout = obj.obj("laneLayout")
        return IndexedMediaSegment(
            id = file.absoluteFile.path,
            filePath = file.absolutePath,
            fileName = file.name,
            sidecarPath = sidecar?.absolutePath,
            sizeBytes = file.length(),
            startedAtEpochMs = startedAt,
            stoppedAtEpochMs = obj.long("stoppedAtEpochMs"),
            durationMs = duration ?: 0L,
            segmentNumber = obj.long("segmentNumber")?.toInt(),
            recordingSessionId = obj.string("recordingSessionId"),
            eventId = obj.string("eventId"),
            eventRole = obj.string("eventRole"),
            protected = obj.bool("protected") ?: false,
            sourceRole = source,
            layoutKind = layout,
            cameraId = obj.string("cameraId"),
            lanes = lanes,
            originalWidth = laneLayout?.long("originalWidth")?.toInt()
                ?: profileSize?.long("width")?.toInt(),
            originalHeight = laneLayout?.long("originalHeight")?.toInt()
                ?: profileSize?.long("height")?.toInt(),
        )
    }

    fun sidecarCandidates(file: File): List<File> = listOf(
        File(file.absolutePath + ".sidecar.json"),
        File(file.parentFile, file.nameWithoutExtension + ".json"),
    ).distinctBy { it.absolutePath }

    private fun resolveSource(obj: JsonObject?): IndexedSourceRole {
        return when (obj.string("sourceRole")?.uppercase()) {
            "SURROUND" -> IndexedSourceRole.SURROUND
            "CABIN" -> IndexedSourceRole.CABIN
            "IR" -> IndexedSourceRole.IR
            else -> when (obj.string("cameraId")) {
                "2" -> IndexedSourceRole.SURROUND
                "1" -> IndexedSourceRole.CABIN
                "0" -> IndexedSourceRole.IR
                else -> IndexedSourceRole.UNKNOWN
            }
        }
    }

    private fun resolveLayout(obj: JsonObject?, source: IndexedSourceRole): IndexedLayoutKind {
        return when (obj.string("layoutKind")?.uppercase()) {
            "FOUR_LANE_V1" -> IndexedLayoutKind.FOUR_LANE_V1
            "SINGLE_V1" -> IndexedLayoutKind.SINGLE_V1
            else -> when {
                obj.obj("laneLayout") != null -> IndexedLayoutKind.FOUR_LANE_V1
                source == IndexedSourceRole.SURROUND -> IndexedLayoutKind.FOUR_LANE_V1
                source == IndexedSourceRole.CABIN || source == IndexedSourceRole.IR -> IndexedLayoutKind.SINGLE_V1
                else -> IndexedLayoutKind.UNKNOWN
            }
        }
    }

    private fun readLanes(obj: JsonObject?): List<IndexedLane> {
        val values = runCatching { obj?.get("laneLayout")?.jsonObject?.get("lanes")?.jsonArray }.getOrNull()
            ?: return emptyList()
        return values.mapNotNull { value ->
            runCatching {
                val lane = value.jsonObject
                IndexedLane(
                    label = lane.string("label") ?: return@runCatching null,
                    x0 = lane.long("x0")?.toInt() ?: return@runCatching null,
                    x1 = lane.long("x1")?.toInt() ?: return@runCatching null,
                    y0 = lane.long("y0")?.toInt() ?: return@runCatching null,
                    y1 = lane.long("y1")?.toInt() ?: return@runCatching null,
                    displayOrder = lane.long("displayOrder")?.toInt() ?: 0,
                    lane = lane.long("lane")?.toInt() ?: 0,
                )
            }.getOrNull()
        }.let(::restoreMissingLaneNumbers).sortedBy { it.displayOrder }
    }

    /** Older sidecars stored crop coordinates but not the explicit lane index. */
    private fun restoreMissingLaneNumbers(lanes: List<IndexedLane>): List<IndexedLane> {
        if (lanes.size != 4) return lanes
        if (lanes.map { it.lane }.toSet() == setOf(1, 2, 3, 4)) return lanes
        val xSpread = lanes.maxOf { it.x0 } - lanes.minOf { it.x0 }
        val ySpread = lanes.maxOf { it.y0 } - lanes.minOf { it.y0 }
        val sourceOrder = lanes.indices.sortedBy { index ->
            if (ySpread >= xSpread) lanes[index].y0 else lanes[index].x0
        }
        val sourceLaneByIndex = sourceOrder.mapIndexed { sourceIndex, originalIndex ->
            originalIndex to sourceIndex + 1
        }.toMap()
        return lanes.mapIndexed { index, lane -> lane.copy(lane = sourceLaneByIndex.getValue(index)) }
    }

    private fun durationBetween(start: Long, stop: Long?): Long? =
        stop?.let { (it - start).coerceAtLeast(0L) }

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject?.long(key: String): Long? = this?.get(key)?.jsonPrimitive?.longOrNull
    private fun JsonObject?.bool(key: String): Boolean? = this?.get(key)?.jsonPrimitive?.booleanOrNull
    private fun JsonObject?.obj(key: String): JsonObject? = runCatching { this?.get(key)?.jsonObject }.getOrNull()
}

object MediaIndexGrouper {
    fun build(segments: List<IndexedMediaSegment>): MediaIndexSnapshot {
        val sessions = buildSessions(segments)
        val events = buildEvents(segments)
        return MediaIndexSnapshot(
            segments = segments.sortedByDescending { it.startedAtEpochMs },
            sessions = sessions.sortedByDescending { it.startedAtEpochMs },
            events = events.sortedByDescending { it.startedAtEpochMs },
        )
    }

    private fun buildSessions(segments: List<IndexedMediaSegment>): List<IndexedRecordingSession> {
        val modern = segments.filter { !it.recordingSessionId.isNullOrBlank() }
            .groupBy { it.recordingSessionId!! }
            .map { (id, values) -> IndexedRecordingSession(id, ordered(values), legacy = false) }
        val legacySegments = segments.filter { it.recordingSessionId.isNullOrBlank() }
            .sortedWith(compareBy<IndexedMediaSegment> { it.startedAtEpochMs }.thenBy { it.segmentNumber ?: 0 })
        val legacy = mutableListOf<MutableList<IndexedMediaSegment>>()
        legacySegments.forEach { current ->
            val group = legacy.lastOrNull()
            val previous = group?.lastOrNull()
            if (previous == null || !canJoinLegacy(previous, current)) {
                legacy += mutableListOf(current)
            } else {
                group += current
            }
        }
        return modern + legacy.map { values ->
            IndexedRecordingSession("legacy:${values.first().id}", ordered(values), legacy = true)
        }
    }

    private fun buildEvents(segments: List<IndexedMediaSegment>): List<IndexedRecordingEvent> {
        val modern = segments.filter { !it.eventId.isNullOrBlank() }
            .groupBy { it.eventId!! }
            .map { (id, values) -> IndexedRecordingEvent(id, eventOrdered(values), legacy = false) }
        val modernIds = modern.flatMap { it.segments }.mapTo(mutableSetOf()) { it.id }
        val legacy = segments.filter { it.protected && it.id !in modernIds }.map { segment ->
            IndexedRecordingEvent("legacy-event:${segment.id}", listOf(segment), legacy = true)
        }
        return modern + legacy
    }

    private fun canJoinLegacy(previous: IndexedMediaSegment, current: IndexedMediaSegment): Boolean {
        if (previous.sourceRole != current.sourceRole || previous.layoutKind != current.layoutKind) return false
        val previousNumber = previous.segmentNumber
        val currentNumber = current.segmentNumber
        if (previousNumber != null && currentNumber != null && currentNumber != previousNumber + 1) return false
        val previousEnd = previous.stoppedAtEpochMs
            ?: (previous.startedAtEpochMs + previous.durationMs.takeIf { it > 0L }.orZero())
        val gap = current.startedAtEpochMs - previousEnd
        return gap in -5_000L..LEGACY_MAX_GAP_MS
    }

    private fun ordered(values: List<IndexedMediaSegment>): List<IndexedMediaSegment> =
        values.sortedWith(compareBy<IndexedMediaSegment> { it.startedAtEpochMs }.thenBy { it.segmentNumber ?: 0 })

    private fun eventOrdered(values: List<IndexedMediaSegment>): List<IndexedMediaSegment> {
        val roleOrder = mapOf("PREVIOUS" to 0, "CURRENT" to 1, "NEXT" to 2)
        return values.sortedWith(
            compareBy<IndexedMediaSegment> { roleOrder[it.eventRole?.uppercase()] ?: 3 }
                .thenBy { it.startedAtEpochMs },
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L
    private const val LEGACY_MAX_GAP_MS = 30_000L
}
