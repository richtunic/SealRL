package com.junkfood.seal.util

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.Downloader
import com.junkfood.seal.Downloader.onProcessEnded
import com.junkfood.seal.Downloader.onProcessStarted
import com.junkfood.seal.Downloader.onTaskEnded
import com.junkfood.seal.Downloader.onTaskError
import com.junkfood.seal.Downloader.onTaskStarted
import com.junkfood.seal.Downloader.toNotificationId
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.junkfood.seal.util.FileUtil.getFileName
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.junkfood.seal.util.FileUtil.moveFilesToSdcard
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

object DownloadUtil {

    object CookieScheme {
        const val NAME = "name"
        const val VALUE = "value"
        const val SECURE = "is_secure"
        const val EXPIRY = "expires_utc"
        const val HOST = "host_key"
        const val PATH = "path"
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private const val TAG = "DownloadUtil"

    const val BASENAME = "%(title).200B"

    const val EXTENSION = ".%(ext)s"

    private const val ID = "[%(id)s]"

    private const val CLIP_TIMESTAMP = "%(section_start)d-%(section_end)d"

    const val OUTPUT_TEMPLATE_DEFAULT = BASENAME + EXTENSION

    const val OUTPUT_TEMPLATE_ID = "$BASENAME $ID$EXTENSION"

    private const val OUTPUT_TEMPLATE_CLIPS = "$BASENAME [$CLIP_TIMESTAMP]$EXTENSION"

    private const val OUTPUT_TEMPLATE_CHAPTERS =
        "chapter:$BASENAME/%(section_number)d - %(section_title).200B$EXTENSION"

    private const val OUTPUT_TEMPLATE_SPLIT = "$BASENAME/$OUTPUT_TEMPLATE_DEFAULT"

    private const val PLAYLIST_TITLE_SUBDIRECTORY_PREFIX = "%(playlist)s/"

    private const val CROP_ARTWORK_COMMAND =
        """--ppa "ffmpeg: -c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"""""

    @CheckResult
    fun getPlaylistOrVideoInfo(
        playlistURL: String,
        downloadPreferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Result<YoutubeDLInfo> =
        YoutubeDL.runCatching {
            ToastUtil.makeToastSuspend(context.getString(R.string.fetching_playlist_info))
            val request = YoutubeDLRequest(playlistURL)
            with(request) {
                //            addOption("--compat-options", "no-youtube-unavailable-videos")
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("-o", BASENAME)
                addOption("-R", "1")
                addOption("--socket-timeout", "5")
                downloadPreferences.run {
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this, toFormatSorter())
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                }
            }
            execute(request, playlistURL).out.run {
                val playlistInfo = jsonFormat.decodeFromString<PlaylistResult>(this)
                if (playlistInfo.type != "playlist") {
                    jsonFormat.decodeFromString<VideoInfo>(this)
                } else playlistInfo
            }
        }

    @CheckResult
    private fun getVideoInfo(
        request: YoutubeDLRequest,
        taskKey: String? = null,
    ): Result<VideoInfo> =
        runCatching {
            val response: YoutubeDLResponse =
                YoutubeDL.getInstance().execute(request, taskKey, null)
            jsonFormat.decodeFromString<VideoInfo>(response.out)
        }.onFailure { th ->
            Log.e(TAG, "getVideoInfo failed", th)
        }

