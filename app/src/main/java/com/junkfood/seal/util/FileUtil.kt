package com.junkfood.seal.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.CheckResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import java.io.File
import okhttp3.internal.closeQuietly

const val AUDIO_REGEX = "(mp3|aac|opus|m4a)$"
const val MEDIA_REGEX = "\\.(mp4|mkv|webm|mov|m4v|mp3|aac|opus|m4a|jpg|jpeg|png|webp)$"
const val THUMBNAIL_REGEX = "\\.(jpg|png)$"
const val SUBTITLE_REGEX = "\\.(lrc|vtt|srt|ass|json3|srv.|ttml)$"
private const val PRIVATE_DIRECTORY_SUFFIX = ".Seal"
private const val RECENT_DOWNLOAD_WINDOW_MS = 10 * 60 * 1000L

object FileUtil {
    fun openFileFromResult(downloadResult: Result<List<String>>) {
        val filePaths = downloadResult.getOrNull()
        if (filePaths.isNullOrEmpty()) return
        openFile(filePaths.first()) {
            ToastUtil.makeToastSuspend(context.getString(R.string.file_unavailable))
        }
    }

    inline fun openFile(path: String, onFailureCallback: (Throwable) -> Unit) =
        path
            .runCatching {
                createIntentForOpeningFile(this)?.run { context.startActivity(this) }
                    ?: throw Exception()
            }
            .onFailure { onFailureCallback(it) }

