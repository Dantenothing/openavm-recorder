package com.dante.zeekrcheck

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.lifecycle.ViewModelProvider
import com.dante.zeekrcheck.core.SyncReason
import kotlinx.coroutines.*

/** Read-only sync for lifecycle/background work and legacy refresh intents. Never starts HVAC. */
class WidgetRefreshService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val model = ViewModelProvider(application as VehicleApplication,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(application))[CheckViewModel::class.java]
                val interactive = params.extras.getBoolean("interactive", true)
                model.syncOverview(if (interactive) SyncReason.WIDGET else SyncReason.APP, interactive)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { OverviewStore.get(this@WidgetRefreshService).edit { it.copy(message = "查询未完成 · 保留上次记录") } }
            finally { jobs.remove(params.jobId); jobFinished(params, false) }
        }
        jobs[params.jobId] = job
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun request(context: Context, interactive: Boolean) {
            val store = OverviewStore.get(context)
            if (store.state.value.vehicleKey == null) return
            val manager = context.getSystemService(JobScheduler::class.java)
            val jobId = if (interactive) MANUAL_JOB_ID else VehicleWidgetProvider.JOB_ID
            if (manager.getPendingJob(jobId) != null) return
            if (!interactive && store.state.value.refreshing()) return
            if (!interactive && !AssistantStore.get(context).state.value.widgetSyncEnabled) return
            if (interactive && !store.state.value.refreshing()) store.edit { it.copy(message = "等待读取车辆状态") }
            val builder = JobInfo.Builder(jobId, ComponentName(context, WidgetRefreshService::class.java))
                .setExtras(PersistableBundle().apply { putBoolean("interactive", interactive) })
            if (interactive) builder.setOverrideDeadline(0) else builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (manager.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS && interactive)
                store.edit { it.copy(message = "刷新未能启动 · 请稍后再试") }
        }
        private const val MANUAL_JOB_ID = 3104
    }
}
