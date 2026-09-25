package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImportedProtocolTest {
    private val valid get() = Fixture.vectors.getValue("config").toString()
    @Test fun importedEnvelopeCannotBeUsedAsAnExternalConfig() {
        val profile = ImportedProtocol.create(valid)
        assertEquals(profile.id, ImportedProtocol.parse(profile.encode()).id)
        assertEquals(Fixture.config().fingerprint(), ProtocolConfig.parse(profile.text).fingerprint())
        assertFalse(profile.toString().contains(Fixture.config().hmacSecret))
        assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse(profile.encode()) }
        val forged = Json.parseToJsonElement(profile.encode()).jsonObject.toMutableMap().apply { put("source", JsonPrimitive("BUNDLED")) }
        assertThrows(IllegalArgumentException::class.java) { ImportedProtocol.parse(JsonObject(forged).toString()) }
    }
    @Test fun rejectsCredentialsUrlsDuplicateAndEscapedDuplicateKeys() {
        for (extra in listOf("token", "server_url", "source")) {
            val text = valid.dropLast(1) + ",\"$extra\":\"do-not-print-this-value\"}"
            val error = assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse(text) }
            assertFalse(error.message.orEmpty().contains("do-not-print"))
        }
        for (key in listOf("hmac_access_key", "hmac_\\u0061ccess_key")) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolConfig.parse(valid.dropLast(1) + ",\"$key\":\"duplicate\"}") }
        }
    }
    @Test fun rejectsWrongTypesMalformedJsonAndOversizedUtf8BeforeSaving() {
        listOf("[]", "{}", valid + "false", valid.dropLast(1) + ",}", "{\"hmac_access_key\":{}}",
            "\u00e9".repeat(32_769)).forEach { text -> assertThrows(IllegalArgumentException::class.java) { ProtocolFile.parse(text) } }
        assertThrows(IllegalArgumentException::class.java) { ProtocolFile.utf8(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertEquals(valid, ProtocolFile.parse("\uFEFF$valid").toString())
    }
    @Test fun pausingCloudKeepsPersonalPreferencesAndCannotResumeAnOperation() {
        val old = AssistantState(guardEnabled = true, homeGuardEnabled = true, widgetSyncEnabled = true,
            preferences = ComfortPreferences(target = 24), parkingNote = "my parking note", operationPending = true,
            pendingBodyAction = BodyAction.HORN)
        val paused = CloudReset.pause(old)
        assertEquals(old.paused, paused.paused)
        assertFalse(paused.guardEnabled || paused.homeGuardEnabled || paused.widgetSyncEnabled || paused.operationPending)
        assertNull(paused.pendingBodyAction)
        assertEquals(old.preferences, paused.preferences)
        assertEquals(old.parkingNote, paused.parkingNote)
        assertEquals(CloudReset.message, paused.operationMessage)
        assertEquals(paused, CloudReset.pause(paused))
    }
}
