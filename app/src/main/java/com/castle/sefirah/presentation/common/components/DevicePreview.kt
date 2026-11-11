package com.castle.sefirah.presentation.common.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import sefirah.common.util.base64ToBitmap
import sefirah.domain.model.PairedDevice

@Composable
fun DevicePreview(device: PairedDevice) = DevicePreview(device.presentation, device.avatar)

@Composable
fun DevicePreview(presentation: sefirah.domain.model.DevicePresentation?, previewImage: String?, scale: Float = 1f) {
    val ratio = presentation?.displayRatio() ?: 1f
    val kind = presentation?.kind ?: "unknown"
    val bitmap = remember(previewImage, presentation) {
        if (presentation?.wallpaperSource in listOf("wallpaper", "userSelected")) base64ToBitmap(previewImage) else null
    }
    val height = minOf(60f, 76f / ratio) * scale
    val outline = MaterialTheme.colorScheme.outline
    Column(Modifier.width((76f * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size((height * ratio).dp, height.dp)
            .border((2f * scale.coerceAtMost(1.5f)).dp, outline, RoundedCornerShape((if (kind == "phone") 8f else 3f).times(scale).dp))
            .padding((3f * scale).dp).clip(RoundedCornerShape((3f * scale).dp))
            .background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(rememberAsyncImagePainter(bitmap), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (kind == "unknown") Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (kind == "computer") HorizontalDivider(Modifier.width((24f * scale).dp).padding(top = (3f * scale).dp),
            thickness = (3f * scale.coerceAtMost(1.5f)).dp, color = outline)
    }
}
