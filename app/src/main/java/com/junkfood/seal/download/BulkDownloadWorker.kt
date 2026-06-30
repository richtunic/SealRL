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
                    errorMessage = buildUserFriendlyErrorMessage(
                        item = it,
                        stage = ErrorStage.Queue,
                        throwable = e,
                    ),
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
                    .ifEmpty { com.junkfood.seal.util.BRAVE_CHROMIUM_USER_AGENT }
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
                    errorMessage = buildUserFriendlyErrorMessage(
                        item = current,
                        stage = ErrorStage.FetchInfo,
                        throwable = th,
                    ),
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
                    errorMessage = buildUserFriendlyErrorMessage(
                        item = current,
                        stage = ErrorStage.Download,
                        throwable = th,
                    ),
                    updatedAt = System.currentTimeMillis()
                ))
                return
            }

            val pathList = finalDownloadResult.getOrThrow()
            val downloadedPath = pathList.firstOrNull() ?: ""
            if (downloadedPath.isBlank()) {
                queueDao.update(current.copy(
                    status = QueueStatus.FAILED,
                    errorMessage = buildUserFriendlyErrorMessage(
                        item = current,
                        stage = ErrorStage.SaveFile,
                        throwable = null,
                    ),
                    updatedAt = System.currentTimeMillis()
                ))
                return
            }

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
                videoUrl = videoInfo.webpageUrl ?: current.url,
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

    private enum class ErrorStage(val label: String) {
        Queue("cola"),
        FetchInfo("lectura del enlace"),
        Download("descarga"),
        SaveFile("guardado"),
    }

    private fun buildUserFriendlyErrorMessage(
        item: QueueItemEntity,
        stage: ErrorStage,
        throwable: Throwable?,
    ): String {
        val rawMessage = throwable?.message.orEmpty()
        val normalized = rawMessage.lowercase()
        val httpCode = Regex("""(?i)\b(?:http\s*)?(?:error\s*)?([45]\d{2})\b""")
            .find(rawMessage)
            ?.groupValues
            ?.getOrNull(1)

        val mainMessage =
            when {
                stage == ErrorStage.SaveFile ->
                    "La descarga terminó, pero SeaRL no encontró el archivo guardado."

                normalized.contains("login") ||
                    normalized.contains("sign in") ||
                    normalized.contains("log in") ||
                    normalized.contains("authentication") ||
                    normalized.contains("authenticate") ||
                    normalized.contains("auth_token") ||
                    normalized.contains("cookies") ||
                    normalized.contains("private") ||
                    normalized.contains("not authorized") ||
                    normalized.contains("authorization") ||
                    normalized.contains("confirm your age") ||
                    normalized.contains("age-restricted") ->
                    "Este enlace parece requerir inicio de sesión o permisos de la cuenta."

                normalized.contains("unsupported url") ||
                    normalized.contains("no suitable extractor") ||
                    normalized.contains("not a valid url") ||
                    normalized.contains("invalid url") ||
                    normalized.contains("url could be a direct video link") ->
                    "SeaRL no reconoce este tipo de enlace."

                normalized.contains("video unavailable") ||
                    normalized.contains("not available") ||
                    normalized.contains("removed") ||
                    normalized.contains("deleted") ||
                    normalized.contains("does not exist") ||
                    normalized.contains("content isn't available") ||
                    normalized.contains("content is not available") ||
                    normalized.contains("this content is unavailable") ->
                    "El video no está disponible públicamente o fue eliminado."

                normalized.contains("no video formats") ||
                    normalized.contains("requested format is not available") ||
                    normalized.contains("format is not available") ||
                    normalized.contains("no media found") ||
                    normalized.contains("no downloadable media") ->
                    "No se encontró una versión descargable del video."

                normalized.contains("unable to extract") ||
                    normalized.contains("could not extract") ||
                    normalized.contains("failed to extract") ||
                    normalized.contains("did not match pattern") ||
                    normalized.contains("extractor failed") ->
                    "La plataforma cambió la forma en que entrega el video y SeaRL no pudo leerlo."

                httpCode == "401" || httpCode == "403" ->
                    "La plataforma rechazó el acceso al video."

                httpCode == "404" ->
                    "La plataforma indica que el enlace no existe o ya no está disponible."

                httpCode == "429" ||
                    httpCode == "503" ||
                    normalized.contains("too many requests") ||
                    normalized.contains("rate-limit") ||
                    normalized.contains("rate limit") ||
                    normalized.contains("temporarily unavailable") ->
                    "La plataforma bloqueó temporalmente demasiados intentos."

                normalized.contains("timeout") ||
                    normalized.contains("timed out") ||
                    normalized.contains("failed to connect") ||
                    normalized.contains("unable to download webpage") ||
                    normalized.contains("unable to download api page") ||
                    normalized.contains("no address associated") ||
                    normalized.contains("network is unreachable") ||
                    normalized.contains("connection reset") ||
                    normalized.contains("connection refused") ->
                    "No se pudo conectar con la plataforma."

                normalized.contains("unable to rename") ||
                    normalized.contains("unable to open for writing") ||
                    normalized.contains("file name too long") ->
                    "SeaRL no pudo crear el archivo de salida en el dispositivo."

                normalized.contains("no space") ||
                    normalized.contains("enospc") ->
                    "No hay espacio suficiente para guardar el archivo."

                normalized.contains("permission denied") ||
                    normalized.contains("eacces") ->
                    "SeaRL no tiene permiso para guardar o leer el archivo."

                item.platform.equals("Facebook", ignoreCase = true) ->
                    "Facebook no entregó un video descargable para este enlace."

                else ->
                    "No se pudo completar esta descarga."
            }

        val action =
            when {
                mainMessage.contains("inicio de sesión") ->
                    "Abre Ajustes > Iniciar sesión (WebView), inicia sesión en ${item.platform} y vuelve a intentar."

                mainMessage.contains("no reconoce") ->
                    "Copia el enlace directo del post/video y prueba de nuevo."

                mainMessage.contains("no está disponible") ||
                    mainMessage.contains("no existe") ->
                    "Verifica que el enlace abra en el navegador y que el contenido siga publicado."

                mainMessage.contains("versión descargable") ->
                    "Prueba desactivar 'Solo descargar audio' o intenta con otro enlace del mismo video."

                mainMessage.contains("cambió la forma") ->
                    "Esto normalmente requiere actualizar yt-dlp o reportar el enlace para revisar el extractor."

                mainMessage.contains("rechazó") ->
                    "Inicia sesión en WebView o prueba más tarde; puede ser contenido privado o limitado por la plataforma."

                mainMessage.contains("bloqueó temporalmente") ->
                    "Espera unos minutos antes de reintentar."

                mainMessage.contains("conectar") ->
                    "Revisa tu conexión y vuelve a intentarlo."

                mainMessage.contains("espacio") ->
                    "Libera espacio en el dispositivo y reintenta."

                mainMessage.contains("permiso") ->
                    "Revisa los permisos de almacenamiento de SeaRL."

                mainMessage.contains("archivo de salida") ->
                    "Prueba con un título más corto, otra carpeta de descarga o libera permisos de almacenamiento."

                item.platform.equals("Facebook", ignoreCase = true) ->
                    "Asegúrate de haber iniciado sesión en Facebook desde WebView y reporta este enlace si sigue fallando."

                else ->
                    "Intenta de nuevo. Si se repite, reporta el enlace."
            }

        val reportDetail = buildString {
            append("Reporte: ")
            append(item.platform)
            append(" / ")
            append(stage.label)
            httpCode?.let {
                append(" / HTTP ")
                append(it)
            }
            val compactRaw = rawMessage
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(90)
            if (!compactRaw.isNullOrEmpty()) {
                append(" / ")
                append(compactRaw)
            }
        }

        return "$mainMessage $action\n$reportDetail"
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
