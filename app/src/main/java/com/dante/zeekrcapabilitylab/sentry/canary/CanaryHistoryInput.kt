package com.dante.zeekrcapabilitylab.sentry.canary

import com.dante.zeekrcapabilitylab.sentry.BoundedGopQueue
import com.dante.zeekrcapabilitylab.sentry.EncodedGop

/** Takes ownership of already-pinned history; only newly arriving GOPs use the live backlog. */
class CanaryHistoryInput(pinned: List<EncodedGop>, backlogCapacity: Int = 32) : AutoCloseable {
    private val history = ArrayDeque(pinned)
    private val live = BoundedGopQueue(backlogCapacity)

    fun offer(gop: EncodedGop) = live.offer(gop)
    // History and close are owned by the single writer; live.offer may run on the producer.
    fun poll(timeoutMs: Long = 100): EncodedGop? = history.removeFirstOrNull() ?: live.poll(timeoutMs)
    override fun close() {
        while (history.isNotEmpty()) history.removeFirst().release()
        live.close()
    }
}
