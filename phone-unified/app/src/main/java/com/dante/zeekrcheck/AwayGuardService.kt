package com.dante.zeekrcheck

import android.app.job.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.lifecycle.ViewModelProvider
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*

/** One schedule serves all widgets and the opted-in guard. Restarts never replay commands. */
class AwayGuardService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            var confirmAgain = false
            var confirmJourney = false
            try {
                val model = ViewModelProvider(application as VehicleApplication,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(application))[CheckViewModel::class.java]
                val reason = runCatching { SyncReason.valueOf(params.extras.getString("reason") ?: "PERIODIC") }.getOrDefault(SyncReason.PERIODIC)
                val report = model.syncOverview(reason)
                if (report != null) {
                    val decision = model.checkAwayGuard(report)
                    confirmAgain = decision.followUp; confirmJourney = decision.journeyFollowUp
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { AssistantStore.get(this@AwayGuardService).edit { it.copy(guardMessage = "本次后台检查未完成 · 未补发控制") } }
            finally {
                jobs.remove(params.jobId); jobFinished(params, false)
                if (params.jobId == HOME_CONFIRM_JOB) getSystemService(JobScheduler::class.java).cancel(HOME_CONFIRM_JOB)
                if (params.jobId == JOURNEY_CONFIRM_JOB) getSystemService(JobScheduler::class.java).cancel(JOURNEY_CONFIRM_JOB)
                if (confirmAgain) scheduleHomeConfirmation(this@AwayGuardService)
                if (confirmJourney) scheduleJourneyConfirmation(this@AwayGuardService)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        const val PERIODIC_JOB = 4102
        const val EVENT_JOB = 4103
        const val HOME_CONFIRM_JOB = 4104
        const val JOURNEY_CONFIRM_JOB = 4105
        fun hasWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return VehicleWidgetProvider.providers
                .any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        }
        fun needed(context: Context): Boolean {
            val state = AssistantStore.get(context).state.value
            return SyncPolicy.needsPeriodic(OverviewStore.get(context).state.value.vehicleKey != null,
                hasWidgets(context), state.widgetSyncEnabled, (state.guardEnabled || state.homeGuardEnabled) && !state.paused && state.home?.verified == true)
        }
        fun schedule(context: Context) {
            val manager = context.getSystemService(JobScheduler::class.java)
            val state = AssistantStore.get(context).state.value
            if (!state.homeGuardEnabled || state.paused || state.home?.verified != true) manager.cancel(HOME_CONFIRM_JOB)
            if ((!state.guardEnabled && !state.homeGuardEnabled) || state.paused || state.home?.verified != true) manager.cancel(JOURNEY_CONFIRM_JOB)
            if (!needed(context)) { manager.cancel(PERIODIC_JOB); manager.cancel(EVENT_JOB); manager.cancel(HOME_CONFIRM_JOB); manager.cancel(JOURNEY_CONFIRM_JOB); return }
            if (manager.getPendingJob(PERIODIC_JOB) != null) return
            manager.schedule(JobInfo.Builder(PERIODIC_JOB, ComponentName(context, AwayGuardService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(SyncPolicy.PERIOD_MS).setPersisted(true).build())
        }
        fun trigger(context: Context, reason: SyncReason) {
            if (!needed(context)) return
            val manager = context.getSystemService(JobScheduler::class.java)
            if (manager.getPendingJob(EVENT_JOB) != null) return
            manager.schedule(JobInfo.Builder(EVENT_JOB, ComponentName(context, AwayGuardService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0)
                .setExtras(PersistableBundle().apply { putString("reason", reason.name) }).build())
        }
        private fun scheduleHomeConfirmation(context: Context) {
            val manager = context.getSystemService(JobScheduler::class.java)
            if (manager.getPendingJob(HOME_CONFIRM_JOB) != null || !AssistantStore.get(context).claimHomeFollowUp()) return
            manager.schedule(JobInfo.Builder(HOME_CONFIRM_JOB, ComponentName(context, AwayGuardService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(HomeSentryGuard.FOLLOW_UP_DELAY_MS)
                .setExtras(PersistableBundle().apply { putString("reason", "HOME_CONFIRM") }).build())
        }
        private fun scheduleJourneyConfirmation(context: Context) {
            val manager = context.getSystemService(JobScheduler::class.java)
            if (manager.getPendingJob(JOURNEY_CONFIRM_JOB) != null || !AssistantStore.get(context).claimJourneyFollowUp()) return
            manager.schedule(JobInfo.Builder(JOURNEY_CONFIRM_JOB, ComponentName(context, AwayGuardService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(ParkingJourney.FOLLOW_UP_DELAY_MS)
                .setExtras(PersistableBundle().apply { putString("reason", SyncReason.JOURNEY_CONFIRM.name) }).build())
        }
    }
}
