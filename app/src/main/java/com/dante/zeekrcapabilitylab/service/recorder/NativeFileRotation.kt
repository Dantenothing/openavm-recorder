package com.dante.zeekrcapabilitylab.service.recorder

/** Native recorder ownership. A queued file can contain video even if its callback arrives after Stop. */
internal class NativeFileRotation<T>(first: T, private val limit: Int = 256) {
    val owned = mutableListOf(first)
    var current: T = first
        private set
    var queued: T? = null
        private set
    var preparing = false
        private set
    var stopping = false
        private set
    var switches = 0
        private set

    fun approaching(): Boolean {
        if (stopping || preparing || queued != null || owned.size >= limit) return false
        preparing = true
        return true
    }
    fun offer(next: T): Boolean {
        if (stopping || !preparing || queued != null || next in owned) return false
        preparing = false
        queued = next
        owned += next
        return true
    }
    fun switched(): Pair<T, T>? {
        if (stopping) return null
        val next = queued ?: return null
        val old = current
        current = next
        queued = null
        switches++
        return old to next
    }
    fun stop() { stopping = true }
}

internal object NativeFileRotationPolicy {
    fun maxBytes(bitrate: Int, seconds: Int): Long {
        require(bitrate > 0 && seconds in 1..180)
        return bitrate.toLong() * seconds / 8
    }
    const val MAX_FILES = 256
    const val FILE_WATCHDOG_MS = 180_000L
    const val HANDOFF_WATCHDOG_MS = 15_000L
}

internal object NativePendingRecoveryPolicy {
    enum class Action { RETAIN, RECOVER, REMOVE_EMPTY }
    fun decide(sameProcess: Boolean, bytes: Long?, onlyVideo: Boolean): Action = when {
        sameProcess -> Action.RETAIN
        bytes == 0L && onlyVideo -> Action.REMOVE_EMPTY
        bytes != null && bytes > 0L && onlyVideo -> Action.RECOVER
        else -> Action.RETAIN
    }
}
