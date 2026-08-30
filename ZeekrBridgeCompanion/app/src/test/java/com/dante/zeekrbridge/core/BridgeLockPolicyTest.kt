package com.dante.zeekrbridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLockPolicyTest {

    @Test
    fun repeatedWakeAcquireIsIdempotent() {
        val policy = BridgeLockPolicy()
        assertTrue(policy.acquireWake())
        assertTrue(policy.wakeHeld)
        assertFalse(policy.acquireWake())
        assertTrue(policy.wakeHeld)
    }

    @Test
    fun wifiAcquireIsIndependentOfWakeState() {
        val policy = BridgeLockPolicy()
        // Service started before Wi-Fi was available: only wake held.
        assertTrue(policy.acquireWake())
        assertFalse(policy.wifiHeld)
        // Later (or repeated onStartCommand): Wi-Fi lock can still be picked up.
        assertTrue(policy.acquireWifi())
        assertTrue(policy.wakeHeld)
        assertTrue(policy.wifiHeld)
        assertFalse(policy.acquireWifi())
        assertTrue(policy.wifiHeld)
    }

    @Test
    fun releaseAllClearsBothLocks() {
        val policy = BridgeLockPolicy()
        policy.acquireWake()
        policy.acquireWifi()
        policy.releaseAll()
        assertFalse(policy.wakeHeld)
        assertFalse(policy.wifiHeld)
        assertFalse(policy.isHeld())
        policy.releaseAll()
        assertFalse(policy.isHeld())
    }

    @Test
    fun wifiLockHeldWheneverWifiManagerAvailable() {
        assertTrue(BridgeLockRules.holdWifiLock(true))
        assertFalse(BridgeLockRules.holdWifiLock(false))
    }
}
