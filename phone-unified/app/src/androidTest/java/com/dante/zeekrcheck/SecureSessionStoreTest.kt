package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.SavedSession
import com.dante.zeekrcheck.core.SessionPersistence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

class SecureSessionStoreTest {
    @Test fun freshStoreInstanceRestoresEncryptedSessionAndLogoutDeletesFileAndKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "instrumentation-session"
        val store = SecureSessionStore(context, name)
        val file = File(context.noBackupFilesDir, "$name.sealed")
        val session = SavedSession("SYNTHETIC-USER-TOKEN", "Bearer SYNTHETIC-ACCESS-TOKEN",
            "d294932f-f97e-4b6d-a63c-41bfa80d83ca", "a".repeat(64))
        val alias = "com.dante.zeekrcheck.$name.v1"
        try {
            store.clear()
            val persistence = SessionPersistence(store)
            assertTrue(persistence.write(persistence.current(), session))
            assertFalse(file.readText().contains("SYNTHETIC"))
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(keys.getKey(alias, null).encoded)
            val restored = SecureSessionStore(context, name).load()!!
            assertEquals(session.accessToken, restored.accessToken)
            assertEquals(session.userToken, restored.userToken)
            assertEquals(session.deviceId, restored.deviceId)
            assertThrows(Exception::class.java) { SecureConfigStore(context, name).load() }
            val epoch = persistence.current()
            assertTrue(persistence.write(persistence.advance(), null))
            assertFalse(persistence.write(epoch, session))
            assertNull(store.load()); assertFalse(file.exists()); assertFalse(keys.containsAlias(alias))
        } finally { store.clear() }
    }

    @Test fun corruptedSessionFailsClosedWithoutTouchingOtherStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "instrumentation-session"
        val other = "instrumentation-other"
        val store = SecureSessionStore(context, name)
        val preserved = SecureSessionStore(context, other)
        val record = SavedSession("SYNTHETIC-USER", "SYNTHETIC-ACCESS",
            "d294932f-f97e-4b6d-a63c-41bfa80d83ca", "b".repeat(64))
        try {
            store.save(record); preserved.save(record)
            val file = File(context.noBackupFilesDir, "$name.sealed")
            file.writeBytes(file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertThrows(Exception::class.java) { store.load() }
            store.clear()
            assertNull(store.load())
            assertEquals(record.accessToken, preserved.load()!!.accessToken)
        } finally { store.clear(); preserved.clear() }
    }
}
