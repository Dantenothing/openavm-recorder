package com.dante.zeekrcapabilitylab.sentry

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Single-writer queue. offer never waits; retained samples stay charged to the shared pool. */
class BoundedGopQueue(capacity: Int = 20) : AutoCloseable {
    init { require(capacity in 1..64) }
    private val queue = ArrayBlockingQueue<EncodedGop>(capacity)
    private var closed = false
    @Synchronized fun offer(gop: EncodedGop): Boolean {
        if (closed) return false
        val retained = gop.retain()
        if (queue.offer(retained)) return true
        retained.release()
        return false
    }
    fun poll(timeoutMs: Long = 100): EncodedGop? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    val size get() = queue.size
    @Synchronized override fun close() {
        closed = true
        while (true) (queue.poll() ?: break).release()
    }
}
