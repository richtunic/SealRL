package com.junkfood.seal.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.junkfood.seal.R
import com.junkfood.seal.util.BulkUrlParser

fun platformLogoForDownload(videoUrl: String, extractor: String): String? {
    val fromUrl = BulkUrlParser.getPlatformName(videoUrl)
    if (fromUrl == "Story") return "Instagram"
    if (fromUrl in listOf("Instagram", "X", "TikTok", "YouTube", "Threads", "Facebook")) return fromUrl
    val source = extractor.lowercase()
    return when {
        "instagram" in source -> "Instagram"
        "twitter" in source || source == "x" -> "X"
        "tiktok" in source -> "TikTok"
        "youtube" in source -> "YouTube"
        "threads" in source -> "Threads"
        "facebook" in source -> "Facebook"
        else -> null
    }
}

@Composable
fun PlatformIcon(platform: String, modifier: Modifier = Modifier) {
    val resource = when (platform.lowercase()) {
        "instagram" -> R.drawable.platform_instagram
        "x" -> R.drawable.platform_x
        "tiktok" -> R.drawable.platform_tiktok
        "youtube" -> R.drawable.platform_youtube
        "threads" -> R.drawable.platform_threads
        "facebook" -> R.drawable.platform_facebook
        else -> return
    }
    Image(painter = painterResource(resource), contentDescription = platform, modifier = modifier)
}
