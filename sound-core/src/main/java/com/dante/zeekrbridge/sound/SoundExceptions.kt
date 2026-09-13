package com.dante.zeekrbridge.sound

/** Shared cancellation and stable error codes; each application owns its translations. */
class SoundCancellation {
    @Volatile var cancelled = false
        private set
    fun cancel() { cancelled = true }
    fun check() { if (cancelled) throw SoundCancelledException() }
}

class SoundCancelledException : Exception("转换已取消")
class SoundInputException(message: String, val code: String = "INPUT_ERROR", cause: Throwable? = null) : Exception(message, cause)
class SoundIoException(message: String, val code: String = "IO_ERROR", cause: Throwable? = null) : Exception(message, cause)
