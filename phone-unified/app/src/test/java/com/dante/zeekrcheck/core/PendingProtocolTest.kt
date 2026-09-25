package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingProtocolTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun provisioningIsConsumedOnceAndCleared() {
        val file = directory.newFile(PendingProtocol.NAME)
        file.writeText("synthetic-only")
        assertEquals("synthetic-only", PendingProtocol.consume(directory.root))
        assertFalse(file.exists())
        assertNull(PendingProtocol.consume(directory.root))
    }
    @Test fun oversizedProvisioningIsRejectedAndStillCleared() {
        val file = directory.newFile(PendingProtocol.NAME)
        file.writeText("x".repeat(65_537))
        assertThrows(IllegalArgumentException::class.java) { PendingProtocol.consume(directory.root) }
        assertFalse(file.exists())
    }
}
