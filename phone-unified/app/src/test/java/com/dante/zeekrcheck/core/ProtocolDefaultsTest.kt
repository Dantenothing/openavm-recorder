package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test

class ProtocolDefaultsTest {
    private fun synthetic() = Fixture.vectors.getValue("config").toString()

    @Test fun firstInstallIsReadyWithoutAnImportedFileAndPersistsOnce() {
        val bundled = synthetic()
        var writes = 0
        var saved: String? = null
        val first = ProtocolDefaults.loadOrInstall({ saved }, { bundled }) { saved = it; writes++ }
        assertEquals(bundled, first)
        val second = ProtocolDefaults.loadOrInstall({ saved }, { error("must reuse saved profile") }) { writes++ }
        assertEquals(first, second)
        assertEquals(1, writes)
    }

    @Test fun savedCustomProfileWinsWithoutReadingOrReplacingIt() {
        val saved = synthetic()
        assertEquals(saved, ProtocolDefaults.loadOrInstall({ saved }, { error("must not load default") }) { error("must not save") })
    }

    @Test fun unreadableSavedProfileDoesNotSilentlyOverwriteUserConfiguration() {
        assertThrows(IllegalStateException::class.java) {
            ProtocolDefaults.loadOrInstall({ error("cannot decrypt") }, { error("must not load default") }) { error("must not save") }
        }
    }

    @Test fun invalidDefaultIsRejectedBeforeItCanBePersisted() {
        var saved = false
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolDefaults.loadOrInstall({ null }, { "{}" }) { saved = true }
        }
        assertFalse(saved)
    }
}
