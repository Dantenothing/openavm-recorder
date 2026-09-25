package com.dante.zeekrcheck.core

/** A default is installed only when no saved configuration exists; failures never erase an override. */
object ProtocolDefaults {
    fun loadOrInstall(loadSaved: () -> String?, loadDefault: () -> String, save: (String) -> Unit): String {
        loadSaved()?.let { saved ->
            ProtocolConfig.parse(saved)
            return saved
        }
        val bundled = loadDefault()
        ProtocolConfig.parse(bundled)
        save(bundled)
        return bundled
    }
}
