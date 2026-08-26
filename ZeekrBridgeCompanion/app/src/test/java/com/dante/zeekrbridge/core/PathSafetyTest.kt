package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PathSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun traversalAndSeparatorsAreStripped() {
        assertEquals("a.mp4", PathSafety.cleanFileName("../../a.mp4"))
        assertEquals("a.mp4", PathSafety.cleanFileName("..\\..\\a.mp4"))
        assertEquals("a.mp4", PathSafety.cleanFileName("a.mp4"))
        assertNull(PathSafety.cleanFileName(".."))
        assertNull(PathSafety.cleanFileName("."))
        assertNull(PathSafety.cleanFileName(""))
        assertNull(PathSafety.cleanFileName("bad\u0000name"))
    }

    @Test
    fun carIdFallsBackToNeutralValue() {
        assertEquals("abc", PathSafety.cleanCarId("a/b\\c"))
        assertEquals("unknown", PathSafety.cleanCarId("../"))
        assertEquals("unknown", PathSafety.cleanCarId(null))
    }

    @Test
    fun uploadIdMustBeFlat() {
        assertEquals("abc-123", PathSafety.cleanUploadId("abc-123"))
        assertEquals("abc_123", PathSafety.cleanUploadId("abc_123"))
        assertNull(PathSafety.cleanUploadId("../abc"))
        assertNull(PathSafety.cleanUploadId("a/b"))
        assertNull(PathSafety.cleanUploadId("."))
        assertNull(PathSafety.cleanUploadId(".."))
        assertNull(PathSafety.cleanUploadId("..."))
        assertNull(PathSafety.cleanUploadId("a.b"))
        assertNull(PathSafety.cleanUploadId("a%2Fb"))
        assertNull(PathSafety.cleanUploadId(""))
    }

    @Test
    fun uniqueFileAvoidsExistingAndPartial() {
        val dir = tmp.newFolder("out")
        val existing = java.io.File(dir, "a.mp4")
        existing.writeBytes(ByteArray(1))
        java.io.File(dir, "a-1.mp4.partial").writeBytes(ByteArray(1))
        val target = PathSafety.uniqueFile(dir, "a.mp4")
        assertEquals("a-2.mp4", target.name)
    }
}
