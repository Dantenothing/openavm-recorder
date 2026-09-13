package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.util.Utils

internal val PRODUCT_DIRECTION_LABELS_ZH = listOf("前", "后", "左", "右")
internal val PRODUCT_DIRECTION_LABELS_EN = listOf("Front", "Rear", "Left", "Right")
internal fun productDirectionLabels(): List<String> = listOf(
    Utils.t("Front", "前"),
    Utils.t("Rear", "后"),
    Utils.t("Left", "左"),
    Utils.t("Right", "右"),
)

/** Product overlay shared by live preview and recording playback. */
@Composable
internal fun FourLaneDirectionOverlay(
    labels: List<String> = productDirectionLabels(),
    displayMode: FourLaneDisplayMode = FourLaneDisplayMode.FOUR_GRID,
    interactionEnabled: Boolean = false,
    zoom: Float = 1f,
    onLaneTapped: ((Int) -> Unit)? = null,
    onTransformGesture: ((zoomChange: Float, panX: Float, panY: Float) -> Unit)? = null,
) {
    val selectedLane = displayMode.singleLane
    if (selectedLane != null) {
        val gestureModifier = if (interactionEnabled && onTransformGesture != null) {
            Modifier.pointerInput(selectedLane, onTransformGesture) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    onTransformGesture(zoomChange, pan.x, pan.y)
                }
            }
        } else {
            Modifier
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(0.5.dp, Color(0x4DFFFFFF))
                .then(gestureModifier)
                .clickable(enabled = interactionEnabled && onLaneTapped != null) {
                    onLaneTapped?.invoke(selectedLane)
                },
        ) {
            DirectionBadge(
                text = labels.getOrElse(selectedLane - 1) {
                    productDirectionLabels()[selectedLane - 1]
                },
            )
            if (zoom > 1.01f) {
                Text(
                    text = "%.1f×".format(zoom),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
Column(Modifier.fillMaxSize()) {
        repeat(2) { row ->
            Row(Modifier.weight(1f)) {
                repeat(2) { column ->
                    val slot = row * 2 + column
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(0.5.dp, Color(0x4DFFFFFF))
                            .clickable(enabled = interactionEnabled && onLaneTapped != null) {
                                onLaneTapped?.invoke(slot + 1)
                            },
                    ) {
                        DirectionBadge(
                            text = labels.getOrElse(slot) { productDirectionLabels()[slot] },
                        )
                    }
                }
            }
        }
    }
    }

}

@Composable
internal fun FourLaneLensToggle(
    mode: FourLaneLensMode,
    onModeChanged: (FourLaneLensMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xCC111111))
            .padding(3.dp),
    ) {
        LensOption(
            text = Utils.t("Original / Wide", "原始 / 广角"),
            selected = mode == FourLaneLensMode.FISHEYE,
            onClick = { onModeChanged(FourLaneLensMode.FISHEYE) },
        )
        LensOption(
            text = Utils.t("Corrected", "标准修正"),
            selected = mode == FourLaneLensMode.STANDARD,
            onClick = { onModeChanged(FourLaneLensMode.STANDARD) },
        )
    }
}

@Composable
private fun LensOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color(0xFF6750A4) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun DirectionBadge(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(8.dp)
            .background(Color(0xB3000000), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}
