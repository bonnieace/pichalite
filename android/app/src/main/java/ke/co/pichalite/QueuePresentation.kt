package ke.co.pichalite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.*
import kotlinx.coroutines.delay

internal const val PHOTO_PAGE_SIZE = 50

/** Studio stays stable for the entire import/processing run; Ready stays usable. */
internal fun LazyListScope.photoQueue(
    state: QueueState,
    importing: Boolean,
    importedCount: Int,
    importTotal: Int,
    readyTab: Boolean,
    page: Int,
    onPageChange: (Int) -> Unit,
    row: @Composable (Photo) -> Unit,
) {
    val streaming = !readyTab && (importing || (!state.paused && state.pending > 0))
    val photos = if (readyTab) state.photos.filter { it.status == "ready" } else state.photos
    when {
        streaming -> item(key = "queue_activity", contentType = "activity") {
            QueueActivity(importing, importedCount, importTotal, state.pending, state.paused)
        }
        !state.loaded -> item(key = "queue_loading") { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange) }
        photos.isEmpty() -> item(key = "queue_empty") {
            Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(20.dp)).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (readyTab) Icons.Outlined.CheckCircle else Icons.Outlined.Collections, null, tint = Orange, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (readyTab) "Your finished photos land here." else "A little lighter starts here.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(if (readyTab) "Choose a few photos in the Studio\nto get your first batch ready." else "Choose photos above, or share them\nto PichaLite from your Gallery.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        else -> {
            val pageCount = ((photos.size + PHOTO_PAGE_SIZE - 1) / PHOTO_PAGE_SIZE).coerceAtLeast(1)
            val currentPage = page.coerceIn(0, pageCount - 1)
            val first = currentPage * PHOTO_PAGE_SIZE
            val end = (first + PHOTO_PAGE_SIZE).coerceAtMost(photos.size)
            item(key = "page_controls", contentType = "pagination") {
                Row(Modifier.fillMaxWidth().testTag("photo_pagination"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Photos ${first + 1}–$end of ${photos.size}", color = Muted, fontSize = 12.sp)
                        Text("Page ${currentPage + 1} of $pageCount", fontSize = 12.sp)
                    }
                    IconButton(onClick = { onPageChange(currentPage - 1) }, enabled = currentPage > 0) {
                        Icon(Icons.Outlined.ChevronLeft, "Previous page")
                    }
                    IconButton(onClick = { onPageChange(currentPage + 1) }, enabled = currentPage + 1 < pageCount) {
                        Icon(Icons.Outlined.ChevronRight, "Next page")
                    }
                }
            }
            items(photos.subList(first, end), key = { "photo:${it.id}" }, contentType = { "photo" }) { row(it) }
        }
    }
}

internal enum class QueueAnimation(val resource: Int, val label: String) {
    IMPORT(R.raw.photo_upload, "Bringing your photos in"),
    COMPRESSION(R.raw.photo_compression, "Making room for more"),
    PROCESSING(R.raw.photo_processing, "Importing & compressing"),
}

internal fun queueAnimation(importing: Boolean, pending: Int, paused: Boolean): QueueAnimation = when {
    importing && pending > 0 && !paused -> QueueAnimation.PROCESSING
    importing -> QueueAnimation.IMPORT
    else -> QueueAnimation.COMPRESSION
}

@Composable internal fun QueueActivity(importing: Boolean, importedCount: Int, importTotal: Int, pending: Int, paused: Boolean = false) {
    val requested = queueAnimation(importing, pending, paused)
    var stage by remember { mutableStateOf(requested) }
    LaunchedEffect(requested, importing, paused) {
        // A fast worker can empty the queue between provider arrivals. Keep the
        // combined animation through those gaps rather than restarting Lotties.
        if (importing && !paused && stage == QueueAnimation.PROCESSING && requested == QueueAnimation.IMPORT) delay(1600)
        stage = requested
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFF1EFE7))
            .padding(horizontal = 20.dp, vertical = 16.dp).testTag("queue_activity"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QueueMotion(stage)
        Text(stage.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(when (stage) {
            QueueAnimation.PROCESSING -> "$importedCount of $importTotal imported · $pending remaining"
            QueueAnimation.IMPORT -> "$importedCount of $importTotal imported"
            QueueAnimation.COMPRESSION -> "$pending photo${if (pending == 1) "" else "s"} remaining"
        }, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, minLines = 2, maxLines = 2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(if (paused) "Compression resumes when you unpause.\nFinished photos are available in Ready."
            else "Each photo starts compressing as soon as it arrives.\nFinished photos are available in Ready.",
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp, minLines = 2)
    }
}

@Composable private fun QueueMotion(stage: QueueAnimation) {
    // Warm all three small compositions once; count updates cannot reload them.
    val upload by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.photo_upload))
    val compression by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.photo_compression))
    val processing by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.photo_processing))
    val composition = when (stage) {
        QueueAnimation.IMPORT -> upload
        QueueAnimation.COMPRESSION -> compression
        QueueAnimation.PROCESSING -> processing
    }
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    Box(Modifier.size(144.dp).testTag("queue_animation"), contentAlignment = Alignment.Center) {
        if (composition == null) Icon(Icons.Outlined.PhotoLibrary, null, tint = Orange, modifier = Modifier.size(44.dp))
        // Progress is read during drawing, never by the screen or queue layout.
        LottieAnimation(composition, progress = { progress }, modifier = Modifier.fillMaxSize().graphicsLayer {
            // Reflect both the arrow and its travel, turning upload into download.
            scaleY = if (stage == QueueAnimation.IMPORT) -1f else 1f
        })
    }
}

@Composable internal fun PhotoNotice(message: String, isError: Boolean, dismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (isError) Color(0xFFFFE8DE) else Color(0xFFEDF1EE))
            .semantics { stateDescription = if (isError) "Needs attention" else "Information" }
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null,
            tint = if (isError) Orange else Green, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, Modifier.weight(1f), fontSize = 12.sp, color = Ink)
        IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss", tint = Muted) }
    }
}
