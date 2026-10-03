package com.dante.zeekrbridge.player

import kotlinx.coroutines.CancellationException

/** A completed null is a failure; absence of a result alone means loading. */
internal data class SessionLoadResult<S, T>(val selection: S, val attempt: Int, val data: T?) {
    fun matches(selected: S, requestedAttempt: Int): Boolean = selection == selected && attempt == requestedAttempt
}

internal suspend fun <S, T> loadSessionResult(selection: S, attempt: Int, load: suspend () -> T?): SessionLoadResult<S, T> {
    val data = try { load() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    return SessionLoadResult(selection, attempt, data)
}
