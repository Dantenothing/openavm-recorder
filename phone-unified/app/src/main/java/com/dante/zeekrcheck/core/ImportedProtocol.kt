package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.util.UUID

/** Provenance is written by the importer, never accepted as a field in an external config. */
class ImportedProtocol private constructor(val id: String, val importedAt: Long, val text: String) {
    fun encode(): String = buildJsonObject {
        put("schema", 1); put("source", "USER_IMPORTED"); put("adapter", "AU_166")
        put("id", id); put("importedAt", importedAt); put("config", Json.parseToJsonElement(text))
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= SealedConfig.MAX_BYTES) { "配置内容过长，请精简文件后重试" } }
    override fun toString() = "ImportedProtocol(redacted)"
    companion object {
        fun create(text: String, now: Long = System.currentTimeMillis()): ImportedProtocol {
            val normalized = ProtocolFile.parse(text).toString()
            ProtocolConfig.parse(normalized)
            return ImportedProtocol(UUID.randomUUID().toString(), now, normalized).also { it.encode() }
        }
        fun parse(text: String): ImportedProtocol {
            try {
                val root = Json.parseToJsonElement(text).jsonObject
                require(root.keys == setOf("schema", "source", "adapter", "id", "importedAt", "config"))
                require(root["schema"] == JsonPrimitive(1) && root["source"] == JsonPrimitive("USER_IMPORTED") && root["adapter"] == JsonPrimitive("AU_166"))
                val id = root.getValue("id").jsonPrimitive.content
                require(UUID.fromString(id).toString() == id)
                val time = root.getValue("importedAt").jsonPrimitive.long.also { require(it > 0) }
                val config = root.getValue("config").toString()
                ProtocolConfig.parse(config)
                return ImportedProtocol(id, time, config).also { it.encode() }
            } catch (_: Exception) { throw IllegalArgumentException("本机连接配置无法读取，请重新导入") }
        }
    }
}
