package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget

/** Optional test ownership adapter. Null preserves the ordinary product behaviour. */
interface RecorderDiagnosticScope {
    val target: UsbExportTarget
    fun admit(incomingBytes: Long)
    fun opened(output: UsbMediaStoreRecordingOutputHandle)
    /** Called only after recorder/output close and the product commit have returned. */
    fun committed(output: UsbMediaStoreRecordingOutputHandle)
    fun event(name: String, segment: Int, generation: Long, revision: Long)
}
