package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.util.Utils

/**
 * Phone features stay visible in the product navigation, but are deliberately
 * disabled for this Beta. The transfer implementation remains available for
 * future development and diagnostics.
 */
@Composable
fun PhoneScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f))
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            Utils.t("Phone connection", "手机互联"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        DisabledFeatureCard(
            title = Utils.t("Browser download unavailable", "浏览器下载暂不可用"),
            body = Utils.t(
                "Zeekr isolates the system hotspot network from third-party apps. " +
                    "Phones, tablets and computers cannot reach the local download server, " +
                    "even when they are connected to the same hotspot. Browser sharing is disabled in this Beta.",
                "Zeekr 将系统热点网络与第三方 App 隔离。即使手机、平板或电脑已经连接同一个热点，" +
                    "也无法访问车机内的本地下载服务，因此本 Beta 已停用浏览器分享。",
            ),
        )

        DisabledFeatureCard(
            title = Utils.t("Dedicated phone app", "专用手机 App"),
            body = Utils.t(
                "Under development. Phone pairing and recording transfer will return after a reliable car-initiated connection has been validated.",
                "正在开发中。待车机主动连接手机的传输方式完成稳定性验证后，手机配对和录像传输功能会重新开放。",
            ),
        )

        Spacer(Modifier.height(2.dp))
        Text(
            Utils.t(
                "Recording, playback and local storage on the head unit are not affected.",
                "车机端的录像、回放和本地存储功能不受影响。",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DisabledFeatureCard(title: String, body: String) {
    Card(
        Modifier
            .fillMaxWidth()
            .alpha(0.58f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
