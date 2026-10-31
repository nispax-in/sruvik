package `in`.nispax.sruvik.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import `in`.nispax.sruvik.MainActivity
import `in`.nispax.sruvik.ai.RealEsrganUpscaler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal enum class UpscaleStatus { IDLE, RUNNING, COMPLETED, FAILED, CANCELLED }

internal data class UpscaleUiState(
    val status: UpscaleStatus = UpscaleStatus.IDLE,
    val sourceUri: String? = null,
    val scale: Int = 4,
    val progress: Float = 0f,
    val progressLabel: String = "Preparing on-device model…",
    val message: String = "Choose a photo to start enhancing it.",
    val resultPath: String? = null,
    val sourceWasOptimized: Boolean = false,
    val notificationsEnabled: Boolean = true
)

internal object UpscaleSession {
    private val mutableState = MutableStateFlow(UpscaleUiState())
    val state = mutableState.asStateFlow()

    fun selectSource(uri: String) {
        mutableState.value = UpscaleUiState(sourceUri = uri)
    }

    fun start(uri: String, scale: Int, notificationsEnabled: Boolean) {
        mutableState.value = UpscaleUiState(
            status = UpscaleStatus.RUNNING,
            sourceUri = uri,
            scale = scale,
            progressLabel = "Preparing on-device model…",
            message = "AI enhancement is running on this device.",
            notificationsEnabled = notificationsEnabled
        )
    }

    fun progress(done: Int, total: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(
            status = UpscaleStatus.RUNNING,
            progress = if (total > 0) done.toFloat() / total else 0f,
            progressLabel = "Reconstructing tile $done of $total",
            message = "Your photo is being enhanced locally."
        )
    }

    fun completed(path: String, sourceWasOptimized: Boolean) {
        mutableState.value = mutableState.value.copy(
            status = UpscaleStatus.COMPLETED,
            progress = 1f,
            progressLabel = "Complete",
            message = if (sourceWasOptimized) {
                "AI complete. Source resolution was reduced to stay within this phone's memory budget."
            } else {
                "AI enhancement complete. Your image stayed on this device."
            },
            resultPath = path,
            sourceWasOptimized = sourceWasOptimized
        )
    }

    fun failed(message: String) {
        mutableState.value = mutableState.value.copy(
            status = UpscaleStatus.FAILED,
            message = message,
            progressLabel = "Could not complete"
        )
    }

    fun cancelled() {
        mutableState.value = mutableState.value.copy(
            status = UpscaleStatus.CANCELLED,
            message = "Processing cancelled. No file was saved.",
            progressLabel = "Cancelled"
        )
    }

    fun clearResult() {
        val current = mutableState.value
        if (current.status != UpscaleStatus.RUNNING) {
            mutableState.value = current.copy(
                status = UpscaleStatus.IDLE,
                progress = 0f,
                resultPath = null,
                sourceWasOptimized = false,
                message = "Choose a photo to start enhancing it."
            )
        }
    }
}

/**
 * Owns image decode and AI inference independently of any Compose screen. Switching
 * tabs or placing the activity in Recents therefore cannot cancel the inference job.
 */
