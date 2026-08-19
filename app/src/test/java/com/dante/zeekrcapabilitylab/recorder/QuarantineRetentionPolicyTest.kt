package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.QuarantineRetentionPolicy
import com.dante.zeekrcapabilitylab.service.recorder.QuarantinedEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class QuarantineRetentionPolicyTest {

    private val mb = 1024L * 1024L
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_000L * day

    private fun unit(name: String, bytes: Long, modified: Long) = QuarantinedEvidence(
        path = "/quarantine/$name",
        totalBytes = bytes,
        lastModifiedMs = modified,
    )

    @Test
    fun nothingIsDeletedWhileWithinEveryLimit() {
        val units = listOf(
            unit("a.partial", 100L * mb, now - day),
            unit("b.partial", 100L * mb, now - 2 * day),
        )
        assertEquals(emptyList<String>(), QuarantineRetentionPolicy.selectEvictions(units, now))
    }

    @Test
    fun oldestUnitsAreDeletedFirstWhenOverTheByteBudget() {
        val units = listOf(
            unit("old.partial", 200L * mb, now - 3 * day),
            unit("new.partial", 200L * mb, now - day),
            unit("mid.partial", 200L * mb, now - 2 * day),
        )
        assertEquals(
            listOf("/quarantine/old.partial"),
            QuarantineRetentionPolicy.selectEvictions(units, now, maxTotalBytes = 400L * mb),
        )
    }

    @Test
    fun deletionsAreReturnedOldestFirst() {
        val units = listOf(
            unit("d.partial", 200L * mb, now - 4 * day),
            unit("c.partial", 200L * mb, now - 3 * day),
            unit("b.partial", 200L * mb, now - 2 * day),
            unit("a.partial", 200L * mb, now - day),
        )
        assertEquals(
            listOf("/quarantine/d.partial", "/quarantine/c.partial"),
            QuarantineRetentionPolicy.selectEvictions(units, now, maxTotalBytes = 400L * mb),
        )
    }

    @Test
    fun smallerOlderUnitsAreKeptWhenTheyStillFitTheBudget() {
        val units = listOf(
            unit("new-big.partial", 300L * mb, now - day),
            unit("mid-big.partial", 300L * mb, now - 2 * day),
            unit("old-small.sidecar.json", 1L * mb, now - 3 * day),
        )
        assertEquals(
            listOf("/quarantine/mid-big.partial"),
            QuarantineRetentionPolicy.selectEvictions(units, now, maxTotalBytes = 320L * mb),
        )
    }

    @Test
    fun unitCountCapDeletesTheOldestBeyondTheCap() {
        val units = (1..5).map { unit("u$it.partial", 1L * mb, now - it * day) }
        assertEquals(
            listOf("/quarantine/u5.partial", "/quarantine/u4.partial"),
            QuarantineRetentionPolicy.selectEvictions(units, now, maxUnits = 3),
        )
    }

    @Test
    fun unitsOlderThanTheAgeLimitAreDeletedRegardlessOfBudget() {
        val units = listOf(
            unit("fresh.partial", 1L * mb, now - day),
            unit("stale.partial", 1L * mb, now - 40 * day),
        )
        assertEquals(
            listOf("/quarantine/stale.partial"),
            QuarantineRetentionPolicy.selectEvictions(units, now, maxAgeMs = 30 * day),
        )
    }

    @Test
    fun newestUnitAlwaysSurvivesEvenWhenItBreaksEveryLimit() {
        val oversizedAndOld = unit("only.partial", 900L * mb, now - 90 * day)
        assertEquals(
            emptyList<String>(),
            QuarantineRetentionPolicy.selectEvictions(listOf(oversizedAndOld), now),
        )
        // Once newer evidence exists, the oversized old unit becomes deletable.
        val withNewer = listOf(oversizedAndOld, unit("newer.partial", 1L * mb, now - day))
        assertEquals(
            listOf("/quarantine/only.partial"),
            QuarantineRetentionPolicy.selectEvictions(withNewer, now),
        )
    }
}
