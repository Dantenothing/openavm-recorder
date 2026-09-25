package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GridLayoutContractTest {
    @Test fun desktopFixtureUsesTheSameValidatedContract() {
        val text = checkNotNull(javaClass.getResource("/grid/vertical-5140-to-2560.json")).readText()
        val fixture = Json.decodeFromString<GridLayoutContract>(text)
        assertTrue(fixture.validate().isEmpty())
        assertEquals(5140, fixture.inputHeight)
        assertEquals(listOf(0, 90, 180, 270), fixture.lanes.map { it.bakedRotationClockwise })
        assertTrue(fixture.lanes[1].bakedMirrorAfterRotation)
    }
    private fun lanes() = (0..3).map { GridInputLane(it + 1, PixelRectangle(0, 4 + it * 1284, 1280, 1280), it) }
    private fun plan() = GridLayoutContract.plan(1280, 5140, 2560, lanes())
    @Test fun separatorAndPaddingRowsAreExcludedUsingExplicitRects() {
        val grid = plan()
        assertEquals(listOf(4, 1288, 2572, 3856), grid.lanes.map { it.source.top })
        assertEquals(PixelRectangle(1280, 1280, 1280, 1280), grid.lanes.last().encoded)
        assertTrue(grid.validate().isEmpty())
    }
    @Test fun outputSizeAndSlotOrderDoNotChangeSourcePixels() {
        val small = GridLayoutContract.plan(1280, 5140, 1280, lanes().map { it.copy(displaySlot = 3 - it.displaySlot) })
        assertEquals(lanes().first().source, small.lanes.first().source)
        assertEquals(PixelRectangle(640, 640, 640, 640), small.lanes.first().encoded)
    }
    @Test fun bakedTransformMustNotBeAppliedAgainByPlayer() {
        val grid = GridLayoutContract.plan(1280, 5140, 1920, lanes().map { it.copy(rotationClockwise = 90, mirrorAfterRotation = true) })
        assertTrue(grid.validate().isEmpty()); assertEquals(90, grid.lanes[0].bakedRotationClockwise)
        assertEquals(0, grid.lanes[0].playbackRotationClockwise)
        assertTrue(grid.copy(lanes = grid.lanes.map { it.copy(playbackRotationClockwise = 90) }).validate().contains("DOUBLE_TRANSFORM"))
    }
    @Test fun serializationRoundTripKeepsInputAndEncodedGeometrySeparate() {
        val original = plan()
        assertEquals(original, Json.decodeFromString<GridLayoutContract>(Json.encodeToString(original)))
    }
    @Test fun aSquareWithoutThisContractIsNeverInferredToBeGrid() {
        assertTrue(plan().copy(format = "SINGLE_V1").validate().contains("UNSUPPORTED_FORMAT"))
        assertTrue(plan().copy(schemaVersion = 99).validate().contains("UNSUPPORTED_FORMAT"))
    }
    @Test(expected = IllegalArgumentException::class) fun overlappingSourceIsRejected() {
        GridLayoutContract.plan(1280, 5140, 2560, lanes().map { it.copy(source = PixelRectangle(0, 0, 1280, 1280)) })
    }
    @Test(expected = IllegalArgumentException::class) fun repeatedSlotIsRejected() {
        GridLayoutContract.plan(1280, 5140, 2560, lanes().map { it.copy(displaySlot = 0) })
    }
    @Test(expected = IllegalArgumentException::class) fun missingCameraIsRejected() {
        GridLayoutContract.plan(1280, 5140, 2560, lanes().dropLast(1))
    }
    @Test(expected = IllegalArgumentException::class) fun negativeSourceAndOverflowAreRejected() {
        GridLayoutContract.plan(1280, 5140, 2560, lanes().map { it.copy(source = PixelRectangle(Int.MAX_VALUE, 0, 1280, 1280)) })
    }
    @Test(expected = IllegalArgumentException::class) fun nonSquareLaneCannotBeSilentlyStretched() {
        GridLayoutContract.plan(1280, 5140, 2560, lanes().map { it.copy(source = it.source.copy(height = 1279)) })
    }
}
