package com.dante.zeekrcapabilitylab.preflight.remote

import com.dante.zeekrcapabilitylab.preflight.obj
import org.junit.Assert.*
import org.junit.Test

class LabCameraRecipeTest {
    @Test fun boundedCameraPlansRoundTripAndAllowAnExplicitLongWait() {
        for((profile,seconds) in mapOf("CAMERA_SMOKE" to 20,"CAMERA_MINUTE" to 240,"CAMERA_SOAK" to 600)) {
            val json=obj("profile" to profile);val experiment=LabExperiment.parse(json)
            assertEquals(json,experiment.json());assertTrue(experiment.realCamera);assertEquals(seconds,experiment.cameraPlan().seconds)
            val recipe=LabRecipe.parse(obj("recipeVersion" to 1,"steps" to listOf(obj("op" to "START","experiment" to json),
                obj("op" to "WAIT","event" to "STAGE","stage" to "camera_LIVE","timeoutMs" to 30_000),
                obj("op" to "WAIT","event" to "RUN_FINISHED","timeoutMs" to 1_500_000))))
            assertEquals(3,recipe.steps.size)
        }
    }
    @Test fun remoteCannotChangeCameraGeometryTimeBudgetOrRateUnderAFixedProfile() {
        for(p in listOf(obj("profile" to "CAMERA_SMOKE","cameraId" to "0"),obj("profile" to "CAMERA_SMOKE","durationSeconds" to 3_600),
            obj("profile" to "CAMERA_SMOKE","bitrateBps" to 28_000_000),obj("profile" to "CAMERA_SMOKE","frameRate" to 60)))
            assertThrows(IllegalArgumentException::class.java) {LabExperiment.parse(p)}
        assertThrows(IllegalArgumentException::class.java) {LabRecipe.parse(obj("recipeVersion" to 1,"steps" to listOf(
            obj("op" to "START","experiment" to obj("profile" to "CAMERA_SOAK")),obj("op" to "WAIT","event" to "RUN_FINISHED","timeoutMs" to 1_500_001))))}
    }
}
