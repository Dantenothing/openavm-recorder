package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.pow

/** Display-only data. Plate text is never an identity, request parameter or diagnostic field. */
data class VehicleAppearance(
    val bodyColor: String = "#F2F3EF", val plateEnabled: Boolean = false, val plateText: String = "",
    val plateBackground: String = "#FFFFFF", val plateForeground: String = "#18231F",
    val plateStyleId: String = "standard", val revision: Long = 0, val widgetPlateDefault: Boolean = false,
) {
    fun problem(): String? = when {
        listOf(bodyColor, plateBackground, plateForeground).any { !it.matches(Regex("#[0-9a-fA-F]{6}")) } -> "颜色格式为 #RRGGBB，例如 #275C50"
        plateText.length > 12 -> "车牌最多 12 个字符，请缩短后保存"
        plateText.any { !it.isLetterOrDigit() && it != ' ' && it != '-' } -> "车牌支持字母、数字、空格和连字符"
        plateEnabled && plateText.isBlank() -> "请输入展示号码，或关闭自定义车牌"
        plateStyleId != "standard" || revision < 0 -> "外观设置无效"
        else -> null
    }
    fun contrast(): Double {
        fun luminance(color: String): Double {
            if (!color.matches(Regex("#[0-9a-fA-F]{6}"))) return 0.0
            val rgb = color.drop(1).toInt(16)
            fun linear(x: Int): Double { val c = x / 255.0; return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4) }
            return .2126 * linear(rgb shr 16) + .7152 * linear((rgb shr 8) and 255) + .0722 * linear(rgb and 255)
        }
        val a = luminance(plateBackground); val b = luminance(plateForeground)
        return (maxOf(a,b) + .05) / (minOf(a,b) + .05)
    }
    fun suggestedForeground() = if (copy(plateForeground = "#000000").contrast() >= copy(plateForeground = "#FFFFFF").contrast()) "#000000" else "#FFFFFF"
    fun cacheKey(width: Int, showPlate: Boolean): String {
        // Privacy variants omit the private text from their inputs as well as their rendered pixels.
        val visible = if (showPlate && plateEnabled) plateText else ""
        return hash("$ASSET/$ASSET_VERSION/front45/$width/$revision/$bodyColor/$plateEnabled/$visible/$plateBackground/$plateForeground/$plateStyleId/$showPlate")
    }
    fun json() = buildJsonObject {
        put("bodyColor",bodyColor); put("plateEnabled",plateEnabled); put("plateText",plateText)
        put("plateBackground",plateBackground); put("plateForeground",plateForeground); put("plateStyleId",plateStyleId)
        put("revision",revision); put("widgetPlateDefault",widgetPlateDefault)
    }
    override fun toString() = "VehicleAppearance(private)"
    companion object {
        const val ASSET = "zeekr-7x-front45"
        const val ASSET_VERSION = 1
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun parse(obj: JsonObject): VehicleAppearance = VehicleAppearance(
            obj.getValue("bodyColor").jsonPrimitive.content, obj.getValue("plateEnabled").jsonPrimitive.boolean,
            obj.getValue("plateText").jsonPrimitive.content, obj.getValue("plateBackground").jsonPrimitive.content,
            obj.getValue("plateForeground").jsonPrimitive.content, obj.getValue("plateStyleId").jsonPrimitive.content,
            obj.getValue("revision").jsonPrimitive.long, obj["widgetPlateDefault"]?.jsonPrimitive?.boolean ?: false,
        ).also { require(it.problem() == null) }
    }
}

data class WidgetAppearance(val vehicleKey: String, val showPlateText: Boolean = false) {
    init { require(vehicleKey.matches(Regex("[a-f0-9]{64}"))) }
    fun canControl(currentVehicle: String?) = currentVehicle == vehicleKey
}

data class AppearanceData(val profileId: String = UUID.randomUUID().toString(),
    val vehicles: Map<String, VehicleAppearance> = emptyMap(), val names: Map<String,String> = emptyMap(),
    val widgets: Map<Int, WidgetAppearance> = emptyMap()) {
    fun encode() = buildJsonObject {
        put("schema",1); put("profile",profileId)
        put("vehicles",buildJsonObject { vehicles.forEach { (key,value) -> put(key,value.json()) } })
        put("names",buildJsonObject { names.forEach { (key,name) -> put(key,name) } })
        put("widgets",buildJsonObject { widgets.forEach { (id,binding) -> put(id.toString(),buildJsonObject { put("vehicle",binding.vehicleKey); put("plate",binding.showPlateText) }) } })
    }.toString()
    fun saved(key: String, draft: VehicleAppearance, nickname: String, privacy: Map<Int,Boolean>): AppearanceData {
        require(key.matches(Regex("[a-f0-9]{64}")) && draft.problem() == null)
        val next = draft.copy(revision = (vehicles[key]?.revision ?: 0) + 1)
        return copy(vehicles = vehicles + (key to next), names = names + (key to nickname.take(20)),
            widgets = widgets.mapValues { (id,binding) -> if (binding.vehicleKey == key) binding.copy(showPlateText = privacy[id] ?: binding.showPlateText) else binding })
    }
    companion object {
        fun parse(text: String): AppearanceData {
            require(text.toByteArray().size <= 65536)
            val o = Json.parseToJsonElement(text).jsonObject
            require(o["schema"]?.jsonPrimitive?.int == 1)
            val profile = o.getValue("profile").jsonPrimitive.content
            require(UUID.fromString(profile).toString() == profile)
            val vehicles = o.getValue("vehicles").jsonObject.mapValues { VehicleAppearance.parse(it.value.jsonObject) }
            val names = o.getValue("names").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val widgets = o.getValue("widgets").jsonObject.map { (id,e) ->
                require(id.toInt() > 0); id.toInt() to WidgetAppearance(e.jsonObject.getValue("vehicle").jsonPrimitive.content, e.jsonObject.getValue("plate").jsonPrimitive.boolean)
            }.toMap()
            require(vehicles.size <= 40 && names.size <= 40 && widgets.size <= 32)
            require((vehicles.keys + names.keys).all { it.matches(Regex("[a-f0-9]{64}")) } && names.values.all { it.length <= 20 })
            return AppearanceData(profile,vehicles,names,widgets)
        }
    }
}
