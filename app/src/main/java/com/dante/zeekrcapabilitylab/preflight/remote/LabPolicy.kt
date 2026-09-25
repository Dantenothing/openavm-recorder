package com.dante.zeekrcapabilitylab.preflight.remote

import kotlinx.serialization.json.*

internal fun JsonObject.string(key:String)=(get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key:String)=(get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.flag(key:String)=(get(key) as? JsonPrimitive)?.booleanOrNull==true
internal object LabPolicy {
    const val DURATION_MS=4*60*60*1000L
    const val MAX_APK=4*1024*1024L
    val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val hash=Regex("[0-9a-f]{64}")
    val kinds=setOf("CAPABILITIES","P1_BASIC","P1_INPUT","STOP","REPORT","OPEN_HARNESS","PREPARE_UPDATE","INSTALL_UPDATE")
    val terminal=setOf("SUCCEEDED","FAILED","REJECTED","INTERRUPTED")
    fun recoveryState(state:String)=if(state in setOf("ACCEPTED","RUNNING")) "INTERRUPTED" else state
    fun canAccept(id:String,records:JsonObject)=uuid.matches(id) && id !in records && records.size<128
    fun testPassed(status:JsonObject,kind:String="CAPABILITIES"):Boolean {
        val required=setOf("build","public_capabilities")+when(kind) {
            "CAPABILITIES"->emptySet()
            "P1_BASIC"->setOf("egl_small","usb_fd","p1_basic")
            "P1_INPUT"->setOf("egl_small","usb_fd","p1_input","oes_input","shared_pool_pressure","encoded_load")
            else->return false
        }
        val deferred=setOf("synthetic_encode_decode","lossless_repack","candidate_camera_smoke","candidate_soak",
            "window_scripts","pressure","real_source_overload","oes_input","shared_pool_pressure")-required
        val entries=(status["results"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.split(": ",limit=2) }
        if(entries.any {it.size!=2} || entries.map {it[0]}.toSet().size!=entries.size)return false
        val results=entries.associate {it[0] to it[1]}
        return !status.flag("blocked") && status.string("cleanup")=="CONFIRMED" &&
            status.string("reason").isBlank() && required.all {results[it]=="PASS"} &&
            results.filterKeys {it !in deferred}.values.all {it=="PASS" || it=="SKIP"}
    }
    fun active(state:JsonObject,elapsed:Long,boot:Int):Boolean = state.flag("armed") && boot>=0 &&
        state.number("boot")==boot.toLong() && elapsed>=state.number("startedElapsed") &&
        elapsed<state.number("deadlineElapsed") && state.number("deadlineElapsed")-state.number("startedElapsed")==DURATION_MS
    fun validateCommand(command:JsonObject,session:String,version:Int,serverNow:Long,build:String="") {
        require(uuid.matches(command.string("id")) && command.string("session")==session) { "COMMAND_IDENTITY_INVALID" }
        require(command.string("kind") in if(version>=80)LabContract.kinds else kinds) { "COMMAND_NOT_SUPPORTED" }
        require(command.number("expires")>serverNow && command.number("issued")<=serverNow &&
            command.number("expires")-command.number("issued") in 1..180_000) { "COMMAND_EXPIRED" }
        require(command.number("expected_version")==version.toLong()) { "COMMAND_VERSION_MISMATCH" }
        if(version>=80) {LabContract.validate(command,build);return}
        val payload=command["payload"] as? JsonObject ?: error("COMMAND_PAYLOAD_INVALID")
        if(command.string("kind") in setOf("PREPARE_UPDATE","INSTALL_UPDATE")) {
            require(payload.keys==setOf("sha256","versionCode") && hash.matches(payload.string("sha256")) &&
                payload.number("versionCode")>version) { "UPDATE_IDENTITY_INVALID" }
        } else require(payload.isEmpty()) { "UNSUPPORTED_PARAMETERS" }
    }
    fun verifyApk(expectedPackage:String,actualPackage:String?,installedSigners:Set<String>,candidateSigners:Set<String>,
                  installedVersion:Long,candidateVersion:Long,expectedVersion:Long,actualHash:String,expectedHash:String,bytes:Long) {
        require(actualPackage==expectedPackage) { "APK_PACKAGE_MISMATCH" }
        require(installedSigners.isNotEmpty() && installedSigners==candidateSigners) { "APK_SIGNER_MISMATCH" }
        require(candidateVersion==expectedVersion && candidateVersion>installedVersion) { "APK_VERSION_MISMATCH" }
        require(hash.matches(expectedHash) && actualHash==expectedHash && bytes in 1024..MAX_APK) { "APK_INTEGRITY_MISMATCH" }
    }
}
