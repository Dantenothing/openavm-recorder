package com.dante.zeekrcapabilitylab.sentry.canary

import android.os.Build
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.dante.zeekrcapabilitylab.sentry.EncodedBufferPool

/** Anonymous RAM, not a disk-backed rolling file and not a large Java byte array. */
object CanaryNativeMemory {
    fun create(): EncodedBufferPool {
        return if (Build.VERSION.SDK_INT >= 27) createSupported() else error("NATIVE_RAM_REQUIRES_API_27")
    }

    @RequiresApi(27)
    private fun createSupported(): EncodedBufferPool {
        val shared = SharedMemory.create("OpenAVM-Sentry", EncodedBufferPool.CANARY_CAPACITY_BYTES)
        try {
            val mapped = shared.mapReadWrite()
            try {
                return EncodedBufferPool.mapped(mapped) { SharedMemory.unmap(mapped) }
            } catch (error: Throwable) {
                SharedMemory.unmap(mapped)
                throw error
            }
        } finally {
            // Closing the descriptor leaves the mapping valid until all leases are released.
            shared.close()
        }
    }
}
