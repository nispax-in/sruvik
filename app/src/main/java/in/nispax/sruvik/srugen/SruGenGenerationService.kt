package `in`.nispax.sruvik.srugen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import `in`.nispax.sruvik.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground owner for a user-started, on-device SruGen diffusion job.
 * The actual inference/session stays in SruGenGenerationSession; this service
 * makes its progress visible outside the Activity and provides a notification
 * action that asks the model to stop at its next safe checkpoint.
 */
class SruGenGenerationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SruGenGenerationSession.cancel()
                val state = SruGenGenerationSession.state.value
                notificationManager().notify(
                    NOTIFICATION_ID,
                    buildProgressNotification(state.copy(progressLabel = "Stopping at a safe checkpoint…"))
                )
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundCompat(
                    buildProgressNotification(
                        SruGenGenerationState(
                            status = SruGenGenerationStatus.RUNNING,
                            progressLabel = "Preparing local image generation…"
                        )
                    )
                )
                val request = takePendingRequest()
                if (request != null) {
                    val started = SruGenGenerationSession.start(
                        applicationContext,
                        request.chat,
                        request.generationPrompt,
                        request.modelId,
                        request.mode,
                        request.sourceAttachmentId
                    )
                    if (!started && SruGenGenerationSession.state.value.status != SruGenGenerationStatus.RUNNING) {
                        finishNotification(
                            SruGenGenerationStatus.FAILED,
                            "Could not start image generation",
                            "Another generation could not be started."
                        )
                        return START_NOT_STICKY
                    }
                }
                observeSession()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun observeSession() {
        observationJob?.cancel()
        observationJob = serviceScope.launch {
            SruGenGenerationSession.state.collect { state ->
                if (state.status == SruGenGenerationStatus.RUNNING) {
                    notificationManager().notify(NOTIFICATION_ID, buildProgressNotification(state))
                } else if (state.status in setOf(
                        SruGenGenerationStatus.COMPLETED,
                        SruGenGenerationStatus.CANCELLED,
                        SruGenGenerationStatus.FAILED
                    )
                ) {
                    finishNotification(state.status, titleFor(state.status), state.message)
                    observationJob?.cancel()
                }
            }
        }
    }

    private fun titleFor(status: SruGenGenerationStatus): String = when (status) {
        SruGenGenerationStatus.COMPLETED -> "Your image is ready"
        SruGenGenerationStatus.CANCELLED -> "Generation stopped"
        SruGenGenerationStatus.FAILED -> "Generation couldn't finish"
        else -> "Image generation"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SruGen image generation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and stop control for on-device SruGen image generation"
                setShowBadge(false)
            }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(state: SruGenGenerationState): Notification {
        val percent = state.progress.coerceIn(0, 100)
        val stopIntent = Intent(this, SruGenGenerationService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sruvik · SruGen")
            .setContentText(
                if (percent > 0) "${state.progressLabel} · $percent%"
                else state.progressLabel.ifBlank { "Preparing on-device model…" }
            )
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .setProgress(100, percent, percent == 0)
            .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
            .build()
    }

    private fun finishNotification(
        status: SruGenGenerationStatus,
        title: String,
        detail: String
    ) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val text = when (status) {
            SruGenGenerationStatus.COMPLETED ->
                "Saved in the encrypted SruGen chat. Use the image card to save to device or Srukrypt."
            SruGenGenerationStatus.CANCELLED ->
                "Stopped at a safe checkpoint. Your prompt remains in the encrypted conversation."
            else -> detail.ifBlank { "Open Sruvik to review the result." }
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Sruvik · $title")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        notificationManager().notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openAppPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        observationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class PendingRequest(
        val chat: SruGenChat,
        val generationPrompt: String,
        val modelId: String,
        val mode: SruGenMode,
        val sourceAttachmentId: String?
    )

    companion object {
        const val ACTION_START = "in.nispax.sruvik.action.START_SRUGEN"
        const val ACTION_STOP = "in.nispax.sruvik.action.STOP_SRUGEN"

        private const val CHANNEL_ID = "srugen_generation"
        private const val NOTIFICATION_ID = 7412
        private const val REQUEST_STOP = 7413
        private const val REQUEST_OPEN_APP = 7414

        @Volatile private var pendingRequest: PendingRequest? = null

        @Synchronized
        private fun takePendingRequest(): PendingRequest? {
            val request = pendingRequest
            pendingRequest = null
            return request
        }

        @Synchronized
        fun start(
            context: Context,
            chat: SruGenChat,
            generationPrompt: String,
            modelId: String,
            mode: SruGenMode = SruGenMode.TEXT_TO_IMAGE,
            sourceAttachmentId: String? = null
        ) {
            check(
                SruGenGenerationSession.state.value.status != SruGenGenerationStatus.RUNNING &&
                    pendingRequest == null
            ) {
                "Another image is already being generated."
            }
            pendingRequest = PendingRequest(chat, generationPrompt, modelId, mode, sourceAttachmentId)
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context, SruGenGenerationService::class.java).setAction(ACTION_START)
                )
            } catch (failure: Exception) {
                pendingRequest = null
                throw failure
            }
        }

        fun stop(context: Context) {
            if (SruGenGenerationSession.cancel()) return
            runCatching {
                context.startService(
                    Intent(context, SruGenGenerationService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
