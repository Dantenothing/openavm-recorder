package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.diagnostic.PowerDiagnosticEvidence
import com.dante.zeekrcapabilitylab.diagnostic.PowerDiagnosticRepository
import com.dante.zeekrcapabilitylab.diagnostic.VehicleAwayProbe
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Temporary on-car diagnostic exit. It never opens a camera or changes recording state. */
@Composable
fun PhoneScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var evidence by remember { mutableStateOf<PowerDiagnosticEvidence?>(null) }

    fun refresh() {
        scope.launch { evidence = withContext(Dispatchers.IO) { PowerDiagnosticRepository.collect(context) } }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f))
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("离车与预览诊断", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("本页只记录证据，不会自动停止或恢复录像。测试前点“开始新测试”，回来后刷新并拍下二维码。")

        evidence?.let { current ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    current.humanText(),
                    Modifier.padding(18.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val payload = current.qrPayload()
            val qr = remember(payload) { runCatching { qrBitmap(payload) }.getOrNull() }
            if (qr != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("紧凑诊断二维码", fontWeight = FontWeight.SemiBold)
                        Image(qr.asImageBitmap(), "诊断二维码", Modifier.size(330.dp))
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                VehicleAwayProbe.startTestMarker()
                refresh()
            }) { Text("开始新测试") }
            OutlinedButton(onClick = ::refresh) { Text("刷新证据") }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(
                "第一轮锁车测试不会自动停止录像。请先做未录像锁车，再做录像锁车；录像测试需由你在 10–15 分钟后手动结束。",
                Modifier.padding(18.dp),
            )
        }
        Text("手机连接功能仍在开发中，本页只是 alpha7 的临时诊断入口。")
    }
}

private fun qrBitmap(payload: String, size: Int = 700): Bitmap {
    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
