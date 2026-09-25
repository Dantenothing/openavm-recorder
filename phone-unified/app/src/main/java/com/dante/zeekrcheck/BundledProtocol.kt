package com.dante.zeekrcheck

import android.content.Context
import com.dante.zeekrcheck.core.ProtocolConfig

/** App-level AU protocol profile; contains no account, session, vehicle or phone identity. */
internal object BundledProtocol {
    const val ASSET = "connection/zeekr-au-166.json"
    fun load(context: Context): String = context.assets.open(ASSET).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(output.size() + read <= 65_536) { "Bundled connection profile exceeds the size limit" }
            output.write(buffer, 0, read)
        }
        output.toString("UTF-8").also { ProtocolConfig.parse(it) }
    }
}
