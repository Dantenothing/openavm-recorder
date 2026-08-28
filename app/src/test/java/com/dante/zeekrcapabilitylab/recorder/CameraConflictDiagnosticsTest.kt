package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.CameraConflictDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraConflictDiagnosticsTest {
    @Test
    fun concurrentSetsAreStableAndHumanReadable() {
        val encoded = CameraConflictDiagnostics.encodeConcurrentSets(
            setOf(setOf("2", "0"), setOf("1", "0")),
        )

        assertEquals("0+1|0+2", encoded)
        assertEquals(
            CameraConflictDiagnostics.NONE_DECLARED,
            CameraConflictDiagnostics.encodeConcurrentSets(emptySet()),
        )
    }

    @Test
    fun pairSupportUsesDeclaredSupersets() {
        val sets = setOf(setOf("0", "1", "2"))

        assertTrue(CameraConflictDiagnostics.supportsPair(sets, "1", "2"))
        assertFalse(CameraConflictDiagnostics.supportsPair(emptySet(), "1", "2"))
    }

    @Test
    fun availabilityFilterIncludesKnownAndActiveCameraIds() {
        assertTrue(CameraConflictDiagnostics.shouldLogAvailability("0", "2"))
        assertTrue(CameraConflictDiagnostics.shouldLogAvailability("2", "2"))
        assertTrue(CameraConflictDiagnostics.shouldLogAvailability("external", "external"))
        assertFalse(CameraConflictDiagnostics.shouldLogAvailability("external", "2"))
    }

    @Test
    fun physicalIdsAreSortedPerCamera() {
        val encoded = CameraConflictDiagnostics.encodePhysicalIds(
            mapOf(
                "2" to setOf("rear", "front"),
                "0" to emptySet(),
            ),
        )

        assertEquals("0=-|2=front+rear", encoded)
    }
}
