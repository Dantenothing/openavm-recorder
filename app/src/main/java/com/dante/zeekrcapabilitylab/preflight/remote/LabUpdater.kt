package com.dante.zeekrcapabilitylab.preflight.remote

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.sha
import kotlinx.serialization.json.*

internal class LabUpdater(private val context:Context,private val store:LabStore) {
    fun capabilities()=obj("sdk" to Build.VERSION.SDK_INT,"selfUpdateApi" to (Build.VERSION.SDK_INT>=31),
        "installAllowed" to context.packageManager.canRequestPackageInstalls(),
        "overlayAllowed" to android.provider.Settings.canDrawOverlays(context))
    @Suppress("DEPRECATION")
    private fun verify(hash:String,version:Long) {
        if(Build.VERSION.SDK_INT<31)error("SELF_UPDATE_REQUIRES_ANDROID_12")
        val apk=store.apk(hash)
        val pm=context.packageManager
        val installed=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate=pm.getPackageArchiveInfo(apk.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("APK_PARSE_FAILED")
        LabPolicy.verifyApk(context.packageName,candidate.packageName,
            installed.signingInfo?.apkContentsSigners.orEmpty().map { sha(it.toByteArray()) }.toSet(),
            candidate.signingInfo?.apkContentsSigners.orEmpty().map { sha(it.toByteArray()) }.toSet(),
            installed.longVersionCode,candidate.longVersionCode,version,sha(apk.readBytes()),hash,apk.length())
    }
    fun prepare(wire:LabWire,payload:JsonObject):JsonObject {
        check(store.state.flag("allowUpdates")) { "UPDATES_NOT_ENABLED" }
        val hash=payload.string("sha256");val version=payload.number("versionCode")
        val old=store.state["prepared"] as? JsonObject
        if(!store.apk(hash).isFile) wire.download(store.state.string("sessionId"),hash,store.apk(hash))
        verify(hash,version)
        store.change("prepared" to obj("sha256" to hash,"versionCode" to version))
        old?.string("sha256")?.takeIf { it!=hash && LabPolicy.hash.matches(it) }?.let { store.apk(it).delete() }
        return obj("reason" to "APK_PREPARED","sha256" to hash,"versionCode" to version,"bytes" to store.apk(hash).length())
    }
    fun install(command:JsonObject,continueAllowed:()->Boolean):Int {
        check(store.state.flag("allowUpdates") && context.packageManager.canRequestPackageInstalls()) { "INSTALL_PERMISSION_REQUIRED" }
        val payload=command["payload"]!!.jsonObject
        check(store.state["prepared"]==payload) { "APK_NOT_PREPARED" }
        verify(payload.string("sha256"),payload.number("versionCode"))
        val installer=context.packageManager.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(store.apk(payload.string("sha256")).length())
            if(Build.VERSION.SDK_INT>=31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id=installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val apk=store.apk(payload.string("sha256"))
                session.openWrite("base.apk",0,apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) };session.fsync(out) }
                store.change("installerSession" to id,"updatingCommand" to command.string("id"),
                    "updateFrom" to BuildConfig.VERSION_CODE,"updateTarget" to payload.number("versionCode"))
                val intent=Intent(context,LabUpdateReceiver::class.java).setAction(INSTALL_RESULT)
                    .putExtra("commandId",command.string("id"))
                val flags=PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0
                check(continueAllowed()) { "SESSION_OR_COMMAND_EXPIRED" }
                session.commit(PendingIntent.getBroadcast(context,id,intent,flags).intentSender)
            }
        } catch(t:Throwable) { runCatching { installer.abandonSession(id) };throw t }
        return id
    }
    companion object { const val INSTALL_RESULT="openavm.remote.INSTALL_RESULT" }
}

class LabUpdateReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==LabUpdater.INSTALL_RESULT) {
            val store=LabStore(context)
            val id=intent.getStringExtra("commandId").orEmpty()
            val session=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)
            if(id!=store.state.string("updatingCommand") || session.toLong()!=store.state.number("installerSession"))return
            val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
            val data=obj("commandId" to id,"sessionId" to session,"status" to status)
            store.change("installerCallback" to data)
            if(!RemoteLabService.installerEvent(data) && store.active())
                runCatching { ContextCompat.startForegroundService(context,Intent(context,RemoteLabService::class.java).setAction(RemoteLabService.RESUME)) }
            if(store.active() && status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION") val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if(confirmation!=null)runCatching {context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            }
        } else if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) {
            // This receiver runs in :remoteLab. Never resume an old session after a reboot or expiry.
            if(runCatching { LabStore(context).active() }.getOrDefault(false))
                runCatching { ContextCompat.startForegroundService(context,Intent(context,RemoteLabService::class.java).setAction(RemoteLabService.RESUME)) }
        }
    }
}
