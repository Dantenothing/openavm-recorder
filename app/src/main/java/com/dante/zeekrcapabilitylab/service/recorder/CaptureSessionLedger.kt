package com.dante.zeekrcapabilitylab.service.recorder

import java.util.Collections
import java.util.IdentityHashMap

/** Camera-thread confined. Keeps outputs alive while their old session still produces frames. */
class CaptureSessionLedger<S : Any, O : Any>(private val release: (O) -> Unit) {
    private class Entry<O : Any> {
        val sequences = mutableSetOf<Int>()
        val outputs: MutableSet<O> = Collections.newSetFromMap(IdentityHashMap())
        var closed = false
    }
    private val entries = IdentityHashMap<S, Entry<O>>()

    fun submitted(session: S, sequence: Int) {
        val entry = entries.getOrPut(session) { Entry() }
        check(!entry.closed) { "Capture submitted to a closed session" }
        entry.sequences.add(sequence)
    }

    fun pending(session: S?): Set<Int> = entries[session]?.sequences?.toSet().orEmpty()

    fun hasOtherInFlight(session: S?): Boolean =
        entries.any { (owner, entry) -> owner !== session && entry.sequences.isNotEmpty() }

    val retiredOutputCount: Int get() = entries.values.sumOf { it.outputs.size }

    fun retire(session: S, output: O) {
        entries.getOrPut(session) { Entry() }.outputs.add(output)
        releaseDrained(session)
    }

    fun sequenceEnded(session: S, sequence: Int) {
        entries[session]?.sequences?.remove(sequence)
        releaseDrained(session)
    }

    fun sessionClosed(session: S) {
        entries.getOrPut(session) { Entry() }.closed = true
        releaseDrained(session)
    }

    fun deviceClosed() {
        // Only the owning CameraDevice's onClosed can settle missing sequence callbacks.
        entries.values.forEach { entry -> entry.outputs.forEach(release) }
        entries.clear()
    }

    private fun releaseDrained(session: S) {
        val entry = entries[session] ?: return
        if (!entry.closed || entry.sequences.isNotEmpty()) return
        entry.outputs.forEach(release)
        entries.remove(session)
    }
}
