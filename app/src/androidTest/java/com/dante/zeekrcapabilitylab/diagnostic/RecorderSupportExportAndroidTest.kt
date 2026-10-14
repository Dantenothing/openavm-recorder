package com.dante.zeekrcapabilitylab.diagnostic

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.ui.product.RecorderDiagnosticsDialog
import com.dante.zeekrcapabilitylab.ui.product.RecorderSupportSaveModel
import com.dante.zeekrcapabilitylab.ui.product.SettingsScreen
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import kotlinx.serialization.json.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Injects only the document-picker result; collection and Android document IO are real. */
class RecorderSupportExportAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var oldLanguage = AppLanguageMode.SYSTEM
    private var request: Int? = null
    private var requestedAt = 0L
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val intent = contract.createIntent(context, input)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals("application/json", intent.type)
            assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("OpenAVM-diagnostics-"))
            request = requestCode; requestedAt = System.currentTimeMillis()
        }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    @Before fun before() {
        assumeTrue(Build.HARDWARE == "ranchu")
        assertFalse(CameraRecordingService.isRunning()); assertFalse(StandaloneMirrorService.isRunning())
        assertTrue(CanaryCameraInterlock.normalIdle())
        oldLanguage = AppLanguage.mode.value
        AppLanguage.setMode(context, AppLanguageMode.ENGLISH)
    }
    @After fun after() {
        AppLanguage.setMode(context, oldLanguage)
        assertFalse("Export must not start recording", CameraRecordingService.isRunning())
        assertFalse("Export must not open preview", StandaloneMirrorService.isRunning())
        assertTrue(CanaryCameraInterlock.normalIdle())
    }
    private fun show(): StateRestorationTester = StateRestorationTester(compose).also { restore ->
        restore.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ZeekrCapabilityLabTheme { RecorderDiagnosticsDialog {} }
            }
        }
    }
    private fun ready(label: String = "Export diagnostics file") {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun requestFile(label: String = "Export diagnostics file") {
        ready(label)
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(20_000) { request != null }
    }
    private fun document() = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "OpenAVM-export-test-${UUID.randomUUID()}.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }))

    @Test fun ordinarySettingsExposeTheRestorableDialogWithoutEnablingDeveloperTools() {
        val settings = SettingsStore.get(context)
        val previous = settings.developerModeEnabled
        settings.setDeveloperModeEnabled(false)
        try {
            val restore = StateRestorationTester(compose)
            restore.setContent { ZeekrCapabilityLabTheme { SettingsScreen() } }
            compose.onNodeWithText("Recording diagnostics").performScrollTo().performClick()
            ready()
            restore.emulateSavedInstanceStateRestore()
            ready()
            assertFalse(settings.developerModeEnabled)
        } finally { settings.setDeveloperModeEnabled(previous) }
    }

    @Test fun oneDocumentSurvivesSavedStateRestoreAndContainsPassiveSnapshots() {
        val restore = show()
        requestFile()
        val snapshotLatestTime = requestedAt
        restore.emulateSavedInstanceStateRestore()
        val uri = document()
        try {
            compose.runOnIdle { registry.dispatchResult(request!!, Activity.RESULT_OK, Intent().setData(uri)) }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Diagnostics saved as one file. Attach it to your support message.").fetchSemanticsNodes().isNotEmpty() }
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue(bytes.size in 1..RecorderSupportReport.MAX_BYTES)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            assertEquals(RecorderSupportReport.FORMAT, value["format"]!!.jsonPrimitive.content)
            assertTrue("Restoration must save the requested snapshot, not resample another run",
                value["exportedAtEpochMs"]!!.jsonPrimitive.long <= snapshotLatestTime)
            assertTrue(value["recorder"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
            assertTrue(value["away"]!!.jsonObject["available"]!!.jsonPrimitive.boolean)
            assertFalse(value["videoIncluded"]!!.jsonPrimitive.boolean)
            assertFalse(value["coordinatesIncluded"]!!.jsonPrimitive.boolean)
            File(context.filesDir, "support-export-verified.json").writeBytes(bytes)
            capture("support-export-en.png")
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun cancellingThePickerAllowsAnotherExportInChinese() {
        AppLanguage.setMode(context, AppLanguageMode.SIMPLIFIED_CHINESE)
        show(); requestFile("导出诊断文件")
        compose.runOnIdle { registry.dispatchResult(request!!, Activity.RESULT_CANCELED, null) }
        ready("导出诊断文件")
        compose.onAllNodesWithText("诊断已保存为一个文件，可直接作为附件发送。").assertCountEquals(0)
        capture("support-export-zh.png")
    }

    @Test fun restoringTheDialogDuringDocumentWriteDoesNotCancelOrDuplicateIt() {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val model = RecorderSupportSaveModel { application, snapshot, uri ->
            writes.incrementAndGet()
            entered.complete(Unit)
            proceed.await()
            application.contentResolver.openOutputStream(uri, "wt")!!.use { RecorderSupportReport.write(snapshot, it) }
        }
        compose.runOnIdle {
            ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
            })[RecorderSupportSaveModel::class.java]
        }
        val restore = show()
        requestFile()
        val uri = document()
        try {
            compose.runOnIdle { registry.dispatchResult(request!!, Activity.RESULT_OK, Intent().setData(uri)) }
            compose.waitUntil(20_000) { entered.isCompleted }
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Export diagnostics file").assertIsNotEnabled()
            compose.runOnIdle { proceed.complete(Unit) }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Diagnostics saved as one file. Attach it to your support message.").fetchSemanticsNodes().isNotEmpty() }
            ready()
            assertEquals(1, writes.get())
            val value = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            assertEquals(RecorderSupportReport.FORMAT, value["format"]!!.jsonPrimitive.content)
        } finally {
            proceed.complete(Unit)
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun aMissingDestinationShowsFailureAndAllowsRetry() {
        show(); requestFile()
        val uri = document()
        context.contentResolver.delete(uri, null, null)
        compose.runOnIdle { registry.dispatchResult(request!!, Activity.RESULT_OK, Intent().setData(uri)) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Could not save the file. Try another folder.").fetchSemanticsNodes().isNotEmpty() }
        ready()
        compose.onAllNodesWithText("Diagnostics saved as one file. Attach it to your support message.").assertCountEquals(0)
    }
    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
