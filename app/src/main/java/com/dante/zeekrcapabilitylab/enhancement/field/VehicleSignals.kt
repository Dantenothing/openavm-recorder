package com.dante.zeekrcapabilitylab.enhancement.field

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

enum class VehicleReadKind { FUNCTION, FUNCTION_ZONE, SENSOR_EVENT, SENSOR_VALUE }

data class VehicleSignalSpec(
    val key: String,
    val nameEn: String,
    val nameZh: String,
    val id: Int,
    val zone: Int = 0,
    val kind: VehicleReadKind,
    val unit: String?,
    val confidence: String,
    val fast: Boolean = false
)

/**
 * Fixed read allowlist from the pinned 2026-10-03 telemetry-reference Signal.java.
 * reference_observed means observed by that reference; it does not certify this car/firmware.
 * Driver/passenger zones are roles, not assumed left/right positions.
 */
object VehicleSignalCatalog {
    private const val OBSERVED = "reference_observed"
    private const val PROVISIONAL = "provisional"
    private const val UNVERIFIED = "unverified"

    private fun function(key: String, en: String, zh: String, id: Int, fast: Boolean = false,
        confidence: String = OBSERVED) =
        VehicleSignalSpec(key, en, zh, id, kind = VehicleReadKind.FUNCTION, unit = null,
            confidence = confidence, fast = fast)

    private fun zoned(key: String, en: String, zh: String, id: Int, zone: Int, fast: Boolean = false) =
        VehicleSignalSpec(key, en, zh, id, zone, VehicleReadKind.FUNCTION_ZONE, null, OBSERVED, fast)

    private fun event(key: String, en: String, zh: String, id: Int, fast: Boolean = false,
        confidence: String = OBSERVED) =
        VehicleSignalSpec(key, en, zh, id, kind = VehicleReadKind.SENSOR_EVENT, unit = null,
            confidence = confidence, fast = fast)

    private fun sensor(key: String, en: String, zh: String, id: Int, unit: String?, fast: Boolean = false,
        confidence: String = OBSERVED) =
        VehicleSignalSpec(key, en, zh, id, kind = VehicleReadKind.SENSOR_VALUE, unit = unit,
            confidence = confidence, fast = fast)

