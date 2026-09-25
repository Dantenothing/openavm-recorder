package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject

/** Deterministic workflow state, ticked by the visible main-process bridge. No network/native waits. */
internal class LabRecipeEngine(val commandId:String,private val recipe:LabRecipe,private val started:Long,private val host:Host) {
    data class View(val runId:String,val active:Boolean,val cleanup:String,val outcome:String,val stages:Set<String>,val detail:JsonObject)
    interface Host {
        fun start(experiment:LabExperiment):String?
        fun view():View
        fun cancel(runId:String)
        fun event(type:String,data:JsonObject)
    }
    var childRun:String?=null;private set
    var terminal=false;private set
    var result:JsonObject?=null;private set
    var phase="READY";private set
    private var step=0
    private var entered=started
    private var cancelling=false
    private var cancelAt=0L
    private var cancelReason="USER_CANCELLED"
    private var plannedCancel=false
    private var anyCancelled=false
    private val outcomes=ArrayList<JsonObject>()
    fun snapshot()=obj("commandId" to commandId,"recipeHash" to recipe.hash,"step" to step,"phase" to phase,
        "runId" to childRun,"terminal" to terminal,"result" to result)
    fun cancel(now:Long,reason:String="USER_CANCELLED") {
        if(terminal || cancelling)return
        cancelling=true;cancelAt=now;cancelReason=reason;phase="CANCELLING"
        childRun?.let(host::cancel)
        host.event("CANCEL_REQUESTED",obj("commandId" to commandId,"runId" to childRun,"reason" to reason))
    }
    private fun finish(outcome:String,cleanup:String,reason:String) {
        if(terminal)return
        terminal=true;phase="FINISHED"
        result=obj("commandState" to "ACCEPTED","runState" to "FINISHED","outcome" to outcome,"cleanupState" to cleanup,
            "reason" to reason,"recipeHash" to recipe.hash,"runs" to outcomes.toList(),"runId" to childRun)
        host.event("RECIPE_FINISHED",result!!)
    }
    fun tick(now:Long,online:Boolean) {
        if(terminal)return
        if(now-started>=LabRecipe.MAX_MS && !cancelling)cancel(now,"RECIPE_DEADLINE")
        if(cancelling) {
            val child=childRun
            if(child==null){finish(if(cancelReason=="USER_CANCELLED")"CANCELLED" else "INCONCLUSIVE","CONFIRMED",cancelReason);return}
            val v=host.view()
            if(v.runId==child && !v.active) {
                outcomes+=obj("runId" to child,"outcome" to v.outcome,"cleanupState" to v.cleanup)
                finish(if(v.cleanup!="CONFIRMED" || cancelReason!="USER_CANCELLED")"INCONCLUSIVE" else "CANCELLED",v.cleanup,cancelReason)
            } else if(now-cancelAt>=40_000)finish("INCONCLUSIVE","UNKNOWN","CANCEL_CLEANUP_UNCONFIRMED")
            return
        }
        // At most 24 pure steps; every START/WAIT yields before a subsequent native start.
        while(step<recipe.steps.size && !terminal) {
            val next=recipe.steps[step]
            when(next.op) {
                "START"->{
                    if(!online){phase="WAITING_FOR_CONNECTION";return}
                    val exp=requireNotNull(next.experiment)
                    val id=host.start(exp)
                    if(id==null){finish("INCONCLUSIVE","UNKNOWN","START_GATE_REJECTED");return}
                    childRun=id;plannedCancel=false;phase="RUNNING"
                    host.event("EXPERIMENT_STARTED",obj("commandId" to commandId,"runId" to id,"configuration" to exp.json(),
                        "configurationHash" to payloadHash(exp.json()),"referenceAcceptance" to exp.reference))
                    step++;entered=now;return
                }
                "WAIT"->{
                    val v=host.view();val matches=v.runId==childRun
                    if(matches && !v.active) {
                        if(v.cleanup!="CONFIRMED"){finish("INCONCLUSIVE",v.cleanup,"CLEANUP_UNCONFIRMED");return}
                        if(next.event!="RUN_FINISHED"){finish("FAIL",v.cleanup,"EVENT_NOT_OBSERVED_BEFORE_END");return}
                        outcomes+=obj("runId" to childRun,"outcome" to v.outcome,"cleanupState" to v.cleanup)
                        anyCancelled=anyCancelled || v.outcome=="CANCELLED"
                        childRun=null
                        if(v.outcome!="PASS" && !(plannedCancel && v.outcome=="CANCELLED")) {finish(v.outcome,v.cleanup,"EXPERIMENT_NOT_PASS");return}
                    } else if(next.event=="STAGE" && matches && next.stage in v.stages) {
                        host.event("EXPECTED_STAGE_OBSERVED",obj("runId" to childRun,"stage" to next.stage))
                    } else {
                        phase="WAIT_${next.event}"
                        if(now-entered>=next.timeoutMs)cancel(now,"WAIT_TIMEOUT")
                        return
                    }
                }
                "SNAPSHOT"->host.event("EXPERIMENT_SNAPSHOT",host.view().detail)
                "CANCEL"->{plannedCancel=true;childRun?.let(host::cancel);host.event("CANCEL_REQUESTED",obj("runId" to childRun,"planned" to true))}
            }
            step++;entered=now
        }
        if(!terminal && step==recipe.steps.size)finish(if(anyCancelled)"CANCELLED" else "PASS","CONFIRMED","RECIPE_COMPLETED")
    }
}
