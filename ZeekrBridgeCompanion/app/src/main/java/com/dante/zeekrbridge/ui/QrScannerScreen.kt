package com.dante.zeekrbridge.ui

import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner backed by CameraX + ZXing. Decodes a pairing payload
 * and invokes [onResult] with the raw text; [onCancel] closes the scanner.
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                            if (finished) {
                                image.close()
                                return@setAnalyzer
                            }
                            val text = decodeQr(image)
                            image.close()
                            if (text != null && !finished) {
                                finished = true
                                onResult(text)
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (t: Throwable) {
                        error = com.dante.zeekrbridge.ui.t("Camera could not start: {0}", "相机启动失败：{0}", t.message ?: t.javaClass.simpleName)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                t("Scan the pairing QR code on the vehicle screen", "扫描车机屏幕上的配对二维码"),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                t("The QR code expires after about 5 minutes. Generate a new code on the vehicle if needed.", "二维码有效期约 5 分钟；若已过期请让车机重新生成。"),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            error?.let {
                Text(it, color = Color(0xFFFF8A80))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel) {
                    Text(t("Cancel", "取消"), color = Color.White)
                }
                Button(onClick = onCancel) {
                    Text(t("Enter pairing code manually", "手动输入配对码"))
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            finished = true
        }
    }
}

private fun decodeQr(image: ImageProxy): String? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val yPlane = image.planes[0] ?: return null
    val rowStride = yPlane.rowStride
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0 || rowStride <= 0) return null
    val y = yPlane.buffer
    val data = ByteArray(rowStride * height)
    val originalPosition = y.position()
    y.position(0)
    y.get(data)
    y.position(originalPosition)
    return try {
        val source = PlanarYUVLuminanceSource(
            data,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
        result.text
    } catch (t: Throwable) {
        null
    }
}
