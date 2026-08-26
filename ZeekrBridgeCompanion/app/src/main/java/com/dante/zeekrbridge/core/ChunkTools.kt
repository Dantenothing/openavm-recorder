package com.dante.zeekrbridge.core

import java.io.File

/**
 * Pure Kotlin helpers for numeric chunk names and upload completeness.
 *
 * Only the exact form `chunk-<non-negative integer>` is accepted, so lexical
 * sorting can never put `chunk-10` before `chunk-2`.
 */
object ChunkTools {
    private val CHUNK_NAME = Regex("^chunk-([0-9]+)$")

    fun parseChunkName(name: String): Int? {
        val match = CHUNK_NAME.matchEntire(name) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        return if (value <= Int.MAX_VALUE) value.toInt() else null
    }

    fun parseChunkFile(file: File): Int? = parseChunkName(file.name)

    /** Numerically sorted indices of finalized chunks; `.partial`/junk ignored. */
    fun parseChunkFiles(files: Array<File>?): List<Int> =
        files.orEmpty().mapNotNull { parseChunkFile(it) }.sorted()

    /** True when [indices] is exactly `0 until total` with no missing/duplicate semantics. */
    fun isComplete(indices: Collection<Int>, total: Int): Boolean {
        if (total < 0 || indices.size != total) return false
        val unique = indices.toSet()
        if (unique.size != total) return false
        return unique.all { it in 0 until total }
    }
}
