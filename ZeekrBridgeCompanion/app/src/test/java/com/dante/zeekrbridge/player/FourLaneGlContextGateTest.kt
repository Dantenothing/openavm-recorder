package com.dante.zeekrbridge.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FourLaneGlContextGateTest {

    @Test
    fun requestQueuedBeforeGlContextWaitsForSurfaceCreated() {
        val gate = FourLaneGlContextGate()

        assertFalse(gate.requestTextureCreation())
        assertTrue(gate.onGlContextCreated())

        gate.onTextureCreated()
        assertFalse(gate.onGlContextCreated())
    }

    @Test
    fun requestAfterGlContextCanCreateImmediately() {
        val gate = FourLaneGlContextGate()

        assertFalse(gate.onGlContextCreated())
        assertTrue(gate.requestTextureCreation())
    }
}
