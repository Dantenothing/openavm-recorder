package com.dante.zeekrcapabilitylab.diagnostic

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticSettings
import com.dante.zeekrcapabilitylab.preflight.obj
import com.dante.zeekrcapabilitylab.preflight.sha
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Opt-in fixture, no camera use. Only a disposable emulator may create this synthetic identity.
 * Its one-way token hash is enrolled separately by the private relay administrator. No token leaves
 * Android Keystore. This test is not part of normal device test runs or the delivered APK. */
class SupportUpdateFixtureAndroidTest {
    @Test fun prepareDisposableEmulatorIdentity() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("supportUpdateFixture")=="disposable-emulator")
        check(Build.HARDWARE=="ranchu" && BuildConfig.RECORDER_SUPPORT_ENABLED)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val settings=DiagnosticSettings(context)
        assertNull("Must not reuse owner credentials",settings.load())
        val connection=settings.prepare(BuildConfig.DIAGNOSTIC_ENDPOINT)
        settings.paired()
        AppLanguage.setMode(context,AppLanguageMode.ENGLISH)
        File(context.getExternalFilesDir(null),"support-update-fixture.json").writeText(obj(
            "syntheticEmulator" to true,"hardware" to Build.HARDWARE,"deviceId" to connection.device,
            "tokenHash" to sha(connection.token.toByteArray())).toString())
        assertTrue(settings.load()!!.paired)
    }
}
