package com.junkfood.seal.ui.page.bulk

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.junkfood.seal.database.objects.QueueItemEntity
import com.junkfood.seal.database.objects.QueueStatus
import com.junkfood.seal.download.BulkDownloadWorker
import com.junkfood.seal.util.BulkUrlParser
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.junkfood.seal.util.InstagramMediaItem
import com.junkfood.seal.util.DownloadUtil

class BulkDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _mediaSelectionList = MutableStateFlow<List<InstagramMediaItem>?>(null)
    val mediaSelectionList = _mediaSelectionList.asStateFlow()

    private val _isLoadingMedia = MutableStateFlow(false)
    val isLoadingMedia = _isLoadingMedia.asStateFlow()

    private val queueDao = com.junkfood.seal.database.BulkDatabaseUtil.queueDao
    private val workManager = WorkManager.getInstance(application)

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    val queueItems: StateFlow<List<QueueItemEntity>> = queueDao.observeQueue()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val metrics: StateFlow<Metrics> = queueItems.map { items ->
        Metrics(
            total = items.size,
            pending = items.count { it.status == QueueStatus.PENDING },
            downloading = items.count { it.status == QueueStatus.DOWNLOADING },
            completed = items.count { it.status == QueueStatus.COMPLETED },
            failed = items.count { it.status == QueueStatus.FAILED },
            canceled = items.count { it.status == QueueStatus.CANCELED },
            paused = items.count { it.status == QueueStatus.PAUSED }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = Metrics()
    )

    private val urlRegex = Regex("""https?://[^\s\n]+""")

    private fun formatUrls(text: String): String {
        val matches = urlRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val sb = StringBuilder()
        var lastIndex = 0
        for (match in matches) {
            // Append everything before the URL
            sb.append(text.substring(lastIndex, match.range.first))
            
            // Append the URL itself
            val url = match.value
            sb.append(url)
            
            // Check what follows the URL
            val nextIndex = match.range.last + 1
            if (nextIndex < text.length) {
                val nextChar = text[nextIndex]
                if (nextChar == '\n') {
                    sb.append('\n')
                    lastIndex = nextIndex + 1
                } else if (nextChar.isWhitespace()) {
                    sb.append('\n')
                    lastIndex = nextIndex + 1
                } else {
                    sb.append('\n')
                    lastIndex = nextIndex
                }
            } else {
                sb.append('\n')
                lastIndex = nextIndex
            }
        }
        if (lastIndex < text.length) {
            sb.append(text.substring(lastIndex))
        }
        return sb.toString()
    }

    private fun insertNewlinesBeforeUrls(text: String): String {
        val regex = Regex("""https?://""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val sb = StringBuilder()
        var lastIndex = 0
        for (match in matches) {
            val idx = match.range.first
            if (idx == 0) {
                sb.append(text.substring(lastIndex, match.range.last + 1))
                lastIndex = match.range.last + 1
                continue
            }

            val charBefore = text[idx - 1]
            if (charBefore == '\n') {
                sb.append(text.substring(lastIndex, idx))
            } else if (charBefore.isWhitespace()) {
                sb.append(text.substring(lastIndex, idx - 1))
                sb.append('\n')
            } else {
                sb.append(text.substring(lastIndex, idx))
                sb.append('\n')
            }
            sb.append(match.value)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            sb.append(text.substring(lastIndex))
        }
        return sb.toString()
    }

    fun onInputTextChange(text: String) {
        val oldText = _inputText.value
        val lengthDiff = text.length - oldText.length
        val shouldFormat = when {
            lengthDiff > 1 -> true
            lengthDiff == 1 -> {
                // Find the character that was added.
                var addedChar: Char? = null
                var i = 0
                while (i < oldText.length && i < text.length) {
                    if (oldText[i] != text[i]) {
                        addedChar = text[i]
                        break
                    }
                    i++
                }
                if (addedChar == null && text.length > oldText.length) {
                    addedChar = text.last()
                }
                addedChar?.isWhitespace() == true
            }
            else -> false
        }

        if (shouldFormat) {
            val preprocessed = insertNewlinesBeforeUrls(text)
            _inputText.value = formatUrls(preprocessed)
        } else {
            _inputText.value = text
        }
    }

    private val _audioOnly = MutableStateFlow("bulk_audio_only".getBoolean(false))
    val audioOnly = _audioOnly.asStateFlow()

    fun toggleAudioOnly(enabled: Boolean) {
        viewModelScope.launch {
            "bulk_audio_only".updateBoolean(enabled)
            _audioOnly.value = enabled
        }
    }




    fun parseAndAddToQueue() {
        val text = _inputText.value
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val parsedUrls = BulkUrlParser.parse(text)
            if (parsedUrls.isEmpty()) return@launch

            val history = com.junkfood.seal.util.DatabaseUtil.getDownloadHistory()
            val itemsToEnqueue = mutableListOf<QueueItemEntity>()
            val mediaItemsToSelect = mutableListOf<InstagramMediaItem>()
            var loginRequired = false
            var totalInstagramFound = 0

            val instagramUrls = parsedUrls.filter { it.contains("instagram.com", ignoreCase = true) }
            if (instagramUrls.isNotEmpty()) {
                _isLoadingMedia.value = true
            }

            for (url in parsedUrls) {
                if (url.contains("instagram.com", ignoreCase = true)) {
                    totalInstagramFound++
                    try {
                        val mediaList = DownloadUtil.fetchInstagramMediaList(url)
                        if (mediaList != null) {
                            val nonDownloaded = mediaList.filter { !com.junkfood.seal.util.DatabaseUtil.isInstagramItemDownloaded(it.id, history) }
                            if (nonDownloaded.size > 1) {
                                mediaItemsToSelect.addAll(nonDownloaded)
                            } else if (nonDownloaded.size == 1) {
                                val item = nonDownloaded.first()
                                val displayTitle = if (!item.author.isNullOrEmpty()) "${item.author} · ${item.title}" else item.title
                                itemsToEnqueue.add(QueueItemEntity(
                                    url = item.mediaUrl,
                                    normalizedUrl = item.mediaUrl,
                                    platform = if (item.title.contains("Story", ignoreCase = true)) "Story" else "Instagram",
                                    title = displayTitle,
                                    progress = 0f,
                                    status = QueueStatus.PENDING,
                                    errorMessage = null,
                                    outputPath = null
                                ))
                            } else if (mediaList.isNotEmpty()) {
                                // All items in this Instagram URL were already downloaded
                            } else {
                                // Empty list returned, fallback to raw url
                                if (com.junkfood.seal.util.DatabaseUtil.isUrlAlreadyDownloaded(url, history) == null) {
                                    val platform = if (url.contains("instagram.com/stories", ignoreCase = true)) "Story" else "Instagram"
                                    itemsToEnqueue.add(QueueItemEntity(
                                        url = url,
                                        normalizedUrl = url,
                                        platform = platform,
                                        title = null,
                                        progress = 0f,
                                        status = QueueStatus.PENDING,
                                        errorMessage = null,
                                        outputPath = null
                                    ))
                                }
                            }
                        } else {
                            // Fetch returned null, fallback to raw url if not already downloaded
                            if (com.junkfood.seal.util.DatabaseUtil.isUrlAlreadyDownloaded(url, history) == null) {
                                val platform = if (url.contains("instagram.com/stories", ignoreCase = true)) "Story" else "Instagram"
                                itemsToEnqueue.add(QueueItemEntity(
                                    url = url,
                                    normalizedUrl = url,
                                    platform = platform,
                                    title = null,
                                    progress = 0f,
                                    status = QueueStatus.PENDING,
                                    errorMessage = null,
                                    outputPath = null
                                ))
                            }
                        }
                    } catch (e: com.junkfood.seal.util.InstagramLoginRequiredException) {
                        loginRequired = true
                        if (com.junkfood.seal.util.DatabaseUtil.isUrlAlreadyDownloaded(url, history) == null) {
                            val platform = if (url.contains("instagram.com/stories", ignoreCase = true)) "Story" else "Instagram"
                            itemsToEnqueue.add(QueueItemEntity(
                                url = url,
                                normalizedUrl = url,
                                platform = platform,
                                title = null,
                                progress = 0f,
                                status = QueueStatus.PENDING,
                                errorMessage = null,
                                outputPath = null
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e("BulkDownloadViewModel", "fetchInstagramMediaList failed for $url: ${e.message}", e)
                        if (com.junkfood.seal.util.DatabaseUtil.isUrlAlreadyDownloaded(url, history) == null) {
                            val platform = if (url.contains("instagram.com/stories", ignoreCase = true)) "Story" else "Instagram"
                            itemsToEnqueue.add(QueueItemEntity(
                                url = url,
                                normalizedUrl = url,
                                platform = platform,
                                title = null,
                                progress = 0f,
                                status = QueueStatus.PENDING,
                                errorMessage = null,
                                outputPath = null
                            ))
                        }
                    }
                } else {
                    if (com.junkfood.seal.util.DatabaseUtil.isUrlAlreadyDownloaded(url, history) == null) {
                        val platform = BulkUrlParser.getPlatformName(url)
                        itemsToEnqueue.add(QueueItemEntity(
                            url = url,
                            normalizedUrl = url,
                            platform = platform,
                            title = null,
                            progress = 0f,
                            status = QueueStatus.PENDING,
                            errorMessage = null,
                            outputPath = null
                        ))
                    }
                }
            }

            _isLoadingMedia.value = false

            if (loginRequired) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        com.junkfood.seal.App.context,
                        "Inicio de sesión en Instagram requerido para obtener de forma directa. Ve a Ajustes -> Iniciar sesión (WebView)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            if (totalInstagramFound > 0 && mediaItemsToSelect.isEmpty() && itemsToEnqueue.none { it.platform == "Instagram" || it.platform == "Story" }) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        com.junkfood.seal.App.context,
                        "El contenido de Instagram ya ha sido descargado",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

            if (mediaItemsToSelect.isNotEmpty()) {
                _mediaSelectionList.value = mediaItemsToSelect
            }

            if (itemsToEnqueue.isNotEmpty()) {
                queueDao.insertAll(itemsToEnqueue)
                triggerDownloadWorker()
            }

            withContext(Dispatchers.Main) {
                _inputText.value = ""
            }
        }
    }

    fun enqueueSelectedItems(selectedItems: List<InstagramMediaItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val newItems = selectedItems.map { item ->
                val displayTitle = if (!item.author.isNullOrEmpty()) "${item.author} · ${item.title}" else item.title
                QueueItemEntity(
                    url = item.mediaUrl,
                    normalizedUrl = item.mediaUrl,
                    platform = if (item.title.contains("Story", ignoreCase = true)) "Story" else "Instagram",
                    title = displayTitle,
                    progress = 0f,
                    status = QueueStatus.PENDING,
                    errorMessage = null,
                    outputPath = null
                )
            }
            queueDao.insertAll(newItems)
            _mediaSelectionList.value = null
            triggerDownloadWorker()
        }
    }

    fun cancelMediaSelection() {
        _mediaSelectionList.value = null
    }

    fun triggerDownloadWorker() {
        val workRequest = OneTimeWorkRequestBuilder<BulkDownloadWorker>()
            .addTag("BulkDownloadWork")
            .build()
        workManager.enqueueUniqueWork(
            "BulkDownloadWork",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    fun pauseQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            "bulk_queue_paused".updateBoolean(true)
            // Cancel current work request to stop worker loop
            workManager.cancelUniqueWork("BulkDownloadWork")
            // Mark downloading items as PENDING or PAUSED
            val items = queueItems.value
            items.forEach {
                if (it.status == QueueStatus.DOWNLOADING) {
                    queueDao.update(it.copy(status = QueueStatus.PENDING, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun resumeQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            "bulk_queue_paused".updateBoolean(false)
            triggerDownloadWorker()
        }
    }

    fun cancelQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            workManager.cancelUniqueWork("BulkDownloadWork")
            val items = queueItems.value
            items.forEach {
                if (it.status == QueueStatus.PENDING || it.status == QueueStatus.DOWNLOADING) {
                    queueDao.update(it.copy(status = QueueStatus.CANCELED, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    fun deleteCompleted() {
        viewModelScope.launch(Dispatchers.IO) {
            queueDao.deleteByStatus(QueueStatus.COMPLETED)
        }
    }

    fun retryFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = queueItems.value
            items.forEach {
                if (it.status == QueueStatus.FAILED || it.status == QueueStatus.CANCELED) {
                    queueDao.update(it.copy(status = QueueStatus.PENDING, progress = 0f, errorMessage = null, updatedAt = System.currentTimeMillis()))
                }
            }
            triggerDownloadWorker()
        }
    }

    fun clearQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            workManager.cancelUniqueWork("BulkDownloadWork")
            queueDao.clearQueue()
        }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            queueDao.deleteById(id)
        }
    }

    fun retryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            queueDao.getById(id)?.let {
                queueDao.update(it.copy(status = QueueStatus.PENDING, progress = 0f, errorMessage = null, updatedAt = System.currentTimeMillis()))
            }
            triggerDownloadWorker()
        }
    }
}

data class Metrics(
    val total: Int = 0,
    val pending: Int = 0,
    val downloading: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val canceled: Int = 0,
    val paused: Int = 0
)
