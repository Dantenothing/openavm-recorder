package com.dante.zeekrcheck.core

import okhttp3.Call
import okhttp3.Callback
import java.util.concurrent.CancellationException

/** Serializes revocation with enqueue, including calls waiting in OkHttp's dispatcher. */
class CloudRequestGate {
    private var generation = 0L
    private var enabled = false
    private val calls = mutableSetOf<Call>()
    @Synchronized fun open() { revoke(); enabled = true }
    @Synchronized fun revoke() {
        enabled = false; generation++
        calls.toList().forEach(Call::cancel); calls.clear()
    }
    @Synchronized fun permit() = Permit(this, generation)
    @Synchronized private fun enqueue(epoch: Long, call: Call, callback: Callback) {
        if (!enabled || generation != epoch) throw CancellationException("Cloud configuration changed")
        calls.add(call)
        try { call.enqueue(callback) } catch (e: Exception) { calls.remove(call); throw e }
    }
    @Synchronized private fun finished(call: Call) { calls.remove(call) }
    class Permit internal constructor(private val gate: CloudRequestGate, private val epoch: Long) {
        fun enqueue(call: Call, callback: Callback) = gate.enqueue(epoch, call, callback)
        fun finished(call: Call) = gate.finished(call)
    }
    companion object { fun denied() = CloudRequestGate().permit() }
}
