package com.dante.zeekrcheck.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

interface SessionStorage {
    fun load(): SavedSession?
    fun save(session: SavedSession)
    fun clear()
}

/** Serializes disk writes and logout. A callback from an old account cannot resurrect its session. */
class SessionPersistence(private val storage: SessionStorage) {
    private val lock = Mutex()
    private val version = AtomicInteger()
    private val changes = AtomicInteger()
    val renewalMutex = Mutex()
    fun revision() = changes.get()
    fun advance(): Int { changes.incrementAndGet(); return version.incrementAndGet() }
    fun current() = version.get()
    suspend fun load(): SavedSession? = lock.withLock { withContext(Dispatchers.IO) { storage.load() } }
    suspend fun write(epoch: Int, session: SavedSession?): Boolean = withContext(NonCancellable) {
        lock.withLock {
            if (epoch != version.get()) return@withLock false
            withContext(Dispatchers.IO) { if (session == null) storage.clear() else storage.save(session) }
            changes.incrementAndGet()
            epoch == version.get()
        }
    }
}
