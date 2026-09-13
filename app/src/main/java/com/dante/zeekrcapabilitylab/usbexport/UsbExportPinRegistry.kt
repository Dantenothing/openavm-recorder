package com.dante.zeekrcapabilitylab.usbexport

import com.dante.zeekrcapabilitylab.service.recorder.RecorderStorageLock
import java.io.File

object UsbExportPinRegistry {
    private val pathsByOperation = mutableMapOf<String, Set<String>>()

    fun acquire(operationId: String, files: List<File>) = synchronized(RecorderStorageLock.lock) {
        pathsByOperation[operationId] = files.mapTo(linkedSetOf(), ::stablePath)
    }

    fun release(operationId: String) = synchronized(RecorderStorageLock.lock) {
        pathsByOperation.remove(operationId)
    }

    fun isPinned(file: File): Boolean = isPinned(file.absolutePath)

    fun isPinned(path: String): Boolean = synchronized(RecorderStorageLock.lock) {
        val stable = stablePath(File(path))
        pathsByOperation.values.any { stable in it }
    }

    fun restore(active: Map<String, List<String>>) = synchronized(RecorderStorageLock.lock) {
        pathsByOperation.clear()
        active.forEach { (operationId, paths) ->
            pathsByOperation[operationId] = paths.mapTo(linkedSetOf()) { stablePath(File(it)) }
        }
    }

    private fun stablePath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}

