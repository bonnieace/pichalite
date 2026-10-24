package ke.co.pichalite

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

@Composable internal fun PhotoPreviewDialog(photo: Photo, index: Int, total: Int, dismiss: () -> Unit, previous: () -> Unit, next: () -> Unit, share: () -> Unit) {
    var zoom by remember(photo.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var unavailable by remember(photo.id, photo.preview) { mutableStateOf(false) }
    val transform = rememberTransformableState { scale, pan, _ ->
        zoom = (zoom * scale).coerceIn(1f, 5f)
        val limitX = viewport.width * (zoom - 1f) / 2f
        val limitY = viewport.height * (zoom - 1f) / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF11181C)).safeDrawingPadding().testTag("photo_preview")) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close preview", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(photo.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${index + 1} of $total", color = Color.LightGray, fontSize = 12.sp)
                }
                if (photo.status == "ready") IconButton(onClick = share) { Icon(Icons.Outlined.IosShare, "Share preview", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it }.clipToBounds().transformable(transform), contentAlignment = Alignment.Center) {
                AsyncImage(photo.preview, "Full photo: ${photo.name}", contentScale = ContentScale.Fit,
                    onError = { unavailable = true }, onSuccess = { unavailable = false },
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y })
                if (unavailable) Text("This photo is no longer available.\nIt may have been moved or deleted.", color = Color.LightGray, modifier = Modifier.padding(24.dp))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = previous, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous photo", tint = if (index > 0) Color.White else Color.DarkGray) }
                Text("Pinch to zoom", color = Color.LightGray, fontSize = 12.sp)
                IconButton(onClick = next, enabled = index + 1 < total) { Icon(Icons.Outlined.ChevronRight, "Next photo", tint = if (index + 1 < total) Color.White else Color.DarkGray) }
            }
        }
    }
}
