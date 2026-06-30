package com.junkfood.seal.ui.page.settings.network

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView as AndroidWebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.web.AccompanistWebChromeClient
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.google.android.material.R
import com.junkfood.seal.util.BRAVE_CHROMIUM_USER_AGENT
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.USER_AGENT_STRING
import com.junkfood.seal.util.connectWithDelimiter

private const val TAG = "WebViewPage"
private const val SESSION_COOKIE_EXPIRY = 1893456000L

private fun CookieManager.hasCookie(url: String, name: String): Boolean =
    getCookie(url)
        ?.split(";")
        ?.map { it.trim().substringBefore("=") }
        ?.any { it == name } == true

private fun CookieManager.persistentStartUrl(url: String): String {
    return when {
        url.contains("facebook.com", ignoreCase = true) || url.contains("fb.com", ignoreCase = true) -> {
            val hasFacebookSession =
                hasCookie("https://www.facebook.com", "c_user") ||
                    hasCookie("https://m.facebook.com", "c_user") ||
                    hasCookie("https://facebook.com", "c_user")
            if (hasFacebookSession) {
                "https://m.facebook.com/"
            } else {
                "https://m.facebook.com/login/"
            }
        }

        url.contains("x.com", ignoreCase = true) || url.contains("twitter.com", ignoreCase = true) -> {
            val hasXSession =
                (hasCookie("https://x.com", "auth_token") && hasCookie("https://x.com", "ct0")) ||
                    (hasCookie("https://twitter.com", "auth_token") && hasCookie("https://twitter.com", "ct0"))
            if (hasXSession) {
                "https://x.com/home"
            } else {
                "https://x.com/i/flow/login"
            }
        }

        url.contains("instagram.com", ignoreCase = true) -> {
            if (hasCookie("https://www.instagram.com", "sessionid")) {
                "https://www.instagram.com/"
            } else {
                url
            }
        }

        url.contains("threads.com", ignoreCase = true) || url.contains("threads.net", ignoreCase = true) -> {
            if (hasCookie("https://www.threads.com", "sessionid")) {
                "https://www.threads.com/"
            } else {
                url
            }
        }

        else -> url
    }
}

data class Cookie(
    val domain: String = "",
    val name: String = "",
    val value: String = "",
    val includeSubdomains: Boolean = true,
    val path: String = "/",
    val secure: Boolean = true,
    val expiry: Long = SESSION_COOKIE_EXPIRY,
) {
    constructor(
        url: String,
        name: String,
        value: String,
    ) : this(domain = url.toDomain(), name = name, value = value)

    fun toNetscapeCookieString(): String {
        return connectWithDelimiter(
            domain,
            includeSubdomains.toString().uppercase(),
            path,
            secure.toString().uppercase(),
            expiry.toString(),
            name,
            value,
            delimiter = "\u0009",
        )
    }
}

private val domainRegex = Regex("""http(s)?://(\w*(www|m|account|sso))?|/.*""")

private fun String.toDomain(): String {
    return this.replace(domainRegex, "")
}

private fun makeCookie(url: String, cookieString: String): Cookie {
    cookieString.split("=", limit = 2).run {
        return Cookie(url = url, name = first(), value = getOrElse(1) { "" })
    }
}

private fun String.cookieHost(): String =
    removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .substringBefore(":")

private fun String.netscapeCookieDomain(): String {
    val host = cookieHost().removePrefix("www.").removePrefix("m.").removePrefix("mbasic.").removePrefix("web.")
    return if (host.startsWith(".")) host else ".$host"
}

private fun cookieDomainsForUrl(url: String): List<String> =
    when {
        url.contains("facebook.com", ignoreCase = true) || url.contains("fb.com", ignoreCase = true) ->
            listOf(
                "https://facebook.com",
                "https://www.facebook.com",
                "https://m.facebook.com",
                "https://mbasic.facebook.com",
                "https://web.facebook.com",
            )

        url.contains("instagram.com", ignoreCase = true) ->
            listOf("https://instagram.com", "https://www.instagram.com")

        url.contains("threads.com", ignoreCase = true) || url.contains("threads.net", ignoreCase = true) ->
            listOf(
                "https://threads.com",
                "https://www.threads.com",
                "https://threads.net",
                "https://www.threads.net",
                "https://instagram.com",
                "https://www.instagram.com",
            )

        url.contains("x.com", ignoreCase = true) || url.contains("twitter.com", ignoreCase = true) ->
            listOf("https://x.com", "https://twitter.com", "https://mobile.twitter.com")

        else -> listOf(url)
    }

