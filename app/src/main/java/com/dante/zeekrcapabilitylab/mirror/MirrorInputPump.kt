package com.dante.zeekrcapabilitylab.mirror

/** Input-thread owner. Reading an unchanged texture must never renew its freshness. */
internal class MirrorInputPump(
    private val clock: () -> Long,
    private val acquireTimestamp: () -> Long,
    private val freshFrame: () -> Unit,
) {
    private val createdAt = clock()
    @Volatile var pollingEnabled = true
    @Volatile private var stopped = false
    @Volatile var notifications = 0L; private set
    @Volatile var frames = 0L; private set
    @Volatile var pollAttempts = 0L; private set
    @Volatile var polledFrames = 0L; private set
    @Volatile var lastNotificationAt: Long? = null; private set
    @Volatile var lastPollAt: Long? = null; private set
    @Volatile var lastFrameAt: Long? = null; private set
    @Volatile var timestamp = 0L; private set

    fun notification() {
        if (stopped) return
        notifications++
        lastNotificationAt = clock()
        acquire(false)
    }

    fun heartbeat() {
        val now = clock()
        if (!needsFallback(now)) return
        if (lastPollAt?.let { now < it || now - it < 33 } == true) return
        lastPollAt = now
        pollAttempts++
        acquire(true)
    }

    fun heartbeatDelayMs(): Long = if (needsFallback(clock())) 33L else 250L
    fun stop() { stopped = true }

    private fun needsFallback(now: Long): Boolean {
        val notified = lastNotificationAt ?: createdAt
        return !stopped && pollingEnabled && now >= notified && now - notified >= 250
    }

    private fun acquire(fromPoll: Boolean) {
        val next = acquireTimestamp()
        if (next <= 0 || next == timestamp) return
        timestamp = next
        lastFrameAt = clock()
        frames++
        if (fromPoll) polledFrames++
        freshFrame()
    }
}
