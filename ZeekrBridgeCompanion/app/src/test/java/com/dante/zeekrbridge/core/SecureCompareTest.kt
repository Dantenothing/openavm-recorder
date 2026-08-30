package com.dante.zeekrbridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCompareTest {

    @Test
    fun equalValuesMatch() {
        assertTrue(SecureCompare.equals("car-1", "car-1"))
        assertTrue(SecureCompare.equals("", ""))
    }

    @Test
    fun differentValuesDoNotMatch() {
        assertFalse(SecureCompare.equals("car-1", "car-2"))
        assertFalse(SecureCompare.equals("car-1", "car-1x"))
    }

    @Test
    fun nullNeverMatches() {
        assertFalse(SecureCompare.equals(null, "car-1"))
        assertFalse(SecureCompare.equals("car-1", null))
        assertFalse(SecureCompare.equals(null, null))
    }
}