private fun CookieManager.exportCookiesForUrl(url: String): String {
    val cookies =
        cookieDomainsForUrl(url)
            .flatMap { domainUrl ->
                getCookie(domainUrl)
                    ?.split(";")
                    ?.mapNotNull { rawCookie ->
                        val parts = rawCookie.trim().split("=", limit = 2)
                        val name = parts.firstOrNull()?.trim().orEmpty()
                        val value = parts.getOrNull(1).orEmpty()
                        if (name.isBlank() || value.isBlank()) {
                            null
                        } else {
                            Cookie(
                                domain = domainUrl.netscapeCookieDomain(),
                                name = name,
                                value = value,
                            )
                        }
                    }
                    .orEmpty()
            }
            .distinctBy { "${it.domain}:${it.name}" }

    return if (cookies.isEmpty()) "" else cookies.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
        acc.append(cookie.toNetscapeCookieString()).append("\n")
    }.toString()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPage(cookiesViewModel: CookiesViewModel, onDismissRequest: () -> Unit) {

    val state by cookiesViewModel.stateFlow.collectAsStateWithLifecycle()
    Log.d(TAG, state.editingCookieProfile.url)

    val cookieManager = CookieManager.getInstance()
    val cookieSet = remember { mutableSetOf<Cookie>() }
    val profileUrl = state.editingCookieProfile.url
    val websiteUrl = remember(state.editingCookieProfile.url) {
        cookieManager.persistentStartUrl(state.editingCookieProfile.url)
    }
    val webViewState = rememberWebViewState(websiteUrl)

    DisposableEffect(Unit) {
        onDispose {
            cookieManager.flush()
            cookieManager.exportCookiesForUrl(profileUrl).takeIf { it.isNotBlank() }?.let {
                FileUtil.writeContentToFile(it, com.junkfood.seal.App.context.getCookiesFile())
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(webViewState.pageTitle.toString(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { onDismissRequest() }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            stringResource(id = androidx.appcompat.R.string.abc_action_mode_done),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(id = R.string.abc_action_mode_done))
                    }
                },
            )
        },
    ) { paddingValues ->
        val webViewClient = remember {
            object : AccompanistWebViewClient() {
                override fun onPageFinished(view: AndroidWebView, url: String?) {
                    super.onPageFinished(view, url)
                    cookieManager.flush()
                    cookieManager.exportCookiesForUrl(profileUrl).takeIf { it.isNotBlank() }?.let {
                        FileUtil.writeContentToFile(it, view.context.getCookiesFile())
                    }
                    if (url.isNullOrEmpty()) return
                }

                override fun shouldOverrideUrlLoading(
                    view: AndroidWebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    return if (request?.url?.scheme?.contains("http") == true)
                        super.shouldOverrideUrlLoading(view, request)
                    else true
                }
            }
        }
        val webViewChromeClient = remember {
            object : AccompanistWebChromeClient() {
                override fun onCreateWindow(
                    view: AndroidWebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val parent = view ?: return false
                    val popup = AndroidWebView(parent.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(true)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = BRAVE_CHROMIUM_USER_AGENT
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        setWebViewClient(
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    popupView: AndroidWebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val targetUrl = request?.url?.toString() ?: return false
                                    parent.loadUrl(targetUrl)
                                    return true
                                }
                            }
                        )
                    }
                    val transport = resultMsg?.obj as? AndroidWebView.WebViewTransport ?: return false
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
        }
        WebView(
            state = webViewState,
            client = webViewClient,
            chromeClient = webViewChromeClient,
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            captureBackPresses = true,
            factory = { context ->
                AndroidWebView(context).apply {
                    settings.run {
                        javaScriptCanOpenWindowsAutomatically = true
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                        userAgentString = BRAVE_CHROMIUM_USER_AGENT
                        USER_AGENT_STRING.updateString(BRAVE_CHROMIUM_USER_AGENT)
                    }
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                }
            },
        )
    }
}
