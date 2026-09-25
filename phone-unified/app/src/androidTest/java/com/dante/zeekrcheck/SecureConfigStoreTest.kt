package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

class SecureConfigStoreTest {
    @Test fun keystoreRoundTripTamperDetectionAndDeletionUseAnIsolatedSyntheticStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "instrumentation-test"
        val store = SecureConfigStore(context, name)
        val file = File(context.noBackupFilesDir, "$name.sealed")
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val text = buildJsonObject {
            put("hmac_access_key", "SYNTHETIC-ACCESS-KEY")
            put("hmac_secret_key", "SYNTHETIC-HMAC-SECRET")
            put("password_public_key", Base64.getEncoder().encodeToString(rsa.encoded))
            put("prod_secret", "SYNTHETIC-PROD-SECRET")
            put("vin_key", "0123456789abcdef"); put("vin_iv", "fedcba9876543210")
        }.toString()
        try {
            store.clear()
            assertNull(store.load())
            store.save(text)
            assertEquals(text, SecureConfigStore(context, name).load())
            assertFalse(file.readText().contains("SYNTHETIC"))
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(keys.getKey("com.dante.zeekrcheck.$name.v1", null).encoded)
            val altered = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(altered)
            assertThrows(Exception::class.java) { store.load() }
            store.save(text)
            assertEquals(text, store.load())
            store.clear()
            assertNull(store.load()); assertFalse(file.exists())
        } finally { store.clear() }
    }
}
