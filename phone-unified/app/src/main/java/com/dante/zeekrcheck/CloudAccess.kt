package com.dante.zeekrcheck

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class CloudAccessState(val checking: Boolean = true, val ready: Boolean = false,
    val session: Boolean = false, val needsImport: Boolean = false, val error: Boolean = false)

/** Starts closed, even when Android starts a receiver before the first Activity. Local media is independent. */
object CloudAccess {
    private val gate = CloudRequestGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private var started = false
    private var profile: ImportedProtocol? = null
    @Volatile private var actionId: String? = null
    private val mutable = MutableStateFlow(CloudAccessState())
    val state = mutable.asStateFlow()
    val ready get() = mutable.value.ready
    val authorized get() = ready && mutable.value.session
    private fun prefs(context: Context) = context.getSharedPreferences("cloud_access_v1", Context.MODE_PRIVATE)
    fun permit() = gate.permit()
    fun stamp(context: Context, intent: Intent): Intent = intent.putExtra("cloudActionId", actionId)
    fun accepts(intent: Intent?) = authorized && actionId != null && intent?.getStringExtra("cloudActionId") == actionId
    fun accepts(id: String?) = authorized && actionId != null && id == actionId
    fun actionId() = actionId

    @Synchronized fun initialize(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                try {
                    val settings = prefs(app)
                    val store = SecureConfigStore(app)
                    val legacy = SecureConfigStore.legacyExists(app)
                    val loaded = withContext(Dispatchers.IO) { runCatching { store.profile() } }
                    val candidate = loaded.getOrNull()
                    val reset = settings.getInt("migration", 0) != 1 || settings.getBoolean("switchPending", false) || legacy || candidate == null
                    val needsImport = legacy || settings.getBoolean("needsImport", false) || loaded.isFailure || settings.getBoolean("switchPending", false)
                    if (reset) {
                        check(settings.edit().putBoolean("switchPending", true).putBoolean("needsImport", needsImport).commit())
                        revoke(app)
                        pauseWork(app)
                        withContext(Dispatchers.IO) {
                            AppSessions.get(app).write(AppSessions.get(app).current(), null)
                            SecureConfigStore.clearLegacy(app)
                            // An incomplete switch never silently authorizes either side of the transaction.
                            if (settings.getInt("migration", 0) == 1 && candidate != null) store.clear()
                            SecureConfigStore(app, "connection-staging").clear()
                        }
                        check(settings.edit().putInt("migration", 1).putBoolean("switchPending", false).commit())
                    }
                    profile = withContext(Dispatchers.IO) { store.profile() }
                    val saved = if (profile != null) withContext(Dispatchers.IO) {
                        runCatching { SecureSessionStore(app).load()?.matches(ProtocolConfig.parse(profile!!.text)) == true }.getOrDefault(false)
                    } else false
                    if (profile != null) gate.open()
                    actionId = settings.getString("actionId", null)
                    mutable.value = CloudAccessState(checking = false, ready = profile != null, session = saved, needsImport = needsImport,
                        error = loaded.isFailure)
                } catch (_: Exception) {
                    gate.revoke(); profile = null; actionId = null
                    mutable.value = CloudAccessState(checking = false, needsImport = true, error = true)
                } finally {
                    initialized.complete(Unit)
                    VehicleWidgetProvider.updateAll(app)
                    if (authorized) DepartureScheduler.sync(app)
                }
            }
        }
    }
    suspend fun loaded(context: Context): ImportedProtocol? { initialize(context); initialized.await(); return profile.takeIf { ready } }

    /** The temporary record is validated/read back before invalidating a working configuration. */
    suspend fun import(context: Context, text: String): ImportedProtocol {
        loaded(context)
        val candidate = ImportedProtocol.create(text)
        return mutex.withLock {
            val staged = SecureConfigStore(context, "connection-staging")
            withContext(Dispatchers.IO) { staged.save(candidate) }
            try {
                check(prefs(context).edit().putBoolean("switchPending", true).commit())
                revoke(context); pauseWork(context)
                withContext(Dispatchers.IO) {
                    AppSessions.get(context).write(AppSessions.get(context).current(), null)
                    SecureConfigStore(context).save(candidate)
                    SecureConfigStore.clearLegacy(context)
                }
                check(prefs(context).edit().putInt("migration", 1).putBoolean("needsImport", false).putBoolean("switchPending", false).commit())
                profile = candidate; gate.open()
                mutable.value = CloudAccessState(checking = false, ready = true)
                candidate
            } catch (e: Exception) {
                gate.revoke(); profile = null
                mutable.value = CloudAccessState(checking = false, needsImport = true, error = true)
                throw e
            } finally { withContext(NonCancellable + Dispatchers.IO) { staged.clear() }; VehicleWidgetProvider.updateAll(context) }
        }
    }
    suspend fun remove(context: Context, removeConfig: Boolean) {
        loaded(context)
        mutex.withLock {
            try {
                check(prefs(context).edit().putBoolean("switchPending", true).commit())
                revoke(context); pauseWork(context)
                withContext(Dispatchers.IO) {
                    AppSessions.get(context).write(AppSessions.get(context).current(), null)
                    if (removeConfig) SecureConfigStore(context).clear()
                    SecureConfigStore.clearLegacy(context)
                }
                if (removeConfig) profile = null
                check(prefs(context).edit().putBoolean("switchPending", false).putBoolean("needsImport", false).commit())
                if (profile != null) gate.open()
                mutable.value = CloudAccessState(checking = false, ready = profile != null)
            } catch (e: Exception) {
                gate.revoke(); profile = null; actionId = null
                mutable.value = CloudAccessState(checking = false, needsImport = true, error = true); throw e
            } finally { VehicleWidgetProvider.updateAll(context) }
        }
    }
    fun sessionChanged(context: Context, saved: Boolean) {
        if (!ready) return
        mutable.value = mutable.value.copy(session = saved)
        if (!saved) { gate.revoke(); gate.open(); rotateActions(context) }
        VehicleWidgetProvider.updateAll(context)
        if (!saved) cancelJobs(context)
    }
    fun beginLogin(context: Context) {
        check(ready)
        gate.revoke(); rotateActions(context)
        mutable.value = mutable.value.copy(session = false)
        pauseWork(context)
        gate.open()
    }
    private fun rotateActions(context: Context) {
        actionId = UUID.randomUUID().toString()
        check(prefs(context).edit().putString("actionId", actionId).commit())
    }
    private fun revoke(context: Context) {
        gate.revoke(); mutable.value = mutable.value.copy(ready = false, session = false)
        AppSessions.get(context).advance(); rotateActions(context)
    }
    fun cancelJobs(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        listOf(3101, 3104, 4102, 4103, 4104, 4105).forEach(scheduler::cancel)
    }
    private fun pauseWork(context: Context) {
        cancelJobs(context)
        val assistant = AssistantStore.get(context)
        assistant.state.value.plans.forEach { DepartureScheduler.cancel(context, it.id) }
        assistant.edit(CloudReset::pause)
        listOf(CardActionService::class.java, PreparationService::class.java, TemperatureUpdateService::class.java)
            .forEach { context.stopService(Intent(context, it)) }
        val overview = OverviewStore.get(context)
        overview.edit { it.copy(actionInFlight = null, actionAt = null, refreshingAt = null, pendingBody = null, message = "云端尚未配置；保留上次记录") }
        OnlinePresenceStore(context).let { store -> store.load()?.let(store::clear) }
    }
}
