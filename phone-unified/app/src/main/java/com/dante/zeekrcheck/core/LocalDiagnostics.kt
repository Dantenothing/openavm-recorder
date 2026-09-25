package com.dante.zeekrcheck.core

import java.io.File
import java.io.ByteArrayOutputStream

/** Debug USB provisioning uses this app's private directory; never external storage. */
object PendingProtocol {
    const val NAME = "pending-protocol.json"
    fun consume(directory: File): String? {
        val file = File(directory, NAME)
        if (!file.isFile) return null
        return try {
            require(file.length() <= 65_536) { "配置文件超过 64 KB" }
            file.inputStream().use { stream ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(data.size() + count <= 65_536) { "配置文件超过 64 KB" }
                    data.write(buffer, 0, count)
                }
                data.toString("UTF-8")
            }
        } finally { check(file.delete()) { "临时配置未能清除" } }
    }
}
