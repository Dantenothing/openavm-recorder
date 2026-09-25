package com.dante.zeekrbridge.core

import org.junit.Assert.*
import org.junit.Test

class PairingWindowTest {
    private var now = 100L
    private val window = PairingWindow({ now }, 1_000)
    @Test fun closedBlankAndExpiredCodesAreNeverAccepted() {
        assertEquals(PairingWindow.Result.REJECTED, window.attempt("", "a", true))
        window.open("123456")
        assertFalse(window.valid(""))
        now += 1_000
        assertFalse(window.valid("123456"))
        assertEquals(PairingWindow.Result.REJECTED, window.attempt("123456", "a", true))
    }
    @Test fun rotatingAddressesCannotBypassGlobalBudget() {
        window.open("123456")
        repeat(4) { assertEquals(PairingWindow.Result.REJECTED, window.attempt("000000", "source-$it", true)) }
        assertEquals(PairingWindow.Result.LIMITED, window.attempt("000000", "new-source", true))
        assertEquals(PairingWindow.Result.LIMITED, window.attempt("123456", "sixth", true))
        assertTrue(window.lockedOut)
        now += 10_000
        assertEquals(PairingWindow.Result.LIMITED, window.attempt("123456", "sixth", true))
        window.open("654321")
        assertEquals(PairingWindow.Result.ACCEPTED, window.attempt("654321", "sixth", true))
    }
    @Test fun individualSourceCannotGuessPastThreeFailures() {
        window.open("123456")
        repeat(3) { window.attempt("000000", "a", true) }
        assertEquals(PairingWindow.Result.LIMITED, window.attempt("123456", "a", true))
        assertEquals(PairingWindow.Result.ACCEPTED, window.attempt("123456", "b", true))
    }
    @Test fun malformedIdentityConsumesBudgetAndSuccessMustBeClosedByOwner() {
        window.open("123456")
        repeat(4) { window.attempt("123456", "a-$it", false) }
        assertEquals(PairingWindow.Result.LIMITED, window.attempt("123456", "a-5", false))
        window.open("654321")
        assertEquals(PairingWindow.Result.ACCEPTED, window.attempt("654321", "b", true))
        window.close()
        assertEquals(PairingWindow.Result.REJECTED, window.attempt("654321", "b", true))
    }
}
