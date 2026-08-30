package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.RecordingSessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSessionIdentityTest {

    @Test
    fun oneManualSessionKeepsOneIdUntilTerminalEnd() {
        val generated = ArrayDeque(listOf("session-a", "session-b"))
        val identity = RecordingSessionIdentity { generated.removeFirst() }

        assertNull(identity.currentId)
        val first = identity.beginNewSession()

        assertEquals("session-a", first)
        assertEquals(first, identity.requireCurrentId())
        assertEquals(first, identity.requireCurrentId())

        identity.endSession()
        assertNull(identity.currentId)

        val second = identity.beginNewSession()
        assertEquals("session-b", second)
        assertNotEquals(first, second)
    }
}
