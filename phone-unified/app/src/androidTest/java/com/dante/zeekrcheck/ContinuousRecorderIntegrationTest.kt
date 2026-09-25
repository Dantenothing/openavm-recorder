package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.dante.zeekrbridge.core.*
import com.dante.zeekrbridge.player.FourLaneGlView
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android decoder, OES renderer and exporter; exclusively synthetic local files. */
class ContinuousRecorderIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val colors = listOf(Color.rgb(210,40,40), Color.rgb(35,190,50), Color.rgb(40,65,210), Color.rgb(215,185,35))

    @Test fun fourSegmentsPlayOnOneSurfaceAndAllViewsExportWithoutStorageSeams() {
        OpenAvmIntegration.initialize(context)
        assumeFalse(MediaExportQueue.jobs.value.any { it.state in setOf(MediaExportState.QUEUED, MediaExportState.RUNNING, MediaExportState.CANCELLING) })
        val language = PhoneLanguage.mode
        instrumentation.runOnMainSync { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) }
        val prefix = "qa-continuous-${UUID.randomUUID()}"
        val files = (0..3).map { File(ReceivedStore.receivedDir(), "$prefix-$it.mp4") }
        val jobs = mutableListOf<String>()
        val temporary = File(context.cacheDir, "$prefix-export.mp4")
        var mounted by mutableStateOf(false)
        var detail by mutableStateOf(false)
        try {
            files.forEachIndexed { index, file ->
                instrumentation.context.assets.open("synthetic-continuous.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
                File(file.absolutePath + ".sidecar.json").writeText(metadata(prefix,index))
            }
            ReceivedStore.refresh()
            runBlocking { MediaIndexStore.refresh(ReceivedStore.files.value, force = true) }
            val segments = MediaIndexScanner.scan(files).sessions.single().segments
            assertEquals(4,segments.size)
            assertTrue(segments.all { it.raster is RecordingRasterMetadata.Repacked && it.durationMs == 2000L })
            val cover = runBlocking { MediaThumbnailCache.loadOrCreate(context, segments.first()) }
            assertNotNull(cover)
            val evidence = File(context.getExternalFilesDir(null), "qa-local16").apply { mkdirs() }
            File(evidence,"continuous-cover.png").outputStream().use { cover!!.compress(Bitmap.CompressFormat.PNG,100,it) }
            MediaMetadataRetriever().apply {
                setDataSource(files.first().absolutePath)
                val decoded = getFrameAtTime(500_000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)!!
                File(evidence,"continuous-decoded.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG,100,it) }
                File(evidence,"continuous-colors.txt").writeText("frame=${decoded.colorSpace}; cover=${cover!!.colorSpace}; pixel=${Integer.toHexString(decoded.getPixel(640,500))}")
                decoded.recycle(); release()
            }
            assertQuadrants(cover!!)
            mounted = true
            compose.setContent { AssistantTheme {
                if (mounted) OpenAvmDestination("media",detail,{detail=it},{},{}) else Text("Synthetic recorder QA complete")
            } }
            compose.onNode(hasText("4 个分段", substring = true) and hasText("0:08")).performScrollTo().performClick()
            compose.waitUntil(25_000) { progress() > 500f }
            val originalSurface = surfaceView()
            assertNotNull(originalSurface)
            compose.waitUntil(25_000) { progress() >= 6500f }
            assertSame("All four segments reuse the GL view", originalSurface, surfaceView())
            val bounds = compose.onNodeWithTag("recorder_video_surface").fetchSemanticsNode().boundsInRoot
            val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
            // Dialog/library roots can be offset; use the actual native GL view's screen coordinates.
            val position = IntArray(2)
            instrumentation.runOnMainSync { originalSurface!!.getLocationOnScreen(position) }
            val viewBitmap = Bitmap.createBitmap(screenshot, position[0], position[1], originalSurface!!.width, originalSurface.height)
            assertTrue(bounds.width > 0)
            assertQuadrants(viewBitmap)
            val proof = File(context.getExternalFilesDir(null), "qa-local15/continuous-playback.png")
            proof.parentFile!!.mkdirs()
            proof.outputStream().use { viewBitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            viewBitmap.recycle(); screenshot.recycle()
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            instrumentation.runOnMainSync {
                jobs += MediaExportQueue.enqueue(context, segments, listOf(MediaExportTarget.ORIGINAL,
                    MediaExportTarget.FRONT, MediaExportTarget.REAR, MediaExportTarget.LEFT, MediaExportTarget.RIGHT), 0, 8000)
                jobs += MediaExportQueue.enqueue(context, listOf(segments.first()), listOf(MediaExportTarget.ORIGINAL), 0, 2000)
            }
            compose.waitUntil(180_000) {
                jobs.all { id -> MediaExportQueue.jobs.value.first { it.id == id }.state in setOf(MediaExportState.COMPLETED, MediaExportState.FAILED, MediaExportState.CANCELLED) }
            }
            jobs.forEachIndexed { index, id ->
                val job = MediaExportQueue.jobs.value.first { it.id == id }
                assertEquals("${job.plan.target}: ${job.message}",MediaExportState.COMPLETED,job.state)
                val uri = job.outputUri?.let(Uri::parse) ?: Uri.fromFile(File(job.outputPath!!))
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
                    if (job.plan.target == MediaExportTarget.ORIGINAL) {
                        assertEquals(3840,width); assertEquals(1728,height)
                        context.contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                        val document = EmbeddedRecordingMetadata.inspect(temporary)
                        assertNull(document.error); assertNotNull(document.document)
                        assertFalse(document.document!!.contains("continuousTimeline"))
                        val imported = MediaIndexScanner.scan(listOf(temporary)).sessions.single().segments.single()
                        assertTrue(imported.raster is RecordingRasterMetadata.Repacked)
                        assertEquals(if(index == jobs.lastIndex) 2000L else 8000L, imported.durationMs)
                    } else {
                        assertEquals(1280,width); assertEquals(1280,height)
                        val colorIndex = listOf(MediaExportTarget.FRONT,MediaExportTarget.REAR,MediaExportTarget.LEFT,MediaExportTarget.RIGHT).indexOf(job.plan.target)
                        // Exercise both sides of segment boundaries, not just the first segment.
                        for (time in listOf(1_000_000L, 6_500_000L)) {
                            val frame = retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST)!!
                            for (x in listOf(.2f,.5f,.8f)) for (y in listOf(.2f,.5f,.8f)) {
                                val pixel = frame.getPixel((frame.width*x).toInt(),(frame.height*y).toInt())
                                assertColor(colors[colorIndex],pixel)
                                assertUniform(frame.getPixel(frame.width/2,frame.height/2), pixel)
                            }
                            frame.recycle()
                        }
                    }
                } finally { retriever.release() }
            }
        } finally {
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            jobs.forEach { id ->
                instrumentation.runOnMainSync { MediaExportQueue.requestCancel(context,id) }
                MediaExportQueue.jobs.value.firstOrNull { it.id == id }?.let { job ->
                    job.outputUri?.let { context.contentResolver.delete(Uri.parse(it),null,null) }
                    job.outputPath?.let { File(it).delete() }
                }
                MediaExportQueue.dismiss(id)
            }
            temporary.delete()
            files.forEach { file -> File(file.absolutePath + ".sidecar.json").delete(); file.delete() }
            ReceivedStore.refresh()
            runBlocking { MediaIndexStore.refresh(ReceivedStore.files.value,force=true) }
            instrumentation.runOnMainSync { PhoneLanguage.selectMode(language); VehicleWidgetProvider.updateAll(context) }
        }
    }

    private fun progress(): Float = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.ProgressBarRangeInfo] }
        .filter { it.range.endInclusive >= 8000f }.maxOfOrNull { it.current } ?: 0f

    private fun surfaceView(): FourLaneGlView? {
        var result: FourLaneGlView? = null
        fun visit(view: View) { if(view is FourLaneGlView) result=view else if(view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) } }
        instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { visit(it.window.decorView) } }
        return result
    }

    private fun assertQuadrants(bitmap: Bitmap) {
        colors.forEachIndexed { i, color ->
            val center=bitmap.getPixel(((i%2+.5f)*bitmap.width/2).toInt(),((i/2+.5f)*bitmap.height/2).toInt())
            for (x in listOf(.2f,.5f,.8f)) for (y in listOf(.2f,.5f,.8f)) {
                val pixel=bitmap.getPixel(((i%2+x)*bitmap.width/2).toInt(),((i/2+y)*bitmap.height/2).toInt())
                assertColor(color,pixel)
                assertUniform(center,pixel)
            }
        }
    }

    private fun assertColor(expected: Int, actual: Int) {
        // This is a raster/segment test, not a colour-fidelity test. On this device the raw
        // MediaMetadataRetriever frame changes (210,40,40) to (249,0,27), even with BT.709
        // declared; ffmpeg decodes the fixture correctly. It happens before our raster code.
        // Distinct hue, brightness, saturation and uniform interiors reject swapped lanes,
        // seams, padding and blank frames without asserting platform-specific RGB conversion.
        val reference=FloatArray(3); val observed=FloatArray(3)
        Color.colorToHSV(expected,reference); Color.colorToHSV(actual,observed)
        val distance=kotlin.math.abs(reference[0]-observed[0]).let { minOf(it,360-it) }
        assertTrue("Wrong lane colour: ${Integer.toHexString(expected)} / ${Integer.toHexString(actual)}",
            distance < 18 && observed[1] > .55f && observed[2] > .5f)
    }

    private fun assertUniform(expected: Int, actual: Int) {
        for (channel in listOf<(Int)->Int>(Color::red, Color::green, Color::blue))
            assertTrue("Storage seam or mixed lane",kotlin.math.abs(channel(expected)-channel(actual)) < 14)
    }

    private fun metadata(session: String, index: Int): String = JSONObject()
        .put("schemaVersion",9).put("recordingSessionId",session).put("recordingMode","NORMAL")
        .put("sourceRole","SURROUND").put("layoutKind","FOUR_LANE_V1").put("segmentNumber",index+1)
        .put("startedAtEpochMs",1600000000000L+index*2000).put("actualTrack",JSONObject().put("durationMs",2000))
        .put("rasterLayout",JSONObject().put("version",1).put("layout",StripRepackContract.FORMAT)
            .put("coordinateOrigin","TOP_LEFT").put("inputWidth",1280).put("inputHeight",5140)
            .put("stripHeight",1728).put("encodedWidth",3840).put("encodedHeight",1728))
        .put("continuousTimeline",JSONObject().put("version",1).put("runId",session)
            .put("firstPtsUs",50_000_000L+index*2_000_000).put("lastPtsUs",51_800_000L+index*2_000_000)
            .put("endExclusivePtsUs",52_000_000L+index*2_000_000).put("frames",10).put("startsWithKeyFrame",true))
        .put("laneLayout",JSONObject().put("originalWidth",1280).put("originalHeight",5140)
            .put("lanes",JSONArray().also { lanes -> listOf(4,1288,2572,3856).forEachIndexed { i, y ->
                lanes.put(JSONObject().put("lane",i+1).put("displayOrder",i+1).put("label","View ${i+1}")
                    .put("x0",0).put("x1",1280).put("y0",y).put("y1",y+1280))
            } })).toString()
}
