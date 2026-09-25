package com.dante.zeekrcheck.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares concurrent reads, never cached results or vehicle writes. The owner's cancellation ends the read. */
class ReadCoalescer<K, V> {
    private val mutex = Mutex()
    private val active = mutableMapOf<K, CompletableDeferred<V>>()
    suspend fun read(key: K, request: suspend () -> V): V {
        var owner = false
        val result = mutex.withLock {
            active[key] ?: CompletableDeferred<V>().also { active[key] = it; owner = true }
        }
        if (!owner) return result.await()
        try {
            return request().also { result.complete(it) }
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock { if (active[key] === result) active.remove(key) }
            }
        }
    }
}

/** A manual refresh queued behind a passive read must still run its online phase. */
class OverviewReadCoordinator<V> {
    private val reads = ReadCoalescer<Pair<String, Boolean>, V>()
    private val serial = Mutex()
    suspend fun read(key: String, manual: Boolean, request: suspend () -> V): V =
        reads.read(key to manual) { serial.withLock { request() } }
}
