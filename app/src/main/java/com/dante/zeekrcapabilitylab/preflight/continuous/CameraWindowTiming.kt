package com.dante.zeekrcapabilitylab.preflight.continuous

/** A previously good average does not certify a window which has stopped producing fresh images. */
internal data class CameraWindowTiming(val drawFps:Double,val updateFps:Double,
    val maxDrawGapMs:Long,val maxUpdateGapMs:Long,val drawAgeMs:Long?,val updateAgeMs:Long?) {
    fun healthy(requireFresh:Boolean)=drawFps>=24.0 && updateFps>=24.0 && maxDrawGapMs<200 && maxUpdateGapMs<200 &&
        (!requireFresh || drawAgeMs in 0L..199L && updateAgeMs in 0L..199L)
}
