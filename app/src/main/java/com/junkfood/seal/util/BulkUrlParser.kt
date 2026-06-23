package com.junkfood.seal.util

object BulkUrlParser {
    private val urlRegex = Regex("""https?://[^\s]+""")
    private val twitterRegex = Regex("""https?://(?:[a-zA-Z0-9\-]+\.)?(?:twitter|x)\.com/[a-zA-Z0-9_]+/status/(\d+)""")
    private val instagramRegex = Regex("""https?://(?:[a-zA-Z0-9\-]+\.)?instagram\.com/(?:p|reel|tv)/([a-zA-Z0-9_\-]+)""")

    fun parse(input: String): List<String> {
        return urlRegex.findAll(input)
            .map { it.value.trim() }
            .map { cleanUrl(it) }
            .map { normalizeUrl(it) }
            .filter { isSupported(it) }
            .distinct()
            .toList()
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
        if (!urlRegex.containsMatchIn(insertedText)) return currentText
        if (insertedText.endsWith('\n') || insertedText.endsWith('\r')) return currentText
        if (insertEnd < currentText.length && currentText[insertEnd] == '\n') return currentText

        return buildString {
            append(currentText.substring(0, insertEnd))
            append('\n')
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
            var clean = url.trim().lowercase()
            if (clean.contains("?")) {
                clean = clean.substringBefore("?")
            }
            while (clean.endsWith("/")) {
                clean = clean.dropLast(1)
            }

            // Normalize Twitter / X status links
            val twitterMatch = twitterRegex.find(clean)
            if (twitterMatch != null) {
                val tweetId = twitterMatch.groupValues[1]
                return "https://x.com/status/$tweetId"
            }

            // Normalize Instagram posts, reels, and TV links
            val instagramMatch = instagramRegex.find(clean)
            if (instagramMatch != null) {
                val shortcode = instagramMatch.groupValues[1]
                return "https://instagram.com/reel/$shortcode"
            }

            // Generic normalization fallback
            return clean
                .replace("https://www.twitter.com", "https://x.com")
                .replace("https://twitter.com", "https://x.com")
                .replace("https://mobile.twitter.com", "https://x.com")
                .replace("https://www.instagram.com", "https://instagram.com")
        } catch (e: Exception) {
            return url
        }
    }
}