    private fun createIntentForFile(path: String?): Intent? {
        if (path == null) return null

        val uri = resolveUriForPath(path) ?: return null
        val mimeType = context.contentResolver.getType(uri) ?: getMimeType(path)

        return Intent().apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            setDataAndType(uri, mimeType)
        }
    }

    private fun resolveUriForPath(path: String): Uri? =
        runCatching {
                val parsedUri = Uri.parse(path)
                if (parsedUri.scheme == "content") {
                    DocumentFile.fromSingleUri(context, parsedUri)?.takeIf { it.exists() }?.uri
                        ?: parsedUri
                } else {
                    val file = File(path)
                    val existingFile =
                        if (file.exists()) {
                            file
                        } else {
                            findDownloadedFileByName(file.name)
                        }
                    existingFile?.let {
                        FileProvider.getUriForFile(context, context.getFileProvider(), it)
                    }
                }
            }
            .getOrNull()

    private fun findDownloadedFileByName(fileName: String): File? {
        if (fileName.isBlank()) return null
        return getExternalDownloadDirectory()
            .walkTopDown()
            .firstOrNull { it.isFile && it.name == fileName }
    }

    private fun getMimeType(path: String): String {
        val extension = path.substringBefore("#").substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "media/*"
    }

    fun createIntentForOpeningFile(path: String?): Intent? =
        createIntentForFile(path)?.let {
            it.apply {
                action = (Intent.ACTION_VIEW)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    fun createIntentForSharingFile(path: String?): Intent? =
        createIntentForFile(path)?.apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, data)
            val mimeType = type ?: data?.let { context.contentResolver.getType(it) } ?: "media/*"
            setDataAndType(this.data, mimeType)
            clipData = ClipData(null, arrayOf(mimeType), ClipData.Item(data))
        }

    fun Context.getFileProvider() = "$packageName.provider"

    fun String.getFileSize(): Long =
        this.run {
            val length = File(this).length()
            if (length == 0L) DocumentFile.fromSingleUri(context, Uri.parse(this))?.length() ?: 0L
            else length
        }

    fun String.getFileName(): String =
        this.run {
            File(this).nameWithoutExtension.ifEmpty {
                DocumentFile.fromSingleUri(context, Uri.parse(this))?.name ?: "video"
            }
        }

    fun deleteFile(path: String) =
        path.runCatching {
            if (!File(path).delete()) DocumentFile.fromSingleUri(context, Uri.parse(this))?.delete()
        }

    @CheckResult
    fun scanFileToMediaLibraryPostDownload(title: String, downloadDir: String): List<String> {
        val dir = File(downloadDir)
        val titleMatches =
            dir
                .walkTopDown()
                .filter { it.isFile && title.isNotBlank() && it.absolutePath.contains(title) }
                .toList()

        val now = System.currentTimeMillis()
        val files =
            titleMatches.ifEmpty {
                dir
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            it.name.contains(Regex(MEDIA_REGEX, RegexOption.IGNORE_CASE)) &&
                            now - it.lastModified() <= RECENT_DOWNLOAD_WINDOW_MS
                    }
                    .sortedByDescending { it.lastModified() }
                    .toList()
            }

        return files
            .onEach { file ->
                try {
                    file.setLastModified(System.currentTimeMillis())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .map { it.absolutePath }
            .toMutableList()
            .apply {
                MediaScannerConnection.scanFile(context, this.toList().toTypedArray(), null, null)
                removeAll {
                    it.contains(Regex(THUMBNAIL_REGEX, RegexOption.IGNORE_CASE)) ||
                        it.contains(Regex(SUBTITLE_REGEX, RegexOption.IGNORE_CASE))
                }
            }
    }

    fun scanDownloadDirectoryToMediaLibrary(downloadDir: String) =
        File(downloadDir)
            .walkTopDown()
            .filter { it.isFile }
            .map { it.absolutePath }
            .run {
                MediaScannerConnection.scanFile(context, this.toList().toTypedArray(), null, null)
            }

    @CheckResult
    fun moveFilesToSdcard(tempPath: File, sdcardUri: String): Result<List<String>> {
        val uriList = mutableListOf<String>()
        val destDir =
            Uri.parse(sdcardUri).run {
                DocumentsContract.buildDocumentUriUsingTree(
                    this,
                    DocumentsContract.getTreeDocumentId(this),
                )
            }
        val res =
            tempPath.runCatching {
                walkTopDown().forEach {
                    if (it.isDirectory) return@forEach
                    val mimeType =
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "*/*"

                    val destUri =
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            destDir,
                            mimeType,
                            it.name,
                        ) ?: return@forEach

                    val inputStream = it.inputStream()
                    val outputStream =
                        context.contentResolver.openOutputStream(destUri) ?: return@forEach
                    inputStream.copyTo(outputStream)
                    inputStream.closeQuietly()
                    outputStream.closeQuietly()
                    uriList.add(destUri.toString())
                }
                uriList
            }
        tempPath.deleteRecursively()
        return res
    }

    fun clearTempFiles(downloadDir: File): Int {
        var count = 0
        downloadDir.walkTopDown().forEach {
            if (it.isFile && !it.isHidden) {
                if (it.delete()) count++
            }
        }
        return count
    }

    fun Context.getConfigDirectory(): File = cacheDir

    fun Context.getConfigFile(suffix: String = "") = File(getConfigDirectory(), "config$suffix.txt")

    fun Context.getCookiesFile() = File(getConfigDirectory(), "cookies.txt")

    fun getExternalTempDir() =
        File(getExternalDownloadDirectory(), "tmp").apply {
            mkdirs()
            createEmptyFile(".nomedia")
        }

    fun Context.getSdcardTempDir(child: String?): File =
        getExternalTempDir().run { child?.let { resolve(it) } ?: this }

    fun Context.getArchiveFile(): File = filesDir.createEmptyFile("archive.txt").getOrThrow()

    fun Context.getInternalTempDir() = File(filesDir, "tmp")

    internal fun getExternalDownloadDirectory() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Seal")
            .also { it.mkdir() }

    internal fun getExternalPrivateDownloadDirectory() =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PRIVATE_DIRECTORY_SUFFIX,
        )

    fun File.createEmptyFile(fileName: String): Result<File> =
        this.runCatching {
                mkdirs()
                resolve(fileName).apply { this@apply.createNewFile() }
            }
            .onFailure { it.printStackTrace() }

    fun writeContentToFile(content: String, file: File): File = file.apply { writeText(content) }

    fun getRealPath(treeUri: Uri): String {
        val path: String = treeUri.path.toString()
        Log.d(TAG, path)
        if (!path.contains("primary:")) {
            ToastUtil.makeToast("This directory is not supported")
            return getExternalDownloadDirectory().absolutePath
        }
        val last: String = path.split("primary:").last()
        return Environment.getExternalStorageDirectory().absolutePath + "/$last"
    }

    private const val TAG = "FileUtil"
}