    val signals: List<VehicleSignalSpec> = listOf(
        event("gear", "Gear", "挡位", 0x00200200, fast = true),
        sensor("speed", "Vehicle speed", "车速", 0x00100100, "km/h", fast = true),
        function("ignition", "Ignition state", "点火状态", 0x20259000, fast = true),
        function("brake_pedal", "Brake pedal pressed", "制动踏板踩下", 0x20317A00, fast = true),
        sensor("brake_depth", "Brake depth (raw; scale unverified)", "制动深度（原始值，量程待验证）",
            0x00101300, null, fast = true, confidence = PROVISIONAL),
        sensor("throttle_depth", "Accelerator depth (raw; scale unverified)", "加速踏板深度（原始值，量程待验证）",
            0x00101400, null, fast = true, confidence = PROVISIONAL),
        sensor("steering", "Steering (raw; unit unverified)", "方向盘转角（原始值，单位待验证）",
            0x00101000, null, fast = true, confidence = PROVISIONAL),
        function("auto_hold", "Auto Hold setting", "自动驻车开关", 0x20060400),
        function("auto_hold_active", "Auto Hold holding request", "自动驻车保持请求", 0x20320600, fast = true),
        function("indicator", "Turn signal request", "转向灯请求", 0x2A091500, fast = true),
        function("indicator_display", "Turn signal flashing display", "转向灯闪烁指示", 0x2A091400, fast = true),
        function("turn_left", "Left turn lamp", "左转向灯", 0x21051100, fast = true),
        function("turn_right", "Right turn lamp", "右转向灯", 0x21051200, fast = true),
        function("low_beam", "Low beam", "近光灯", 0x21050100),
        function("high_beam", "High beam", "远光灯", 0x21050200),
        function("drl", "Daytime running lamp signal", "日行灯信号", 0x21050900),
        function("front_fog", "Front fog lamp", "前雾灯", 0x21050400, confidence = UNVERIFIED),
        function("rear_fog", "Rear fog lamp", "后雾灯", 0x21050500),
        function("front_position_lamp", "Front position lamps", "前位置灯", 0x21050800),
        function("rear_position_lamp", "Rear position lamps", "后位置灯", 0x21050C00),
        function("light_switch", "Lighting switch position", "灯光开关位置", 0x20040E00),
        function("reverse_lamp", "Reverse lamp", "倒车灯", 0x21050E00, fast = true),
        function("stop_lamp", "Brake lamp", "制动灯", 0x21050D00, fast = true),
        zoned("door_driver", "Driver door", "主驾车门", 0x21020100, 0x1),
        zoned("door_passenger", "Passenger door", "副驾车门", 0x21020100, 0x4),
        zoned("door_rear_left", "Rear left door", "左后车门", 0x21020100, 0x10),
        zoned("door_rear_right", "Rear right door", "右后车门", 0x21020100, 0x40),
        zoned("door_frunk", "Front trunk", "前备箱", 0x21020100, 0x10000000),
        zoned("door_trunk", "Rear trunk", "后备箱", 0x21020100, 0x20000000),
        function("charge_port", "Charge port door", "充电口盖", 0x21020500),
        zoned("sunroof_shade", "Roof shade (raw)", "天窗遮阳帘（原始值）", 0x20080100, 0x8),
        event("belt_driver", "Driver belt", "主驾安全带", 0x00201200),
        event("belt_passenger", "Passenger belt", "副驾安全带", 0x00201300, confidence = UNVERIFIED),
        event("belt_rear_left", "Rear left belt", "左后安全带", 0x00201800, confidence = UNVERIFIED),
        event("belt_rear_center", "Rear center belt", "后排中央安全带", 0x00201A00, confidence = UNVERIFIED),
        event("belt_rear_right", "Rear right belt", "右后安全带", 0x00201900, confidence = UNVERIFIED),
        event("seat_driver", "Driver seat occupancy", "主驾座位占用", 0x00203300),
        event("seat_passenger", "Passenger seat occupancy", "副驾座位占用", 0x00203400),
        zoned("mirror_dip_driver", "Driver mirror reverse tilt", "主驾后视镜倒车下翻", 0x2031EF00, 0x1, fast = true),
        zoned("mirror_dip_passenger", "Passenger mirror reverse tilt", "副驾后视镜倒车下翻", 0x2031EF00, 0x4, fast = true),
        function("stock_360", "Factory 360 display state", "原厂全景影像显示状态", 0x2031FE00, fast = true),
        function("stock_popup", "Factory camera popup", "原厂影像浮窗", 0x2031B200, fast = true),
        function("park_assist", "Parking assist setting", "泊车辅助开关", 0x23030100),
        sensor("odometer", "Odometer", "总里程", 0x00100700, "km"),
        sensor("battery", "Battery state of charge", "动力电池电量", 0x00404000, "%"),
        sensor("range", "Remaining range", "剩余续航", 0x00100800, "km"),
        sensor("temp_outside", "Outside temperature", "车外温度", 0x00100B00, "°C"),
        sensor("temp_inside", "Cabin temperature", "车内温度", 0x00100C00, "°C"),
        sensor("battery_temp", "Battery temperature", "动力电池温度", 0x00102A00, "°C"),
        event("day_night", "Day / night state", "昼夜状态", 0x00201000),
        function("sentry_mode", "Sentry signal (meaning unverified)", "哨兵信号（含义待验证）",
            0x20240100, confidence = UNVERIFIED),
        function("aeb", "Automatic emergency braking setting", "自动紧急制动开关", 0x20070E00),
        function("fcw", "Forward collision warning sensitivity", "前碰撞预警灵敏度", 0x200E0200),
        function("ldw", "Lane departure warning setting", "车道偏离预警开关", 0x28084100, confidence = UNVERIFIED),
        function("lka", "Lane keeping assist setting", "车道保持辅助开关", 0x20070100),
        function("bsd", "Blind spot detection setting", "盲区监测开关", 0x28081600, confidence = UNVERIFIED),
        function("rcw", "Rear collision warning setting", "后碰撞预警开关", 0x20071000),
        function("lane_change_assist", "Lane change assist setting", "变道辅助开关", 0x20070300),
        function("auto_lane_change", "Automatic lane change setting", "自动变道开关", 0x28040100),
        function("door_open_warning", "Door opening warning setting", "开门预警开关", 0x20120100),
        function("lcc", "Lane centering setting", "车道居中开关", 0x28085B00, confidence = UNVERIFIED)
    )

    private val byKey = signals.associateBy { it.key }

    /** Both ID and read shape must match; accepting an arbitrary spec must never scan new IDs. */
    fun allows(spec: VehicleSignalSpec): Boolean = byKey[spec.key]?.let {
        it.id == spec.id && it.zone == spec.zone && it.kind == spec.kind
    } == true
}

