package com.junkfood.seal.ui.page.settings.network

import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewPageCookieTest {
    @Test
    fun `social cookie export keeps Meta sessions together`() {
        val domains = cookieDomainsToPersistForUrl("https://m.facebook.com/login/")

        assertTrue(domains.contains("https://www.facebook.com"))
        assertTrue(domains.contains("https://www.instagram.com"))
        assertTrue(domains.contains("https://www.threads.com"))
    }

    @Test
    fun `Threads session also checks Instagram cookie domains`() {
        val domains = cookieDomainsForUrl("https://www.threads.com")

        assertTrue(domains.contains("https://www.threads.com"))
        assertTrue(domains.contains("https://www.instagram.com"))
    }

    @Test
    fun `refreshed cookies update matching entries and preserve other sites`() {
        val existing =
            COOKIE_HEADER +
                ".facebook.com\tTRUE\t/\tTRUE\t1\tc_user\told-user\n" +
                ".youtube.com\tTRUE\t/\tTRUE\t1\tSID\tkeep-youtube\n"
        val refreshed =
            COOKIE_HEADER +
                ".facebook.com\tTRUE\t/\tTRUE\t2\tc_user\tnew-user\n" +
                ".instagram.com\tTRUE\t/\tTRUE\t2\tsessionid\tnew-instagram\n"

        val merged = mergeNetscapeCookieContent(existing, refreshed)

        assertTrue(merged.contains("\tc_user\tnew-user"))
        assertTrue(merged.contains("\tSID\tkeep-youtube"))
        assertTrue(merged.contains("\tsessionid\tnew-instagram"))
        assertEquals(1, merged.lineSequence().count { it.contains("\tc_user\t") })
    }

    @Test
    fun `HttpOnly cookie is replaced by its refreshed equivalent`() {
        val existing =
            COOKIE_HEADER +
                "#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t1\tsessionid\told-session\n"
        val refreshed =
            COOKIE_HEADER +
                ".instagram.com\tTRUE\t/\tTRUE\t2\tsessionid\tnew-session\n"

        val merged = mergeNetscapeCookieContent(existing, refreshed)

        assertTrue(merged.contains("\tsessionid\tnew-session"))
        assertEquals(1, merged.lineSequence().count { it.contains("\tsessionid\t") })
    }
}
