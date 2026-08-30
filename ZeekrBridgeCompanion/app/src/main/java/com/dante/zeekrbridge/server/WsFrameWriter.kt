package com.dante.zeekrbridge.server

import java.io.OutputStream

/**
 * Serializes all WebSocket frame writes (pong/close/sendText) under one lock so
 * a UI broadcast can never interleave mid-frame with the run-loop pong reply.
 */
class WsFrameWriter(private val out: OutputStream) {
    private val lock = Any()

    fun write(vararg frames: ByteArray) {
        synchronized(lock) {
            frames.forEach { out.write(it) }
            out.flush()
        }
    }
}
