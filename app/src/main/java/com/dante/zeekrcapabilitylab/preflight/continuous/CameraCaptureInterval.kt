package com.dante.zeekrcapabilitylab.preflight.continuous

internal data class CameraCaptureSample(val frame:Long,val timestampNs:Long)

/** Classify native failures by frame identity, even when callbacks arrive after stop was requested. */
internal data class CameraCaptureInterval(
    val firstFrame:Long?,val lastFrame:Long?,val callbacks:Int,val sensorFramesNotAcquired:Int,
    val acquiredWithoutResult:Int,val failures:Int?,val lostBuffers:Int?,val unattributableFailures:Int,
    val maximumSensorGapMs:Double?
) {
    val confirmed:Boolean get()=firstFrame!=null && lastFrame!=null && firstFrame<=lastFrame
    companion object {
        fun inspect(samples:List<CameraCaptureSample>,acquired:List<Long>,failed:List<Long>,lost:List<Long>):CameraCaptureInterval {
            val selected=if(acquired.isEmpty())emptyList() else samples.filter {it.timestampNs in acquired.first()..acquired.last()}
            val known=selected.map {it.timestampNs}.toHashSet();val admitted=acquired.toHashSet()
            val first=acquired.firstOrNull()?.let {stamp->samples.firstOrNull {it.timestampNs==stamp}?.frame}
            val last=acquired.lastOrNull()?.let {stamp->samples.lastOrNull {it.timestampNs==stamp}?.frame}
            val range=if(first!=null && last!=null && first>=0 && last>=first)first..last else null
            return CameraCaptureInterval(first?.takeIf {range!=null},last?.takeIf {range!=null},selected.size,
                selected.count {it.timestampNs !in admitted},acquired.count {it !in known},
                range?.let {r->failed.count {it in r}},range?.let {r->lost.count {it in r}},
                failed.count {it<0}+lost.count {it<0},selected.zipWithNext {a,b->(b.timestampNs-a.timestampNs)/1e6}.maxOrNull())
        }
    }
}
