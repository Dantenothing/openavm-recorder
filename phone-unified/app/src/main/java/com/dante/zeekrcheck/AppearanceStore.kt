package com.dante.zeekrcheck

import android.content.Context
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A local private profile plus the stable vehicle hash scopes all appearances and widget bindings. */
internal class AppearanceStore(context: Context, name: String = "appearance") {
    private val disk = SecureRecordStore(context, name, SealedConfig.Purpose.APPEARANCE) { AppearanceData.parse(it) }
    private val loaded = runCatching { disk.load()?.let(AppearanceData::parse) ?: AppearanceData() }
    val readable = loaded.isSuccess
    private val mutable = MutableStateFlow(loaded.getOrDefault(AppearanceData()))
    val state = mutable.asStateFlow()
    @Synchronized fun edit(transform: (AppearanceData) -> AppearanceData) {
        check(readable) { "本机外观记录未能读取" }
        val next = transform(mutable.value)
        if (next == mutable.value) return
        disk.save(next.encode()); mutable.value = next
    }
    @Synchronized fun binding(id: Int, key: String?, name: String): WidgetAppearance? {
        mutable.value.widgets[id]?.let { return it }
        if (key == null || !readable) return null
        val appearance = mutable.value.vehicles[key] ?: VehicleAppearance()
        val binding = WidgetAppearance(key, appearance.widgetPlateDefault)
        edit { it.copy(widgets = it.widgets + (id to binding), names = it.names + (key to name)) }
        return binding
    }
    fun appearance(key: String?) = state.value.vehicles[key] ?: VehicleAppearance()
    fun save(key: String, draft: VehicleAppearance, name: String, privacy: Map<Int,Boolean>) = edit { it.saved(key,draft,name,privacy) }
    fun bind(id: Int, key: String, name: String) = edit {
        it.copy(widgets = it.widgets + (id to WidgetAppearance(key, appearance(key).widgetPlateDefault)), names = it.names + (key to name))
    }
    companion object {
        @Volatile private var instance: AppearanceStore? = null
        fun get(context: Context): AppearanceStore = instance ?: synchronized(this) {
            instance ?: AppearanceStore(context.applicationContext).also { instance = it }
        }
    }
}
