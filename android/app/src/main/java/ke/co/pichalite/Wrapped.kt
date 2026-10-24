package ke.co.pichalite

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** Derived from the full durable history, never a visible page or cache file. */
internal data class WrappedStats(
    val imported: Int,
    val finished: Int,
    val smaller: Int,
    val retained: Int,
    val replaced: Int,
    val pending: Int,
    val failed: Int,
    val sourceBytes: Long,
    val outputBytes: Long,
    val reducedBytes: Long,
    val reclaimedBytes: Long,
    val smallerSourceBytes: Long,
    val largestReduction: Long,
    val bestReductionPercent: Int,
) {
    val averageReductionPercent: Int get() = if (smallerSourceBytes == 0L) 0 else
        (reducedBytes.toDouble() / smallerSourceBytes * 100).toInt().coerceIn(0, 100)

    companion object {
        fun from(state: QueueState, files: LocalPhotoFiles): WrappedStats = from(
            state, state.photos.count { files.wasDeleted(it) },
            state.photos.count { files.replacementState(it) == "not_smaller" || files.replacementState(it) == "kept" },
        )

        fun from(state: QueueState, replaced: Int = 0, retained: Int = 0): WrappedStats {
            // A historical completion remains valid even after its working files are released.
            val done = state.photos.filter { it.outputBytes != null }
            val smaller = done.filter { it.outputBytes!! < it.inputBytes }
            return WrappedStats(
                imported = state.total, finished = done.size, smaller = smaller.size,
                retained = retained, replaced = replaced, pending = state.pending,
                failed = state.photos.count { it.status == "failed" },
                sourceBytes = done.sumOf { it.inputBytes }, outputBytes = done.sumOf { it.outputBytes!! },
                reducedBytes = state.savedBytes, reclaimedBytes = state.reclaimedBytes,
                smallerSourceBytes = smaller.sumOf { it.inputBytes },
                largestReduction = smaller.maxOfOrNull { it.inputBytes - it.outputBytes!! } ?: 0,
                bestReductionPercent = smaller.maxOfOrNull {
                    ((it.inputBytes - it.outputBytes!!).toDouble() / it.inputBytes * 100).toInt().coerceIn(0, 100)
                } ?: 0,
            )
        }
    }
}

internal fun wrappedBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
    value >= 1024 -> String.format(Locale.US, "%.0f KB", value / 1024.0)
    else -> "$value B"
}

private fun count(value: Int): String = String.format(Locale.US, "%,d", value)

/** The very same renderer powers the preview and the exported 1080 × 1920 story. */
internal object WrappedArtwork {
    const val CARD_COUNT = 4
    private val ink = 0xFF202D32.toInt()
    private val cream = 0xFFFAF7F0.toInt()
    private val orange = 0xFFD5502B.toInt()
    private val green = 0xFF33684C.toInt()
    private val peach = 0xFFF2CEB4.toInt()

