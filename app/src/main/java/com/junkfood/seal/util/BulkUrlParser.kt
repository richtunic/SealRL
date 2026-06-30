package com.junkfood.seal.util

object BulkUrlParser {
    private val urlRegex = Regex("""https?://[^\s]+""")
    private val urlBoundaryRegex = Regex("""https?://.*?(?=https?://|\s|$)""")
    private val twitterRegex = Regex("""https?://(?:[a-zA-Z0-9\-]+\.)?(?:twitter|x)\.com/[a-zA-Z0-9_]+/status/(\d+)""")
    private val instagramRegex = Regex("""https?://(?:[a-zA-Z0-9\-]+\.)?instagram\.com/(?:p|reel|tv)/([a-zA-Z0-9_\-]+)""")

    fun parse(input: String): List<String> {
        val separated = separateGluedUrls(input)
        return urlBoundaryRegex.findAll(separated)
            .map { it.value.trim() }
            .map { cleanUrl(it) }
            .map { normalizeUrl(it) }
            .filter { isSupported(it) }
            .distinct()
            .toList()
    }

    fun separateGluedUrls(text: String): String {
        val regex = Regex("""(?<=.)(?<!\s)https?://""")
        return text.replace(regex) { "\n" + it.value }
    }

    fun formatInputText(text: String): String {
        val urls = urlBoundaryRegex.findAll(separateGluedUrls(text)).map { it.value.trim() }.toList()
        if (urls.isEmpty()) return text

        return urls.joinToString(separator = "\n", postfix = "\n")
    }

    fun normalizePastedUrls(previousText: String, currentText: String): String {
        if (currentText.length <= previousText.length) return currentText

        var prefixLength = 0
        while (
            prefixLength < previousText.length &&
            prefixLength < currentText.length &&
            previousText[prefixLength] == currentText[prefixLength]
        ) {
            prefixLength++
        }

        var suffixLength = 0
        while (
            suffixLength < previousText.length - prefixLength &&
            suffixLength < currentText.length - prefixLength &&
            previousText[previousText.lastIndex - suffixLength] ==
                currentText[currentText.lastIndex - suffixLength]
        ) {
            suffixLength++
        }

        val insertEnd = currentText.length - suffixLength
        val insertedText = currentText.substring(prefixLength, insertEnd)
        if (!urlRegex.containsMatchIn(insertedText)) return currentText

        return buildString {
            append(currentText.substring(0, prefixLength))
            if (prefixLength > 0 && !currentText[prefixLength - 1].isWhitespace()) {
                append('\n')
            }
            append(formatInputText(insertedText).trimEnd())
            append('\n')
            if (insertEnd < currentText.length && !currentText[insertEnd].isWhitespace()) {
                append('\n')
            }
            append(currentText.substring(insertEnd))
        }
    }

    fun addTrailingNewlineAfterUrlPaste(previousText: String, currentText: String): String {
        if (currentText.length <= previousText.length + 1) return currentText

        var prefixLength = 0
        while (
            prefixLength < previousText.length &&
            prefixLength < currentText.length &&
            previousText[prefixLength] == currentText[prefixLength]
        ) {
            prefixLength++
        }

        var suffixLength = 0
        while (
            suffixLength < previousText.length - prefixLength &&
            suffixLength < currentText.length - prefixLength &&
            previousText[previousText.lastIndex - suffixLength] ==
                currentText[currentText.lastIndex - suffixLength]
        ) {
            suffixLength++
        }

        val insertEnd = currentText.length - suffixLength
        val insertedText = currentText.substring(prefixLength, insertEnd)
        
        val hasUrl = urlRegex.containsMatchIn(insertedText) || 
                     insertedText.contains("instagram.com", ignoreCase = true) || 
                     insertedText.contains("x.com", ignoreCase = true) || 
                     insertedText.contains("twitter.com", ignoreCase = true) ||
                     insertedText.contains("tiktok.com", ignoreCase = true) ||
                     insertedText.contains("facebook.com", ignoreCase = true)
                     
        if (!hasUrl) return currentText

        return buildString {
            append(currentText.substring(0, prefixLength))
            if (prefixLength > 0 && !currentText[prefixLength - 1].isWhitespace()) {
                append('\n')
            }
            append(insertedText)
            if (insertEnd < currentText.length && !currentText[insertEnd].isWhitespace()) {
                append('\n')
            } else if (insertEnd == currentText.length && !insertedText.endsWith('\n') && !insertedText.endsWith('\r')) {
                append('\n')
            }
            append(currentText.substring(insertEnd))
        }
    }

    private fun cleanUrl(url: String): String {
        return url
            .trim()
            .trimEnd(',', '.', ';', ')', ']')
    }

    private fun normalizeUrl(url: String): String {
        return url
            .replace("https://twitter.com", "https://x.com")
            .replace("https://www.twitter.com", "https://x.com")
            .replace("https://mobile.twitter.com", "https://x.com")
    }

