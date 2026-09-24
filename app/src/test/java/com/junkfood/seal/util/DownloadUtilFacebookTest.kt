package com.junkfood.seal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUtilFacebookTest {
    @Test
    fun `recognizes Facebook shared media URLs`() {
        assertTrue(DownloadUtil.isFacebookShareUrl("https://www.facebook.com/share/r/abc123/"))
        assertTrue(DownloadUtil.isFacebookShareUrl("https://m.facebook.com/share/v/abc123/"))
        assertTrue(DownloadUtil.isFacebookShareUrl("https://facebook.com/share/abc123/"))
        assertTrue(DownloadUtil.isFacebookShareUrl("https://fb.watch/abc123/"))
    }

    @Test
    fun `does not classify unrelated hosts as Facebook shares`() {
        assertFalse(DownloadUtil.isFacebookShareUrl("https://example.com/facebook.com/share/r/abc"))
        assertFalse(DownloadUtil.isFacebookShareUrl("https://notfacebook.com/share/r/abc"))
    }

    @Test
    fun `accepts canonical Facebook video and story destinations`() {
        assertTrue(DownloadUtil.isCanonicalFacebookMediaUrl("https://www.facebook.com/reel/123/"))
        assertTrue(
            DownloadUtil.isCanonicalFacebookMediaUrl(
                "https://www.facebook.com/stories/example/123456789/"
            )
        )
        assertTrue(
            DownloadUtil.isCanonicalFacebookMediaUrl(
                "https://www.facebook.com/example/videos/123456789/"
            )
        )
        assertTrue(
            DownloadUtil.isCanonicalFacebookMediaUrl(
                "https://www.facebook.com/story.php?story_fbid=123&id=456"
            )
        )
    }

    @Test
    fun `rejects login and unresolved share destinations`() {
        assertFalse(
            DownloadUtil.isCanonicalFacebookMediaUrl(
                "https://www.facebook.com/login/?next=%2Freel%2F123"
            )
        )
        assertFalse(
            DownloadUtil.isCanonicalFacebookMediaUrl("https://www.facebook.com/share/r/abc123/")
        )
    }

    @Test
    fun `recovers canonical story from login redirect`() {
        assertEquals(
            "https://www.facebook.com/stories/example/123456789/",
            DownloadUtil.extractCanonicalFacebookRedirectTarget(
                "https://www.facebook.com/login/?next=%2Fstories%2Fexample%2F123456789%2F"
            ),
        )
    }

    @Test
    fun `recovers canonical reel from nested Facebook redirect`() {
        assertEquals(
            "https://www.facebook.com/reel/123456789/",
            DownloadUtil.extractCanonicalFacebookRedirectTarget(
                "https://l.facebook.com/l.php?u=https%253A%252F%252Fwww.facebook.com%252Freel%252F123456789%252F"
            ),
        )
    }

    @Test
    fun `converts shared Facebook story to extractor supported URL`() {
        assertEquals(
            "https://www.facebook.com/story.php?story_fbid=1091480556544142&id=109103901963795",
            DownloadUtil.facebookUrlForExtractor(
                "https://web.facebook.com/stories/109103901963795/" +
                    "UzpfSVNDOjEwOTE0ODA1NTY1NDQxNDI=/?view_single=1"
            ),
        )
    }

    @Test
    fun `leaves malformed Facebook story URL unchanged`() {
        val malformed = "https://www.facebook.com/stories/example/not-base64/"

        assertEquals(malformed, DownloadUtil.facebookUrlForExtractor(malformed))
    }
}