    fun render(stats: WrappedStats, page: Int, width: Int = 1080): Bitmap {
        require(page in 0 until CARD_COUNT)
        require(width in 1..1080)
        val bitmap = Bitmap.createBitmap(width, width * 16 / 9, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / 1080f, width / 1080f)
        val light = page == 1 || page == 3
        val foreground = if (light) ink else cream
        canvas.drawColor(when (page) { 0 -> ink; 1 -> peach; 2 -> green; else -> cream })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, x: Float, y: Float, size: Float, bold: Boolean = false, color: Int = foreground) {
            paint.color = color; paint.textSize = size
            paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(value, x, y, paint)
        }
        fun fitted(value: String, y: Float, size: Float = 156f) {
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = size
            text(value, 78f, y, minOf(size, size * 920f / paint.measureText(value).coerceAtLeast(1f)), true)
        }
        fun rule(y: Float) {
            paint.color = if (light) 0x33202D32 else 0x44FAF7F0
            paint.strokeWidth = 2f; canvas.drawLine(78f, y, 1002f, y, paint)
        }
        fun metric(label: String, value: String, y: Float) {
            text(label, 78f, y, 32f)
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD); paint.textSize = 38f
            text(value, 1002f - paint.measureText(value), y, 38f, true)
            rule(y + 30f)
        }

        text("pichalite", 78f, 120f, 48f, true)
        text("MY WRAPPED  /  ALL TIME", 78f, 192f, 24f, true)
        text("0${page + 1} / 04", 836f, 120f, 28f)

        // An original graphic: tilted photo prints, a rising sun and shrinking frames.
        canvas.save()
        canvas.translate(540f, 522f)
        for (i in 0..2) {
            canvas.save(); canvas.rotate(-18f + i * 16f)
            paint.color = if (i == 2) orange else if (i == 1) peach else 0xFF698473.toInt()
            canvas.drawRoundRect(RectF(-215f, -190f, 215f, 190f), 28f, 28f, paint)
            paint.color = cream
            canvas.drawRoundRect(RectF(-180f, -155f, 180f, 108f), 12f, 12f, paint)
            paint.color = if (page == 2) green else ink
            val mountain = android.graphics.Path().apply {
                moveTo(-180f, 108f); lineTo(-45f, -48f); lineTo(40f, 36f)
                lineTo(105f, -18f); lineTo(180f, 108f); close()
            }
            canvas.drawPath(mountain, paint)
            paint.color = orange; canvas.drawCircle(92f, -78f, 32f, paint)
            canvas.restore()
        }
        paint.color = if (light) green else peach
        canvas.drawCircle(318f, -172f, 38f, paint)
        canvas.drawCircle(-330f, 130f, 16f, paint)
        canvas.restore()

        when (page) {
            0 -> {
                text("Room for", 78f, 865f, 94f, true)
                text("what's next.", 78f, 974f, 94f, true)
                fitted(wrappedBytes(stats.reclaimedBytes), 1190f)
                text("SPACE RECLAIMED ON MY PHONE", 78f, 1250f, 29f, true)
                metric("Photos finished", count(stats.finished), 1390f)
                metric("Local originals replaced", count(stats.replaced), 1480f)
                text("Counted after verified replacement and cleanup.", 78f, 1620f, 28f)
            }
            1 -> {
                text("Small files.", 78f, 865f, 94f, true)
                text("Big difference.", 78f, 974f, 94f, true)
                fitted(wrappedBytes(stats.reducedBytes), 1190f)
                text("TOTAL COMPRESSION REDUCTION", 78f, 1250f, 29f, true)
                metric("Photos made smaller", count(stats.smaller), 1390f)
                metric("Reduction across smaller photos", "${stats.averageReductionPercent}%", 1480f)
                text("Compression reduction includes copies not yet replaced.", 78f, 1620f, 27f)
            }
            2 -> {
                text("Every photo.", 78f, 865f, 94f, true)
                text("Part of the story.", 78f, 964f, 83f, true)
                fitted(count(stats.finished), 1150f, 180f)
                text("PHOTOS FINISHED", 78f, 1210f, 29f, true)
                metric("Imported", count(stats.imported), 1330f)
                metric("Local originals retained", count(stats.retained), 1420f)
                metric("In the queue", count(stats.pending), 1510f)
                metric("Need a retry", count(stats.failed), 1600f)
            }
            3 -> {
                text("My biggest", 78f, 865f, 94f, true)
                text("little win.", 78f, 974f, 94f, true)
                fitted("${stats.bestReductionPercent}%", 1170f, 180f)
                text("BEST SINGLE-PHOTO REDUCTION", 78f, 1230f, 29f, true)
                metric("Largest file reduction", wrappedBytes(stats.largestReduction), 1360f)
                metric("Finished originals, combined", wrappedBytes(stats.sourceBytes), 1450f)
                metric("Generated JPEGs, combined", wrappedBytes(stats.outputBytes), 1540f)
                text("My photos. Lighter. All processed on my phone.", 78f, 1650f, 30f)
            }
        }
        rule(1745f)
        text("YOUR PHOTOS. LIGHTER.", 78f, 1810f, 25f, true)
        text("Made with PichaLite", 78f, 1858f, 27f)
        text("No photos shared. Just my stats.", 565f, 1858f, 25f)
        return bitmap
    }

    fun description(stats: WrappedStats, page: Int): String = when (page) {
        0 -> "My PichaLite Wrapped. ${wrappedBytes(stats.reclaimedBytes)} space reclaimed, ${stats.finished} photos finished, ${stats.replaced} local originals replaced."
        1 -> "${wrappedBytes(stats.reducedBytes)} compression reduction. ${stats.smaller} photos made smaller, ${stats.averageReductionPercent}% average reduction across smaller photos."
        2 -> "${stats.finished} photos finished of ${stats.imported} imported. ${stats.retained} local originals retained, ${stats.pending} queued, ${stats.failed} need retry."
        else -> "Best single-photo reduction ${stats.bestReductionPercent}%. Largest reduction ${wrappedBytes(stats.largestReduction)}. Finished originals total ${wrappedBytes(stats.sourceBytes)}, generated JPEGs total ${wrappedBytes(stats.outputBytes)}."
    }
}

