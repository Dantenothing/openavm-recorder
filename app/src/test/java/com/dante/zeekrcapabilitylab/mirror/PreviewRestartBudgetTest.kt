package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class PreviewRestartBudgetTest {
    @Test fun noFramesAndStaleFramesShareOneBudget() {
        val budget = PreviewRestartBudget()
        assertTrue(budget.request("PREVIEW_FIRST_FRAME_TIMEOUT", true))
        assertFalse(budget.request("PREVIEW_SOURCE_STALLED", true))
        assertFalse(budget.request("PREVIEW_FIRST_FRAME_TIMEOUT", true))
        budget.newRequest()
        assertTrue(budget.request("PREVIEW_SOURCE_STALLED", true))
    }
    @Test fun cleanupErrorsAndRevokedIntentNeverAuthorizeACameraOpen() {
        val budget = PreviewRestartBudget()
        for (reason in listOf("CLEANUP_UNCONFIRMED", "CAMERA_ERROR_3", "GL_INPUT_THREAD_STALLED", "DISABLED"))
            assertFalse(budget.request(reason, true))
        assertFalse(budget.request("PREVIEW_FIRST_FRAME_TIMEOUT", false))
    }
}
