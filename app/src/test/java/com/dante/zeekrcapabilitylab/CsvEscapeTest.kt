package com.dante.zeekrcapabilitylab

import com.dante.zeekrcapabilitylab.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvEscapeTest {

    @Test
    fun plainValueIsUnchanged() {
        assertEquals("hello", Utils.csvEscape("hello"))
    }

    @Test
    fun commaAndQuotesAreQuoted() {
        assertEquals("\"a,\"\"b\"\"\"", Utils.csvEscape("a,\"b\""))
    }

    @Test
    fun newlinesAreQuoted() {
        assertEquals("\"line1\nline2\"", Utils.csvEscape("line1\nline2"))
    }

    @Test
    fun nullBecomesEmpty() {
        assertEquals("", Utils.csvEscape(null))
    }
}
