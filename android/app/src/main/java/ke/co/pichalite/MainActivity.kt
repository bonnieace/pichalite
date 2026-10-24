package ke.co.pichalite

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.Application
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class Photo(val id: String, val name: String, val input: String, val inputBytes: Long, val output: String?, val outputBytes: Long?, val status: String, val error: String?, val savedUri: String? = null) {
    val preview: Any get() = savedUri?.let(Uri::parse) ?: File(output ?: input)
    companion object {
        fun fromJson(p: JSONObject) = Photo(p.getString("id"), p.getString("name"), p.getString("input"), p.getLong("input_bytes"),
            if (p.isNull("output")) null else p.getString("output"), if (p.isNull("output_bytes")) null else p.getLong("output_bytes"),
            p.getString("status"), if (p.isNull("error")) null else p.getString("error"), if (p.isNull("saved_uri")) null else p.getString("saved_uri"))
    }
}
data class QueueState(val loaded: Boolean = false, val paused: Boolean = false, val pending: Int = 0, val savedBytes: Long = 0, val photos: List<Photo> = emptyList(), val reclaimedBytes: Long = 0, val completed: Int = 0, val total: Int = 0)

class PhotoModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(QueueState()); private set
    var importing by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var messageIsError by mutableStateOf(false); private set
    var importedCount by mutableIntStateOf(0); private set
    var importTotal by mutableIntStateOf(0); private set
    private val context get() = getApplication<Application>()
    private val onboarding = LocalFlowIntroStore(app)
    var showOnboarding by mutableStateOf(!onboarding.completed); private set
    fun completeOnboarding() { onboarding.complete(); showOnboarding = false }
    fun replayOnboarding() { showOnboarding = true }
    val localFiles = LocalPhotoFiles(app)
    var selectedIds by mutableStateOf(setOf<String>()); private set
    var working by mutableStateOf(false); private set
    internal var localUi by mutableStateOf(LocalQueueUi()); private set
    private suspend fun refreshLocalUi() {
        val photos = state.photos
        localUi = withContext(Dispatchers.IO) { LocalQueueUi.read(photos, localFiles) }
    }
    val selectedPhotos get() = state.photos.filter { it.status == "ready" && it.id in selectedIds }
    fun select(photo: Photo, selected: Boolean) { selectedIds = if (selected) selectedIds + photo.id else selectedIds - photo.id }
    fun selectAll() { selectedIds = state.photos.filter { it.status == "ready" }.map { it.id }.toSet() }
    fun clearSelection() { selectedIds = emptySet() }
    fun importLocal(result: LocalImports) {
        importPhotos(result.uris, local = true, grants = result)
    }
    fun saveToFolder(tree: Uri) = operateSelected("saved") { localFiles.exportToFolder(it, tree) }
    fun saveToGallery() = operateSelected("saved to Gallery") { localFiles.exportToGallery(it) }
    var replacementApproval: PendingIntent? = null; private set
    private val replacementUi = app.getSharedPreferences("replacement_ui", 0)
    private var approvalPhotos: List<Photo> = emptyList()
    private var finishingReplacements = false
    fun takeReplacementApproval(): PendingIntent? {
        val request = replacementApproval ?: return null
        replacementApproval = null
        return request
    }
    private suspend fun recordReplacementApproval(photos: List<Photo>, request: PendingIntent) {
        approvalPhotos = photos
        withContext(Dispatchers.IO) { replacementUi.edit().putStringSet("photos", photos.map { it.id }.toSet()).commit() }
        replacementApproval = request
    }
    private suspend fun finishLocalPhotos() {
        if (approvalPhotos.isNotEmpty() || finishingReplacements) return
        finishingReplacements = true
        try {
            val needsPermission = mutableListOf<Photo>()
            var request: PendingIntent? = null
            val foreground = state.pending == 0 && !importing
            val photos = state.photos
            withContext(Dispatchers.IO) {
                for (photo in photos) {
                    if (!localFiles.hasLocalSource(photo) || localFiles.cacheReleased(photo) || photo.status != "ready") continue
                    val approval = localFiles.replaceWhenReady(photo, foreground = foreground)
                    LocalReplacementRunner.release(context, photo, localFiles)
                    if (approval != null) {
                        needsPermission += photo
                        request = approval
                        if (Build.VERSION.SDK_INT < 30) break
                    }
                }
            }
            refreshLocalUi()
            val approvalRequest = request
            if (approvalRequest != null) {
                val grouped = if (needsPermission.size > 1) withContext(Dispatchers.IO) { localFiles.batchReplacementPermission(needsPermission) } else approvalRequest
                recordReplacementApproval(needsPermission, grouped)
            }
        } finally { finishingReplacements = false }
    }
    fun completeReplacementApproval(approved: Boolean) = viewModelScope.launch {
        // Android may deliver the result to a recreated Activity before its first snapshot.
        finishingReplacements = true
        try {
            if (!state.loaded) refreshSnapshot()
            val ids = replacementUi.getStringSet("photos", emptySet()) ?: emptySet()
            val photos = approvalPhotos.ifEmpty { state.photos.filter { it.id in ids } }
            withContext(Dispatchers.IO) {
                for (photo in photos) {
                    if (approved) localFiles.replaceWhenReady(photo, foreground = true, retry = true).let {
                        if (it != null) localFiles.declineReplacement(photo)
                    } else localFiles.declineReplacement(photo)
                }
            }
            for (photo in photos) LocalReplacementRunner.release(context, photo, localFiles)
            replacementUi.edit().remove("photos").commit()
            approvalPhotos = emptyList()
            replacementApproval = null
            refreshLocalUi()
        } finally { finishingReplacements = false }
    }
    fun retryReplacement(photo: Photo) = viewModelScope.launch {
        if (approvalPhotos.isNotEmpty() || working || finishingReplacements) return@launch
        working = true
        try {
            val request = withContext(Dispatchers.IO) { localFiles.replaceWhenReady(photo, foreground = true, retry = true) }
            LocalReplacementRunner.release(context, photo, localFiles)
            if (request != null) {
                recordReplacementApproval(listOf(photo), request)
            }
            refreshLocalUi()
        } finally { working = false }
    }
    private fun operateSelected(action: String, operation: (Photo) -> Unit) = viewModelScope.launch {
        if (working) return@launch
        val photos = selectedPhotos.toList()
        if (photos.isEmpty()) return@launch
        working = true
        var completed = 0
        val errors = mutableListOf<String>()
        try {
            for (photo in photos) {
                try { withContext(Dispatchers.IO) { operation(photo) }; completed++ }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    errors += "${photo.name}: ${e.message ?: "Could not complete the operation"}"
                }
                refreshLocalUi()
            }
            showMessage("$completed of ${photos.size} $action." + if (errors.isEmpty()) "" else " ${errors.first()}", isError = errors.isNotEmpty())
        } finally { working = false }
    }
    fun showMessage(value: String, isError: Boolean = true) { messageIsError = isError; message = value }
    private suspend fun parse(value: JSONObject) {
        state = withContext(Dispatchers.Default) {
            val jobs = value.getJSONArray("jobs")
            QueueState(true, value.getBoolean("paused"), value.getInt("pending"), value.getLong("saved_bytes"), (0 until jobs.length()).map { i ->
                val p = jobs.getJSONObject(i)
                Photo.fromJson(p)
            }, value.optLong("reclaimed_bytes"), value.optInt("completed"), value.optInt("total"))
        }
    }
    private suspend fun refreshSnapshot() { parse(RuntimeGateway.call(context, JSONObject().put("action", "snapshot"))) }
    suspend fun refresh() {
        try { refreshSnapshot(); finishLocalPhotos() }
        catch (e: Exception) { if (e is CancellationException) throw e; showMessage(e.message ?: "Could not open your photos") }
    }
    fun dismissMessage() { message = null }
    fun togglePause() = viewModelScope.launch {
        try {
            parse(RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", !state.paused)))
            if (!state.paused && state.pending > 0) startProcessing()
        } catch (e: Exception) { showMessage(e.message ?: "Could not complete this action") }
    }
    fun retry(photo: Photo) = viewModelScope.launch {
        try { parse(RuntimeGateway.call(context, JSONObject().put("action", "retry").put("id", photo.id))); if (!state.paused) startProcessing() }
        catch (e: Exception) { showMessage(e.message ?: "Could not complete this action") }
    }
    fun startProcessing() {
        if (PhotoService.running) {
            if (message == PROCESSING_WARNING) dismissMessage()
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, PhotoService::class.java))
            if (message == PROCESSING_WARNING) dismissMessage()
        } catch (error: IllegalStateException) {
            android.util.Log.w("PichaLite", "Could not start photo service", error)
            showMessage(PROCESSING_WARNING)
        }
    }
    private companion object {
        const val PROCESSING_WARNING = "Photos saved. Reopen PichaLite to continue processing."
    }

    fun importPhotos(uris: List<Uri>, local: Boolean = false, grants: LocalImports? = null) = viewModelScope.launch {
        if (importing || uris.isEmpty()) return@launch
        importing = true
        PhotoService.importActive = true
        importedCount = 0
        importTotal = uris.distinct().size
        dismissMessage()
        var added = 0
        val errors = mutableListOf<String>()
        try {
            if (!state.paused) startProcessing()
            withContext(Dispatchers.IO) { if (grants != null) localFiles.retainGrants(grants) }
            // Copy up to two provider streams while the native worker compresses
            // each completed file. Never wait for the rest of the batch or a
            // save/replacement pass before handing the next photo to the queue.
            importInParallel(
                items = uris.distinct(),
                transfer = { uri ->
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val mime = resolver.getType(uri)
                        val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> error("Use JPEG, PNG or WebP photos") }
                        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "photo.$extension"
                        val safe = display.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(90).substringBeforeLast('.')
                        val inbox = File(RuntimeGateway.root(context), "inbox").apply { mkdirs() }
                        val destination = File(inbox, "${UUID.randomUUID()}__${safe}.$extension")
                        val part = File(inbox, destination.name + ".part")
                        try {
                            resolver.openInputStream(uri).use { source ->
                                checkNotNull(source) { "Could not read $display" }
                                FileOutputStream(part).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        check(total <= 40L * 1024 * 1024) { "$display is larger than 40 MB" }
                                        check(android.os.StatFs(inbox.absolutePath).availableBytes > count + 16L * 1024 * 1024) { "Not enough free space to import more photos" }
                                        out.write(buffer, 0, count)
                                    }
                                    check(total > 0) { "$display is empty" }
                                    out.fd.sync()
                                }
                            }
                            check(part.renameTo(destination)) { "Could not save $display" }
                            if (local) localFiles.rememberSource(destination, uri)
                            destination
                        } finally { part.delete() }
                    }
                },
                onImported = { input ->
                    RuntimeGateway.call(context, JSONObject().put("action", "enqueue").put("input", input.absolutePath))
                    added++
                    importedCount = added
                },
                onFailure = { errors += it.message ?: "A photo could not be imported" },
            )
            refresh()
            if (added > 0 && !state.paused) startProcessing()
            showMessage("$added photo${if (added == 1) "" else "s"} imported." + if (errors.isEmpty()) "" else " ${errors.first()}", isError = errors.isNotEmpty())
            refreshLocalUi()
        } finally { importing = false; PhotoService.importActive = false }
    }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<PhotoModel>()
    private val replacementPermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.completeReplacementApproval(it.resultCode == Activity.RESULT_OK)
    }
    private fun launchReplacementPermission() {
        if (model.showOnboarding) return
        model.takeReplacementApproval()?.let {
            try { replacementPermission.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            catch (e: Exception) { model.completeReplacementApproval(false); model.showMessage(e.message ?: "Could not open Android’s replacement prompt") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PichaTheme {
            if (model.showOnboarding) LocalFlowOnboarding(model::completeOnboarding)
            else PichaScreen(model, ::sharePhoto, ::sharePhotos)
        } }
        // A configuration change must not import the same incoming intent twice.
        if (savedInstanceState == null) acceptShare(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.refresh()
                if (model.state.pending > 0 && !model.state.paused) model.startProcessing()
                launchReplacementPermission()
                while (isActive) {
                    delay(900)
                    model.refresh()
                    if (model.state.pending > 0 && !model.state.paused) model.startProcessing()
                    launchReplacementPermission()
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptShare(intent) }
    @Suppress("DEPRECATION")
    private fun acceptShare(intent: Intent) {
        val photos = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
        model.importPhotos(photos)
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(Intent.EXTRA_STREAM)
    }
    private fun sharePhoto(photo: Photo) = sharePhotos(listOf(photo))
    private fun sharePhotos(photos: List<Photo>) {
        try { startActivity(FinishedPhotosSharing.chooser(this, photos)) }
        catch (e: Exception) { model.showMessage(e.message ?: "Could not share these photos") }
    }

}

internal val Ink = Color(0xFF202D32)
internal val Paper = Color(0xFFFAF7F0)
internal val Orange = Color(0xFFD5502B)
internal val Muted = Color(0xFF68716D)
internal val Line = Color(0xFFE4E2D9)
internal val Green = Color(0xFF33684C)

@Composable private fun PichaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Orange, onPrimary = Color.White, background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink, secondary = Green), content = content)
}

