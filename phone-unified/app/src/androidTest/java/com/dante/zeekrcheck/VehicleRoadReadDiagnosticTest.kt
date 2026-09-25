package com.dante.zeekrcheck

import android.location.Geocoder
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in lookup of the saved vehicle coordinate; no car request and no coordinate/address export. */
class VehicleRoadReadDiagnosticTest {
    @Test fun inspectPhoneRoadProvider() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectRoad") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val point = AssistantStore.get(context).state.value.location
        val label = point?.let { runCatching { PlaceLookup(context).nearbyRoad(it) }.getOrNull() }
        File(context.filesDir, "road-provider-diagnostic.json").writeText(JSONObject()
            .put("providerAvailable", Geocoder.isPresent()).put("vehicleCoordinateAvailable", point != null)
            .put("nearbyLabelReturned", !label.isNullOrBlank()).toString())
    }
}
