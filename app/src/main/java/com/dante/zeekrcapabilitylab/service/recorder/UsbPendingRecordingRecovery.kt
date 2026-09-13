package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context

/** Stable entry point kept separate from the compatibility implementation. */
object UsbPendingRecordingRecovery {
    fun recoverMounted(context: Context) {
        UsbPendingRecordingRecoveryEngine.recoverMounted(context)
    }
}