@Composable private fun PichaScreen(model: PhotoModel, onShare: (Photo) -> Unit, onBatchShare: (List<Photo>) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var previewingId by rememberSaveable { mutableStateOf<String?>(null) }
    var studioPage by rememberSaveable { mutableIntStateOf(0) }
    var readyPage by rememberSaveable { mutableIntStateOf(0) }
    val studioList = rememberLazyListState()
    val readyList = rememberLazyListState()
    val state = model.state
    var showExports by remember { mutableStateOf(false) }
    val selected = model.selectedPhotos
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val files = rememberLauncherForActivityResult(LocalImagesContract()) {
        model.importLocal(it)
        if (it.uris.isNotEmpty()) requestNotifications()
    }
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) files.launch(Unit) else model.showMessage("Storage access is needed to save and replace local images.")
    }
    val compressLocal: () -> Unit = {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(model.getApplication<Application>(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            legacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else files.launch(Unit)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            model.saveToFolder(uri)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        model.importPhotos(it)
        if (it.isNotEmpty()) requestNotifications()
    }
    val addPhotos: () -> Unit = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val ready = state.completed
    Scaffold(
        containerColor = Paper,
        bottomBar = {
            PichaBottomBar(tab, ready) { tab = it }
        }
    ) { padding ->
        if (tab == 2) WrappedScreen(model, Modifier.padding(padding))
        else LazyColumn(state = if (tab == 1) readyList else studioList, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Orange), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.FilterHdr, null, tint = Color.White, modifier = Modifier.size(23.dp)) }
                        Spacer(Modifier.width(9.dp)); Text("picha", fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, letterSpacing = (-1).sp); Text("lite", fontSize = 25.sp, letterSpacing = (-1).sp, color = Orange)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("●  ON-DEVICE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Green)
                        IconButton(onClick = model::replayOnboarding, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.HelpOutline, "How PichaLite works", tint = Muted, modifier = Modifier.size(20.dp)) }
                    }
                }
            }
            if (tab == 0) {
                item {
                    Column {
                        Text("MAKE ROOM FOR MORE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Orange, letterSpacing = 2.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Your photos.\nLighter.", fontSize = 48.sp, lineHeight = 49.sp, letterSpacing = (-2).sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(12.dp))
                        Text("Less data to send. More space to keep.\nPrepare a whole batch, right on your phone.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                        Spacer(Modifier.height(21.dp))
                        Button(onClick = compressLocal, enabled = !model.importing && !model.working, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text(if (model.importing) "Importing your photos…" else "Compress local photos", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = addPhotos, enabled = !model.importing && !model.working, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Google Photos")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("JPEG, PNG & WebP", fontSize = 11.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFF1EAD9)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SPACE RECLAIMED", fontSize = 10.sp, color = Muted, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                            Text(bytes(state.reclaimedBytes), fontSize = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            Text("${bytes(state.savedBytes)} total compression reduction", fontSize = 12.sp, color = Muted)
                        }
                        Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFE4D6B5)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SouthWest, null, tint = Green, modifier = Modifier.size(32.dp)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your photo queue", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            val waitingForPermission = model.localUi.waiting
                            val needsAttention = model.localUi.attention
                            val replacing = model.localUi.replacing
                            Text(when {
                                state.paused -> "Paused · saved safely for later"
                                state.pending > 0 -> "${state.completed} compressed · ${state.pending} remaining"
                                waitingForPermission > 0 -> "Waiting for Android permission for $waitingForPermission photos"
                                replacing > 0 -> "Saving and replacing $replacing photos…"
                                needsAttention > 0 -> "$needsAttention originals kept · retry replacement below"
                                else -> "Finished · totals saved"
                            }, fontSize = 12.sp, color = Muted)
                        }
                        if (state.pending > 0 || state.paused) TextButton(onClick = { model.togglePause() }) {
                            Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, modifier = Modifier.size(18.dp)); Text(if (state.paused) "Resume" else "Pause")
                        }
                    }
                }
            } else {
                item {
                    Column {
                        Text("READY WHEN YOU ARE", color = Orange, letterSpacing = 2.sp, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp)); Text("Good to go.", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
                        Spacer(Modifier.height(8.dp)); Text("Select finished photos to share or export another copy.", color = Muted, fontSize = 14.sp)
                    }
                }
            }
            if (tab == 1 && ready > 0) item(key = "selection") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF1EAD9)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { model.selectAll() }, enabled = !model.working) { Text("Select all") }
                        TextButton(onClick = { model.clearSelection() }, enabled = !model.working) { Text("Clear") }
                    }
                    if (selected.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onBatchShare(selected) }, enabled = !model.working, modifier = Modifier.weight(1f)) { Text("Share") }
                        }
                        TextButton(onClick = { showExports = !showExports }, enabled = !model.working) { Text(if (showExports) "Hide export options" else "Export another copy") }
                        if (showExports) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { folder.launch(null) }, enabled = !model.working, modifier = Modifier.weight(1f)) { Text("To folder", fontSize = 12.sp, maxLines = 1) }
                            if (Build.VERSION.SDK_INT >= 29) OutlinedButton(onClick = { model.saveToGallery() }, enabled = !model.working, modifier = Modifier.weight(1f)) { Text("To Gallery") }
                        }
                        if (model.working) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working on selected photos…", fontSize = 12.sp) }
                    }
                }
            }
            if (model.message != null) item(key = "notice") {
                PhotoNotice(model.message!!, model.messageIsError, model::dismissMessage)
            }
            photoQueue(state, model.importing, model.importedCount, model.importTotal, readyTab = tab == 1,
                page = if (tab == 1) readyPage else studioPage,
                onPageChange = { if (tab == 1) readyPage = it else studioPage = it }) { photo ->
                PhotoRow(photo, state.paused, { onShare(photo) }, { model.retry(photo) },
                    photo.id in model.selectedIds, { model.select(photo, it) }, !model.working,
                    model.localUi.photos[photo.id]?.status,
                    if (model.localUi.photos[photo.id]?.retry == true) ({ model.retryReplacement(photo) }) else null,
                    selectable = tab == 1, open = { previewingId = photo.id })
            }
        }
    }
    val previewPhotos = if (tab == 1) state.photos.filter { it.status == "ready" } else state.photos
    previewPhotos.indexOfFirst { it.id == previewingId }.takeIf { it >= 0 }?.let { index ->
        PhotoPreviewDialog(previewPhotos[index], index, previewPhotos.size,
            dismiss = { previewingId = null },
            previous = { previewingId = previewPhotos[index - 1].id },
            next = { previewingId = previewPhotos[index + 1].id },
            share = { onShare(previewPhotos[index]) })
    }

}

