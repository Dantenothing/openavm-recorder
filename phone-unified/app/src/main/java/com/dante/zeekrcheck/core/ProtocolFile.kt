package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A bounded, flat string object. Check duplicate decoded keys BEFORE building a Map. */
object ProtocolFile {
    val fields = setOf("hmac_access_key", "hmac_secret_key", "password_public_key", "prod_secret", "vin_key", "vin_iv")
    fun utf8(bytes: ByteArray): String = try {
        require(bytes.size <= 65_536)
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw IllegalArgumentException("请选择不超过 64 KB 的 UTF-8 配置文件") }

    fun parse(input: String): JsonObject {
        require(input.length <= 65_536 && input.toByteArray(Charsets.UTF_8).size <= 65_536) { "配置文件超过 64 KB" }
        val text = input.removePrefix("\uFEFF")
        var pos = 0
        fun invalid(): Nothing = throw IllegalArgumentException("配置必须是包含六个文本字段的 JSON 对象")
        fun space() { while (pos < text.length && text[pos] in " \t\r\n") pos++ }
        fun expect(char: Char) { space(); if (pos >= text.length || text[pos++] != char) invalid() }
        fun string(): String {
            space()
            val start = pos
            if (pos >= text.length || text[pos++] != '"') invalid()
            var escaped = false
            while (pos < text.length) {
                val char = text[pos++]
                if (char == '"' && !escaped) return try {
                    Json.parseToJsonElement(text.substring(start, pos)).jsonPrimitive.content
                } catch (_: Exception) { invalid() }
                escaped = if (escaped) false else char == '\\'
            }
            invalid()
        }
        expect('{')
        val values = linkedMapOf<String, JsonElement>()
        space()
        if (pos < text.length && text[pos] != '}') while (true) {
            val key = string()
            require(key in fields) { "配置含有额外字段；请选择六字段连接配置，不要使用账号导出文件" }
            require(key !in values) { "配置含有重复字段" }
            expect(':')
            values[key] = JsonPrimitive(string())
            space()
            if (pos >= text.length) invalid()
            if (text[pos] == '}') break
            expect(',')
        }
        expect('}'); space()
        if (pos != text.length) invalid()
        require(values.keys == fields) { "配置必须包含全部六个字段" }
        return JsonObject(values)
    }
}
