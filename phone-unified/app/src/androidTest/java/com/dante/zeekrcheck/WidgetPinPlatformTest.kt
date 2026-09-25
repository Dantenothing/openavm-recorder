package com.dante.zeekrcheck

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Read-only diagnostics by default; the separate foreground request probe needs an explicit argument. */
class WidgetPinPlatformTest {
    @Test fun explicitlyRequestedForegroundPinReportsPlatformOutcome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeWidgetPin") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val manager = AppWidgetManager.getInstance(activity)
                val result = runCatching { manager.requestPinAppWidget(
                    ComponentName(activity, CompactVehicleWidgetProvider::class.java), null, null) }
                val report = "supported=${manager.isRequestPinAppWidgetSupported}; accepted=${result.getOrNull()}; " +
                    "exception=${result.exceptionOrNull()?.javaClass?.simpleName}; detail=${result.exceptionOrNull()?.message}"
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
            }
        }
    }

    @Test fun reportsLauncherSupportAndRegisteredWidgetProviders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = AppWidgetManager.getInstance(context)
        val names = listOf("SquareVehicleWidgetProvider", "StripVehicleWidgetProvider",
            "CompactVehicleWidgetProvider", "VehicleWidgetProvider")
        val registered = manager.installedProviders.filter { it.provider.packageName == context.packageName }
        assertEquals(4, registered.size)
        val report = buildString {
            append("pinSupported=${manager.isRequestPinAppWidgetSupported}")
            names.forEach { name ->
                val component = ComponentName(context.packageName, "com.dante.zeekrcheck.$name")
                val info = registered.single { it.provider == component }
                append("; $name: existing=${manager.getAppWidgetIds(component).size}, ")
                append("cells=${info.targetCellWidth}x${info.targetCellHeight}, ")
                append("previewImage=${info.previewImage}, previewLayout=${info.previewLayout}, ")
                append("initialLayout=${info.initialLayout}")
            }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
    }
}
