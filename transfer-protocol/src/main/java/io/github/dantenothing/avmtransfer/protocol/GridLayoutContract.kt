package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable

@Serializable
data class PixelRectangle(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun within(w: Int, h: Int): Boolean = left >= 0 && top >= 0 && width > 0 && height > 0 &&
        left.toLong() + width <= w && top.toLong() + height <= h
    fun overlaps(other: PixelRectangle): Boolean = left.toLong() < other.left.toLong() + other.width &&
        other.left.toLong() < left.toLong() + width && top.toLong() < other.top.toLong() + other.height &&
        other.top.toLong() < top.toLong() + height
}

@Serializable
data class GridInputLane(val id: Int, val source: PixelRectangle, val displaySlot: Int,
                         val rotationClockwise: Int = 0, val mirrorAfterRotation: Boolean = false)

@Serializable
data class GridEncodedLane(val id: Int, val source: PixelRectangle, val encoded: PixelRectangle,
                           val bakedRotationClockwise: Int, val bakedMirrorAfterRotation: Boolean,
                           val playbackRotationClockwise: Int = 0, val playbackMirror: Boolean = false)

/** Explicit geometry; no role inference and no "all square videos are four-camera" heuristic. */
@Serializable
data class GridLayoutContract(val schemaVersion: Int = 1, val format: String = FORMAT,
                              val inputWidth: Int, val inputHeight: Int,
                              val encodedWidth: Int, val encodedHeight: Int,
                              val lanes: List<GridEncodedLane>) {
    fun validate(): List<String> = buildList {
        if (schemaVersion != 1 || format != FORMAT) add("UNSUPPORTED_FORMAT")
        if (inputWidth !in 1..16384 || inputHeight !in 1..16384) add("INVALID_INPUT_SIZE")
        if (encodedWidth !in setOf(1280, 1920, 2560) || encodedHeight != encodedWidth) add("INVALID_OUTPUT_SIZE")
        if (lanes.size != 4 || lanes.map { it.id }.toSet() != setOf(1, 2, 3, 4)) add("INVALID_LANES")
        val cell = encodedWidth / 2
        val expected = (0..3).map { PixelRectangle(it % 2 * cell, it / 2 * cell, cell, cell) }.toSet()
        if (lanes.map { it.encoded }.toSet() != expected) add("INVALID_GRID_TILES")
        lanes.forEach { lane ->
            if (!lane.source.within(inputWidth, inputHeight) || lane.source.width != lane.source.height) add("INVALID_SOURCE_RECT")
            if (!lane.encoded.within(encodedWidth, encodedHeight)) add("INVALID_ENCODED_RECT")
            if (lane.bakedRotationClockwise !in setOf(0, 90, 180, 270)) add("INVALID_ROTATION")
            if (lane.playbackRotationClockwise != 0 || lane.playbackMirror) add("DOUBLE_TRANSFORM")
        }
        for (i in lanes.indices) for (j in 0 until i) if (lanes[i].source.overlaps(lanes[j].source)) add("OVERLAPPING_SOURCES")
    }.distinct()

    companion object {
        const val FORMAT = "OPENAVM_GRID_LAYOUT_V1"
        fun plan(inputWidth: Int, inputHeight: Int, outputSide: Int, input: List<GridInputLane>): GridLayoutContract {
            require(input.size == 4 && input.map { it.displaySlot }.toSet() == setOf(0, 1, 2, 3)) { "Invalid display slots" }
            val cell = outputSide / 2
            val result = GridLayoutContract(inputWidth = inputWidth, inputHeight = inputHeight,
                encodedWidth = outputSide, encodedHeight = outputSide,
                lanes = input.map { lane -> GridEncodedLane(lane.id, lane.source,
                    PixelRectangle(lane.displaySlot % 2 * cell, lane.displaySlot / 2 * cell, cell, cell),
                    lane.rotationClockwise, lane.mirrorAfterRotation) })
            require(result.validate().isEmpty()) { result.validate().joinToString() }
            return result
        }
    }
}
