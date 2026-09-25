package com.dante.zeekrbridge.core

/** Memory-only, monotonic-time window. Its owner serializes all access. */
class PairingWindow(private val now: () -> Long, private val ttlMs: Long = Protocol.PAIR_CODE_TTL_MS) {
    enum class Result { ACCEPTED, REJECTED, LIMITED }
    private var code = ""
    private var deadline = 0L
    private var failures = 0
    private var exhausted = false
    private val sources = mutableMapOf<String, Int>()

    fun open(value: String) {
        require(value.matches(Regex("[0-9]{6}")))
        close()
        code = value
        deadline = now() + ttlMs
    }

    fun close() {
        code = ""
        deadline = 0
        failures = 0
        exhausted = false
        sources.clear()
    }

    fun valid(value: String): Boolean = code.isNotEmpty() && now() < deadline &&
        SecureCompare.equals(code, value)

    fun attempt(value: String, source: String, validIdentity: Boolean): Result {
        if (exhausted) return Result.LIMITED
        if (code.isEmpty() || now() >= deadline) {
            close()
            return Result.REJECTED
        }
        // A source already at its limit also consumes the global budget. Rotating
        // addresses or supplying malformed identities cannot bypass the window cap.
        val sourceFailures = sources[source] ?: 0
        if (sourceFailures >= 3) return failure(source, limited = true)
        if (!validIdentity || !valid(value)) return failure(source, limited = false)
        return Result.ACCEPTED
    }

    private fun failure(source: String, limited: Boolean): Result {
        failures++
        sources[source] = (sources[source] ?: 0) + 1
        if (failures >= 5) {
            exhausted = true
            code = ""
            return Result.LIMITED
        }
        return if (limited) Result.LIMITED else Result.REJECTED
    }

    val lockedOut: Boolean get() = exhausted
}