@Composable internal fun PhotoRow(photo: Photo, paused: Boolean, share: () -> Unit, retry: () -> Unit, selected: Boolean, select: (Boolean) -> Unit, selectionEnabled: Boolean, localStatus: String?, retryReplacement: (() -> Unit)?, selectable: Boolean = true, open: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${photo.name}", onClick = open), verticalAlignment = Alignment.CenterVertically) {
            // Reserve the selection gutter before completion; the thumbnail never jumps.
            if (selectable) Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                if (photo.status == "ready") Checkbox(checked = selected, onCheckedChange = select, enabled = selectionEnabled)
            }
            AsyncImage(model = photo.preview, contentDescription = photo.name, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(Line))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(5.dp))
                if (photo.status == "ready") {
                    Text("${bytes(photo.inputBytes)} → ${bytes(photo.outputBytes ?: 0)}", color = Muted, fontSize = 12.sp)
                    val reduction = if (photo.inputBytes > 0) ((photo.inputBytes - (photo.outputBytes ?: photo.inputBytes)) * 100 / photo.inputBytes).coerceAtLeast(0) else 0
                    Text(if (reduction > 0) "$reduction% lighter" else "Ready to share", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else Text(when (photo.status) { "failed" -> "Could not process · tap retry"; "processing" -> "Preparing your photo…"; else -> if (paused) "Paused · safely in your queue" else "Waiting in your queue" }, fontSize = 12.sp, color = if (photo.status == "failed") Orange else Muted)
            }
            when (photo.status) {
                "ready" -> IconButton(onClick = share) { Icon(Icons.Outlined.IosShare, "Share ${photo.name}", tint = Orange) }
                "failed" -> IconButton(onClick = retry) { Icon(Icons.Outlined.Refresh, "Retry ${photo.name}", tint = Orange) }
                "processing" -> CircularProgressIndicator(Modifier.size(20.dp), color = Orange, strokeWidth = 2.dp)
                else -> Icon(Icons.Outlined.Schedule, null, tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        if (localStatus != null) Text(localStatus, fontSize = 11.sp, color = if (retryReplacement != null) Orange else Green, modifier = Modifier.padding(top = 6.dp))
        if (retryReplacement != null) TextButton(onClick = retryReplacement, enabled = selectionEnabled) { Text("Retry replacement") }
        if (photo.status == "failed" && photo.error != null) Text(photo.error, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(Modifier.padding(top = 18.dp), color = Line)
    }
}

internal fun bytes(value: Long): String = wrappedBytes(value)