    private fun isSupported(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
               url.startsWith("https://", ignoreCase = true)
    }

    fun getPlatformName(url: String): String {
        return when {
            url.contains("threads.com", ignoreCase = true) || url.contains("threads.net", ignoreCase = true) -> "Threads"
            url.contains("instagram.com/stories", ignoreCase = true) -> "Story"
            url.contains("instagram.com", ignoreCase = true) -> "Instagram"
            url.contains("x.com", ignoreCase = true) || url.contains("twitter.com", ignoreCase = true) -> "X"
            url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true) -> "YouTube"
            url.contains("tiktok.com", ignoreCase = true) -> "TikTok"
            url.contains("facebook.com", ignoreCase = true) || url.contains("fb.watch", ignoreCase = true) -> "Facebook"
            url.contains("twitch.tv", ignoreCase = true) -> "Twitch"
            url.contains("reddit.com", ignoreCase = true) -> "Reddit"
            url.contains("pinterest.com", ignoreCase = true) -> "Pinterest"
            url.contains("vimeo.com", ignoreCase = true) -> "Vimeo"
            url.contains("streamable.com", ignoreCase = true) -> "Streamable"
            else -> {
                try {
                    val uri = java.net.URI(url)
                    val host = uri.host ?: ""
                    val domain = host.removePrefix("www.").substringBefore(".")
                    domain.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }.ifEmpty { "Web" }
                } catch (e: Exception) {
                    "Web"
                }
            }
        }
    }

    fun getPlatformColor(platform: String): Long {
        return when (platform) {
            "Threads" -> 0xFFFFFFFF
            "Instagram" -> 0xFFE1306C
            "Story" -> 0xFFE1306C
            "X" -> 0xFF1DA1F2
            "YouTube" -> 0xFFFF0000
            "TikTok" -> 0xFFEE1D52
            "Facebook" -> 0xFF1877F2
            "Twitch" -> 0xFF9146FF
            "Reddit" -> 0xFFFF4500
            "Pinterest" -> 0xFFBD081C
            "Vimeo" -> 0xFF1AB7EA
            "Streamable" -> 0xFF007FFF
            else -> 0xFF1E1E1E
        }
    }


    fun getCanonicalUrl(url: String): String {
        try {
            val clean = url.trim()
            val cleanLower = clean.lowercase()
            val isYouTubeWatch = cleanLower.contains("youtube.com/watch") || cleanLower.contains("m.youtube.com/watch")
            val isFacebookWatch = cleanLower.contains("facebook.com/watch") || cleanLower.contains("facebook.com/videos")
            
            var videoIdParam: String? = null
            if (isYouTubeWatch || isFacebookWatch) {
                try {
                    val uri = java.net.URI(url)
                    val query = uri.query
                    if (!query.isNullOrEmpty()) {
                        val params = query.split("&").associate {
                            val parts = it.split("=", limit = 2)
                            val key = parts.getOrNull(0) ?: ""
                            val value = parts.getOrNull(1) ?: ""
                            key to value
                        }
                        videoIdParam = params["v"]
                    }
                } catch (e: Exception) {
                    // Ignore URI parsing issues
                }
            }

            var cleanUrl = cleanLower
            if (cleanUrl.contains("?")) {
                cleanUrl = cleanUrl.substringBefore("?")
            }
            while (cleanUrl.endsWith("/")) {
                cleanUrl = cleanUrl.dropLast(1)
            }

            // Normalize Twitter / X status links
            val twitterMatch = twitterRegex.find(cleanUrl)
            if (twitterMatch != null) {
                val tweetId = twitterMatch.groupValues[1]
                return "https://x.com/status/$tweetId"
            }

            // Normalize Instagram posts, reels, and TV links
            val instagramMatch = instagramRegex.find(cleanUrl)
            if (instagramMatch != null) {
                val shortcode = instagramMatch.groupValues[1]
                return "https://instagram.com/reel/$shortcode"
            }

            // Re-apply video id parameter for youtube and facebook watch links
            if ((isYouTubeWatch || isFacebookWatch) && !videoIdParam.isNullOrEmpty()) {
                val base = cleanUrl
                    .replace("https://www.youtube.com", "https://youtube.com")
                    .replace("https://m.youtube.com", "https://youtube.com")
                    .replace("https://www.facebook.com", "https://facebook.com")
                return "$base?v=$videoIdParam"
            }

            // Generic normalization fallback
            return cleanUrl
                .replace("https://www.twitter.com", "https://x.com")
                .replace("https://twitter.com", "https://x.com")
                .replace("https://mobile.twitter.com", "https://x.com")
                .replace("https://www.instagram.com", "https://instagram.com")
        } catch (e: Exception) {
            return url
        }
    }
}
