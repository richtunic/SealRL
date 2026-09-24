package com.junkfood.seal.util

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.junkfood.seal.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/** Threads share links are resolved by its JavaScript app, not by an HTTP redirect. */
object ThreadsWebViewResolver {
    data class Media(val url: String, val webpageUrl: String, val title: String, val thumbnail: String?)

    internal fun isThreadsUrl(url: String): Boolean =
        runCatching {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme == "https" && (host == "threads.com" || host == "www.threads.com" ||
                host == "threads.net" || host == "www.threads.net"
            )
        }.getOrDefault(false)

    internal fun isSafeVideoUrl(url: String): Boolean =
        runCatching {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme == "https" &&
                (host == "cdninstagram.com" || host.endsWith(".cdninstagram.com") ||
                    host == "fbcdn.net" || host.endsWith(".fbcdn.net")) &&
                uri.path.orEmpty().endsWith(".mp4", ignoreCase = true)
        }.getOrDefault(false)

    // Find the nearest post link for each video. This excludes videos in replies to the post.
    internal const val VIDEO_SCRIPT = """(() => {
      const target = location.pathname;
      if (!/^\/@[^/]+\/post\/[A-Za-z0-9_-]+/.test(target)) return null;
      for (const video of document.querySelectorAll('video')) {
        let parent = video.parentElement;
        for (let depth = 0; depth < 30 && parent; depth++, parent = parent.parentElement) {
          const links = [...parent.querySelectorAll('a[href*="/post/"]')]
            .filter(a => { try { return !new URL(a.href).search; } catch (_) { return false; } });
          if (!links.length) continue;
          if (new URL(links[0].href).pathname === target) {
            const source = video.currentSrc || video.src || video.querySelector('source')?.src;
            if (!source) return null;
            return JSON.stringify({
              url: source,
              webpageUrl: location.href,
              title: document.querySelector('meta[property="og:description"]')?.content || 'Threads video',
              thumbnail: document.querySelector('meta[property="og:image"]')?.content || null
            });
          }
          break;
        }
      }
      return null;
    })()"""

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveVideo(url: String): Media? {
        if (!isThreadsUrl(url)) return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(App.context)
                    val handler = Handler(Looper.getMainLooper())
                    var done = false
                    val poll = object : Runnable {
                        override fun run() {
                            if (done || !continuation.isActive) return
                            webView.evaluateJavascript(VIDEO_SCRIPT) { raw ->
                                if (done || !continuation.isActive) return@evaluateJavascript
                                val media = parseResult(raw)
                                if (media != null) {
                                    done = true
                                    continuation.resume(media)
                                    handler.removeCallbacks(this)
                                    webView.stopLoading()
                                    webView.destroy()
                                } else {
                                    handler.postDelayed(this, 500L)
                                }
                            }
                        }
                    }
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = BRAVE_CHROMIUM_USER_AGENT
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            handler.removeCallbacks(poll)
                            handler.post(poll)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            return request.isForMainFrame && !isThreadsUrl(request.url.toString())
                        }
                    }
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    )
                    webView.layout(0, 0, 1080, 1920)
                    continuation.invokeOnCancellation {
                        handler.post {
                            done = true
                            handler.removeCallbacks(poll)
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                    webView.loadUrl(url)
                    handler.post(poll)
                }
            }
        }
    }

    internal fun parseResult(raw: String?): Media? = runCatching {
        if (raw.isNullOrBlank() || raw == "null") return null
        val unquoted = Json.parseToJsonElement(raw).jsonPrimitive.content
        val json = Json.parseToJsonElement(unquoted).jsonObject
        val videoUrl = json["url"]?.jsonPrimitive?.content ?: return null
        val webpageUrl = json["webpageUrl"]?.jsonPrimitive?.content ?: return null
        if (!isSafeVideoUrl(videoUrl) || !isThreadsUrl(webpageUrl) ||
            !webpageUrl.contains("/post/")) return null
        Media(
            url = videoUrl,
            webpageUrl = webpageUrl,
            title = json["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(160).ifBlank { "Threads video" },
            thumbnail = json["thumbnail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") },
        )
    }.getOrNull()
}
