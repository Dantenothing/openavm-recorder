package com.dante.zeekrbridge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small native vector set; no bitmap assets or extended icon library. */
internal object MediaIcons {
    val Play = outline("Play") { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
    val Pause = outline("Pause") {
        moveTo(8f, 5f); lineTo(8f, 19f); moveTo(16f, 5f); lineTo(16f, 19f)
    }
    val BackTen = outline("BackTen") {
        moveTo(4f, 8f); lineTo(4f, 3f); moveTo(4f, 8f); lineTo(9f, 8f)
        moveTo(4.8f, 7f); curveTo(7f, 2f, 15f, 1.5f, 19f, 7f)
        curveTo(23f, 13f, 19f, 21f, 12f, 21f); curveTo(8f, 21f, 5f, 19f, 4f, 16f)
    }
    val ForwardTen = outline("ForwardTen") {
        moveTo(20f, 8f); lineTo(20f, 3f); moveTo(20f, 8f); lineTo(15f, 8f)
        moveTo(19.2f, 7f); curveTo(17f, 2f, 9f, 1.5f, 5f, 7f)
        curveTo(1f, 13f, 5f, 21f, 12f, 21f); curveTo(16f, 21f, 19f, 19f, 20f, 16f)
    }
    val Volume = outline("Volume") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f)
        moveTo(19f, 5f); curveTo(24f, 9f, 24f, 15f, 19f, 19f)
    }
    val Muted = outline("Muted") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(17f, 9f); lineTo(22f, 15f); moveTo(22f, 9f); lineTo(17f, 15f)
    }
    val Export = outline("Export") {
        moveTo(12f, 15f); lineTo(12f, 3f); moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
        moveTo(5f, 12f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 12f)
    }
    val Save = outline("Save") {
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f)
        moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f)
    }
    val Video = outline("Video") {
        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(9f, 8f); lineTo(15f, 12f); lineTo(9f, 16f); close()
    }

    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()
}
