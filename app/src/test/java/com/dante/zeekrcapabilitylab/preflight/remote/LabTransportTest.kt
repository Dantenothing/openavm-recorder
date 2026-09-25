package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LabTransportTest {
    @Test fun totalPollBudgetIncludesUtf8StatusAndEvents() {
        val status=obj("large" to "图".repeat(8000))
        val events=JsonArray((1..24).map {obj("seq" to it,"text" to "x".repeat(1900))})
        val body=LabTransport.poll(status,events)
        assertTrue(body.toString().toByteArray().size<=32768)
        assertTrue(body.getValue("events").jsonArray.size in 1..5)
        assertEquals(1L,body.getValue("events").jsonArray.first().jsonObject.number("seq"))
    }
    @Test fun oversizeStatusIsRejectedInsteadOfDroppingIdentity() {
        assertThrows(IllegalArgumentException::class.java) {LabTransport.poll(obj("x" to "x".repeat(24576)),JsonArray(emptyList()))}
    }
}
