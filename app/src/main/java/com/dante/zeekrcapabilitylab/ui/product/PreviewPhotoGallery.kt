package com.dante.zeekrcapabilitylab.ui.product

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.mirror.PreviewPhotos
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.*

@Composable fun PreviewPhotoGallery() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }) { Text(Utils.t("Recent preview photos", "最近保存的预览照片")) }
    if (!show) return
    val photos = remember(show) { PreviewPhotos.recent(context) }
    var index by remember { mutableIntStateOf(0) }
    val photo = photos.getOrNull(index)
    val bitmap by produceState<android.graphics.Bitmap?>(null, photo?.uri) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching {
            context.contentResolver.openInputStream(Uri.parse(photo?.uri ?: return@withContext null)).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        }.getOrNull() }
    }
    AlertDialog(onDismissRequest = { show = false }, title = { Text(Utils.t("Preview photos", "预览照片")) }, text = {
        Column {
            bitmap?.let { Image(it.asImageBitmap(), photo?.name, Modifier.fillMaxWidth().height(320.dp), contentScale = ContentScale.Fit) }
                ?: Text(Utils.t("Connect the USB drive to view saved photos.", "连接对应 USB 后可查看已保存的照片。"))
            Text(photo?.name ?: Utils.t("No photos yet", "暂无照片"))
            Row {
                TextButton(enabled = index > 0, onClick = { index-- }) { Text("‹") }
                Text("${if(photos.isEmpty()) 0 else index+1} / ${photos.size}", Modifier.padding(16.dp))
                TextButton(enabled = index + 1 < photos.size, onClick = { index++ }) { Text("›") }
            }
        }
    }, confirmButton = { TextButton(onClick = { show = false }) { Text(Utils.t("Close", "关闭")) } })
}