data class VehicleSignalReading(
    val key: String,
    val raw: Double?,
    val value: Double?,
    val text: String?,
    val valid: Boolean,
    val status: String,
    /** True only after the requested getter returns. It does not assert that its value is usable. */
    val sourceAvailable: Boolean = false
)

/** Pure value interpretation. All output numbers are finite and safe for JSON. */
object VehicleSignalDecode {
    private val enumSentinelValues = setOf(255.0, 254.0, 253.0, -1.0, -65535.0)
    // Numeric sensors use a different placeholder set: -1 can be a temperature or
    // steering value, while 253/254 can be a real distance. Match the getter kind,
    // not the boxed Number type returned by an OEM implementation.
    private val sensorSentinelValues = setOf(255.0, -65535.0)
    private val binaryKeys = setOf(
        "brake_pedal", "auto_hold", "auto_hold_active", "turn_left", "turn_right", "low_beam", "high_beam",
        "drl", "front_fog", "rear_fog", "front_position_lamp", "rear_position_lamp", "reverse_lamp", "stop_lamp",
        "stock_popup", "park_assist", "sentry_mode", "aeb", "ldw", "lka", "bsd", "rcw", "lane_change_assist",
        "auto_lane_change", "door_open_warning", "lcc"
    )

    fun decode(spec: VehicleSignalSpec, raw: Number?): VehicleSignalReading {
        if (!VehicleSignalCatalog.allows(spec)) return unavailable(spec, "NOT_ALLOWLISTED")
        if (raw == null) return invalid(spec, null, "NO_VALUE")
        val number = raw.toDouble()
        if (!number.isFinite()) return invalid(spec, null, "NON_FINITE")
        val sentinels = if (spec.kind == VehicleReadKind.SENSOR_VALUE) sensorSentinelValues else enumSentinelValues
        if (number in sentinels) return invalid(spec, number, "SENTINEL")
        if (number != 0.0 && abs(number) < 1e-6) return invalid(spec, number, "TINY_SENTINEL")
        if (spec.kind == VehicleReadKind.SENSOR_VALUE) {
            val value = if (spec.key == "speed") number * 3.6 else number
            if (!value.isFinite()) return invalid(spec, number, "NON_FINITE_CONVERSION")
            if (spec.key in setOf("speed", "odometer", "range") && value < 0.0) {
                return invalid(spec, number, "OUT_OF_RANGE")
            }
            if (spec.key == "battery" && value !in 0.0..100.0) return invalid(spec, number, "OUT_OF_RANGE")
            val status = if (spec.key in setOf("steering", "brake_depth", "throttle_depth")) "PROVISIONAL_RAW" else "OK"
            return VehicleSignalReading(spec.key, number, value, null, true, status, true)
        }
        if (number < Int.MIN_VALUE || number > Int.MAX_VALUE || number != number.toInt().toDouble()) {
            return invalid(spec, number, "NON_INTEGER_ENUM")
        }
        val code = number.toInt()
        val text = when {
            spec.key == "gear" -> when (code) {
                0x00200230 -> "P"
                0x00200240 -> "R"
                0x00200210 -> "N"
                0x00200220 -> "D"
                else -> null
            }
            spec.key in binaryKeys -> when (code) { 0 -> "off"; 1 -> "on"; else -> null }
            spec.key.startsWith("door_") || spec.key == "charge_port" ->
                when (code) { 0 -> "closed"; 1 -> "open"; else -> null }
            spec.key.startsWith("belt_") -> when (code) { 0 -> "unfastened"; 1 -> "fastened"; else -> null }
            spec.key.startsWith("seat_") -> seatState(spec, code)
            spec.key == "stock_360" -> when (code) {
                1 -> "shown"
                2 -> "hidden"
                // Reference only speculates that 0 is the side-camera view; retain it as unknown.
                else -> null
            }
            spec.key == "indicator" || spec.key == "indicator_display" -> when (code) {
                0 -> "off"; 1 -> "left"; 2 -> "right"; 3 -> "hazard"; else -> null
            }
            spec.key.startsWith("mirror_dip_") -> when (code) {
                1 -> "normal"; 2 -> "tilted"; 3 -> "returning"; 4 -> "tilting"; else -> null
            }
            spec.key == "day_night" -> when (code) { 0x00201001 -> "day"; 0x00201002 -> "night"; else -> null }
            spec.key == "ignition" -> when (code) {
                0x00200104 -> "accessory"; 0x00200105 -> "on"; 0x00200107 -> "driving"; else -> null
            }
            spec.key == "light_switch" -> when (code) {
                0 -> "off"; 0x20040E01 -> "position"; 0x20040E02 -> "low_beam"; 0x20040E03 -> "auto"; else -> null
            }
            spec.key == "fcw" -> when (code) {
                0 -> "off"; 0x200E0201 -> "low"; 0x200E0202 -> "medium"; 0x200E0203 -> "high"; else -> null
            }
            else -> null
        }
        if (spec.key == "sunroof_shade") return VehicleSignalReading(spec.key, number, number, null, true, "RAW_ONLY", true)
        if (text == null) return invalid(spec, number, "UNKNOWN_ENUM")
        return VehicleSignalReading(spec.key, number, number, text, true, "OK", true)
    }

