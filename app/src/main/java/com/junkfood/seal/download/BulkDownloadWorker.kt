package com.junkfood.seal.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.junkfood.seal.MainActivity
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.database.objects.QueueItemEntity
import com.junkfood.seal.database.objects.QueueStatus
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yausername.youtubedl_android.YoutubeDL

class BulkDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val queueDao = com.junkfood.seal.database.BulkDatabaseUtil.queueDao
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val NOTIFICATION_ID = 23456
        private const val CHANNEL_ID = "download_notification"
    }

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        createNotificationChannelIfNeeded()

        var currentItem: QueueItemEntity? = null
        try {
            // Show initial foreground notification
            setForeground(getForegroundInfo("SeaRL", "Iniciando descargas...", 0))

            // Cache download history locally to prevent hammering SQLite with regex query compiles
            val historyCache = DatabaseUtil.getDownloadHistory().toMutableList()

            while (true) {
                // Check if queue is paused
                if ("bulk_queue_paused".getBoolean(false)) {
                    break
                }

                val nextItem = queueDao.getNextByStatus(QueueStatus.PENDING) ?: break
                currentItem = nextItem

                // Process item
                processItem(nextItem, historyCache)
                currentItem = null

                // Small delay to allow the main thread/UI to breathe, preventing recomposition-flood freezes (ANR)
                kotlinx.coroutines.delay(150)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Worker was canceled (e.g. queue canceled/paused by user)
                currentItem?.let {
                    val isPaused = "bulk_queue_paused".getBoolean(false)
                    val newStatus = if (isPaused) QueueStatus.PENDING else QueueStatus.CANCELED
                    queueDao.update(it.copy(
                        status = newStatus,
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            }
            return@withContext ListenableWorker.Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()
            currentItem?.let {
                queueDao.update(it.copy(
                    status = QueueStatus.FAILED,
                    errorMessage = e.message ?: "Ocurrió un error inesperado",
                    updatedAt = System.currentTimeMillis()
                ))
            }
            return@withContext ListenableWorker.Result.failure()
        } finally {
            notificationManager.cancel(NOTIFICATION_ID)
        }

        ListenableWorker.Result.success()
    }

    private suspend fun processItem(item: QueueItemEntity, historyCache: MutableList<DownloadedVideoInfo>) {
        // Mark as downloading
        var current = item.copy(
            status = QueueStatus.DOWNLOADING,
            progress = 0f,
            updatedAt = System.currentTimeMillis()
        )
        queueDao.update(current)

        // Launch a cancellation listener in App's non-cancellable application scope.
        // If the worker's coroutine job is cancelled, we immediately destroy the native process.
        val workerJob = kotlin.coroutines.coroutineContext[Job]
        val cancellationListener = com.junkfood.seal.App.applicationScope.launch {
            try {
                workerJob?.join()
            } finally {
                if (workerJob?.isCancelled == true) {
                    YoutubeDL.destroyProcessById(current.id.toString())
                }
            }
        }

        try {
            // Check if already downloaded
            val historyRecord = DatabaseUtil.isUrlAlreadyDownloaded(item.url, historyCache)
            if (historyRecord != null) {
                queueDao.update(current.copy(
                    status = QueueStatus.COMPLETED,
                    progress = 1f,
                    outputPath = historyRecord.videoPath,
                    completedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                ))
                return
            }

            var downloadPreferences = DownloadUtil.DownloadPreferences.createFromPreferences()
            if ("bulk_audio_only".getBoolean(false)) {
                downloadPreferences = downloadPreferences.copy(extractAudio = true)
            }
            val webviewUserAgent = com.junkfood.seal.util.PreferenceUtil.run {
                com.junkfood.seal.util.USER_AGENT_STRING.getString()
            }
            downloadPreferences = downloadPreferences.copy(
                cookies = true,
                userAgentString = webviewUserAgent
            )


            // 1. Fetch Video Info
            updateNotification(current, 0, "Obteniendo información...")
            val infoResult = DownloadUtil.fetchVideoInfoFromUrl(
                url = item.url,
                taskKey = current.id.toString(),
                preferences = downloadPreferences
            )

            if (infoResult.isFailure) {
                val th = infoResult.exceptionOrNull()
                queueDao.update(current.copy(
                    status = QueueStatus.FAILED,
                    errorMessage = th?.message ?: "Error al obtener info del video",
                    updatedAt = System.currentTimeMillis()
                ))
                return
            }

            val videoInfo = infoResult.getOrThrow()
            current = current.copy(
                title = videoInfo.title ?: current.title,
                updatedAt = System.currentTimeMillis()
            )
            queueDao.update(current)

            // 2. Start Download
            updateNotification(current, 0, "Descargando...")

            var lastProgressUpdate = 0L
            val progressCallback: (Float, Long, String) -> Unit = { progressPercentage, _, _ ->
                val progress = (progressPercentage / 100f).coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 500 || progressPercentage >= 100f) {
                    lastProgressUpdate = now
                    com.junkfood.seal.App.applicationScope.launch(Dispatchers.IO) {
                        queueDao.update(current.copy(progress = progress, updatedAt = now))
                    }
                    val progressInt = progressPercentage.toInt().coerceAtLeast(0)
                    updateNotification(current, progressInt, "Descargando ($progressInt%)")
                }
            }

            val finalDownloadResult = DownloadUtil.downloadVideo(
                videoInfo = videoInfo,
                taskId = current.id.toString(),
                downloadPreferences = downloadPreferences,
                progressCallback = progressCallback
            )

            if (finalDownloadResult.isFailure) {
                val th = finalDownloadResult.exceptionOrNull()
                queueDao.update(current.copy(
                    status = QueueStatus.FAILED,
                    errorMessage = th?.message ?: "Error al descargar el video",
                    updatedAt = System.currentTimeMillis()
                ))
                return
            }

            val pathList = finalDownloadResult.getOrThrow()
            val downloadedPath = pathList.firstOrNull() ?: ""

            // Mark as completed
            queueDao.update(current.copy(
                status = QueueStatus.COMPLETED,
                progress = 1f,
                outputPath = downloadedPath,
                completedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ))

            // Save to app's native download history
            val videoHistoryInfo = DownloadedVideoInfo(
                id = 0,
                videoTitle = videoInfo.title ?: current.title ?: current.url,
                videoAuthor = videoInfo.uploader ?: videoInfo.uploaderId ?: videoInfo.channel ?: "Autor desconocido",
                videoUrl = current.url,
                thumbnailUrl = videoInfo.thumbnail ?: "",
                videoPath = downloadedPath,
                extractor = videoInfo.extractor ?: videoInfo.extractorKey ?: "Unknown"
            )
            DatabaseUtil.insertInfo(videoHistoryInfo)
            historyCache.add(videoHistoryInfo)
        } finally {
            cancellationListener.cancel()
        }
    }


    private fun updateNotification(item: QueueItemEntity, progress: Int, text: String) {
        val title = item.title ?: item.url
        val info = getForegroundInfo("Descargando: $title", text, progress)
        notificationManager.notify(NOTIFICATION_ID, info.notification)
    }

    private fun getForegroundInfo(title: String, text: String, progress: Int): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            return ForegroundInfo(NOTIFICATION_ID, notification)
        }

    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Descargas SeaRL",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notificaciones para descargas masivas"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
}