internal object WrappedSharing {
    fun intent(context: Context, stats: WrappedStats, page: Int?): Intent {
        check(stats.finished > 0) { "Finish a photo to create your Wrapped" }
        require(page == null || page in 0 until WrappedArtwork.CARD_COUNT)
        val directory = File(context.cacheDir, "wrapped").apply { mkdirs() }
        // Unique paths keep an earlier share readable while a new one is created.
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
        val id = UUID.randomUUID().toString()
        val created = mutableListOf<File>()
        try {
            val uris = (page?.let { listOf(it) } ?: (0 until WrappedArtwork.CARD_COUNT).toList()).map { index ->
                val file = File(directory, "pichalite-wrapped-$id-${index + 1}.png")
                created += file
                val bitmap = WrappedArtwork.render(stats, index)
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }
            return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_TEXT, "My PichaLite Wrapped: ${stats.finished} photos finished. ${wrappedBytes(stats.reclaimedBytes)} reclaimed on my phone. Your photos. Lighter.")
                clipData = ClipData.newUri(context.contentResolver, "PichaLite Wrapped", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) { created.forEach { it.delete() }; throw e }
    }
}

@Composable internal fun WrappedScreen(model: PhotoModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { WrappedArtwork.CARD_COUNT })
    val page = pager.currentPage
    var sharing by remember { mutableStateOf(false) }
    // Freeze a recap on entry so cards and shares agree while another batch runs.
    val stats = remember(model.state.loaded) { WrappedStats.from(model.state, model.localUi.replaced, model.localUi.retained) }
    val share: (Int?) -> Unit = { selectedPage ->
        if (!sharing) {
            sharing = true
            scope.launch {
                try {
                    val intent = withContext(Dispatchers.IO) { WrappedSharing.intent(context, stats, selectedPage) }
                    context.startActivity(Intent.createChooser(intent, "Share your PichaLite Wrapped"))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    model.showMessage(e.message ?: "Could not share your Wrapped")
                } finally { sharing = false }
            }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("YOUR PHOTOS. YOUR STORY.", color = Orange, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text("Your Wrapped.", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        Text("Your all-time PichaLite story, made to share.", color = Muted, fontSize = 14.sp)
        if (!model.state.loaded || stats.finished == 0) {
            Spacer(Modifier.height(16.dp))
            Icon(Icons.Outlined.AutoAwesome, null, tint = Orange, modifier = Modifier.size(48.dp))
            Text(if (model.state.loaded) "Your first little win is waiting." else "Opening your photo history…", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Finish compressing a photo, then come back for your personal recap.", color = Muted)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(WrappedArtwork.CARD_COUNT) { index ->
                    Box(Modifier.weight(1f).height(24.dp).clickable(onClickLabel = "Show Wrapped card ${index + 1}") {
                        scope.launch { pager.animateScrollToPage(index) }
                    }, contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)).background(if (index == page) Orange else Line))
                    }
                }
            }
            WrappedCardPager(stats, pager, Modifier.fillMaxWidth().aspectRatio(9f / 16f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(page - 1) } }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous Wrapped card") }
                Text("${page + 1} of ${WrappedArtwork.CARD_COUNT} · Swipe to explore", color = Muted, fontSize = 12.sp)
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(page + 1) } }, enabled = page < WrappedArtwork.CARD_COUNT - 1) { Icon(Icons.Outlined.ChevronRight, "Next Wrapped card") }
            }
            Button(onClick = { share(page) }, enabled = !sharing, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.IosShare, null); Spacer(Modifier.width(8.dp))
                Text(if (sharing) "Preparing your graphics…" else "Share this card")
            }
            OutlinedButton(onClick = { share(null) }, enabled = !sharing, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Share all 4 cards") }
            Text("A snapshot of your finished history. Reopen Wrapped to refresh. Only stats and graphics are shared; your photos and filenames stay private.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            if (model.messageIsError && model.message != null) PhotoNotice(model.message!!, true, model::dismissMessage)
        }
    }
}

@Composable internal fun WrappedCardPager(stats: WrappedStats, pager: PagerState, modifier: Modifier = Modifier) {
    HorizontalPager(state = pager, modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Ink).testTag("wrapped_artwork")) { currentPage ->
        val artwork by produceState<Bitmap?>(null, stats, currentPage) {
            value = withContext(Dispatchers.Default) { WrappedArtwork.render(stats, currentPage, 540) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            artwork?.let { Image(it.asImageBitmap(), WrappedArtwork.description(stats, currentPage), Modifier.fillMaxSize()) }
        }
    }
}