    private fun seatState(spec: VehicleSignalSpec, code: Int): String? = when (code) {
        spec.id + 1 -> "unoccupied"
        spec.id + 2 -> "occupied"
        // field1's passenger getter (0x00203400) returned the shared seat enum
        // 0x00203301. Accept only this known family, not arbitrary low-byte 1/2.
        // This is decoder compatibility; physical occupancy remains unconfirmed.
        0x00203301 -> if (spec.key == "seat_passenger") "unoccupied" else null
        0x00203302 -> if (spec.key == "seat_passenger") "occupied" else null
        else -> null
    }

    fun unavailable(spec: VehicleSignalSpec, status: String) =
        VehicleSignalReading(spec.key, null, null, null, false, status, false)

    private fun invalid(spec: VehicleSignalSpec, raw: Double?, status: String) =
        VehicleSignalReading(spec.key, raw, null, null, false, status, true)
}

interface VehicleSignalReader {
    /** Blocking: invoke on the diagnostic worker, never the UI thread. */
    fun connect(): String
    /** One allowlisted getter call. No polling, subscription, reconnect or retry inside this call. */
    fun read(spec: VehicleSignalSpec): VehicleSignalReading
}

/** No queue or wait: a blocked OEM call cannot make another SDK consumer wait for it. */
internal class VehicleSdkAdmission {
    private val entered = AtomicBoolean(false)

    fun <T> tryCall(onBusy: () -> T, call: () -> T): T {
        if (!entered.compareAndSet(false, true)) return onBusy()
        return try {
            call()
        } finally {
            entered.set(false)
        }
    }
}

/** Only the fixed getters below are resolved. No writable SDK API or listener is used. */
class EcarxSignalReader(context: Context) : VehicleSignalReader {
    private val appContext = context.applicationContext ?: context
    private var connectAttempted = false
    private var connectionStatus = "NOT_CONNECTED"
    private var access: VehicleSignalAccess? = null

    override fun connect(): String = sdkAdmission.tryCall({ "SDK_BUSY" }) {
        // Admission must precede this flag: a busy caller may try again later.
        if (connectAttempted) return@tryCall connectionStatus
        connectAttempted = true
        connectionStatus = try {
            val car = synchronized(carLock) {
                if (!carAttempted) {
                    carAttempted = true
                    try {
                        val carClass = Class.forName("com.ecarx.xui.adaptapi.car.Car")
                        val create = carClass.getMethod("create", Context::class.java)
                        if (!Modifier.isStatic(create.modifiers)) {
                            cachedCarStatus = "CAR_FACTORY_NOT_STATIC"
                        } else {
                            cachedCar = create.invoke(null, appContext)
                            cachedCarStatus = if (cachedCar == null) "CAR_FACTORY_RETURNED_NULL" else "CAR_CREATED"
                        }
                    } catch (_: ClassNotFoundException) {
                        cachedCarStatus = "SDK_UNAVAILABLE"
                    } catch (_: NoSuchMethodException) {
                        cachedCarStatus = "CAR_FACTORY_UNAVAILABLE"
                    } catch (error: Throwable) {
                        cachedCarStatus = "CAR_CONNECT_FAILED:${VehicleSignalAccess.errorClass(error)}"
                    }
                }
                cachedCar
            }
            if (car == null) cachedCarStatus else VehicleSignalAccess(car).also { access = it }.status
        } catch (error: Throwable) {
            "CONNECT_FAILED:${VehicleSignalAccess.errorClass(error)}"
        }
        connectionStatus
    }

    override fun read(spec: VehicleSignalSpec): VehicleSignalReading {
        if (!VehicleSignalCatalog.allows(spec)) return VehicleSignalDecode.unavailable(spec, "NOT_ALLOWLISTED")
        return sdkAdmission.tryCall({ VehicleSignalDecode.unavailable(spec, "SDK_BUSY") }) {
            access?.read(spec) ?: VehicleSignalDecode.unavailable(spec, connectionStatus)
        }
    }

