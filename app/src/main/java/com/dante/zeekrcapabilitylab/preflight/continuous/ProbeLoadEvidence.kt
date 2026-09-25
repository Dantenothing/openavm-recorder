package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.preflight.obj

internal object ProbeLoadEvidence {
    enum class Group { TARGET, HIGH }
    fun measure(group: Group, requestedBps: Int, bytes: Long, actualMediaUs: Long, observedNs: Long?, complete: Boolean) = run {
        val media=if(actualMediaUs>0)bytes*8.0*1_000_000/actualMediaUs else null
        val wall=observedNs?.takeIf { it>0 }?.let { bytes*8.0*1e9/it }
        val lower=if(group==Group.TARGET).8 else 1.5
        val upper=if(group==Group.TARGET)1.2 else null
        val reason=when {
            !complete -> "SUITE_ENCODING_INCOMPLETE"
            media==null || wall==null -> "MEASUREMENT_UNAVAILABLE"
            media<requestedBps*lower || wall<requestedBps*lower -> "LOAD_BELOW_GROUP_MINIMUM"
            upper!=null && media>requestedBps*upper -> "LOAD_ABOVE_TARGET_RANGE"
            else -> null
        }
        obj("group" to group.name,"status" to if(!complete)"INCOMPLETE" else if(reason==null)"PASS" else "WARN",
            "reason" to reason,"requestedBps" to requestedBps,"actualPayloadBps" to media,"wallPayloadBps" to wall,
            "actualInputMediaDurationUs" to actualMediaUs,"observedRunMs" to observedNs?.div(1e6),
            "minimumTargetFraction" to lower,"maximumTargetFraction" to upper,
            "wallMeasurementScope" to "FIRST_SOURCE_SUBMISSION_THROUGH_CODEC_AND_USB_FILE_CLOSE",
            "includesContainerBytes" to false,"pattern" to "DYNAMIC_8PX_TILES_WITH_FIXED_MARKER_COLOR_PATCHES",
            "resolutionOrFrameRateReduced" to false)
    }
}
