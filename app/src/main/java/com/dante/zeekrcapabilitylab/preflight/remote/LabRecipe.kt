package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.sha
import kotlinx.serialization.json.*

internal fun canonical(value:JsonElement):String = when(value) {
    is JsonObject -> value.keys.sorted().joinToString(",","{","}") { JsonPrimitive(it).toString()+":"+canonical(value.getValue(it)) }
    is JsonArray -> value.joinToString(",","[","]") { canonical(it) }
    else -> value.toString()
}
internal fun payloadHash(value:JsonElement)=sha(canonical(value).toByteArray(Charsets.UTF_8))
internal fun strictNumber(value:JsonObject,key:String):Long? = (value[key] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
internal fun fields(value:JsonObject,required:Set<String>,optional:Set<String> = emptySet()) {
    require(value.keys.containsAll(required) && value.keys.all { it in required || it in optional }) { "UNKNOWN_OR_MISSING_FIELD" }
}

internal data class LabExperiment(val profile:String,val bitrateBps:Int,val iFrameIntervalSeconds:Int,
    val bitrateMode:String?=null,val avcProfile:String?=null) {
    val reference get()=profile=="REFERENCE_INPUT"
    val health get()=profile.startsWith("HEALTH_")
    val shared get()=profile!="HEALTH_DIRECT"
    val high get()=profile=="HIGH_SHARED"
    val rateControlled get()=profile=="RATE_CONTROL_SHARED"
    val realCamera get()=profile in cameraProfiles
    fun cameraPlan()=com.dante.zeekrcapabilitylab.preflight.continuous.CameraProbePlan(
        requireNotNull(cameraProfiles[profile]),true)
    fun json():JsonObject=if(reference || realCamera)obj("profile" to profile) else obj("profile" to profile,
        "bitrateBps" to bitrateBps,"iFrameIntervalSeconds" to iFrameIntervalSeconds).let {
            if(rateControlled)JsonObject(it+obj("bitrateMode" to bitrateMode,"avcProfile" to avcProfile)) else it
        }
    companion object {
        val cameraProfiles=mapOf("CAMERA_SMOKE" to 20,"CAMERA_MINUTE" to 240,"CAMERA_SOAK" to 600)
        val profiles=setOf("REFERENCE_INPUT","HEALTH_DIRECT","HEALTH_SHARED","TARGET_SHARED","HIGH_SHARED","RATE_CONTROL_SHARED")+cameraProfiles.keys
        fun parse(v:JsonObject):LabExperiment {
            fields(v,setOf("profile"),setOf("bitrateBps","iFrameIntervalSeconds","bitrateMode","avcProfile"))
            val profile=v.string("profile");require(profile in profiles) { "EXPERIMENT_NOT_SUPPORTED" }
            if(profile in cameraProfiles) {
                require(v.size==1) {"CAMERA_OVERRIDES_FORBIDDEN"}
                return LabExperiment(profile,28_000_000,1)
            }
            if(profile=="REFERENCE_INPUT")require(v.size==1) { "REFERENCE_OVERRIDES_FORBIDDEN" }
            val controlled=profile=="RATE_CONTROL_SHARED"
            if(controlled) {
                require(v.string("bitrateMode") in setOf("VBR","CBR")) { "BITRATE_MODE_NOT_SUPPORTED" }
                require(v.string("avcProfile") in setOf("BASELINE","HIGH")) { "AVC_PROFILE_REQUIRED" }
            } else require("bitrateMode" !in v && "avcProfile" !in v) { "LEGACY_PROFILE_OVERRIDES_FORBIDDEN" }
            val health=profile.startsWith("HEALTH_")
            val bitrate=if("bitrateBps" in v)requireNotNull(strictNumber(v,"bitrateBps")) {"BITRATE_OUT_OF_RANGE"} else if(health)3_000_000L else 28_000_000L
            require(bitrate in (if(health)1_000_000L..3_000_000L else 8_000_000L..28_000_000L)) {"BITRATE_OUT_OF_RANGE"}
            val interval=if("iFrameIntervalSeconds" in v)strictNumber(v,"iFrameIntervalSeconds") else 1L
            require(interval in setOf(1L,2L)) { "IFRAME_OUT_OF_RANGE" }
            return LabExperiment(profile,bitrate.toInt(),requireNotNull(interval).toInt(),
                if(controlled)v.string("bitrateMode") else null,if(controlled)v.string("avcProfile") else null)
        }
    }
}

internal data class LabStep(val op:String,val experiment:LabExperiment?=null,val event:String?=null,val stage:String?=null,val timeoutMs:Long=0)
internal data class LabRecipe(val steps:List<LabStep>,val hash:String) {
    companion object {
        const val RUNNER_VERSION=3
        const val MAX_MS=30*60*1000L
        val stagePattern=Regex("(health|full|stress)_(QUERY|GL_ALLOCATE|CODEC_CONFIGURE|PACED_ENCODING|COLD_FILE_DECODE)|camera_(QUERY|GL_ALLOCATE|CODEC_CONFIGURE|OPEN|LIVE|WINDOW_DETACHED|WINDOW_REJOINED|STOP_PRODUCER|COLD_FILE_DECODE)")
        fun parse(value:JsonObject):LabRecipe {
            fields(value,setOf("recipeVersion","steps"));require(strictNumber(value,"recipeVersion")==1L) {"RECIPE_VERSION_INVALID"}
            val flat=ArrayList<JsonObject>()
            fun expand(values:JsonArray,depth:Int) {
                require(values.size in 1..24) {"RECIPE_STEP_LIMIT"}
                for(raw in values) {
                    val v=raw as? JsonObject ?: error("OBJECT_REQUIRED")
                    if(v.string("op")=="REPEAT") {
                        fields(v,setOf("op","times","steps"));val count=strictNumber(v,"times")
                        require(depth==0 && count in 1L..3L) {"REPEAT_LIMIT"}
                        repeat(requireNotNull(count).toInt()) { expand(v["steps"] as? JsonArray ?: error("RECIPE_STEP_LIMIT"),depth+1) }
                    } else flat+=v
                    require(flat.size<=24) {"RECIPE_STEP_LIMIT"}
                }
            }
            expand(value["steps"] as? JsonArray ?: error("RECIPE_VERSION_INVALID"),0)
            var active=false;var runs=0;var waits=0L
            val steps=flat.map { v -> when(val op=v.string("op")) {
                "START"->{fields(v,setOf("op","experiment"));require(!active) {"PREVIOUS_RUN_NOT_JOINED"};active=true;runs++
                    LabStep(op,LabExperiment.parse(v["experiment"] as? JsonObject ?: error("OBJECT_REQUIRED")))}
                "WAIT"->{fields(v,setOf("op","event","timeoutMs"),setOf("stage"));require(active) {"NO_ACTIVE_RUN"}
                    val timeout=strictNumber(v,"timeoutMs");require(timeout in 1_000L..1_500_000L) {"WAIT_LIMIT"};waits+=requireNotNull(timeout)
                    val event=v.string("event");require(event in setOf("RUN_FINISHED","STAGE")) {"EVENT_NOT_SUPPORTED"}
                    if(event=="STAGE")require(stagePattern.matches(v.string("stage"))) {"STAGE_NOT_SUPPORTED"}
                    else {require("stage" !in v) {"UNKNOWN_OR_MISSING_FIELD"};active=false}
                    LabStep(op,event=event,stage=v.string("stage").ifEmpty {null},timeoutMs=timeout)}
                "SNAPSHOT"->{fields(v,setOf("op"));LabStep(op)}
                "CANCEL"->{fields(v,setOf("op"));require(active) {"NO_ACTIVE_RUN"};LabStep(op)}
                else->error("STEP_NOT_SUPPORTED")
            } }
            require(!active && runs in 1..3 && waits<=MAX_MS) {"RECIPE_BUDGET_OR_UNJOINED_RUN"}
            return LabRecipe(steps,payloadHash(value))
        }
    }
}

internal object LabContract {
    val kinds=setOf("CAPABILITIES","RUN_RECIPE","STOP","REPORT","DIAGNOSTICS_LIST","DIAGNOSTICS_RETIRE")
    fun payload(kind:String,p:JsonObject) {
        require(kind in kinds) {"COMMAND_NOT_SUPPORTED"}
        when(kind) {
            "RUN_RECIPE"->LabRecipe.parse(p)
            "STOP"->{fields(p,setOf("commandId"));require(LabPolicy.uuid.matches(p.string("commandId"))) {"CANCEL_TARGET_INVALID"}}
            "DIAGNOSTICS_RETIRE"->{
                fields(p,setOf("inventoryHash","operationIds","acknowledgeEvidenceLoss"))
                require(LabPolicy.hash.matches(p.string("inventoryHash")) && p["acknowledgeEvidenceLoss"]==JsonPrimitive(true)) {"EVIDENCE_REVIEW_REQUIRED"}
                val ids=(p["operationIds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf {x->x.isString}?.content.orEmpty() }.orEmpty()
                require(ids.size in 1..8 && ids.distinct().size==ids.size && ids.all {LabPolicy.uuid.matches(it)}) {"EVIDENCE_LIST_INVALID"}
            }
            else->fields(p,emptySet())
        }
    }
    fun validate(c:JsonObject,build:String) {
        require(strictNumber(c,"protocolVersion")==2L && strictNumber(c,"expectedRunnerVersion")==LabRecipe.RUNNER_VERSION.toLong() &&
            build.matches(Regex("preflight-[0-9a-f]{20}")) && c.string("expectedBuildId")==build) {"COMMAND_CONTRACT_INVALID"}
        val p=c["payload"] as? JsonObject ?: error("COMMAND_PAYLOAD_INVALID")
        require(c.string("payloadHash")==payloadHash(p)) {"PAYLOAD_HASH_MISMATCH"};payload(c.string("kind"),p)
    }
    fun capabilities()=obj("runnerVersion" to LabRecipe.RUNNER_VERSION,"recipeVersion" to 1,"operations" to kinds.sorted(),
        "profiles" to LabExperiment.profiles.sorted(),"frameRate" to 30,"fileCount" to 4,"healthFrames" to 120,"fullFrames" to 600,
        "healthBitrateBps" to listOf(1_000_000,3_000_000),"fullBitrateBps" to listOf(8_000_000,28_000_000),
        "iFrameIntervalSeconds" to listOf(1,2),"parametersApply" to "NEXT_RUN_AFTER_CONFIRMED_CLEANUP",
        "rateControlTrial" to obj("profile" to "RATE_CONTROL_SHARED","bitrateModes" to listOf("VBR","CBR"),
            "avcProfiles" to listOf("BASELINE","HIGH"),"requiresPositiveDeclaration" to true,
            "loadAcceptance" to "MEASURED_0_8_TO_1_2_OF_REQUEST","referenceAcceptance" to false),
        "cameraTrials" to obj("profiles" to LabExperiment.cameraProfiles,"source" to "SURROUND_1280x5140",
            "encodedRaster" to listOf(3840,1728),"realWindows" to listOf("PAGE","APPLICATION_OVERLAY"),
            "sameDeviceSessionCodecAcrossCuts" to true,"videoUploaded" to false,"bitrateAcceptance" to "OBSERVE_ONLY",
            "productionDefaultChanged" to false),
        "maxSteps" to 24,"maxRuns" to 3,"maxRecipeMs" to LabRecipe.MAX_MS,"arbitraryCode" to false,"realCamera" to true)
}
