package com.dante.zeekrbridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class CarRecording(
    val fileName: String,
    val sizeBytes: Long = 0,
    val startedAtEpochMs: Long? = null,
    val stoppedAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val protected: Boolean = false,
    val uploadPinned: Boolean = false,
    val cameraId: String = "",
    val profile: String = "",
    val laneLabels: List<String> = emptyList(),
    val layoutType: String = "",
)

/**
 * Phone-side mirror of the car's recording catalog and status, updated from
 * WebSocket messages the car pushes over the bridge.
 */
object CarCatalogStore {
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow<List<CarRecording>>(emptyList())
    val items: StateFlow<List<CarRecording>> = _items.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _carStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val carStatus: StateFlow<Map<String, String>> = _carStatus.asStateFlow()

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage.asStateFlow()

    private val _uploads = MutableStateFlow<Map<String, String>>(emptyMap())
    val uploads: StateFlow<Map<String, String>> = _uploads.asStateFlow()

    fun setOnline(online: Boolean) {
        _online.value = online
    }

    fun onCatalog(payload: JsonObject) {
        val raw = payload["items"]?.jsonPrimitive?.content ?: run {
            _items.value = emptyList()
            return
        }
        val items = try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(CarRecording.serializer()),
                raw,
            )
        } catch (t: Throwable) {
            emptyList()
        }
        _items.value = items.sortedByDescending { it.startedAtEpochMs ?: 0L }
        _lastMessage.value = "已获取录像列表：${items.size} 条"
    }

    fun onCarStatus(payload: JsonObject) {
        _carStatus.value = payload.mapNotNull { (key, element) ->
            element.jsonPrimitive.contentOrNull?.let { key to it }
        }.toMap()
    }

    fun onUploadQueued(payload: JsonObject) {
        val fileName = payload["fileName"]?.jsonPrimitive?.content ?: "?"
        val ok = payload["ok"]?.jsonPrimitive?.content == "true"
        _lastMessage.value = if (ok) "车机已加入传输：$fileName" else "车机无法加入传输：$fileName"
        if (ok && fileName != "__protected_sync__") {
            _uploads.value = _uploads.value + (fileName to "QUEUED")
        }
    }

    fun onRecordingDeleted(payload: JsonObject) {
        val fileName = payload["fileName"]?.jsonPrimitive?.content ?: "?"
        val ok = payload["ok"]?.jsonPrimitive?.content == "true"
        _lastMessage.value = if (ok) "车机已删除：$fileName" else "车机删除失败：$fileName"
        if (ok) {
            _items.value = _items.value.filterNot { it.fileName == fileName }
        }
    }

    fun clear() {
        _items.value = emptyList()
        _carStatus.value = emptyMap()
        _online.value = false
    }
}
