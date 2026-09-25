package com.dante.zeekrbridge.core

import java.io.File

/**
 * Pure helpers for the received directory. Stale `.partial` files from a crash
 * must never be displayed as received files, and startup cleanup only removes
 * partials whose base name is a valid protocol file name.
 */
object ReceivedFiles {
    fun visibleFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".partial") }
            .sortedBy { it.absolutePath }
            .toList()

    fun cleanupStalePartials(root: File) {
        root.walkTopDown()
            .filter {
                it.isFile &&
                    it.name.endsWith(".partial") &&
                    PathSafety.cleanFileName(it.name.removeSuffix(".partial")) != null
            }
            .forEach { it.delete() }
    }

    fun relatedMetadataFiles(video: File): List<File> = MediaIndexScanner.sidecarCandidates(video).filter(File::isFile)
}
