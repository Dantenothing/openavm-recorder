package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.util.Utils

/** A display preference only: does not start location recording or request map tiles. */
@Composable
fun PlaybackMapSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    var openMap by remember { mutableStateOf(settings.playbackMapDefaultOpen) }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Utils.t("Playback", "录像回放"), style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().testTag("playback-map-default-open").toggleable(
                    value = openMap,
                    role = Role.Switch,
                    onValueChange = { openMap = it; settings.setPlaybackMapDefaultOpen(it) },
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(Utils.t("Show map when playback opens", "打开回放时展开地图"), Modifier.weight(1f))
                Switch(checked = openMap, onCheckedChange = null)
            }
            Text(Utils.t(
                "Shows the recorded route with the video. You can hide it in the player. Your Street map choice is remembered for future playback.",
                "回放视频时展开已记录的轨迹，仍可随时收起。是否加载街道底图会记住你的选择，下次回放继续使用。"),
                style = MaterialTheme.typography.bodyMedium)
            Text(Utils.t(
                "This does not enable location recording. A route is available only when GPS data was recorded with the video.",
                "此项不会开启位置记录；只有录像时记录了 GPS 数据，才能显示路线。"),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
