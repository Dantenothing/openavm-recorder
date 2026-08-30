package com.dante.zeekrbridge.core

import android.content.Context
import com.dante.zeekrbridge.service.BridgeService
import com.dante.zeekrbridge.ui.PhoneLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

data class PairingResult(
    val ok: Boolean,
    val message: String,
    val carDeviceId: String? = null,
)

/**
 * V2 hotspot pairing: the phone scans the car's QR payload and completes the
 * two-step begin/finalize exchange against the car's LAN pairing server. The
 * long-term token is generated here (registered in [PairingManager], stored
 * Keystore-encrypted) and handed to the car so it can connect outbound.
 */
object PhonePairingClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun pairWithCar(
        context: Context,
        payload: PairingPayload.Parsed,
        timeoutMs: Int = 10_000,
    ): PairingResult = withContext(Dispatchers.IO) {
        val phoneIp = findIpv4()
        if (phoneIp.isBlank()) {
            return@withContext PairingResult(false, text("No local network address was found. Turn on the phone hotspot first.", "未检测到局域网地址，请先开启手机热点"))
        }
        val phoneDeviceId = PairingManager.phoneDeviceId.value
        val phoneName = PairingManager.phoneName.value.ifBlank { android.os.Build.MODEL }
        val beginUrl = "http://${payload.host}:${payload.port}/api/pair/begin"
        val beginBody = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "token" to kotlinx.serialization.json.JsonPrimitive(payload.token),
                    "phoneDeviceId" to kotlinx.serialization.json.JsonPrimitive(phoneDeviceId),
                    "phoneName" to kotlinx.serialization.json.JsonPrimitive(phoneName),
                    "phoneIp" to kotlinx.serialization.json.JsonPrimitive(phoneIp),
                    "phonePort" to kotlinx.serialization.json.JsonPrimitive(Protocol.PORT.toString()),
                ),
            ),
        )
        val begin = try {
            postJson(beginUrl, beginBody, timeoutMs)
        } catch (t: Throwable) {
            return@withContext PairingResult(false, text("Cannot connect to the car: ${t.message ?: t.javaClass.simpleName}", "无法连接车机：${t.message ?: t.javaClass.simpleName}"))
        }
        val beginCode = begin.first
        val beginBodyText = begin.second
        if (beginCode != 200) {
            return@withContext PairingResult(false, text("The car rejected pairing ($beginCode)", "车机拒绝配对（$beginCode）"))
        }
        val beginObj = parse(beginBodyText) ?: return@withContext PairingResult(false, text("Invalid response from the car.", "车机响应无效"))
        val carDeviceId = beginObj["carDeviceId"]?.jsonPrimitive?.content ?: return@withContext PairingResult(false, text("The car response has no device ID.", "车机响应缺少 ID"))
        val exchangeToken = beginObj["exchangeToken"]?.jsonPrimitive?.content ?: return@withContext PairingResult(false, text("The car response has no exchange token.", "车机响应缺少交换令牌"))

        val longTermToken = PairingManager.registerCar(carDeviceId, text("Zeekr vehicle", "Zeekr 车机"))
            ?: return@withContext PairingResult(false, text("Could not save pairing on this phone.", "本地保存配对失败"))

        val finalizeUrl = "http://${payload.host}:${payload.port}/api/pair/finalize"
        val finalizeBody = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "exchangeToken" to kotlinx.serialization.json.JsonPrimitive(exchangeToken),
                    "token" to kotlinx.serialization.json.JsonPrimitive(longTermToken),
                    "phoneDeviceId" to kotlinx.serialization.json.JsonPrimitive(phoneDeviceId),
                    "phoneName" to kotlinx.serialization.json.JsonPrimitive(phoneName),
                    "phoneIp" to kotlinx.serialization.json.JsonPrimitive(phoneIp),
                    "phonePort" to kotlinx.serialization.json.JsonPrimitive(Protocol.PORT.toString()),
                ),
            ),
        )
        val finalize = try {
            postJson(finalizeUrl, finalizeBody, timeoutMs)
        } catch (t: Throwable) {
            return@withContext PairingResult(false, text("Pairing confirmation failed: ${t.message ?: t.javaClass.simpleName}", "确认配对失败：${t.message ?: t.javaClass.simpleName}"))
        }
        if (finalize.first != 200) {
            return@withContext PairingResult(false, text("The car could not confirm pairing (${finalize.first})", "车机确认失败（${finalize.first}）"))
        }
        // Start the phone bridge server so the car can connect outbound.
        val prefs = context.getSharedPreferences("phone_product_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_start_server", true)) {
            BridgeService.start(context)
        }
        return@withContext PairingResult(true, text("Paired: $carDeviceId", "配对成功：$carDeviceId"), carDeviceId)
    }

    private fun postJson(url: String, body: String, timeoutMs: Int): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val text = try {
            (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        } finally {
            connection.disconnect()
        }
        return code to text
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject
    } catch (t: Throwable) {
        null
    }

    fun findIpv4(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress
            ?: ""
    } catch (t: Throwable) {
        ""
    }

    private fun text(en: String, zh: String) = PhoneLanguage.text(en, zh)
}