    private companion object {
        // Shared by field diagnostics and recorded telemetry, across all reader instances.
        val sdkAdmission = VehicleSdkAdmission()
        val carLock = Any()
        @Volatile var cachedCar: Any? = null
        @Volatile var carAttempted = false
        @Volatile var cachedCarStatus = "NOT_CONNECTED"
    }
}

/** Android-free reflection boundary so routing, missing sources and redaction can be tested with fakes. */
internal class VehicleSignalAccess(car: Any) {
    private data class Endpoint(val target: Any?, val method: Method?, val status: String)
    private val endpoints: Map<VehicleReadKind, Endpoint>
    val status: String

    init {
        val function = manager(car, "getICarFunction")
        val sensor = manager(car, "getSensorManager")
        endpoints = mapOf(
            VehicleReadKind.FUNCTION to endpoint(function, "getFunctionValue", 1),
            VehicleReadKind.FUNCTION_ZONE to endpoint(function, "getFunctionValue", 2),
            VehicleReadKind.SENSOR_EVENT to endpoint(sensor, "getSensorEvent", 1),
            VehicleReadKind.SENSOR_VALUE to endpoint(sensor, "getSensorLatestValue", 1)
        )
        val anyReadable = endpoints.values.any { it.method != null }
        status = (if (anyReadable) "GETTERS_READY" else "NO_READABLE_GETTERS") + ";" +
            VehicleReadKind.entries.joinToString(";") { "${it.name}=${endpoints.getValue(it).status}" }
    }

    fun read(spec: VehicleSignalSpec): VehicleSignalReading {
        if (!VehicleSignalCatalog.allows(spec)) return VehicleSignalDecode.unavailable(spec, "NOT_ALLOWLISTED")
        val endpoint = endpoints.getValue(spec.kind)
        val method = endpoint.method ?: return VehicleSignalDecode.unavailable(spec, endpoint.status)
        return try {
            val raw = if (spec.kind == VehicleReadKind.FUNCTION_ZONE) method.invoke(endpoint.target, spec.id, spec.zone)
                else method.invoke(endpoint.target, spec.id)
            if (raw != null && raw !is Number) {
                VehicleSignalReading(spec.key, null, null, null, false, "NON_NUMERIC_VALUE", true)
            } else {
                VehicleSignalDecode.decode(spec, raw as Number?)
            }
        } catch (error: Throwable) {
            VehicleSignalDecode.unavailable(spec, "READ_FAILED:${errorClass(error)}")
        }
    }

    private fun manager(car: Any, name: String): Pair<Any?, String> = try {
        val method = exactGetter(car.javaClass, name)
        if (method == null) null to "MANAGER_GETTER_UNAVAILABLE" else {
            val manager = method.invoke(car)
            manager to if (manager == null) "MANAGER_RETURNED_NULL" else "AVAILABLE"
        }
    } catch (error: Throwable) {
        null to "MANAGER_FAILED:${errorClass(error)}"
    }

    private fun endpoint(manager: Pair<Any?, String>, name: String, parameters: Int): Endpoint {
        val target = manager.first ?: return Endpoint(null, null, manager.second)
        return try {
            val method = exactGetter(target.javaClass, name, *Array(parameters) { Int::class.javaPrimitiveType!! })
            Endpoint(target, method, if (method == null) "GETTER_UNAVAILABLE" else "AVAILABLE")
        } catch (error: Throwable) {
            Endpoint(target, null, "GETTER_LOOKUP_FAILED:${errorClass(error)}")
        }
    }

    companion object {
        /** Public interfaces avoid IllegalAccessException on ECARX's non-public implementations. */
        private fun exactGetter(type: Class<*>, name: String, vararg parameters: Class<*>): Method? {
            val visited = mutableSetOf<Class<*>>()
            fun find(current: Class<*>?): Method? {
                if (current == null || !visited.add(current)) return null
                for (contract in current.interfaces) find(contract)?.let { return it }
                if (Modifier.isPublic(current.modifiers)) {
                    try {
                        val method = current.getMethod(name, *parameters)
                        if (Modifier.isPublic(method.declaringClass.modifiers) && !Modifier.isStatic(method.modifiers)) return method
                    } catch (_: NoSuchMethodException) { /* The exact getter may live on a public superclass. */ }
                }
                return find(current.superclass)
            }
            return find(type)
        }

        internal fun errorClass(error: Throwable): String {
            var cause = error
            repeat(8) {
                if ((cause is InvocationTargetException || cause is UndeclaredThrowableException) && cause.cause != null) {
                    cause = cause.cause!!
                }
            }
            return cause.javaClass.simpleName.ifBlank { "Throwable" }
        }
    }
}
