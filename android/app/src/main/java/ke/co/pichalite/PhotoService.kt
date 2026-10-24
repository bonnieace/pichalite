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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitor: Job? = null
    private val channel = "photos"
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
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
                while (isActive) {
                    val state = RuntimeGateway.call(this@PhotoService, JSONObject().put("action", "snapshot"))
                    val jobs = state.getJSONArray("jobs")
                    withContext(Dispatchers.IO) {
                        val files = LocalPhotoFiles(this@PhotoService)
                        for (i in 0 until jobs.length()) {
                            val p = jobs.getJSONObject(i)
                            files.replaceWhenReady(Photo(p.getString("id"), p.getString("name"), p.getString("input"), p.getLong("input_bytes"),
                                if (p.isNull("output")) null else p.getString("output"), if (p.isNull("output_bytes")) null else p.getLong("output_bytes"), p.getString("status"), null))
                        }
                    }
                    val preferences = getSharedPreferences("results", MODE_PRIVATE)
                    var fresh = 0
                    for (i in 0 until jobs.length()) {
                        val photo = jobs.getJSONObject(i)
                        if (photo.getString("status") == "ready" && !preferences.getBoolean(photo.getString("id"), false)) {
                            preferences.edit().putBoolean(photo.getString("id"), true).apply()
                            fresh++
                        }
                    }
                    if (fresh > 0 && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@PhotoService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                        manager.notify(11, notification("$fresh photo${if (fresh == 1) " is" else "s are"} finished · open to review replacement").setAutoCancel(true).build())
                    }
                    val pending = state.getInt("pending")
                    if (pending == 0 || state.getBoolean("paused")) break
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
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
