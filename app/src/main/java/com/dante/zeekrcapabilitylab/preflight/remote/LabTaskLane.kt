package com.dante.zeekrcapabilitylab.preflight.remote

import java.util.concurrent.Executor

/** Owned by the control executor. Generation changes fence callbacks from a former session. */
internal class LabTaskLane(private val control: Executor, private val network: Executor) {
    private var generation = 0L
    var busy = false
        private set
    fun invalidate() { generation++; busy = false }
    fun <T> submit(work: () -> T, complete: (Result<T>) -> Unit): Boolean {
        if (busy) return false
        busy = true
        val ticket = generation
        try { network.execute {
            val result = runCatching(work)
            runCatching { control.execute {
                if (ticket == generation) { busy = false; complete(result) }
            } }
        } } catch(t:RuntimeException) {busy=false;complete(Result.failure(t))}
        return true
    }
}
