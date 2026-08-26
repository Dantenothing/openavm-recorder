package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkToolsTest {

    @Test
    fun chunkTenSortsAfterChunkTwo() {
        val sorted = ChunkTools.parseChunkFiles(
            listOf("chunk-10", "chunk-2", "chunk-1").map { java.io.File(it) }.toTypedArray(),
        )
        assertEquals(listOf(1, 2, 10), sorted)
    }

    @Test
    fun onlyExactChunkNamesAreAccepted() {
        assertEquals(0, ChunkTools.parseChunkName("chunk-0"))
        assertEquals(42, ChunkTools.parseChunkName("chunk-42"))
        assertNull(ChunkTools.parseChunkName("chunk-"))
        assertNull(ChunkTools.parseChunkName("chunk-abc"))
        assertNull(ChunkTools.parseChunkName("chunk--1"))
        assertNull(ChunkTools.parseChunkName("chunk-2.partial"))
        assertNull(ChunkTools.parseChunkName("2"))
        assertNull(ChunkTools.parseChunkName("chunk-999999999999999999999"))
    }

    @Test
    fun missingChunkIsRejected() {
        assertFalse(ChunkTools.isComplete(listOf(0, 2), 3))
        assertTrue(ChunkTools.isComplete(listOf(2, 0, 1), 3))
    }

    @Test
    fun duplicateAndOutOfRangeAreRejected() {
        assertFalse(ChunkTools.isComplete(listOf(0, 1, 1), 3))
        assertFalse(ChunkTools.isComplete(listOf(0, 1, 3), 3))
        assertFalse(ChunkTools.isComplete(listOf(-1, 0, 1), 3))
        assertFalse(ChunkTools.isComplete(emptyList(), 1))
        assertTrue(ChunkTools.isComplete(emptyList(), 0))
    }

    @Test
    fun leadingZeroAliasCountsAsDuplicate() {
        val indices = ChunkTools.parseChunkFiles(
            listOf("chunk-01", "chunk-1", "chunk-2").map { java.io.File(it) }.toTypedArray(),
        )
        assertEquals(listOf(1, 1, 2), indices)
        assertFalse(ChunkTools.isComplete(indices, 3))
    }
}
