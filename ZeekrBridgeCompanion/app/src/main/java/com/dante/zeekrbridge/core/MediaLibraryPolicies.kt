package com.dante.zeekrbridge.core

import java.io.File

enum class LibraryBackAction {
    CLOSE_PLAYER,
    CLOSE_DETAIL,
    CLEAR_SELECTION,
    EXIT_LIBRARY,
}

fun resolveLibraryBackAction(
    playerOpen: Boolean,
    detailOpen: Boolean,
    selectionCount: Int,
): LibraryBackAction = when {
    playerOpen -> LibraryBackAction.CLOSE_PLAYER
    detailOpen -> LibraryBackAction.CLOSE_DETAIL
    selectionCount > 0 -> LibraryBackAction.CLEAR_SELECTION
    else -> LibraryBackAction.EXIT_LIBRARY
}

data class MediaDeletePlan(
    val segments: List<IndexedMediaSegment>,
) {
    val physicalVideoCount: Int get() = segments.size
}

object MediaDeletePlanner {
    fun plan(logicalItems: Collection<List<IndexedMediaSegment>>): MediaDeletePlan {
        val unique = linkedMapOf<String, IndexedMediaSegment>()
        logicalItems.asSequence().flatten().forEach { segment ->
            val normalizedPath = File(segment.filePath).absoluteFile.normalize().path
            unique.putIfAbsent(normalizedPath, segment)
        }
        return MediaDeletePlan(unique.values.toList())
    }
}
