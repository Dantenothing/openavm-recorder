package com.dante.zeekrbridge.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Retain rollback ownership until the IO result has actually reached its caller. */
internal suspend fun <T : Any> withOwnedExportOutput(
    operation: suspend (own: (T) -> Unit) -> T,
    cleanup: suspend (T) -> Unit,
): T {
    var owned: T? = null
    try {
        return operation { value -> check(owned == null); owned = value }
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            owned?.let { value -> runCatching { cleanup(value) }.exceptionOrNull()?.let(error::addSuppressed) }
        }
        throw error
    }
}
