package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePolicy

/** A drag proposes a rate; only release can yield one command, cancellation yields none. */
internal class MirrorRateGesture(private val initial: Int, private val stepDp: Float = 20f) {
    private val values = TimeLapsePolicy.MULTIPLIERS
    private val start = values.indexOf(initial).also { require(it >= 0) }
    var value = initial; private set
    private var ended = false
    init { require(stepDp > 0 && stepDp.isFinite()) }
    fun move(upwardDp: Float): Int {
        if (!ended && upwardDp.isFinite()) {
            val steps = (upwardDp / stepDp).coerceIn(-values.size.toFloat(), values.size.toFloat()).toInt()
            value = values[(start + steps).coerceIn(0, values.lastIndex)]
        }
        return value
    }
    fun finish(): Int? {
        if (ended) return null
        ended = true
        return value.takeIf { it != initial }
    }
    fun cancel() { ended = true; value = initial }
}
