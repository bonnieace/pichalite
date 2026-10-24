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
    private val context get() = getApplication<Application>()
    val localFiles = LocalPhotoFiles(app)
    var selectedIds by mutableStateOf(setOf<String>()); private set
    var working by mutableStateOf(false); private set
    var localVersion by mutableIntStateOf(0); private set
    val selectedPhotos get() = state.photos.filter { it.status == "ready" && it.id in selectedIds }
    fun select(photo: Photo, selected: Boolean) { selectedIds = if (selected) selectedIds + photo.id else selectedIds - photo.id }
    fun selectAll() { selectedIds = state.photos.filter { it.status == "ready" }.map { it.id }.toSet() }
    fun clearSelection() { selectedIds = emptySet() }
    fun importLocal(result: LocalImports) {
        localFiles.retainGrants(result)
        importPhotos(result.uris, local = true)
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
    private fun recordReplacementApproval(photos: List<Photo>, request: PendingIntent) {
        approvalPhotos = photos
        replacementUi.edit().putStringSet("photos", photos.map { it.id }.toSet()).commit()
        replacementApproval = request
    }
    private suspend fun finishLocalPhotos() {
        if (approvalPhotos.isNotEmpty() || finishingReplacements) return
        finishingReplacements = true
        try {
            val needsPermission = mutableListOf<Photo>()
            var request: PendingIntent? = null
            val foreground = state.pending == 0 && !importing
            for (photo in state.photos) {
                if (!localFiles.hasLocalSource(photo) || localFiles.cacheReleased(photo) || photo.status != "ready") continue
                val approval = withContext(Dispatchers.IO) { localFiles.replaceWhenReady(photo, foreground = foreground) }
                LocalReplacementRunner.release(context, photo, localFiles)
                if (approval != null) {
                    needsPermission += photo
                    request = approval
                    if (Build.VERSION.SDK_INT < 30) break
                }
            }
            localVersion++
            if (request != null) {
                if (needsPermission.size > 1) request = withContext(Dispatchers.IO) { localFiles.batchReplacementPermission(needsPermission) }
                recordReplacementApproval(needsPermission, request)
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
            localVersion++
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
            localVersion++
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
                localVersion++
            }
            message = "$completed of ${photos.size} $action." + if (errors.isEmpty()) "" else " ${errors.first()}"
        } finally { working = false }
    }
    private val backups = PhotoBackupStore(app)
    private var backupVersion by mutableIntStateOf(0)
    fun backupStage(photo: Photo): BackupStage {
        @Suppress("UNUSED_VARIABLE") val observedVersion = backupVersion
        return backups.stage(photo)
    }
    fun showMessage(value: String) { message = value }
    fun recordGooglePhotosHandoff(photo: Photo) = viewModelScope.launch {
        try { withContext(Dispatchers.IO) { backups.recordHandoff(photo) }; backupVersion++ }
        catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    fun confirmGooglePhotosBackup(photo: Photo) = viewModelScope.launch {
        try { withContext(Dispatchers.IO) { backups.confirmBackup(photo) }; backupVersion++ }
        catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    private fun parse(value: JSONObject) {
        val jobs = value.getJSONArray("jobs")
        state = QueueState(true, value.getBoolean("paused"), value.getInt("pending"), value.getLong("saved_bytes"), (0 until jobs.length()).map { i ->
            val p = jobs.getJSONObject(i)
            Photo.fromJson(p)
        }, value.optLong("reclaimed_bytes"), value.optInt("completed"), value.optInt("total"))
    }
    private suspend fun refreshSnapshot() { parse(RuntimeGateway.call(context, JSONObject().put("action", "snapshot"))) }
    suspend fun refresh() {
        try { refreshSnapshot(); finishLocalPhotos() }
        catch (e: Exception) { if (e is CancellationException) throw e; message = e.message ?: "Could not open your photos" }
    }
    fun dismissMessage() { message = null }
    fun togglePause() = viewModelScope.launch {
        try {
            parse(RuntimeGateway.call(context, JSONObject().put("action", "pause").put("paused", !state.paused)))
            if (!state.paused && state.pending > 0) startProcessing()
        } catch (e: Exception) { message = e.message }
    }
    fun retry(photo: Photo) = viewModelScope.launch {
        try { parse(RuntimeGateway.call(context, JSONObject().put("action", "retry").put("id", photo.id))); if (!state.paused) startProcessing() }
        catch (e: Exception) { message = e.message }
    }
    fun startProcessing() {
        try { ContextCompat.startForegroundService(context, Intent(context, PhotoService::class.java)) }
        catch (_: IllegalStateException) { message = "Photos saved. Reopen PichaLite to continue processing." }
    }

    fun importPhotos(uris: List<Uri>, local: Boolean = false) = viewModelScope.launch {
        if (importing || uris.isEmpty()) return@launch
        importing = true
        var added = 0
        val errors = mutableListOf<String>()
        try {
            for (uri in uris.distinct()) {
                try {
                    val input = withContext(Dispatchers.IO) {
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
                    RuntimeGateway.call(context, JSONObject().put("action", "enqueue").put("input", input.absolutePath))
                    added++
                    refresh()
                    if (!state.paused) startProcessing()
                } catch (e: Exception) { if (e is CancellationException) throw e; errors += e.message ?: "A photo could not be imported" }
            }
            refresh()
            if (added > 0 && !state.paused) startProcessing()
            message = "$added photos imported." + if (errors.isEmpty()) "" else " ${errors.first()}"
            localVersion++
        } finally { importing = false }
    }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<PhotoModel>()
    private val replacementPermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.completeReplacementApproval(it.resultCode == Activity.RESULT_OK)
    }
    private fun launchReplacementPermission() {
        model.takeReplacementApproval()?.let {
            try { replacementPermission.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            catch (e: Exception) { model.completeReplacementApproval(false); model.showMessage(e.message ?: "Could not open Android’s replacement prompt") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PichaTheme { PichaScreen(model, ::sharePhoto, ::sendToGooglePhotos, ::openGooglePhotos, ::sharePhotos, ::sendBatchToGooglePhotos) } }
        // A configuration change must not import the same incoming intent twice.
        if (savedInstanceState == null) acceptShare(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.refresh()
                if (model.state.pending > 0 && !model.state.paused) model.startProcessing()
                launchReplacementPermission()
                while (isActive) { delay(900); model.refresh(); launchReplacementPermission() }
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
        try { startActivity(Intent.createChooser(FinishedPhotosSharing.intent(this, photos), "Share lighter photos")) }
        catch (e: Exception) { model.showMessage(e.message ?: "Could not share these photos") }
    }
    private fun sendBatchToGooglePhotos(photos: List<Photo>) {
        try {
            startActivity(FinishedPhotosSharing.intent(this, photos, GooglePhotosSharing.PACKAGE))
            photos.forEach { model.recordGooglePhotosHandoff(it) }
            model.showMessage("Sent ${photos.size} copies to Google Photos. Finish saving there, then check backup for each copy.")
        } catch (_: ActivityNotFoundException) { model.showMessage("Google Photos is not available. Use Share for another app.") }
        catch (e: Exception) { model.showMessage(e.message ?: "Could not send these photos") }
    }

    private fun sendToGooglePhotos(photo: Photo) {
        try {
            startActivity(GooglePhotosSharing.intent(this, photo))
            // A successful launch is only a handoff. Cancellation/return is never
            // interpreted as a completed upload and never enables automatic deletion.
            model.recordGooglePhotosHandoff(photo)
        } catch (_: ActivityNotFoundException) {
            model.showMessage("Google Photos is not available. Install or enable it, or use Share for another app.")
        } catch (e: Exception) {
            model.showMessage(e.message ?: "Could not send this photo to Google Photos")
        }
    }

    private fun openGooglePhotos() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/"))) }
        catch (_: ActivityNotFoundException) { model.showMessage("Open Google Photos to check the smaller copy and its backup status.") }
    }
}

private val Ink = Color(0xFF202D32)
private val Paper = Color(0xFFFAF7F0)
private val Orange = Color(0xFFD5502B)
private val Muted = Color(0xFF68716D)
private val Line = Color(0xFFE4E2D9)
private val Green = Color(0xFF33684C)

@Composable private fun PichaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Orange, onPrimary = Color.White, background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink, secondary = Green), content = content)
}

@Composable private fun PichaScreen(model: PhotoModel, onShare: (Photo) -> Unit, onGooglePhotos: (Photo) -> Unit, onOpenGooglePhotos: () -> Unit, onBatchShare: (List<Photo>) -> Unit, onBatchGooglePhotos: (List<Photo>) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var reviewingId by rememberSaveable { mutableStateOf<String?>(null) }
    val state = model.state
    var showExports by remember { mutableStateOf(false) }
    val selected = model.selectedPhotos
    @Suppress("UNUSED_VARIABLE") val localVersion = model.localVersion
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
            NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Outlined.PhotoLibrary, null) }, label = { Text("Studio") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF2DECF)))
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Outlined.CheckCircle, null) }, label = { Text("Ready · $ready") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF2DECF)))
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Orange), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.FilterHdr, null, tint = Color.White, modifier = Modifier.size(23.dp)) }
                        Spacer(Modifier.width(9.dp)); Text("picha", fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, letterSpacing = (-1).sp); Text("lite", fontSize = 25.sp, letterSpacing = (-1).sp, color = Orange)
                    }
                    Text("●  ON-DEVICE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Green)
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
                            Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Google Photos / cloud")
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("JPEG, PNG & WebP · Local files save and replace", fontSize = 11.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(8.dp))
                        Text("Local photos: compress → save to Gallery → delete original. Allow replacement if Android asks. Cloud imports save a copy; cloud originals stay.", fontSize = 11.sp, color = Muted, lineHeight = 16.sp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFF1EAD9)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SPACE RECLAIMED", fontSize = 10.sp, color = Muted, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                            Text(bytes(state.reclaimedBytes), fontSize = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                            Text("${bytes(state.savedBytes)} total compression reduction", fontSize = 12.sp, color = Muted)
                            Text("Local originals are replaced after a verified save", fontSize = 10.sp, color = Muted)
                        }
                        Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFE4D6B5)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SouthWest, null, tint = Green, modifier = Modifier.size(32.dp)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your photo queue", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            val waitingForPermission = state.photos.count { !model.localFiles.wasDeleted(it) && model.localFiles.replacementState(it) == "awaiting" }
                            val needsAttention = state.photos.count { model.localFiles.canRetryReplacement(it) }
                            val replacing = state.photos.count { it.status == "ready" && model.localFiles.hasLocalSource(it) && !model.localFiles.cacheReleased(it) && !model.localFiles.canRetryReplacement(it) && model.localFiles.replacementState(it) != "not_smaller" && (!model.localFiles.wasDeleted(it) || model.localFiles.hasExport(it)) }
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
                        Spacer(Modifier.height(8.dp)); Text("Local photos are saved and replaced automatically. Select finished photos to share or export another copy.", color = Muted, fontSize = 14.sp)
                    }
                }
            }
            if (ready > 0) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF1EAD9)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selected.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { model.selectAll() }, enabled = !model.working) { Text("Select all") }
                        TextButton(onClick = { model.clearSelection() }, enabled = !model.working) { Text("Clear") }
                    }
                    if (selected.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onBatchShare(selected) }, enabled = !model.working, modifier = Modifier.weight(1f)) { Text("Share") }
                            OutlinedButton(onClick = { onBatchGooglePhotos(selected) }, enabled = !model.working, modifier = Modifier.weight(1f)) { Text("Google Photos", fontSize = 12.sp, maxLines = 1) }
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
            if (model.message != null) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFE8DE)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.message!!, Modifier.weight(1f), fontSize = 12.sp)
                    IconButton(onClick = { model.dismissMessage() }) { Icon(Icons.Outlined.Close, "Dismiss") }
                }
            }
            val photos = if (tab == 1) state.photos.filter { it.status == "ready" } else state.photos
            if (!state.loaded) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Orange) }
            else if (photos.isEmpty()) item {
                Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(20.dp)).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (tab == 0) Icons.Outlined.Collections else Icons.Outlined.CheckCircle, null, tint = Orange, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(12.dp)); Text(if (tab == 0) "A little lighter starts here." else "Your finished photos land here.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp)); Text(if (tab == 0) "Choose photos above, or share them\nto PichaLite from your Gallery." else "Choose a few photos in the Studio\nto get your first batch ready.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            items(photos, key = { it.id }) { photo ->
                PhotoRow(photo, state.paused, { onShare(photo) }, { model.retry(photo) }, { reviewingId = photo.id }, model.backupStage(photo),
                    photo.id in model.selectedIds, { model.select(photo, it) }, !model.working,
                    model.localFiles.replacementStatus(photo),
                    if (model.localFiles.canRetryReplacement(photo)) ({ model.retryReplacement(photo) }) else null)
            }
            item { Text("COMPRESSION STAYS ON YOUR PHONE.\nLocal images save to Gallery and replace originals after verification. Cloud import and backup need internet. PichaLite keeps a smaller working copy for sharing.", color = Muted, fontSize = 10.sp, lineHeight = 17.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
    state.photos.find { it.id == reviewingId && it.status == "ready" }?.let { photo ->
        GooglePhotosDialog(photo, model.backupStage(photo), { reviewingId = null },
            { onGooglePhotos(photo) }, onOpenGooglePhotos, { model.confirmGooglePhotosBackup(photo) })
    }
}

@Composable private fun PhotoRow(photo: Photo, paused: Boolean, share: () -> Unit, retry: () -> Unit, googlePhotos: () -> Unit, backupStage: BackupStage, selected: Boolean, select: (Boolean) -> Unit, selectionEnabled: Boolean, localStatus: String?, retryReplacement: (() -> Unit)?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photo.status == "ready") Checkbox(checked = selected, onCheckedChange = select, enabled = selectionEnabled)
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
        if (photo.status == "ready") {
            TextButton(onClick = googlePhotos, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.CloudUpload, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(when (backupStage) {
                    BackupStage.USER_VERIFIED -> "Backup reviewed · next steps"
                    BackupStage.SENT -> "Google Photos · check backup"
                    BackupStage.NOT_SENT -> "Save to Google Photos"
                }, fontSize = 12.sp)
            }
        }
        if (photo.status == "failed" && photo.error != null) Text(photo.error, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        HorizontalDivider(Modifier.padding(top = 18.dp), color = Line)
    }
}

@Composable private fun GooglePhotosDialog(photo: Photo, stage: BackupStage, dismiss: () -> Unit, send: () -> Unit, open: () -> Unit, verify: () -> Unit) {
    var checkedBackup by remember(photo.id, photo.output, photo.outputBytes, stage) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Back up a lighter copy", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(photo.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (File(photo.input).exists()) Column(Modifier.weight(1f)) {
                        AsyncImage(File(photo.input), "Original photo preview", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)).background(Line))
                        Text(if (File(photo.input).exists()) "Original · ${bytes(photo.inputBytes)}" else "Original removed", fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        AsyncImage(photo.preview, "Lighter photo preview", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)).background(Line))
                        Text("Lighter · ${bytes(photo.outputBytes ?: 0)}", fontSize = 12.sp, color = Green)
                    }
                }
                if ((photo.outputBytes ?: photo.inputBytes) >= photo.inputBytes) {
                    Text("This JPEG is not smaller than the original. Keeping the original avoids increasing storage use.", color = Orange)
                }
                when (stage) {
                    BackupStage.NOT_SENT -> Text("Review the quality, then send this smaller copy to Google Photos. Choose the correct account and finish saving it there. Sending does not delete any original.")
                    BackupStage.SENT -> {
                        Text("The copy was handed to Google Photos. PichaLite cannot tell whether you saved it, cancelled, or finished backing it up.")
                        Text("Open Google Photos, check the correct account, open the smaller copy and confirm backup has finished.")
                        Row(Modifier.fillMaxWidth().toggleable(value = checkedBackup, onValueChange = { checkedBackup = it }), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checkedBackup, onCheckedChange = null)
                            Text("I checked the smaller copy and confirmed its cloud backup finished.", fontSize = 13.sp)
                        }
                        Button(onClick = verify, enabled = checkedBackup, modifier = Modifier.fillMaxWidth()) { Text("Record my check") }
                    }
                    BackupStage.USER_VERIFIED -> {
                        Text("You marked the smaller copy as backed up. This is your confirmation, not an automatic cloud check.", color = Green)
                        Text("If you want to remove the original:", fontWeight = FontWeight.Bold)
                        Text("1. Open Google Photos and identify the original by its image, filename and larger file size.\n2. Keep the verified smaller copy.\n3. Select only the original and move it to Trash yourself.")
                        Text("Deleting in Google Photos may also remove synced device copies and album entries. PichaLite cannot undo it or remove cloud originals for you.", color = Orange)
                    }
                }
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("Open Google Photos") }
                Text("Sending a smaller copy alone does not free cloud storage. Original removal is manual.", fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = send) { Text(if (stage == BackupStage.NOT_SENT) "Send lighter copy" else "Send again") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

private fun bytes(value: Long): String = when {
    value >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", value / (1024.0 * 1024))
    value >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", value / 1024.0)
    else -> "$value B"
}

