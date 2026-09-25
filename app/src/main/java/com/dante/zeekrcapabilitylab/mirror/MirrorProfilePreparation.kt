package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal sealed interface MirrorProfileResult {
    data class Ready(val config: RecorderConfig) : MirrorProfileResult
    data class Failed(val reason: String) : MirrorProfileResult
    data object Cancelled : MirrorProfileResult
}

/** Bounded wake preparation: poll metadata only, then hand off one validated immutable config. */
internal object MirrorProfilePreparation {
    suspend fun prepare(role: RecordingSourceRole, waitForReady: Boolean,
        now: () -> Long, allowed: () -> Boolean, lookup: suspend () -> RecorderConfig?,
        onProbe: (attempt: Int, elapsedMs: Long, state: String) -> Unit = { _, _, _ -> },
    ): MirrorProfileResult {
        val started = now()
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) return MirrorProfileResult.Cancelled
            val elapsed = now() - started
            if (elapsed < 0 || waitForReady && elapsed >= 15_000) {
                onProbe(attempt, elapsed.coerceAtLeast(0), "TIMED_OUT")
                return MirrorProfileResult.Failed("NO_${role}_PROFILE_AFTER_WAKE")
            }
            attempt++
            val result = try { lookup() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    onProbe(attempt, now() - started, "QUERY_FAILED")
                    return MirrorProfileResult.Failed("${role}_PROFILE_QUERY_FAILED")
                }
            currentCoroutineContext().ensureActive()
            if (!allowed()) return MirrorProfileResult.Cancelled
            val afterRead = now() - started
            if (afterRead < 0 || waitForReady && afterRead >= 15_000) {
                onProbe(attempt, afterRead.coerceAtLeast(0), "TIMED_OUT")
                return MirrorProfileResult.Failed("NO_${role}_PROFILE_AFTER_WAKE")
            }
            if (result != null) {
                if (result.source.sourceRole != role || result.validate().isNotEmpty()) {
                    onProbe(attempt, afterRead, "INVALID_CONFIG")
                    return MirrorProfileResult.Failed("INVALID_${role}_CONFIG")
                }
                onProbe(attempt, afterRead, "READY")
                return MirrorProfileResult.Ready(result)
            }
            if (!waitForReady) return MirrorProfileResult.Failed("NO_${role}_PROFILE")
            onProbe(attempt, afterRead, "WAITING")
            delay(minOf(750, 15_000 - afterRead))
        }
    }
}
