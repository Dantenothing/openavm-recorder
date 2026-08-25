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
        Text("请选择测试类型。锁车离开确认约 30 秒后会保存当前段并结束 Session；解锁后不会自动恢复。")

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

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                VehicleAwayProbe.startTestMarker(VehicleAwayProbe.TEST_NORMAL_BACKGROUND)
                refresh()
            }, modifier = Modifier.weight(1f)) { Text("正常后台") }
            Button(onClick = {
                VehicleAwayProbe.startTestMarker(VehicleAwayProbe.TEST_OEM_CAMERA)
                refresh()
            }, modifier = Modifier.weight(1f)) { Text("OEM Camera") }
            Button(onClick = {
                VehicleAwayProbe.startTestMarker(VehicleAwayProbe.TEST_VEHICLE_AWAY)
                refresh()
            }, modifier = Modifier.weight(1f)) { Text("锁车离开") }
        }

        OutlinedButton(onClick = ::refresh) { Text("刷新证据") }

        Card(Modifier.fillMaxWidth()) {
            Text(
                "锁车测试预期：约 30 秒后 Session 自动结束并释放 Camera/WakeLock。回来后应保持待机，必须再次手动 Start。",
                Modifier.padding(18.dp),
            )
        }
        Text("手机连接功能仍在开发中，本页只是 alpha8 的临时诊断入口。")
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
