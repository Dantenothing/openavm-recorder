package com.dante.zeekrcapabilitylab.product

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the product home page. Auto preview is now allowed only
 * through the real-car-proven ordinary TextureView path; recording setup remains
 * a separate explicit user action.
 */
class ProductHomeCameraPolicyTest {

    @Test
    fun productHomeAutoStartsProvenTextureViewPreview() {
        assertTrue(ProductHomeCameraPolicy.shouldAutoStartPreview(0L))
        assertTrue(ProductHomeCameraPolicy.shouldAutoStartPreview(999L))
        assertTrue(ProductHomeCameraPolicy.shouldAutoStartPreview(20_000L))
    }

    @Test
    fun pageRevisitRestoresPreview() {
        assertTrue(ProductHomeCameraPolicy.shouldAutoAccessCameraOnRevisit(0))
        assertTrue(ProductHomeCameraPolicy.shouldAutoAccessCameraOnRevisit(1))
        assertTrue(ProductHomeCameraPolicy.shouldAutoAccessCameraOnRevisit(9))
    }

    @Test
    fun autoPreviewAndExplicitUserActionsCanAccessCamera() {
        assertTrue(
            ProductHomeCameraPolicy.cameraAccessAllowed(
                ProductHomeCameraPolicy.TRIGGER_USER_START_RECORDING,
            ),
        )
        assertTrue(
            ProductHomeCameraPolicy.cameraAccessAllowed(
                ProductHomeCameraPolicy.TRIGGER_USER_START_PREVIEW,
            ),
        )
        assertTrue(ProductHomeCameraPolicy.cameraAccessAllowed(ProductHomeCameraPolicy.TRIGGER_AUTO_PREVIEW))
        assertFalse(
            ProductHomeCameraPolicy.cameraAccessAllowed(
                ProductHomeCameraPolicy.TRIGGER_AUTO_START_RECORDING,
            ),
        )
        assertFalse(ProductHomeCameraPolicy.cameraAccessAllowed(""))
    }

    @Test
    fun openingTheAppNeverStartsARecordingSession() {
        val recordScreen =
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/RecordScreen.kt").readText()
        val settingsScreen =
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/SettingsScreen.kt").readText()

        assertFalse(recordScreen.contains("autoStartRecordingEnabled"))
        assertFalse(recordScreen.contains("TRIGGER_AUTO_START_RECORDING"))
        assertFalse(settingsScreen.contains("setAutoStartRecordingEnabled"))
    }

    @Test
    fun recordConfigRemainsUserTriggered() {
        assertTrue(RecordStartPolicy.shouldComputeConfig(RecordStartPolicy.TRIGGER_USER_START))
        assertFalse(RecordStartPolicy.shouldComputeConfig(RecordStartPolicy.TRIGGER_COMPOSITION))
        assertFalse(RecordStartPolicy.shouldComputeConfig(RecordStartPolicy.TRIGGER_STARTUP))
    }

