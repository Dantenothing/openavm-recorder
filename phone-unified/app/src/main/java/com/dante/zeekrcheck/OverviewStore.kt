package com.dante.zeekrcheck

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Same process, same epoch: widget callbacks cannot restore a session after explicit logout. */
object AppSessions {
    @Volatile private var instance: SessionPersistence? = null
    fun get(context: Context): SessionPersistence = instance ?: synchronized(this) {
        instance ?: SessionPersistence(SecureSessionStore(context.applicationContext)).also { instance = it }
    }
}

class OverviewStore private constructor(private val context: android.app.Application) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "vehicle-overview.json"))
    private val mutable = MutableStateFlow(runCatching {
        file.openRead().use { stream ->
            val bytes = stream.readBytesBounded(32768)
            VehicleOverview.decode(bytes.toString(Charsets.UTF_8))
        }
    }.getOrDefault(VehicleOverview()))
    val state = mutable.asStateFlow()
    private var generation = 0
    @Synchronized fun epoch() = generation
    @Synchronized fun edit(transform: (VehicleOverview) -> VehicleOverview) {
        val next = transform(mutable.value)
        val output = file.startWrite()
        try { output.write(next.encode().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        mutable.value = next
        VehicleWidgetProvider.updateAll(context)
    }
    @Synchronized fun select(vehicle: Vehicle) {
        val key = VehicleOverview.key(vehicle)
        if (key == mutable.value.vehicleKey) return
        if (mutable.value.vehicleKey != null) AssistantStore.get(context).forgetVehicle()
        ++generation
        edit { VehicleOverview(vehicleKey = key, nickname = it.nickname, target = it.target) }
        AwayGuardService.schedule(context)
    }
    @Synchronized fun publish(vehicle: Vehicle, report: Report, expectedEpoch: Int = generation) {
        if (expectedEpoch != generation || VehicleOverview.key(vehicle) != mutable.value.vehicleKey || report.demo) return
        val pending = mutable.value.pendingBody
        val temperatureTask = AssistantStore.get(context).state.value.temperatureUpdate
        edit { it.updated(report, retainTemperature = temperatureTask?.let { task -> task.vehicleKey == it.vehicleKey && task.active } == true) }
        AssistantStore.get(context).observeGuard(VehicleOverview.key(vehicle), report.probes)
        report.probes.lastOrNull { it.endpoint==Endpoint.STATUS && it.outcome==ProbeOutcome.SUCCESS }?.let {
            AssistantStore.get(context).observePreparationStatus(it)
            val assistant = AssistantStore.get(context)
            assistant.state.value.temperatureUpdate?.takeIf { task -> task.vehicleKey == VehicleOverview.key(vehicle) }?.let { task ->
                val next = task.observeFinished(it, System.currentTimeMillis())
                if (next != task) {
                    assistant.edit { state -> if (state.temperatureUpdate == task) state.copy(temperatureUpdate = next) else state }
                    if (!next.ownsAc) context.getSystemService(android.app.NotificationManager::class.java).cancel(409)
                }
            }
        }
        if (pending != null && mutable.value.pendingBody == null) {
            AssistantStore.get(context).edit { it.copy(operationPending = false, operationMessage = "${pending.action.title} · 车况已回传") }
        }
    }
    @Synchronized fun clear() {
        ++generation
        AssistantStore.get(context).forgetVehicle()
        edit { VehicleOverview(nickname = it.nickname, target = it.target) }
        AwayGuardService.schedule(context)
    }
    @Synchronized fun publishClimate(vehicle: Vehicle, snapshot: ClimateSnapshot) {
        if (VehicleOverview.key(vehicle) != mutable.value.vehicleKey) return
        edit { previous ->
            val readings = previous.readings.toMutableMap()
            if (snapshot.cabinTemperature != null) readings["cabin_temperature"] = OverviewReading(
                String.format(java.util.Locale.ROOT, "%.1f °C", snapshot.cabinTemperature), snapshot.sourceTime?.toEpochMilli(), snapshot.fetchedAt.toEpochMilli())
            else readings.remove("cabin_temperature")
            previous.copy(readings = readings, acOn = snapshot.acOn, blowerActive = snapshot.blowerActive,
                climateSource = snapshot.sourceTime?.toEpochMilli(), climateFetched = snapshot.fetchedAt.toEpochMilli())
        }
        AssistantStore.get(context).observePreparation(snapshot)
    }
    @Synchronized fun preferences(name: String, target: Int) {
        require(name.isNotBlank() && name.length <= 20 && target in 18..28)
        edit { it.copy(nickname = name.trim(), target = target) }
    }
    @Synchronized fun rename(name: String) {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 20)
        mutable.value.vehicleKey?.let { key ->
            AppearanceStore.get(context).edit { it.copy(names = it.names + (key to cleaned)) }
        }
        edit { it.copy(nickname = cleaned) }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= limit); out.write(buffer, 0, count) }
        return out.toByteArray()
    }
    companion object {
        @Volatile private var instance: OverviewStore? = null
        fun get(context: Context): OverviewStore = instance ?: synchronized(this) {
            instance ?: OverviewStore(context.applicationContext as android.app.Application).also { instance = it }
        }
    }
}
