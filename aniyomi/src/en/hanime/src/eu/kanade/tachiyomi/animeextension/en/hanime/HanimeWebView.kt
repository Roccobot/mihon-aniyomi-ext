package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The site's own page, run in the app's WebView.
 *
 * WHY THIS EXISTS, because it is not the obvious way to write a source: hanime.tv's API
 * (v11) accepts neither plain requests nor requests this extension could sign. Catalog
 * calls carry a signature produced by a WASM module the site ships in its player bundle,
 * and the stream handshake wraps its body in a sealed token and answers with an encrypted
 * header. Reproducing any of that would mean lifting the site's own compiled module and
 * keys, which this extension does not do.
 *
 * So the site's client does its own work, in a real browser, with the user's own session,
 * and this class only:
 *  - reads the DOM the page rendered (`renderedHtml`), and
 *  - watches which media url the page's player ends up requesting (`interceptMedia`).
 *
 * ⚠️ Everything here runs on the MAIN thread and blocks the calling one: a WebView cannot
 * be touched from a background thread, while sources are called from IO threads. Hence the
 * handler + latch dance, and hence every entry point takes a timeout.
 */
class HanimeWebView(private val userAgent: String) {

    private val context = Injekt.get<Application>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Loads [url] and returns the DOM once it contains a match for [waitFor], or whatever
     * it holds when [timeoutMs] runs out. Polling the DOM rather than waiting for
     * `onPageFinished` is deliberate: the site renders its lists after the page is
     * "finished", so that callback fires far too early.
     */
    fun renderedHtml(url: String, waitFor: Regex, timeoutMs: Long = DEFAULT_TIMEOUT): String {
        var html = ""
        val latch = CountDownLatch(1)
        var webView: WebView? = null

        handler.post {
            val view = newWebView()
            webView = view
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    pollDom(view, waitFor, latch) { html = it }
                }
            }
            view.loadUrl(url)
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        destroy(webView)
        return html
    }

    /**
     * Loads [url], lets the page's player run, and returns the first request url matching
     * [waitFor] together with the headers the page sent with it. The headers matter: a
     * signed media url is usually served only with the site's own Referer.
     */
    fun interceptMedia(
        url: String,
        waitFor: Regex,
        timeoutMs: Long = DEFAULT_TIMEOUT,
    ): Pair<String, Map<String, String>>? {
        var found: Pair<String, Map<String, String>>? = null
        val latch = CountDownLatch(1)
        var webView: WebView? = null

        handler.post {
            val view = newWebView()
            webView = view
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val candidate = request.url.toString()
                    if (found == null && waitFor.containsMatchIn(candidate)) {
                        found = candidate to request.requestHeaders
                        latch.countDown()
                    }
                    // null: the request goes through untouched. This class observes, it
                    // does not alter what the page does.
                    return null
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    // Some players only ask for the stream once playback starts, and a
                    // WebView will not start on its own even with autoplay allowed:
                    // clicking play is what a person would do on this same page.
                    view.evaluateJavascript(CLICK_PLAY, null)
                }
            }
            view.loadUrl(url)
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        destroy(webView)
        return found
    }

    private fun pollDom(view: WebView, waitFor: Regex, latch: CountDownLatch, onFound: (String) -> Unit) {
        var attempts = 0
        lateinit var poll: () -> Unit
        poll = {
            view.evaluateJavascript(DOM_DUMP) { raw ->
                val html = raw.unescapeJsString()
                if (waitFor.containsMatchIn(html)) {
                    onFound(html)
                    latch.countDown()
                } else if (++attempts < MAX_POLLS) {
                    handler.postDelayed({ poll() }, POLL_INTERVAL)
                } else {
                    // Hand back what there is: a caller with an empty list says more than
                    // a silent timeout, and the DOM is what the next fix is read from.
                    onFound(html)
                    latch.countDown()
                }
            }
        }
        poll()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
        // Not a trick: it is the switch that lets a page start its own video without a
        // tap, which is what makes the player ask for its stream.
        settings.mediaPlaybackRequiresUserGesture = false
    }

    private fun destroy(webView: WebView?) {
        webView ?: return
        handler.post {
            webView.stopLoading()
            webView.destroy()
        }
    }

    /** `evaluateJavascript` hands back a JSON string literal, not the string. */
    private fun String.unescapeJsString(): String =
        removeSurrounding("\"")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")

    companion object {
        const val DEFAULT_TIMEOUT = 30_000L
        private const val POLL_INTERVAL = 400L
        private const val MAX_POLLS = 60

        private const val DOM_DUMP = "document.documentElement.outerHTML"
        private const val CLICK_PLAY =
            """(function () {
                 var v = document.querySelector('video');
                 if (v) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function () {}); }
                 var b = document.querySelector('[class*=play],[aria-label*=lay],button');
                 if (b) b.click();
               })();"""
    }
}
