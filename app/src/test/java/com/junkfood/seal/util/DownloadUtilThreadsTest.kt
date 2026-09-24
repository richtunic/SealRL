package com.junkfood.seal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUtilThreadsTest {
    @Test
    fun `extracts shortcode from canonical Threads post`() {
        assertEquals(
            "Dbk_iqyjnTD",
            DownloadUtil.extractThreadsPostShortcode(
                "https://www.threads.com/@gallery_cosplayer/post/Dbk_iqyjnTD?xmt=test"
            ),
        )
    }

    @Test
    fun `extracts shortcode from legacy Threads short URL`() {
        assertEquals(
            "Dbk_iqyjnTD",
            DownloadUtil.extractThreadsPostShortcode("https://www.threads.net/t/Dbk_iqyjnTD/"),
        )
    }

    @Test
    fun `share token is not mistaken for media shortcode`() {
        assertNull(
            DownloadUtil.extractThreadsPostShortcode("https://www.threads.com/share/_rDBXzEK3/")
        )
    }

    @Test
    fun `accepts the reported share link and rejects lookalike hosts`() {
        assertTrue(ThreadsWebViewResolver.isThreadsUrl("https://www.threads.com/share/BAT7JlJ5IW/"))
        assertFalse(ThreadsWebViewResolver.isThreadsUrl("https://threads.com.example.org/share/BAT7JlJ5IW/"))
        assertFalse(ThreadsWebViewResolver.isThreadsUrl("http://www.threads.com/share/BAT7JlJ5IW/"))
    }

    @Test
    fun `only accepts HTTPS MP4 media from Meta CDN hosts`() {
        assertTrue(ThreadsWebViewResolver.isSafeVideoUrl("https://scontent.cdninstagram.com/video.mp4?token=abc"))
        assertTrue(ThreadsWebViewResolver.isSafeVideoUrl("https://video.xx.fbcdn.net/video.MP4?token=abc"))
        assertFalse(ThreadsWebViewResolver.isSafeVideoUrl("https://fbcdn.net.example.org/video.mp4"))
        assertFalse(ThreadsWebViewResolver.isSafeVideoUrl("http://video.xx.fbcdn.net/video.mp4"))
        assertFalse(ThreadsWebViewResolver.isSafeVideoUrl("https://video.xx.fbcdn.net/image.jpg"))
    }

    @Test
    fun `parses the WebView result and keeps the canonical post`() {
        val json = """{"url":"https://video.xx.fbcdn.net/video.mp4?x=1","webpageUrl":"https://www.threads.com/@thecanadiancookie/post/DdrkStqEozs?xmt=abc","title":"Video","thumbnail":null}"""
        val raw = kotlinx.serialization.json.JsonPrimitive(json).toString()
        val media = ThreadsWebViewResolver.parseResult(raw)
        assertEquals("https://www.threads.com/@thecanadiancookie/post/DdrkStqEozs?xmt=abc", media?.webpageUrl)
        assertEquals("https://video.xx.fbcdn.net/video.mp4?x=1", media?.url)
        assertNull(ThreadsWebViewResolver.parseResult("null"))
    }
}
