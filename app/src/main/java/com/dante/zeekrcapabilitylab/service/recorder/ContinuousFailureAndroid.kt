package com.dante.zeekrcapabilitylab.service.recorder

import android.media.MediaCodec
import android.os.SystemClock
import android.system.ErrnoException
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.Severity
import com.dante.zeekrcapabilitylab.diagnostic.AwayReportEvidence
import com.dante.zeekrcapabilitylab.event.EventLogger

internal object ContinuousFailureAndroid {
    fun forSession(sessionId: String) = ContinuousFailureCapture(sessionId, SystemClock::elapsedRealtime,
        System::currentTimeMillis, ::numbers, onCaptured = { evidence ->
            // WARN records evidence without changing the UI's last error or the existing stop path.
            EventLogger.logEvent(Categories.SYSTEM, AwayReportEvidence.FIRST_CONTINUOUS_FAILURE,
                severity = Severity.WARN, payload = evidence.facts())
        })

    /** Bounded cause walk, typed fields only. Never use diagnosticInfo/message/stack trace as upload data. */
    private fun numbers(error: Throwable): ContinuousFailureNumbers {
        var cause: Throwable? = error
        repeat(4) { depth ->
            when (val current = cause) {
                is MediaCodec.CodecException -> return ContinuousFailureNumbers(codecErrorCode = current.errorCode,
                    codecRecoverable = current.isRecoverable, codecTransient = current.isTransient,
                    sourceType = current.javaClass.name, causeDepth = depth)
                is ErrnoException -> return ContinuousFailureNumbers(errno = current.errno,
                    sourceType = current.javaClass.name, causeDepth = depth)
            }
            cause = cause?.cause
        }
        return ContinuousFailureNumbers()
    }
}