    @Test
    fun productRecordScreenKeepsTheProvenProducerAndUsesAProductHomeLayout() {
        val source = File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/RecordScreen.kt")
            .readText()
        assertFalse(
            "product home must not instantiate LivePreviewController",
            source.contains("LivePreviewController("),
        )
        assertFalse(
            "product home must not call preview.start",
            source.contains("preview.start("),
        )
        assertFalse(
            "product home must not enumerate Camera2 during composition",
            source.contains("CameraProbe.enumerate("),
        )
        assertFalse(
            "the proven TextureView producer path must not be replaced by the GLES experiment",
            source.contains("FourLaneGlView"),
        )
        assertTrue(
            "manual preview must draw the one proven TextureView through the four-lane container",
            source.contains("FourLaneTextureContainer"),
        )
        assertTrue(
            "four square camera cells require a square 2x2 panel",
            source.contains("private const val FOUR_GRID_ASPECT_RATIO = 1f"),
        )
        assertTrue(
            "manual preview must keep the same square geometry as the static grid",
            source.contains("modifier.aspectRatio(FOUR_GRID_ASPECT_RATIO)"),
        )

        val controllerSource =
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/SafeManualPreviewController.kt")
                .readText()
        assertTrue(
            "manual preview must attach the camera to an ordinary TextureView",
            controllerSource.contains("fun attach(view: TextureView)"),
        )
        assertFalse(
            "the A/B experiment must not create a replacement GL producer texture",
            controllerSource.contains("createSurfaceTexture"),
        )
        assertTrue(
            "the approved HD attempt must request the exact declared SurfaceTexture buffer",
            controllerSource.contains("texture.setDefaultBufferSize(candidate.width, candidate.height)"),
        )
        assertTrue(
            "a replacement recorder preview must explicitly restore the encoder profile buffer",
            controllerSource.contains("texture.setDefaultBufferSize(targetBufferSize.width, targetBufferSize.height)"),
        )
        assertTrue(
            "the HD attempt must retain the stable TextureView buffer fallback",
            controllerSource.contains("restoreTextureViewBuffer(texture)"),
        )
        assertTrue(
            "the HD attempt must remain gated by the exact declared-size policy",
            controllerSource.contains("chooseDeclaredHighResolution(declaredSizes)"),
        )

        val containerSource =
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/FourLaneTextureContainer.kt")
                .readText()
        assertTrue(
            "the display layer must draw the same TextureView child into every lane",
            containerSource.contains("drawChild(canvas, textureView, drawingTime)"),
        )
        assertFalse(
            "the real-time implementation must not use CPU Bitmap readback",
            containerSource.contains("getBitmap"),
        )
        assertTrue(
            "enlarging the product panel must keep the proven input View near its old size",
            containerSource.contains("STABLE_INPUT_SIZE_FRACTION = 0.62f"),
        )
        val overlaySource =
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/FourLaneProductOverlay.kt")
                .readText()
        assertTrue(
            "the visible grid order must be front, rear, left, right",
            overlaySource.contains("listOf(\"前\", \"后\", \"左\", \"右\")"),
        )
        assertTrue(
            source.contains("recorderState.previewFallbackUsed"),
        )
        assertTrue(source.contains("RecordingSourceSelector("))
        assertTrue(source.contains("previewController.startPreview(resolvedIdleSource!!)"))
        assertTrue(source.contains("TRIGGER_AUTO_PREVIEW"))
        assertTrue(source.contains("replacePreviewSurface"))
        assertTrue(source.contains("HomePreviewPane("))
        assertTrue(source.contains("ProductStatusCard("))
        assertTrue(source.contains(".weight(1.75f)"))
        assertTrue(source.contains("手机传输"))
        assertTrue(source.contains("可用空间"))
        assertTrue(source.contains("预计可录"))
        assertTrue(source.contains("自动清理"))
        assertFalse(source.contains("Switch("))
        assertFalse(source.contains("查看原始长条"))
        assertFalse(source.contains("显示：四格"))
        assertFalse(source.contains("Surface ${'$'}{it.width}"))

        val sessionSource =
            File("src/main/java/com/dante/zeekrcapabilitylab/service/recorder/RecorderSession.kt")
                .readText()
        val replacementPath = sessionSource
            .substringAfter("fun replacePreviewSurface(replacement: Surface)")
            .substringBefore("fun stop()")
        assertTrue(replacementPath.contains("configureActiveRecordingSession"))
        assertTrue(replacementPath.contains("ActivePreviewReplacementPolicy.ownsCallback"))
        assertFalse(
            "preview restoration must never stop the active MediaRecorder",
            replacementPath.contains("mediaRecorder?.stop()"),
        )

        val eventsSource =
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/EventsScreen.kt").readText()
        assertTrue(eventsSource.contains("CameraRecordingService.state.collectAsState()"))
        assertTrue(eventsSource.contains("LaunchedEffect(recorderState.libraryRevision)"))
    }
}
