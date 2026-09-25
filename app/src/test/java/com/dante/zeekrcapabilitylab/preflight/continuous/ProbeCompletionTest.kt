package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class ProbeCompletionTest {
    @Test fun validVideoDoesNotHideRetainedFilesOrInsufficientActualWriteLoad() {
        assertEquals("WARN", ProbeCompletion.status(true, true, 8))
        assertEquals("WARN", ProbeCompletion.status(true, true, 0, false))
        assertEquals("PASS", ProbeCompletion.status(true, true, 0))
        assertEquals("FAIL", ProbeCompletion.status(false, true, 0))
    }
    @Test fun providerMessagesCannotLeakIntoSharedReports() {
        assertEquals("DELETE_QUERY_FAILED", ProbeCompletion.deletionReason("DELETE_QUERY_FAILED: content://private/name"))
        assertEquals("OWNERSHIP_MISMATCH", ProbeCompletion.deletionReason("OWNERSHIP_MISMATCH:OWNER,NAME"))
        assertEquals("DELETE_UNCONFIRMED", ProbeCompletion.deletionReason("/private/path"))
    }
}
