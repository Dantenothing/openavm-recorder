package com.dante.zeekrcapabilitylab.mirror

import android.hardware.camera2.CaptureRequest

/** Read the submitted request object only; no camera query or request changes. */
internal object PreviewRequestFacts {
    fun read(request: CaptureRequest): Map<String, String> = buildMap {
        fun value(name: String, key: CaptureRequest.Key<Int>) {
            runCatching { request.get(key) }.getOrNull()?.let { put(name, it.toString()) }
        }
        value("controlMode", CaptureRequest.CONTROL_MODE)
        value("captureIntent", CaptureRequest.CONTROL_CAPTURE_INTENT)
        value("aeMode", CaptureRequest.CONTROL_AE_MODE)
        value("afMode", CaptureRequest.CONTROL_AF_MODE)
        value("awbMode", CaptureRequest.CONTROL_AWB_MODE)
        value("videoStabilizationMode", CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
        runCatching { request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) }.getOrNull()?.let {
            put("aeFpsMin", it.lower.toString()); put("aeFpsMax", it.upper.toString())
        }
    }
}
