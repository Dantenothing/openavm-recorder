package com.dante.zeekrbridge.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Rebuildable derived index. MP4 files and their sidecars remain the source of truth. */
object MediaIndexStore {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(MediaIndexSnapshot())
    val snapshot: StateFlow<MediaIndexSnapshot> = _snapshot.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private var lastFingerprint = ""

    suspend fun refresh(files: Collection<File>, force: Boolean = false) = mutex.withLock {
        val fingerprint = fingerprint(files)
        if (!force && fingerprint == lastFingerprint) return
        _scanning.value = true
        try {
            val next = withContext(Dispatchers.IO) { MediaIndexScanner.scan(files) }
            _snapshot.value = next
            lastFingerprint = fingerprint
        } finally {
            _scanning.value = false
        }
    }

    fun invalidate() {
        lastFingerprint = ""
    }

    private fun fingerprint(files: Collection<File>): String = files.asSequence()
        .filter(File::isFile)
        .sortedBy { it.absolutePath }
        .joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
}