    @CheckResult
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistIndex: Int? = null,
        taskKey: String? = null,
        preferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ): Result<VideoInfo> {
        val isUnsupported = url.contains("tiktok.com", ignoreCase = true) ||
                url.contains("douyin.com", ignoreCase = true) ||
                url.contains("facebook.com", ignoreCase = true) ||
                url.contains("fb.watch", ignoreCase = true) ||
                url.contains("fb.gg", ignoreCase = true)
        if (isUnsupported) {
            return Result.failure(Exception("Plataforma no soportada"))
        }

        val isDirectCdnUrl = url.contains("cdninstagram.com", ignoreCase = true) ||
                url.contains("fbcdn.net", ignoreCase = true) ||
                (url.contains("instagram.f", ignoreCase = true) && url.contains(".fna.fbcdn.net", ignoreCase = true))

        if (isDirectCdnUrl) {
            val ext = when {
                url.contains(".mp4", ignoreCase = true) -> "mp4"
                url.contains(".webm", ignoreCase = true) -> "webm"
                url.contains(".jpeg", ignoreCase = true) ||
                url.contains(".jpg", ignoreCase = true) -> "jpg"
                url.contains(".png", ignoreCase = true) -> "png"
                url.contains(".webp", ignoreCase = true) -> "webp"
                else -> "mp4"
            }
            var title = "Instagram Media"
            var thumbnail = url
            var webpage = url
            var author = "Autor desconocido"
            
            if (url.contains("#")) {
                try {
                    val fragment = url.substringAfter("#", "")
                    val params = fragment.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) {
                            parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
                        } else {
                            parts[0] to ""
                        }
                    }
                    params["ig_title"]?.let { if (it.isNotEmpty()) title = it }
                    params["ig_thumb"]?.let { if (it.isNotEmpty()) thumbnail = it }
                    params["ig_webpage"]?.let { if (it.isNotEmpty()) webpage = it }
                    params["ig_author"]?.let { if (it.isNotEmpty()) author = it }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            title = title.withInstagramAuthor(author)

            val syntheticInfo = VideoInfo(
                id = "instagram_${url.hashCode()}",
                title = title,
                ext = ext,
                webpageUrl = webpage,
                originalUrl = url,
                thumbnail = thumbnail,
                uploader = author,
                extractor = "instagram",
                extractorKey = "Instagram",
            )
            return Result.success(syntheticInfo)
        }

        val (resolvedUrl, thumbnailUrl) = resolveThreadsUrl(url)

        val isResolvedToCdnUrl = resolvedUrl != url && (
            resolvedUrl.contains("cdninstagram.com") ||
            resolvedUrl.contains("fbcdn.net") ||
            resolvedUrl.contains("instagram.f") && resolvedUrl.contains(".fna.fbcdn.net")
        )
        if (isResolvedToCdnUrl) {
            val ext = when {
                resolvedUrl.contains(".mp4", ignoreCase = true) -> "mp4"
                resolvedUrl.contains(".webm", ignoreCase = true) -> "webm"
                resolvedUrl.contains(".jpeg", ignoreCase = true) ||
                resolvedUrl.contains(".jpg", ignoreCase = true) -> "jpg"
                resolvedUrl.contains(".png", ignoreCase = true) -> "png"
                resolvedUrl.contains(".webp", ignoreCase = true) -> "webp"
                else -> "mp4"
            }
            val syntheticInfo = VideoInfo(
                id = "threads_${url.hashCode()}",
                title = "Threads post",
                ext = ext,
                webpageUrl = url,
                originalUrl = resolvedUrl,
                thumbnail = thumbnailUrl,
                extractor = "threads",
                extractorKey = "Threads",
            )
            return Result.success(syntheticInfo)
        }

        if (preferences.cookies && resolvedUrl.isTwitterUrl() && !hasTwitterAuthCookies()) {
            return Result.failure(
                Exception(
                    "X/Twitter login cookies are missing auth_token and ct0. Open Cookies > X / Twitter and complete login, or import a cookies.txt with those cookies."
                )
            )
        }

        with(preferences) {
            val request =
                YoutubeDLRequest(resolvedUrl).apply {
                    addOption("-o", BASENAME)
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this@with, toFormatSorter())
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    /*            if (debug) {
                        addOption("-v")
                    }*/
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    if (playlistIndex != null) {
                        addOption("--playlist-items", playlistIndex)
                        addOption("--dump-json")
                    } else {
                        addOption("--dump-single-json")
                    }
                    addOption("-R", "1")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "5")
                }
            val result = getVideoInfo(request, taskKey)
            if (result.isFailure && cookies && (
                resolvedUrl.contains("tiktok.com") ||
                resolvedUrl.contains("facebook.com") ||
                resolvedUrl.contains("fb.watch") ||
                resolvedUrl.contains("fb.gg")
            )) {
                Log.d(TAG, "fetchVideoInfoFromUrl: FAILED with cookies for TikTok/Facebook, retrying WITHOUT cookies...")
                val fallbackRequest = YoutubeDLRequest(resolvedUrl).apply {
                    addOption("-o", BASENAME)
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this@with, toFormatSorter())
                    // Skip enableCookies
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    if (playlistIndex != null) {
                        addOption("--playlist-items", playlistIndex)
                        addOption("--dump-json")
                    } else {
                        addOption("--dump-single-json")
                    }
                    addOption("-R", "1")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "5")
                }
                return getVideoInfo(fallbackRequest, taskKey)
            }
            return result
        }
    }

    @Serializable
    data class DownloadPreferences(
        val extractAudio: Boolean,
        val createThumbnail: Boolean,
        val downloadPlaylist: Boolean,
        val subdirectoryExtractor: Boolean,
        val subdirectoryPlaylistTitle: Boolean,
        val commandDirectory: String,
        val downloadSubtitle: Boolean,
        val embedSubtitle: Boolean,
        val keepSubtitle: Boolean,
        val subtitleLanguage: String,
        val autoSubtitle: Boolean,
        val autoTranslatedSubtitles: Boolean,
        val convertSubtitle: Int,
        val concurrentFragments: Int,
        val sponsorBlock: Boolean,
        val sponsorBlockCategory: String,
        val cookies: Boolean,
        val aria2c: Boolean,
        val useCustomAudioPreset: Boolean,
        val audioFormat: Int,
        val audioQuality: Int,
        val convertAudio: Boolean,
        val formatSorting: Boolean,
        val sortingFields: String,
        val audioConvertFormat: Int,
        val videoFormat: Int,
        val formatIdString: String,
        val videoResolution: Int,
        val privateMode: Boolean,
        val rateLimit: Boolean,
        val maxDownloadRate: String,
        val privateDirectory: Boolean,
        val cropArtwork: Boolean,
        val sdcard: Boolean,
        val sdcardUri: String,
        val embedThumbnail: Boolean,
        val videoClips: List<VideoClip>,
        val splitByChapter: Boolean,
        val debug: Boolean,
        val proxy: Boolean,
        val proxyUrl: String,
        val newTitle: String,
        val userAgentString: String,
        val outputTemplate: String,
        val useDownloadArchive: Boolean,
        val embedMetadata: Boolean,
        val restrictFilenames: Boolean,
        val supportAv1HardwareDecoding: Boolean,
        val forceIpv4: Boolean,
        val mergeAudioStream: Boolean,
        val mergeToMkv: Boolean,
    ) {
        companion object {
            val EMPTY =
                DownloadPreferences(
                    extractAudio = false,
                    createThumbnail = false,
                    downloadPlaylist = false,
                    subdirectoryExtractor = false,
                    subdirectoryPlaylistTitle = false,
                    commandDirectory = "",
                    downloadSubtitle = false,
                    embedSubtitle = false,
                    keepSubtitle = false,
                    subtitleLanguage = "",
                    autoSubtitle = false,
                    autoTranslatedSubtitles = false,
                    convertSubtitle = 0,
                    concurrentFragments = 0,
                    sponsorBlock = false,
                    sponsorBlockCategory = "",
                    cookies = false,
                    aria2c = false,
                    audioFormat = 0,
                    audioQuality = 0,
                    convertAudio = false,
                    formatSorting = false,
                    sortingFields = "",
                    audioConvertFormat = 0,
                    videoFormat = 0,
                    formatIdString = "",
                    videoResolution = 0,
                    privateMode = false,
                    rateLimit = false,
                    maxDownloadRate = "",
                    privateDirectory = false,
                    cropArtwork = false,
                    sdcard = false,
                    sdcardUri = "",
                    embedThumbnail = false,
                    videoClips = emptyList(),
                    splitByChapter = false,
                    debug = false,
                    proxy = false,
                    proxyUrl = "",
                    newTitle = "",
                    userAgentString = "",
                    outputTemplate = "",
                    useDownloadArchive = false,
                    embedMetadata = false,
                    restrictFilenames = false,
                    supportAv1HardwareDecoding = false,
                    forceIpv4 = false,
                    mergeAudioStream = false,
                    mergeToMkv = false,
                    useCustomAudioPreset = false,
                )

            fun createFromPreferences(): DownloadPreferences {
                val downloadSubtitle = SUBTITLE.getBoolean()
                val embedSubtitle = EMBED_SUBTITLE.getBoolean()
                return DownloadPreferences(
                    extractAudio = EXTRACT_AUDIO.getBoolean(),
                    createThumbnail = THUMBNAIL.getBoolean(),
                    downloadPlaylist = PLAYLIST.getBoolean(),
                    subdirectoryExtractor = SUBDIRECTORY_EXTRACTOR.getBoolean(),
                    subdirectoryPlaylistTitle = SUBDIRECTORY_PLAYLIST_TITLE.getBoolean(),
                    commandDirectory = COMMAND_DIRECTORY.getString(),
                    downloadSubtitle = downloadSubtitle,
                    embedSubtitle = embedSubtitle,
                    keepSubtitle = KEEP_SUBTITLE_FILES.getBoolean(),
                    subtitleLanguage = SUBTITLE_LANGUAGE.getString(),
                    autoSubtitle = AUTO_SUBTITLE.getBoolean(),
                    autoTranslatedSubtitles = AUTO_TRANSLATED_SUBTITLES.getBoolean(),
                    convertSubtitle = CONVERT_SUBTITLE.getInt(),
                    concurrentFragments = CONCURRENT.getInt(),
                    sponsorBlock = SPONSORBLOCK.getBoolean(),
                    sponsorBlockCategory = PreferenceUtil.getSponsorBlockCategories(),
                    cookies = COOKIES.getBoolean(),
                    aria2c = ARIA2C.getBoolean(),
                    useCustomAudioPreset = USE_CUSTOM_AUDIO_PRESET.getBoolean(),
                    audioFormat = AUDIO_FORMAT.getInt(),
                    audioQuality = AUDIO_QUALITY.getInt(),
                    convertAudio = AUDIO_CONVERT.getBoolean(),
                    formatSorting = FORMAT_SORTING.getBoolean(),
                    sortingFields = SORTING_FIELDS.getString(),
                    audioConvertFormat = PreferenceUtil.getAudioConvertFormat(),
                    videoFormat = PreferenceUtil.getVideoFormat(),
                    formatIdString = "",
                    videoResolution = PreferenceUtil.getVideoResolution(),
                    privateMode = PRIVATE_MODE.getBoolean(),
                    rateLimit = RATE_LIMIT.getBoolean(),
                    maxDownloadRate = PreferenceUtil.getMaxDownloadRate(),
                    privateDirectory = PRIVATE_DIRECTORY.getBoolean(),
                    cropArtwork = CROP_ARTWORK.getBoolean(),
                    sdcard = SDCARD_DOWNLOAD.getBoolean(),
                    sdcardUri = SDCARD_URI.getString(),
                    embedThumbnail = EMBED_THUMBNAIL.getBoolean(),
                    videoClips = emptyList(),
                    splitByChapter = false,
                    debug = DEBUG.getBoolean(),
                    proxy = PROXY.getBoolean(),
                    proxyUrl = PROXY_URL.getString(),
                    newTitle = "",
                    userAgentString =
                        if (USER_AGENT.getBoolean()) {
                            USER_AGENT_STRING.getString().ifEmpty { BRAVE_CHROMIUM_USER_AGENT }
                        } else "",
                    outputTemplate = OUTPUT_TEMPLATE.getString(),
                    useDownloadArchive = DOWNLOAD_ARCHIVE.getBoolean(),
                    embedMetadata = EMBED_METADATA.getBoolean(),
                    restrictFilenames = RESTRICT_FILENAMES.getBoolean(),
                    supportAv1HardwareDecoding = checkIfAv1HardwareAccelerated(),
                    forceIpv4 = FORCE_IPV4.getBoolean(),
                    mergeAudioStream = false,
                    mergeToMkv =
                        (downloadSubtitle && embedSubtitle) || MERGE_OUTPUT_MKV.getBoolean(),
                )
            }
        }
    }

    private fun YoutubeDLRequest.enableCookies(userAgentString: String): YoutubeDLRequest =
        this.addOption("--cookies", context.getCookiesFile().absolutePath).apply {
            if (userAgentString.isNotEmpty()) {
                addOption("--user-agent", userAgentString)
            }
        }

    private fun YoutubeDLRequest.enableProxy(proxyUrl: String): YoutubeDLRequest =
        this.addOption("--proxy", proxyUrl)

    private fun String.isTwitterUrl(): Boolean =
        contains("twitter.com", ignoreCase = true) || contains("x.com", ignoreCase = true)

    private fun hasTwitterAuthCookies(): Boolean {
        val twitterCookies = getCookieListFromDatabase().getOrDefault(emptyList())
            .filter { cookie ->
                cookie.domain.endsWith("x.com", ignoreCase = true) ||
                    cookie.domain.endsWith("twitter.com", ignoreCase = true)
            }
        return twitterCookies.any { it.name == "auth_token" && it.value.isNotBlank() } &&
            twitterCookies.any { it.name == "ct0" && it.value.isNotBlank() }
    }

    private fun YoutubeDLRequest.useDownloadArchive(): YoutubeDLRequest =
        this.addOption("--download-archive", context.getArchiveFile().absolutePath)

    @CheckResult
    fun getCookieListFromDatabase(): Result<List<Cookie>> = runCatching {
        CookieManager.getInstance().run {
            if (!hasCookies()) throw Exception("There is no cookies in the database!")
            flush()
        }
        SQLiteDatabase.openDatabase(
                context.dataDir.resolve("app_webview/Default/Cookies").absolutePath,
                null,
                OPEN_READONLY,
            )
            .run {
                val projection =
                    arrayOf(
                        CookieScheme.HOST,
                        CookieScheme.EXPIRY,
                        CookieScheme.PATH,
                        CookieScheme.NAME,
                        CookieScheme.VALUE,
                        CookieScheme.SECURE,
                    )
                val cookieList = mutableListOf<Cookie>()
                query("cookies", projection, null, null, null, null, null).run {
                    while (moveToNext()) {
                        val expiryMicroseconds = getLong(getColumnIndexOrThrow(CookieScheme.EXPIRY))
                        val expiry = if (expiryMicroseconds > 11644473600000000L) {
                            (expiryMicroseconds - 11644473600000000L) / 1000000L
                        } else {
                            expiryMicroseconds
                        }
                        val name = getString(getColumnIndexOrThrow(CookieScheme.NAME))
                        val value = getString(getColumnIndexOrThrow(CookieScheme.VALUE))
                        val path = getString(getColumnIndexOrThrow(CookieScheme.PATH))
                        val secure = getLong(getColumnIndexOrThrow(CookieScheme.SECURE)) == 1L
                        val hostKey = getString(getColumnIndexOrThrow(CookieScheme.HOST))

                        val host = if (hostKey[0] != '.') ".$hostKey" else hostKey
                        val cookie = Cookie(
                            domain = host,
                            name = name,
                            value = value,
                            path = path,
                            secure = secure,
                            expiry = expiry,
                        )
                        cookieList.add(cookie)

                        // X often bounces between web, mobile, and API hosts while resolving media.
                        if (host.endsWith("x.com") || host.endsWith("twitter.com")) {
                            setOf(
                                ".x.com",
                                ".twitter.com",
                                ".api.x.com",
                                ".api.twitter.com",
                                ".mobile.twitter.com",
                            )
                                .filterNot { it == host }
                                .forEach { cookieList.add(cookie.copy(domain = it)) }
                        }
                    }
                    close()
                }
                close()
                cookieList
            }
    }

    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    private fun YoutubeDLRequest.enableAria2c(): YoutubeDLRequest =
        this.addOption("--downloader", "libaria2c.so")

    private fun YoutubeDLRequest.addOptionsForVideoDownloads(
        downloadPreferences: DownloadPreferences
    ): YoutubeDLRequest =
        this.apply {
            downloadPreferences.run {
                addOption("--add-metadata")
                addOption("--no-embed-info-json")
                if (formatIdString.isNotEmpty()) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else {
                    applyFormatSorter(this, toFormatSorter())
                }
                if (downloadSubtitle) {
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    if (embedSubtitle) {
                        addOption("--embed-subs")
                        if (keepSubtitle) {
                            addOption("--write-subs")
                        }
                    } else {
                        addOption("--write-subs")
                    }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (mergeToMkv) {
                    addOption("--remux-video", "mkv")
                    addOption("--merge-output-format", "mkv")
                }
                if (embedThumbnail) {
                    addOption("--embed-thumbnail")
                }
                if (videoClips.isEmpty()) addOption("--embed-chapters")
            }
        }

    @CheckResult
    private fun DownloadPreferences.toAudioFormatSorter(): String =
        this.run {
            if (!useCustomAudioPreset) return@run ""
            val format =
                when (audioFormat) {
                    M4A -> "acodec:aac"
                    OPUS -> "acodec:opus"
                    else -> ""
                }
            val quality =
                when (audioQuality) {
                    HIGH -> "abr~192"
                    MEDIUM -> "abr~128"
                    LOW -> "abr~64"
                    else -> ""
                }
            return@run connectWithDelimiter(format, quality, delimiter = ",")
        }

    @CheckResult
    private fun DownloadPreferences.toVideoFormatSorter(): String =
        this.run {
            val format =
                when (videoFormat) {
                    FORMAT_COMPATIBILITY -> "proto,vcodec:h264,ext"
                    FORMAT_QUALITY ->
                        if (supportAv1HardwareDecoding) {
                            "vcodec:av01"
                        } else {
                            "vcodec:vp9.2"
                        }

                    else -> ""
                }
            val res =
                when (videoResolution) {
                    1 -> "res:2160"
                    2 -> "res:1440"
                    3 -> "res:1080"
                    4 -> "res:720"
                    5 -> "res:480"
                    6 -> "res:360"
                    7 -> "+res"
                    else -> ""
                }
            val sorter = if (videoFormat == FORMAT_COMPATIBILITY) {
                connectWithDelimiter(format, res, delimiter = ",")
            } else {
                connectWithDelimiter(res, format, delimiter = ",")
            }
            return@run sorter
        }

    private fun YoutubeDLRequest.applyFormatSorter(
        preferences: DownloadPreferences,
        sorter: String,
    ) =
        preferences.run {
            if (formatSorting && sortingFields.isNotEmpty()) addOption("-S", sortingFields)
            else if (sorter.isNotEmpty()) addOption("-S", sorter) else {}
        }

    @CheckResult
    fun DownloadPreferences.toFormatSorter(): String =
        connectWithDelimiter(
            this.toVideoFormatSorter(),
            this.toAudioFormatSorter(),
            delimiter = ",",
        )

    private fun YoutubeDLRequest.addOptionsForAudioDownloads(
        id: String,
        preferences: DownloadPreferences,
        playlistUrl: String,
    ): YoutubeDLRequest =
        this.apply {
            with(preferences) {
                addOption("-x")
                if (downloadSubtitle) {
                    addOption("--write-subs")

                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (formatIdString.isNotEmpty()) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else if (convertAudio) {
                    when (audioConvertFormat) {
                        CONVERT_MP3 -> {
                            addOption("--audio-format", "mp3")
                        }

                        CONVERT_M4A -> {
                            addOption("--audio-format", "m4a")
                        }
                    }
                } else {
                    applyFormatSorter(preferences, toAudioFormatSorter())
                }

                if (embedMetadata) {
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")

                    if (cropArtwork) {
                        val configFile = context.getConfigFile(id)
                        FileUtil.writeContentToFile(CROP_ARTWORK_COMMAND, configFile)
                        addOption("--config", configFile.absolutePath)
                    }
                }
                addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")

                if (playlistUrl.isNotEmpty()) {
                    addOption("--parse-metadata", "%(album,playlist,title)s:%(meta_album)s")
                    addOption("--parse-metadata", "%(track_number,playlist_index)d:%(meta_track)s")
                } else {
                    addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
                }
            }
        }

    private fun insertInfoIntoDownloadHistory(
        videoInfo: VideoInfo,
        filePaths: List<String>,
    ): List<String> =
        filePaths.onEach {
            DatabaseUtil.insertInfo(videoInfo.toDownloadedVideoInfo(videoPath = it))
        }

    private fun VideoInfo.toDownloadedVideoInfo(
        id: Int = 0,
        videoPath: String,
    ): DownloadedVideoInfo =
        this.run {
            DownloadedVideoInfo(
                id = id,
                videoTitle = title,
                videoAuthor = uploader ?: channel ?: uploaderId.toString(),
                videoUrl = webpageUrl ?: originalUrl.toString(),
                thumbnailUrl = thumbnail.toHttpsUrl(),
                videoPath = videoPath,
                extractor = extractorKey,
            )
        }

    private fun insertSplitChapterIntoHistory(videoInfo: VideoInfo, filePaths: List<String>) =
        filePaths.onEach {
            DatabaseUtil.insertInfo(
                videoInfo.toDownloadedVideoInfo(videoPath = it).copy(videoTitle = it.getFileName())
            )
        }

    @CheckResult
    fun downloadVideo(
        videoInfo: VideoInfo? = null,
        playlistUrl: String = "",
        playlistItem: Int = 0,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        if (videoInfo == null)
            return Result.failure(Throwable(context.getString(R.string.fetch_info_error_msg)))

        val url = playlistUrl.ifEmpty { videoInfo.originalUrl ?: videoInfo.webpageUrl ?: "" }
        val isUnsupported = url.contains("tiktok.com", ignoreCase = true) ||
                url.contains("douyin.com", ignoreCase = true) ||
                url.contains("facebook.com", ignoreCase = true) ||
                url.contains("fb.watch", ignoreCase = true) ||
                url.contains("fb.gg", ignoreCase = true)
        if (isUnsupported) {
            return Result.failure(Exception("Plataforma no soportada"))
        }

        // --- Direct download path for ALL Threads/Instagram CDN media (video + image) ---
        // When resolveThreadsUrl successfully resolved a Threads post to a direct CDN URL,
        // fetchVideoInfoFromUrl creates a synthetic VideoInfo with extractor="threads" or "instagram".
        // We MUST bypass yt-dlp here because:
        //   1. yt-dlp uses the full CDN URL as a temp filename → "File name too long" error
        //   2. yt-dlp's generic extractor doesn't handle Instagram CDN URLs reliably
        //   3. OkHttp downloads direct URLs correctly with a short filename
        val isDirectCdnMedia = (videoInfo.extractor == "threads" || videoInfo.extractor == "instagram") && videoInfo.originalUrl != null

        if (isDirectCdnMedia) {
            return runCatching {
                val mediaUrl = videoInfo.originalUrl!!
                val ext = videoInfo.ext.ifEmpty { "mp4" }
                val prefix = if (videoInfo.extractor == "threads") "threads" else "instagram"
                
                val igId = if (mediaUrl.contains("#ig_id=")) mediaUrl.substringAfter("#ig_id=").substringBefore("&") else ""
                val fileName = if (igId.isNotEmpty()) {
                    "${prefix}_$igId.$ext"
                } else {
                    "${prefix}_${System.currentTimeMillis()}.$ext"
                }
                
                val destDir = java.io.File(videoDownloadDir)
                destDir.mkdirs()
                val destFile = java.io.File(destDir, fileName)

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val requestUrl = if (mediaUrl.contains("#")) mediaUrl.substringBefore("#") else mediaUrl
                val request = okhttp3.Request.Builder()
                    .url(requestUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                    .header("Referer", if (videoInfo.extractor == "threads") "https://www.threads.com/" else "https://www.instagram.com/")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Throwable("Failed to download media: HTTP ${response.code}")

                response.body?.byteStream()?.use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(65536) // 64KB chunks for faster download
                        var bytesRead: Int
                        var totalRead = 0L
                        val contentLength = response.body?.contentLength() ?: -1L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                progressCallback?.invoke((totalRead * 100f / contentLength), totalRead, "")
                            }
                        }
                    }
                } ?: throw Throwable("Empty response body from CDN")

                progressCallback?.invoke(100f, 0L, "")
                insertInfoIntoDownloadHistory(videoInfo, listOf(destFile.absolutePath))
                listOf(destFile.absolutePath)
            }
        }


        with(downloadPreferences) {
            val url =
                playlistUrl.ifEmpty {
                    videoInfo.originalUrl
                        ?: videoInfo.webpageUrl
                        ?: return Result.failure(
                            Throwable(context.getString(R.string.fetch_info_error_msg))
                        )
                }
            val (resolvedUrl, _) = resolveThreadsUrl(url)
            val request = YoutubeDLRequest(resolvedUrl)
            val pathBuilder = StringBuilder()
            val outputBuilder = StringBuilder()

            request
                .apply {
                    addOption("--no-mtime")
                    //                addOption("-v")
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (debug) {
                        addOption("-v")
                    }
                    if (useDownloadArchive) {
                        val archiveFile = context.getArchiveFile()
                        val archiveFileContent = archiveFile.readText()
                        if (archiveFileContent.contains("${videoInfo.extractor} ${videoInfo.id}")) {
                            return Result.failure(
                                YoutubeDLException(
                                    context.getString(R.string.download_archive_error)
                                )
                            )
                        } else {
                            useDownloadArchive()
                        }
                    }

                    if (rateLimit && maxDownloadRate.isNumberInRange(1, 1000000)) {
                        addOption("-r", "${maxDownloadRate}K")
                    }

                    if (playlistItem != 0 && downloadPlaylist) {
                        addOption("--playlist-items", playlistItem)
                        if (subdirectoryPlaylistTitle && !videoInfo.playlist.isNullOrEmpty()) {
                            outputBuilder.append(PLAYLIST_TITLE_SUBDIRECTORY_PREFIX)
                        }
                        //                    addOption("--compat-options",
                        // "no-youtube-unavailable-videos")
                    } else {
                        addOption("--no-playlist")
                    }

                    if (aria2c) {
                        enableAria2c()
                    } else if (concurrentFragments > 1) {
                        addOption("--concurrent-fragments", concurrentFragments)
                    }

                    if (extractAudio || (videoInfo.vcodec == "none")) {
                        if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                        else pathBuilder.append(audioDownloadDir)
                        addOptionsForAudioDownloads(
                            id = videoInfo.id,
                            preferences = downloadPreferences,
                            playlistUrl = playlistUrl,
                        )
                    } else {
                        if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                        else pathBuilder.append(videoDownloadDir)
                        addOptionsForVideoDownloads(downloadPreferences)
                    }
                    if (sponsorBlock) {
                        addOption("--sponsorblock-remove", sponsorBlockCategory)
                    }

                    if (createThumbnail) {
                        addOption("--write-thumbnail")
                        addOption("--convert-thumbnails", "png")
                    }
                    if (subdirectoryExtractor) {
                        pathBuilder.append("/${videoInfo.extractorKey}")
                    }

                    if (sdcard) {
                        addOption("-P", context.getSdcardTempDir(videoInfo.id).absolutePath)
                    } else {
                        addOption("-P", pathBuilder.toString())
                    }

                    videoClips.forEach {
                        addOption(
                            "--download-sections",
                            "*%d-%d".format(locale = Locale.US, it.start, it.end),
                        )
                    }
                    if (newTitle.isNotEmpty()) {
                        addCommands(listOf("--replace-in-metadata", "title", ".+", newTitle))
                    }
                    if (Build.VERSION.SDK_INT > 23 && !sdcard)
                        addOption("-P", "temp:" + getExternalTempDir())

                    if (splitByChapter) {
                        addOption("-o", OUTPUT_TEMPLATE_CHAPTERS)
                        addOption("--split-chapters")
                    }

                    val output =
                        if (splitByChapter) {
                            OUTPUT_TEMPLATE_SPLIT
                        } else if (videoClips.isEmpty()) {
                            outputTemplate
                        } else {
                            OUTPUT_TEMPLATE_CLIPS
                        }

                    addOption("-o", outputBuilder.append(output).toString())

                    for (s in request.buildCommand()) Log.d(TAG, s)
                }
                .runCatching {
                    YoutubeDL.getInstance()
                        .execute(request = this, processId = taskId, callback = progressCallback)
                }
                .onFailure { th ->
                    if (cookies && (
                        url.contains("tiktok.com") ||
                        url.contains("facebook.com") ||
                        url.contains("fb.watch") ||
                        url.contains("fb.gg")
                    )) {
                        Log.d(TAG, "downloadVideo: Failed with cookies for TikTok/Facebook, retrying WITHOUT cookies...")
                        return downloadVideo(
                            videoInfo = videoInfo,
                            playlistUrl = playlistUrl,
                            playlistItem = playlistItem,
                            taskId = taskId,
                            downloadPreferences = downloadPreferences.copy(cookies = false),
                            progressCallback = progressCallback
                        )
                    }

                    return if (
                        sponsorBlock &&
                            th.message?.contains("Unable to communicate with SponsorBlock API") ==
                                true
                    ) {
                        th.printStackTrace()
                        onFinishDownloading(
                            preferences = this,
                            videoInfo = videoInfo,
                            downloadPath = pathBuilder.toString(),
                            sdcardUri = sdcardUri,
                        )
                    } else Result.failure(th)
                }
            return onFinishDownloading(
                preferences = this,
                videoInfo = videoInfo,
                downloadPath = pathBuilder.toString(),
                sdcardUri = sdcardUri,
            )
        }
    }

    private fun onFinishDownloading(
        preferences: DownloadPreferences,
        videoInfo: VideoInfo,
        downloadPath: String,
        sdcardUri: String,
    ): Result<List<String>> =
        preferences.run {
            val fileName =
                preferences.newTitle.ifEmpty {
                    videoInfo.filename
                        ?: videoInfo.requestedDownloads?.firstOrNull()?.filename
                        ?: videoInfo.title
                }

            Log.d(TAG, "onFinishDownloading: $fileName")
            if (sdcard) {
                moveFilesToSdcard(
                        sdcardUri = sdcardUri,
                        tempPath = context.getSdcardTempDir(videoInfo.id),
                    )
                    .onSuccess {
                        if (privateMode) {
                            return Result.success(emptyList())
                        } else if (splitByChapter) {
                            insertSplitChapterIntoHistory(videoInfo, it)
                        } else {
                            insertInfoIntoDownloadHistory(videoInfo, it)
                        }
                    }
            } else {
                FileUtil.scanFileToMediaLibraryPostDownload(
                        title = fileName,
                        downloadDir = downloadPath,
                    )
                    .run {
                        if (privateMode) Result.success(emptyList())
                        else
                            Result.success(
                                if (splitByChapter) {
                                    insertSplitChapterIntoHistory(videoInfo, this)
                                } else {
                                    insertInfoIntoDownloadHistory(videoInfo, this)
                                }
                            )
                    }
            }
        }

    @CheckResult
    fun executeCustomCommandTask(
        urlString: String,
        taskId: String,
        template: CommandTemplate,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit),
    ): Result<YoutubeDLResponse> {
        val urlList = urlString.split(Regex("[\n ]")).filter { it.isNotBlank() }

        val request =
            with(preferences) {
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                }
            }

        return runCatching {
            YoutubeDL.getInstance()
                .execute(request = request, processId = taskId, callback = progressCallback)
        }
    }

    suspend fun executeCommandInBackground(
        url: String,
        template: CommandTemplate = PreferenceUtil.getTemplate(),
        downloadPreferences: DownloadPreferences = DownloadPreferences.createFromPreferences(),
    ) {
        downloadPreferences.run {
            val taskId = Downloader.makeKey(url = url, templateName = template.name)
            val notificationId = taskId.toNotificationId()
            val urlList = url.split(Regex("[\n ]")).filter { it.isNotBlank() }

            ToastUtil.makeToastSuspend(context.getString(R.string.start_execute))
            val request =
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString)
                    }
                }

            onProcessStarted()
            withContext(Dispatchers.Main) { onTaskStarted(template, url) }
            runCatching {
                    val response =
                        YoutubeDL.getInstance().execute(request = request, processId = taskId) {
                            progress,
                            _,
                            text ->
                            NotificationUtil.makeNotificationForCustomCommand(
                                notificationId = notificationId,
                                taskId = taskId,
                                progress = progress.toInt(),
                                templateName = template.name,
                                taskUrl = url,
                                text = text,
                            )
                            Downloader.updateTaskOutput(
                                template = template,
                                url = url,
                                line = text,
                                progress = progress,
                            )
                        }
                    onTaskEnded(template, url, response.out + "\n" + response.err)
                }
                .onFailure {
                    it.printStackTrace()
                    if (it is YoutubeDL.CanceledException) return@onFailure
                    it.message.run {
                        if (isNullOrEmpty()) onTaskEnded(template, url)
                        else onTaskError(this, template, url)
                    }
                }
            onProcessEnded()
        }
    }

    private fun checkIfAv1HardwareAccelerated(): Boolean {
        if (PreferenceUtil.containsKey(AV1_HARDWARE_ACCELERATED)) {
            return AV1_HARDWARE_ACCELERATED.getBoolean()
        } else {
            val res =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    false
                } else {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                        info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
                            info.isHardwareAccelerated
                    }
                }
            AV1_HARDWARE_ACCELERATED.updateBoolean(res)
            return res
        }
    }

    /**
     * Resolves Threads.com/net post URLs to direct CDN media URLs.
     *
     * Uses session cookies captured via WebView login to call the Threads/Instagram
     * media info API. Tries multiple endpoints and cookie sources.
     */
    private fun createInstagramMediaUrl(
        mediaUrl: String,
        id: String,
        title: String,
        thumbnailUrl: String?,
        webpageUrl: String?,
        author: String?,
    ): String {
        val sb = StringBuilder(mediaUrl)
        sb.append("#ig_id=").append(id)
        try {
            val enc = "UTF-8"
            sb.append("&ig_title=").append(java.net.URLEncoder.encode(title.withInstagramAuthor(author), enc))
            if (!thumbnailUrl.isNullOrEmpty()) {
                sb.append("&ig_thumb=").append(java.net.URLEncoder.encode(thumbnailUrl, enc))
            }
            if (!webpageUrl.isNullOrEmpty()) {
                sb.append("&ig_webpage=").append(java.net.URLEncoder.encode(webpageUrl, enc))
            }
            if (!author.isNullOrEmpty()) {
                sb.append("&ig_author=").append(java.net.URLEncoder.encode(author, enc))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sb.toString()
    }

    private fun String.withInstagramAuthor(author: String?): String {
        val cleanAuthor = author?.takeIf {
            it.isNotBlank() && !it.equals("Autor desconocido", ignoreCase = true)
        } ?: return this
        return if (contains(cleanAuthor, ignoreCase = true)) this else "$cleanAuthor - $this"
    }

    private fun resolveThreadsUrl(url: String): Pair<String, String?> {
        if (!url.contains("threads.com", ignoreCase = true) && !url.contains("threads.net", ignoreCase = true)) return Pair(url, null)

        try {
            // --- Step 1: Extract shortcode ---
            val shortcodeMatch = Regex("""threads\.(?:com|net)/(?:@[^/]+/post/|t/)([A-Za-z0-9_\-]+)""")
                .find(url) ?: return Pair(url, null)
            val shortcode = shortcodeMatch.groupValues[1]

            // --- Step 2: Decode shortcode to media ID ---
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var mediaId = 0L
            for (c in shortcode) {
                val idx = alphabet.indexOf(c)
                if (idx < 0) return Pair(url, null)
                mediaId = mediaId * 64 + idx
            }

            // --- Step 3: Collect cookies from ALL Meta domains in WebView ---
            val cookieManager = android.webkit.CookieManager.getInstance()
            val domains = listOf(
                "https://www.threads.com",
                "https://threads.com",
                "https://www.threads.net",
                "https://threads.net",
                "https://www.instagram.com",
                "https://instagram.com"
            )
            val cookiesByDomain = domains.associateWith { cookieManager.getCookie(it) }
            cookiesByDomain.forEach { (domain, c) ->
                Log.d(TAG, "resolveThreadsUrl cookies[$domain]: ${if (c.isNullOrEmpty()) "NONE" else c.take(80) + "..."}")
            }

            // Merge all available cookies, preferring threads.com then instagram.com
            val allCookies = cookiesByDomain.values
                .filterNotNull()
                .flatMap { it.split(";") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.substringBefore("=").trim() } // deduplicate by name
                .joinToString("; ")

            if (allCookies.isEmpty()) {
                Log.d(TAG, "resolveThreadsUrl: No cookies found in any Meta domain — user must log in first")
                return Pair(url, null)
            }

            // Try to find sessionid (may be from either threads.com or instagram.com)
            val sessionId = allCookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sessionid=") }
                ?.removePrefix("sessionid=") ?: ""

            val csrfToken = allCookies.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("csrftoken=") }
                ?.removePrefix("csrftoken=") ?: ""

            Log.d(TAG, "resolveThreadsUrl: sessionid=${sessionId.take(20)}... csrftoken=${csrfToken.take(20)}...")

            if (sessionId.isEmpty()) {
                Log.d(TAG, "resolveThreadsUrl: No sessionid found — user must complete login in WebView")
                return Pair(url, null)
            }

            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // Use threads-specific cookies (include sessionid)
            val threadsCookieStr = cookiesByDomain["https://www.threads.com"]
                ?: cookiesByDomain["https://threads.com"] ?: allCookies
            val igCookieStr = cookiesByDomain["https://www.instagram.com"]
                ?: cookiesByDomain["https://instagram.com"] ?: allCookies

            // --- Attempt 1: Threads web API with threads.com cookies ---
            val threadsApiUrl = "https://www.threads.com/api/v1/media/$mediaId/info/"
            val req1 = okhttp3.Request.Builder()
                .url(threadsApiUrl)
                .header("Cookie", threadsCookieStr)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                .header("X-IG-App-ID", "238260118697367")
                .header("X-CSRFToken", csrfToken)
                .header("X-IG-WWW-Claim", "0")
                .header("Referer", "https://www.threads.com/")
                .header("Origin", "https://www.threads.com")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val resp1 = client.newCall(req1).execute()
            Log.d(TAG, "resolveThreadsUrl: threads.com/api/v1 → ${resp1.code}")
            if (resp1.isSuccessful) {
                val r = parseInstagramApiResponse(resp1.body?.string())
                if (r != null) return r
            }

            // --- Attempt 2: Instagram web API with instagram.com cookies ---
            if (igCookieStr != threadsCookieStr) {
                val igApiUrl = "https://www.instagram.com/api/v1/media/$mediaId/info/"
                val req2 = okhttp3.Request.Builder()
                    .url(igApiUrl)
                    .header("Cookie", igCookieStr)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-CSRFToken", csrfToken)
                    .header("X-IG-WWW-Claim", "0")
                    .header("Referer", "https://www.instagram.com/")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                val resp2 = client.newCall(req2).execute()
                Log.d(TAG, "resolveThreadsUrl: instagram.com/api/v1 → ${resp2.code}")
                if (resp2.isSuccessful) {
                    val r = parseInstagramApiResponse(resp2.body?.string())
                    if (r != null) return r
                }
            }

            // --- Attempt 3: Read from the app's cookies.txt (written by DownloadUtil cookie export) ---
            val cookiesFile = context.getCookiesFile()
            if (cookiesFile.exists()) {
                val cookiesTxt = cookiesFile.readText()
                val sessionIdFromFile = cookiesTxt.lines()
                    .filter { it.contains("sessionid") && (it.contains("threads.com") || it.contains("instagram.com")) }
                    .firstOrNull()
                    ?.split("\t")
                    ?.getOrNull(6) // value is column 7 in Netscape format
                    ?: ""
                val csrfFromFile = cookiesTxt.lines()
                    .filter { it.contains("csrftoken") && (it.contains("threads.com") || it.contains("instagram.com")) }
                    .firstOrNull()
                    ?.split("\t")
                    ?.getOrNull(6) ?: ""

                Log.d(TAG, "resolveThreadsUrl: cookies.txt sessionid=${sessionIdFromFile.take(20)}...")

                if (sessionIdFromFile.isNotEmpty()) {
                    val cookieHeader = buildString {
                        append("sessionid=$sessionIdFromFile")
                        if (csrfFromFile.isNotEmpty()) append("; csrftoken=$csrfFromFile")
                    }
                    val req3 = okhttp3.Request.Builder()
                        .url("https://www.threads.com/api/v1/media/$mediaId/info/")
                        .header("Cookie", cookieHeader)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                        .header("X-IG-App-ID", "238260118697367")
                        .header("X-CSRFToken", csrfFromFile)
                        .header("Referer", "https://www.threads.com/")
                        .header("Accept", "application/json, text/plain, */*")
                        .build()
                    val resp3 = client.newCall(req3).execute()
                    Log.d(TAG, "resolveThreadsUrl: cookies.txt attempt → ${resp3.code}")
                    if (resp3.isSuccessful) {
                        val r = parseInstagramApiResponse(resp3.body?.string())
                        if (r != null) return r
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "resolveThreadsUrl failed: ${e.message}", e)
        }
        return Pair(url, null)
    }

    /** Parse the JSON response from Instagram/Threads /api/v1/media/{id}/info/ */
    private fun parseInstagramApiResponse(jsonStr: String?): Pair<String, String?>? {
        if (jsonStr.isNullOrEmpty()) return null
        return try {
            val json = org.json.JSONObject(jsonStr)
            val items = json.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val item = items.getJSONObject(0)

            // Extract thumbnail URL first
            var thumbnailUrl: String? = null
            val imageVersions = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
            if (imageVersions != null && imageVersions.length() > 0) {
                thumbnailUrl = imageVersions.getJSONObject(0).getString("url")
            }

            // Extract media URL (video, carousel, or image)
            // Video
            val videoVersions = item.optJSONArray("video_versions")
            if (videoVersions != null && videoVersions.length() > 0) {
                val mediaUrl = videoVersions.getJSONObject(0).getString("url")
                return Pair(mediaUrl, thumbnailUrl)
            }

            // Carousel / album
            val carouselMedia = item.optJSONArray("carousel_media")
            if (carouselMedia != null && carouselMedia.length() > 0) {
                val first = carouselMedia.getJSONObject(0)

                // Try to get thumbnail from carousel item if main one is missing
                if (thumbnailUrl == null) {
                    val ci = first.optJSONObject("image_versions2")?.optJSONArray("candidates")
                    if (ci != null && ci.length() > 0) {
                        thumbnailUrl = ci.getJSONObject(0).getString("url")
                    }
                }

                val cv = first.optJSONArray("video_versions")
                if (cv != null && cv.length() > 0) {
                    return Pair(cv.getJSONObject(0).getString("url"), thumbnailUrl)
                }
                val ci = first.optJSONObject("image_versions2")?.optJSONArray("candidates")
                if (ci != null && ci.length() > 0) {
                    return Pair(ci.getJSONObject(0).getString("url"), thumbnailUrl)
                }
            }

            // Image
            if (imageVersions != null && imageVersions.length() > 0) {
                val mediaUrl = imageVersions.getJSONObject(0).getString("url")
                return Pair(mediaUrl, thumbnailUrl)
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "parseInstagramApiResponse failed: ${e.message}", e)
            null
        }
    }

    private fun getInstagramCookies(): Pair<String, String> {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val domains = listOf(
            "https://www.instagram.com",
            "https://instagram.com"
        )
        val cookiesByDomain = domains.associateWith { cookieManager.getCookie(it) }
        val allCookies = cookiesByDomain.values
            .filterNotNull()
            .flatMap { it.split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.substringBefore("=").trim() }
            .joinToString("; ")

        val csrfToken = allCookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("csrftoken=") }
            ?.removePrefix("csrftoken=") ?: ""

        return Pair(allCookies, csrfToken)
    }

    private fun executeInstagramGetRequest(apiUrl: String, referer: String): String? {
        val (allCookies, csrfToken) = getInstagramCookies()
        val client = okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val req = okhttp3.Request.Builder()
            .url(apiUrl)
            .header("Cookie", allCookies)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-CSRFToken", csrfToken)
            .header("X-IG-WWW-Claim", "0")
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        try {
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (body != null && body.trim().startsWith("<!DOCTYPE html", ignoreCase = true)) {
                    throw InstagramLoginRequiredException()
                }
                return body
            }
            Log.d(TAG, "executeInstagramGetRequest failed for $apiUrl: HTTP ${resp.code}")

            // Fallback: intentar leer cookies.txt
            val cookiesFile = context.getCookiesFile()
            if (cookiesFile.exists()) {
                val cookiesTxt = cookiesFile.readText()
                val sessionIdFromFile = cookiesTxt.lines()
                    .filter { it.contains("sessionid") && it.contains("instagram.com") }
                    .firstOrNull()
                    ?.split("\t")
                    ?.getOrNull(6) ?: ""
                val csrfFromFile = cookiesTxt.lines()
                    .filter { it.contains("csrftoken") && it.contains("instagram.com") }
                    .firstOrNull()
                    ?.split("\t")
                    ?.getOrNull(6) ?: ""
                if (sessionIdFromFile.isNotEmpty()) {
                    val cookieHeader = buildString {
                        append("sessionid=$sessionIdFromFile")
                        if (csrfFromFile.isNotEmpty()) append("; csrftoken=$csrfFromFile")
                    }
                    val reqFallback = okhttp3.Request.Builder()
                        .url(apiUrl)
                        .header("Cookie", cookieHeader)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                        .header("X-IG-App-ID", "936619743392459")
                        .header("X-CSRFToken", csrfFromFile)
                        .header("Referer", referer)
                        .header("Accept", "application/json, text/plain, */*")
                        .build()
                    val respFallback = client.newCall(reqFallback).execute()
                    if (respFallback.isSuccessful) {
                        val bodyFallback = respFallback.body?.string()
                        if (bodyFallback != null && bodyFallback.trim().startsWith("<!DOCTYPE html", ignoreCase = true)) {
                            throw InstagramLoginRequiredException()
                        }
                        return bodyFallback
                    }
                }
            }
        } catch (e: InstagramLoginRequiredException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "executeInstagramGetRequest error: ${e.message}", e)
        }
        return null
    }

    private fun parseInstagramMediaItems(jsonStr: String?): List<InstagramMediaItem>? {
        if (jsonStr.isNullOrEmpty()) return null
        return try {
            val json = org.json.JSONObject(jsonStr)
            val items = json.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val item = items.getJSONObject(0)

            val user = item.optJSONObject("user")
            val username = user?.optString("username") ?: "instagram_user"
            val fullName = user?.optString("full_name")
            val author = if (!fullName.isNullOrEmpty()) "$fullName ($username)" else username
            val code = item.optString("code")
            val webpageUrl = if (!code.isNullOrEmpty()) "https://www.instagram.com/p/$code/" else "https://www.instagram.com/"

            val list = mutableListOf<InstagramMediaItem>()

            val carouselMedia = item.optJSONArray("carousel_media")
            if (carouselMedia != null && carouselMedia.length() > 0) {
                for (i in 0 until carouselMedia.length()) {
                    val subItem = carouselMedia.getJSONObject(i)
                    val id = subItem.optString("id", "${item.optString("id")}_$i")

                    var thumbnailUrl: String? = null
                    val imageVersions = subItem.optJSONObject("image_versions2")?.optJSONArray("candidates")
                    if (imageVersions != null && imageVersions.length() > 0) {
                        thumbnailUrl = imageVersions.getJSONObject(0).getString("url")
                    }

                    val videoVersions = subItem.optJSONArray("video_versions")
                    if (videoVersions != null && videoVersions.length() > 0) {
                        val videoUrl = videoVersions.getJSONObject(0).getString("url")
                        val title = "Instagram Video ${i + 1}"
                        val fullUrl = createInstagramMediaUrl(
                            mediaUrl = videoUrl,
                            id = id,
                            title = title,
                            thumbnailUrl = thumbnailUrl,
                            webpageUrl = webpageUrl,
                            author = author
                        )
                        list.add(InstagramMediaItem(
                            id = id,
                            mediaUrl = fullUrl,
                            thumbnailUrl = thumbnailUrl ?: videoUrl,
                            isVideo = true,
                            title = title,
                            author = author
                        ))
                    } else if (imageVersions != null && imageVersions.length() > 0) {
                        val imageUrl = imageVersions.getJSONObject(0).getString("url")
                        val title = "Instagram Photo ${i + 1}"
                        val fullUrl = createInstagramMediaUrl(
                            mediaUrl = imageUrl,
                            id = id,
                            title = title,
                            thumbnailUrl = thumbnailUrl,
                            webpageUrl = webpageUrl,
                            author = author
                        )
                        list.add(InstagramMediaItem(
                            id = id,
                            mediaUrl = fullUrl,
                            thumbnailUrl = thumbnailUrl ?: imageUrl,
                            isVideo = false,
                            title = title,
                            author = author
                        ))
                    }
                }
            } else {
                // Post individual
                val id = item.optString("id")
                var thumbnailUrl: String? = null
                val imageVersions = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
                if (imageVersions != null && imageVersions.length() > 0) {
                    thumbnailUrl = imageVersions.getJSONObject(0).getString("url")
                }

                val videoVersions = item.optJSONArray("video_versions")
                if (videoVersions != null && videoVersions.length() > 0) {
                    val videoUrl = videoVersions.getJSONObject(0).getString("url")
                    val title = "Instagram Video"
                    val fullUrl = createInstagramMediaUrl(
                        mediaUrl = videoUrl,
                        id = id,
                        title = title,
                        thumbnailUrl = thumbnailUrl,
                        webpageUrl = webpageUrl,
                        author = author
                    )
                    list.add(InstagramMediaItem(
                        id = id,
                        mediaUrl = fullUrl,
                        thumbnailUrl = thumbnailUrl ?: videoUrl,
                        isVideo = true,
                        title = title,
                        author = author
                    ))
                } else if (imageVersions != null && imageVersions.length() > 0) {
                    val imageUrl = imageVersions.getJSONObject(0).getString("url")
                    val title = "Instagram Photo"
                    val fullUrl = createInstagramMediaUrl(
                        mediaUrl = imageUrl,
                        id = id,
                        title = title,
                        thumbnailUrl = thumbnailUrl,
                        webpageUrl = webpageUrl,
                        author = author
                    )
                    list.add(InstagramMediaItem(
                        id = id,
                        mediaUrl = fullUrl,
                        thumbnailUrl = thumbnailUrl ?: imageUrl,
                        isVideo = false,
                        title = title,
                        author = author
                    ))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "parseInstagramMediaItems failed: ${e.message}", e)
            null
        }
    }

    private fun fetchInstagramUserId(username: String): String? {
        val apiUrl = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
        val response = executeInstagramGetRequest(apiUrl, "https://www.instagram.com/$username/") ?: return null
        return try {
            val json = org.json.JSONObject(response)
            json.optJSONObject("data")?.optJSONObject("user")?.optString("id")
        } catch (e: Exception) {
            Log.e(TAG, "fetchInstagramUserId failed: ${e.message}", e)
            null
        }
    }

    private fun fetchInstagramStories(userId: String): List<InstagramMediaItem>? {
        val apiUrl = "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$userId"
        val response = executeInstagramGetRequest(apiUrl, "https://www.instagram.com/") ?: return null
        return try {
            val json = org.json.JSONObject(response)
            val reels = json.optJSONObject("reels") ?: return null
            val userReel = reels.optJSONObject(userId) ?: return null
            val items = userReel.optJSONArray("items") ?: return null

            val user = userReel.optJSONObject("user")
            val username = user?.optString("username") ?: "instagram_user"
            val fullName = user?.optString("full_name")
            val author = if (!fullName.isNullOrEmpty()) "$fullName ($username)" else username
            val webpageUrl = "https://www.instagram.com/stories/$username/"

            val list = mutableListOf<InstagramMediaItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.optString("id")

                var thumbnailUrl: String? = null
                val imageVersions = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
                if (imageVersions != null && imageVersions.length() > 0) {
                    thumbnailUrl = imageVersions.getJSONObject(0).getString("url")
                }

                val videoVersions = item.optJSONArray("video_versions")
                if (videoVersions != null && videoVersions.length() > 0) {
                    val videoUrl = videoVersions.getJSONObject(0).getString("url")
                    val title = "Story Video ${i + 1}"
                    val fullUrl = createInstagramMediaUrl(
                        mediaUrl = videoUrl,
                        id = id,
                        title = title,
                        thumbnailUrl = thumbnailUrl,
                        webpageUrl = webpageUrl,
                        author = author
                    )
                    list.add(InstagramMediaItem(
                        id = id,
                        mediaUrl = fullUrl,
                        thumbnailUrl = thumbnailUrl ?: videoUrl,
                        isVideo = true,
                        title = title,
                        author = author
                    ))
                } else if (imageVersions != null && imageVersions.length() > 0) {
                    val imageUrl = imageVersions.getJSONObject(0).getString("url")
                    val title = "Story Photo ${i + 1}"
                    val fullUrl = createInstagramMediaUrl(
                        mediaUrl = imageUrl,
                        id = id,
                        title = title,
                        thumbnailUrl = thumbnailUrl,
                        webpageUrl = webpageUrl,
                        author = author
                    )
                    list.add(InstagramMediaItem(
                        id = id,
                        mediaUrl = fullUrl,
                        thumbnailUrl = thumbnailUrl ?: imageUrl,
                        isVideo = false,
                        title = title,
                        author = author
                    ))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "fetchInstagramStories failed: ${e.message}", e)
            null
        }
    }

    suspend fun fetchInstagramMediaList(url: String): List<InstagramMediaItem>? = withContext(Dispatchers.IO) {
        // 1. Check if it's a highlights URL
        val highlightMatch = Regex("""instagram\.com/stories/highlights/([0-9]+)""").find(url)
        if (highlightMatch != null) {
            val highlightId = highlightMatch.groupValues[1]
            val stories = fetchInstagramStories("highlight:$highlightId")
            if (!stories.isNullOrEmpty()) return@withContext stories
        }

        // 2. Check if it's a stories URL
        val storiesUserMatch = Regex("""instagram\.com/stories/([A-Za-z0-9_\.]+)/?""").find(url)
        if (storiesUserMatch != null) {
            val username = storiesUserMatch.groupValues[1]
            if (username != "highlights") {
                // 1. Try to get all stories for the user first
                val userId = fetchInstagramUserId(username)
                if (userId != null) {
                    val stories = fetchInstagramStories(userId)
                    if (!stories.isNullOrEmpty()) return@withContext stories
                }

                // 2. Fallback to specific story if fetching all stories failed or returned empty
                val storyIdMatch = Regex("""instagram\.com/stories/[A-Za-z0-9_\.]+/([0-9]+)""").find(url)
                if (storyIdMatch != null) {
                    val storyMediaId = storyIdMatch.groupValues[1]
                    val apiUrl = "https://www.instagram.com/api/v1/media/$storyMediaId/info/"
                    val response = executeInstagramGetRequest(apiUrl, "https://www.instagram.com/")
                    val parsed = parseInstagramMediaItems(response)
                    if (!parsed.isNullOrEmpty()) return@withContext parsed
                }
            }
        }

        // 3. Check if it's a profile URL (excluding reserved routes)
        val profileMatch = Regex("""instagram\.com/([A-Za-z0-9_\.]+)/?""").find(url)
        if (profileMatch != null) {
            val username = profileMatch.groupValues[1]
            val reservedWords = setOf(
                "stories", "p", "reel", "tv", "reels", "explore", "direct", 
                "developer", "accounts", "emails", "about", "legal", "help", 
                "jobs", "privacy", "terms", "directory"
            )
            if (!reservedWords.contains(username.lowercase())) {
                val userId = fetchInstagramUserId(username)
                if (userId != null) {
                    val stories = fetchInstagramStories(userId)
                    if (!stories.isNullOrEmpty()) return@withContext stories
                }
            }
        }

        // 4. Check if it's a post, reel, or IGTV URL
        val postShortcodeMatch = Regex("""instagram\.com/(?:p|reel|tv|reels)/([A-Za-z0-9_\-]+)""").find(url)
        if (postShortcodeMatch != null) {
            val shortcode = postShortcodeMatch.groupValues[1]
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var mediaId = 0L
            for (c in shortcode) {
                val idx = alphabet.indexOf(c)
                if (idx >= 0) {
                    mediaId = mediaId * 64 + idx
                }
            }
            if (mediaId > 0) {
                val apiUrl = "https://www.instagram.com/api/v1/media/${mediaId}/info/"
                val response = executeInstagramGetRequest(apiUrl, "https://www.instagram.com/")
                val parsed = parseInstagramMediaItems(response)
                if (!parsed.isNullOrEmpty()) return@withContext parsed
            }
        }

        null
    }

    private fun unescapeHtml(str: String): String {
        return str
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    private fun unescapeJson(str: String): String {
        var res = str.replace("\\/", "/")
        val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
        res = unicodeRegex.replace(res) { matchResult ->
            val charCode = matchResult.groupValues[1].toInt(16)
            charCode.toChar().toString()
        }
        return res
    }
}

@Serializable
data class InstagramMediaItem(
    val id: String,
    val mediaUrl: String,
    val thumbnailUrl: String?,
    val isVideo: Boolean,
    val title: String,
    val author: String? = null
)

class InstagramLoginRequiredException : Exception("Instagram login required")
