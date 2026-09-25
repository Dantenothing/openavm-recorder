package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class IncidentSelectionTest {
    @Test fun protectsOnlyPreviousTwoFromCurrentDrive() {
        val rows = (1..7).map { IncidentCandidate("usb$it", "drive", it, null) } +
            IncidentCandidate("old", "previous-drive", 6, null)
        assertEquals(setOf("usb4", "usb5"), IncidentSelection.previous(rows, "drive", 6, "event"))
    }
    @Test fun doesNotStealAnOldEventOrSubstituteAnOlderSegment() {
        val rows = listOf(IncidentCandidate("a", "drive", 3, "other"),
            IncidentCandidate("b", "drive", 2, null), IncidentCandidate("c", "drive", 1, null))
        assertEquals(setOf("b"), IncidentSelection.previous(rows, "drive", 4, "event"))
    }
    @Test fun repeatedSaveAndShortDriveStayBounded() {
        val row = IncidentCandidate("first", "drive", 1, "event")
        assertEquals(setOf("first"), IncidentSelection.previous(listOf(row, row), "drive", 2, "event"))
        assertTrue(IncidentSelection.previous(listOf(row), "drive", 1, "event").isEmpty())
    }
}
