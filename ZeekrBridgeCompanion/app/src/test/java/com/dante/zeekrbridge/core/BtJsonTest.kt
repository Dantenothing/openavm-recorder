package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BtJsonTest {

    @Test
    fun replyEchoesRequestId() {
        val reply = BtJson.reply("HELLO_ACK", "req-7", mapOf("ok" to "true"))
        assertEquals("HELLO_ACK", reply["type"]?.toString()?.trim('"'))
        assertEquals("req-7", reply["requestId"]?.toString()?.trim('"'))
        assertEquals("true", reply["ok"]?.toString()?.trim('"'))
    }

    @Test
    fun replyWithoutRequestIdOmitsField() {
        val reply = BtJson.reply("HEARTBEAT_ACK", null, mapOf("ok" to "true"))
        assertNull(reply["requestId"])
    }

    @Test
    fun normalizeBearerWrapsOrKeepsPrefix() {
        assertEquals("Bearer abc", BtJson.normalizeBearer("abc"))
        assertEquals("Bearer abc", BtJson.normalizeBearer("Bearer abc"))
        assertNull(BtJson.normalizeBearer(null))
        assertNull(BtJson.normalizeBearer("   "))
    }
}