class ImageUpscaleService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var processingJob: Job? = null
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                processingJob?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (processingJob?.isActive == true) return START_NOT_STICKY

                val uriString = intent.getStringExtra(EXTRA_URI)
                val requestedScale = intent.getIntExtra(EXTRA_SCALE, 4)
                val notificationsEnabled = intent.getBooleanExtra(EXTRA_NOTIFICATIONS_ENABLED, true)
                if (uriString.isNullOrBlank() || requestedScale !in listOf(2, 4)) {
                    UpscaleSession.failed("The upscaling request was incomplete. Select the photo again.")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                val uri = Uri.parse(uriString)
                UpscaleSession.start(uriString, requestedScale, notificationsEnabled)
                startForegroundCompat(buildProgressNotification("Preparing your photo…", 0, 0))

                processingJob = serviceScope.launch {
                    runUpscale(uri, requestedScale, startId)
                }
                return START_REDELIVER_INTENT
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun runUpscale(uri: Uri, requestedScale: Int, startId: Int) {
        var source: Bitmap? = null
        var enhanced: Bitmap? = null
        try {
            UpscaleSession.progress(0, 0)
            source = withContext(Dispatchers.IO) { decodeImage(this@ImageUpscaleService, uri) }
            val processed = RealEsrganUpscaler.upscale(this, source, requestedScale) { done, total ->
                UpscaleSession.progress(done, total)
                val now = SystemClock.elapsedRealtime()
                if (done == total || now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                    lastNotificationUpdate = now
                    updateProgressNotification(done, total)
                }
            }
            enhanced = processed.bitmap

            val output = withContext(Dispatchers.IO) { persistResult(enhanced!!) }
            UpscaleSession.completed(output.absolutePath, processed.sourceWasOptimized)
            showFinishedNotification(
                "Your enhanced image is ready",
                if (processed.sourceWasOptimized) {
                    "Open Sruvik to review and save the device-safe result."
                } else {
                    "Open Sruvik to review and save your enhanced image."
                }
            )
        } catch (cancelled: CancellationException) {
            UpscaleSession.cancelled()
            showFinishedNotification("Upscaling cancelled", "No enhanced image was saved.")
            throw cancelled
        } catch (error: OutOfMemoryError) {
            UpscaleSession.failed("The phone ran out of memory. Try a smaller source image.")
            showFinishedNotification("Upscaling stopped", "The phone ran low on memory. Try a smaller image.")
        } catch (error: Exception) {
            val message = error.message ?: "AI enhancement couldn't start on this device."
            UpscaleSession.failed(message)
            showFinishedNotification("Upscaling couldn't finish", message)
        } finally {
            source?.recycle()
            enhanced?.recycle()
            processingJob = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
    }

    private fun persistResult(bitmap: Bitmap): File {
        val directory = File(filesDir, "upscale-results")
        check(directory.exists() || directory.mkdirs()) { "Couldn't prepare secure image storage." }
        val output = File(directory, "sruvik-upscaled-${System.currentTimeMillis()}.png")
        output.outputStream().buffered().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Couldn't write the enhanced image."
            }
            stream.flush()
        }
        directory.listFiles()
            ?.filter { it != output && System.currentTimeMillis() - it.lastModified() > RESULT_RETENTION_MS }
            ?.forEach { it.delete() }
        return output
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Image enhancement",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and completion for on-device AI image enhancement"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(text: String, done: Int, total: Int): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sruvik · Image enhancement")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)

        val cancelIntent = Intent(this, ImageUpscaleService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, REQUEST_CANCEL, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            Notification.Action.Builder(null, "Cancel", cancelPendingIntent).build()
        )
        return builder.build()
    }

    private fun updateProgressNotification(done: Int, total: Int) {
        val percent = if (total > 0) (done * 100 / total) else 0
        val notification = buildProgressNotification(
            if (total > 0) "Reconstructing image · $percent% ($done/$total tiles)" else "Preparing the AI model…",
            done,
            total
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun showFinishedNotification(title: String, text: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Sruvik · $title")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this, REQUEST_OPEN_APP, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        processingJob?.cancel()
        UpscaleSession.failed("Android stopped the media-processing session after its allowed time.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        processingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "in.nispax.sruvik.action.START_UPSCALE"
        const val ACTION_CANCEL = "in.nispax.sruvik.action.CANCEL_UPSCALE"
        const val EXTRA_URI = "image_uri"
        const val EXTRA_SCALE = "image_scale"
        const val EXTRA_NOTIFICATIONS_ENABLED = "notifications_enabled"

        private const val CHANNEL_ID = "image_enhancement"
        private const val NOTIFICATION_ID = 7402
        private const val REQUEST_CANCEL = 7403
        private const val REQUEST_OPEN_APP = 7404
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
        private const val RESULT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
