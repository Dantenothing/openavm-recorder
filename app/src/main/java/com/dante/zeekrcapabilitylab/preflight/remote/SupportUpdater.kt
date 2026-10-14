package com.dante.zeekrcapabilitylab.preflight.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.preflight.PreflightActivity
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.sha
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class SupportUpdater(private val context: Context, private val store: LabStore) {
    private fun consent() = check(BuildConfig.RECORDER_SUPPORT_ENABLED && store.active() && store.state.flag("allowUpdates")) { "UPDATES_NOT_ENABLED" }
    @Suppress("DEPRECATION")
    fun verify(p: JsonObject): File {
        if (Build.VERSION.SDK_INT < 28) error("SUPPORT_UPDATE_REQUIRES_ANDROID_9")
        SupportUpdatePolicy.target(p,BuildConfig.VERSION_CODE.toLong())
        val apk = store.apk(p.string("sha256"))
        check(apk.isFile && apk.length() == p.number("bytes")) { "APK_SIZE_MISMATCH" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val buffer=ByteArray(65536); while(true) {
            val n=input.read(buffer); if(n<0)break; digest.update(buffer,0,n)
        } }
        check(digest.digest().joinToString("") { "%02x".format(it) } == p.string("sha256")) { "APK_INTEGRITY_MISMATCH" }
        val pm=context.packageManager
        val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate=pm.getPackageArchiveInfo(apk.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("APK_PARSE_FAILED")
        check(candidate.packageName==context.packageName && candidate.longVersionCode==p.number("versionCode")) { "APK_PACKAGE_OR_VERSION_MISMATCH" }
        val signers=installed.signingInfo?.apkContentsSigners.orEmpty().map { sha(it.toByteArray()) }.toSet()
        check(signers.isNotEmpty() && signers==candidate.signingInfo?.apkContentsSigners.orEmpty().map { sha(it.toByteArray()) }.toSet()) { "APK_SIGNER_MISMATCH" }
        ZipFile(apk).use { zip ->
            check(zip.entries().asSequence().none { it.name.startsWith("assets/connection/") }) { "APK_CONNECTION_ASSET_FORBIDDEN" }
            val entry=zip.getEntry("assets/preflight/build-evidence.json") ?: error("APK_BUILD_EVIDENCE_MISSING")
            check(entry.size in 1..262144) { "APK_BUILD_EVIDENCE_INVALID" }
            val evidence=zip.getInputStream(entry).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            val flags=evidence["flags"] as? JsonObject ?: error("APK_BUILD_EVIDENCE_INVALID")
            check(!flags.flag("PRIVATE_EMULATOR_BUILD") || Build.HARDWARE=="ranchu") { "APK_EMULATOR_ONLY" }
            val nativeAbis=zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
            check(nativeAbis.isEmpty() || Build.SUPPORTED_ABIS.any { it in nativeAbis }) { "APK_ABI_MISMATCH" }
            check(evidence.string("applicationId")==context.packageName && evidence.number("versionCode")==p.number("versionCode") &&
                evidence.string("buildId")==p.string("buildId") && flags.flag("RECORDER_SUPPORT_ENABLED") &&
                flags.number("DIAGNOSTIC_RUNNER_VERSION")==7L) { "APK_SUPPORT_BUILD_MISMATCH" }
        }
        return apk
    }
    fun prepare(wire: LabWire, p: JsonObject): JsonObject {
        consent(); SupportUpdatePolicy.target(p,BuildConfig.VERSION_CODE.toLong())
        val old=store.state["supportPrepared"] as? JsonObject
        val file=store.apk(p.string("sha256"))
        if(!file.isFile) wire.download(store.state.string("sessionId"),p.string("sha256"),file,SupportUpdatePolicy.MAX_APK)
        try { verify(p) } catch(t:Throwable) { file.delete(); throw t }
        consent(); store.change("supportPrepared" to p)
        old?.string("sha256")?.takeIf { it!=p.string("sha256") && LabPolicy.hash.matches(it) }?.let { store.apk(it).delete() }
        return p
    }
    fun install(command: JsonObject, continueAllowed: () -> Boolean): Int {
        consent()
        if (Build.VERSION.SDK_INT < 31) error("SUPPORT_INSTALL_REQUIRES_ANDROID_12")
        check(context.packageManager.canRequestPackageInstalls()) { "INSTALL_PERMISSION_REQUIRED" }
        val p=command.getValue("payload").jsonObject
        check(store.state["supportPrepared"]==p) { "APK_NOT_PREPARED" }
        val apk=verify(p); val installer=context.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName); setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id=installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk",0,apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                consent(); check(continueAllowed()) { "SESSION_OR_COMMAND_EXPIRED" }
                store.change("supportUpdate" to obj("phase" to "COMMITTED","commandId" to command.string("id"),
                    "installerSession" to id,"target" to p,"fromVersion" to BuildConfig.VERSION_CODE,
                    "committedElapsed" to SystemClock.elapsedRealtime(),"resumeRequested" to false))
                val intent=Intent(context,SupportUpdateReceiver::class.java).setAction(RESULT).putExtra("commandId",command.string("id"))
                session.commit(PendingIntent.getBroadcast(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
            }
        } catch(t:Throwable) { runCatching {installer.abandonSession(id)}; throw t }
        return id
    }
    companion object {
        const val RESULT="openavm.support.INSTALL_RESULT"
        fun buildId(context:Context)=runCatching {context.assets.open("preflight/build-evidence.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.string("buildId") }}.getOrDefault("UNAVAILABLE")
    }
}

/** No boot receiver. Resume only the exact update requested during the same unexpired owner session. */
class SupportUpdateReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(!BuildConfig.RECORDER_SUPPORT_ENABLED)return
        val store=LabStore(context)
        val pending=store.state["supportUpdate"] as? JsonObject ?: return
        if(intent.action==SupportUpdater.RESULT) {
            if(intent.getStringExtra("commandId")!=pending.string("commandId") ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1).toLong()!=pending.number("installerSession"))return
            val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
            val next=JsonObject(pending+obj("installerStatus" to status,
                "phase" to when {
                    pending.string("phase")=="CANCELLED" -> "CANCELLED"
                    status==PackageInstaller.STATUS_PENDING_USER_ACTION -> "WAITING_USER"
                    status>0 -> "FAILED"
                    else -> pending.string("phase")
                }))
            store.change("supportUpdate" to next)
            RemoteLabService.supportInstallerEvent()
            // Do not surprise an absent owner with an unbounded installer activity. The session
            // reports WAITING_USER; its UI offers the system confirmation when the owner returns.
            if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION") val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                SupportInstallConfirmation.pending=confirmation
            }
        }
        if(intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED && intent.action!=SupportUpdater.RESULT)return
        val current=store.state["supportUpdate"] as? JsonObject ?: return
        val command=store.records()[current.string("commandId")] as? JsonObject ?: return
        val commandAllowsResume=command.string("state") in setOf("ACCEPTED","RUNNING") ||
            command.string("state")=="SUCCEEDED" && (command["result"] as? JsonObject)?.string("reason")=="UPDATE_INSTALLED"
        if(SupportUpdatePolicy.canResume(current,BuildConfig.VERSION_CODE.toLong(),SupportUpdater.buildId(context),
                store.active(),store.state.flag("allowUpdates")) && commandAllowsResume && !current.flag("resumeRequested")) {
            store.change("supportUpdate" to JsonObject(current+obj("phase" to "INSTALLED","resumeRequested" to true)))
            runCatching {ContextCompat.startForegroundService(context,Intent(context,RemoteLabService::class.java).setAction(RemoteLabService.RESUME))}
            runCatching {context.startActivity(Intent(context,PreflightActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
                .onFailure { store.change("updateResumeError" to "DIAGNOSTIC_PAGE_REQUIRES_OWNER") }
        }
    }
}

/** Isolated-process system intent, never persisted or accepted from remote JSON. */
internal object SupportInstallConfirmation { @Volatile var pending: Intent?=null }
