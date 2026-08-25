package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRetentionPolicyTest {
    @Test
    fun retentionDefaultsToEightHoursAndAcceptsOnlyOfferedValues() {
        assertEquals(8, SettingsStore.DEFAULT_RETENTION_HOURS)
        assertTrue(SettingsStore.DEFAULT_RETENTION_HOURS in SettingsStore.RETENTION_HOURS_OPTIONS)
        assertEquals(8, SettingsStore.sanitizeRetentionHours(-1))
        assertEquals(8, SettingsStore.sanitizeRetentionHours(7))
        assertEquals(12, SettingsStore.sanitizeRetentionHours(12))
    }
}
