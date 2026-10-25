package ke.co.pichalite

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject

class PhotoService : Service() {
    companion object {
        @Volatile internal var importActive = false
        @Volatile internal var running = false
            private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    private val channel = "photos"
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        running = true
        manager.createNotificationChannel(NotificationChannel(channel, "Photo processing", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, channel)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("PichaLite").setContentText(message)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setOnlyAlertOnce(true)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(10, notification("Opening your photo queue…").setOngoing(true).build())
        if (monitor?.isActive != true) monitor = scope.launch {
            try {
                val files = LocalPhotoFiles(this@PhotoService)
                while (isActive) {
                    val state = RuntimeGateway.call(this@PhotoService, JSONObject().put("action", "snapshot"))
                    val jobs = state.getJSONArray("jobs")
                    for (i in 0 until jobs.length()) {
                        val photo = Photo.fromJson(jobs.getJSONObject(i))
                        if (!files.hasLocalSource(photo) || files.cacheReleased(photo) || photo.status != "ready") continue
                        withContext(Dispatchers.IO) { files.replaceWhenReady(photo) }
                        LocalReplacementRunner.release(this@PhotoService, photo, files)
                    }
                    val preferences = getSharedPreferences("results", MODE_PRIVATE)
                    var fresh = 0
                    for (i in 0 until jobs.length()) {
                        val photo = jobs.getJSONObject(i)
                        if (photo.getString("status") == "ready" && !preferences.getBoolean(photo.getString("id"), false)) {
                            preferences.edit().putBoolean(photo.getString("id"), true).apply()
                            val inputBytes = photo.optLong("input_bytes", 0L)
                            val outputBytes = photo.optLong("output_bytes", 0L)
                            PichaAnalytics.capture("compression_completed", mapOf(
                                "input_bytes" to inputBytes,
                                "output_bytes" to outputBytes,
                                "compressed_bytes_saved" to (inputBytes - outputBytes).coerceAtLeast(0L)
                            ))
                            fresh++
                        }
                    }
                    if (fresh > 0 && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@PhotoService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                        manager.notify(11, notification("$fresh photo${if (fresh == 1) " is" else "s are"} finished · open to review replacement").setAutoCancel(true).build())
                    }
                    val pending = state.getInt("pending")
                    if ((pending == 0 && !importActive) || state.getBoolean("paused")) break
                    manager.notify(10, notification("Preparing $pending photo${if (pending == 1) "" else "s"} · works offline").setOngoing(true).build())
                    delay(900)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("PichaLite", "Photo service failed", e)
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping away is an intentional interruption. The SQLite queue remains.
        scope.launch { RuntimeGateway.stop(); stopSelf() }
    }
    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

