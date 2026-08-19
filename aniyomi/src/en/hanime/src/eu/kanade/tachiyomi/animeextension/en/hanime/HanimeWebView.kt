package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
 * and the stream handshake seals its body with a key from that same bundle and answers with
 * an encrypted header. Reproducing any of that would mean lifting the site's own compiled
 * module and keys, which this extension does not do.
 *
 * So the site's client does its own work, in a real browser, with the user's own session,
 * and this class only:
 *  - reads the DOM the page rendered (`renderedHtml`), and
 *  - watches which media url the page's player ends up requesting (`interceptMedia`).
 *
 * ⚠️ Everything here runs on the MAIN thread and blocks the calling one: a WebView cannot
 * be touched from a background thread, while sources are called from IO threads. Hence the
 * handler + latch dance, and hence every entry point takes a timeout.
 *
 * Every step reports to [HanimeLog]: the failure worth diagnosing is a player that sits
 * still without an error, and that one leaves no other trace.
 */
class HanimeWebView(private val userAgent: String) {

    private val context = Injekt.get<Application>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Loads [url] and returns the DOM once it contains a match for [waitFor], or whatever it
     * holds when [timeoutMs] runs out. Polling the DOM rather than waiting for
     * `onPageFinished` is deliberate: the site renders its lists after the page is
     * "finished", so that callback fires far too early.
     */
    fun renderedHtml(url: String, waitFor: Regex, timeoutMs: Long = DEFAULT_TIMEOUT): String {
        var html = ""
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        HanimeLog.log("DOM  load   $url")

        handler.post {
            val view = newWebView()
            webView = view
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    HanimeLog.log("DOM  loaded $finishedUrl")
                    pollDom(view, waitFor, latch) { html = it }
                }
            }
            view.loadUrl(url)
        }

        val inTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        destroy(webView)
        HanimeLog.log("DOM  ${if (inTime) "done" else "TIMEOUT"}, ${html.length} chars, pattern ${if (waitFor.containsMatchIn(html)) "found" else "NOT found"}")
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
    ): MediaResult {
        var found: Pair<String, Map<String, String>>? = null
        val seen = mutableListOf<String>()
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        HanimeLog.log("PLAY load   $url")

        handler.post {
            val view = newWebView()
            webView = view
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val candidate = request.url.toString()
                    // ⚠️ Everything not obviously static is kept, and it is not book-keeping
                    // for its own sake: when no stream turns up, this list is the only way to
                    // learn what the player DID ask for, and the next fix is read from it.
                    if (!STATIC_ASSET.containsMatchIn(candidate)) {
                        if (seen.size < MAX_SEEN) seen += candidate
                        HanimeLog.log("PLAY req    ${request.method} $candidate")
                    }
                    if (found == null && waitFor.containsMatchIn(candidate)) {
                        found = candidate to request.requestHeaders
                        HanimeLog.log("PLAY MATCH  $candidate")
                        HanimeLog.log("PLAY hdrs   ${request.requestHeaders}")
                        latch.countDown()
                    }
                    // null: the request goes through untouched. This class observes, it does
                    // not alter what the page does.
                    return null
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    HanimeLog.log("PLAY loaded $finishedUrl")
                    // ⚠️ An inventory BEFORE clicking anything: the first attempt clicked five
                    // times on a playlist element, because `[class*=play]` also matches
                    // `playlist`. What is actually on the page has to be measured, not assumed.
                    view.evaluateJavascript(DOM_INVENTORY) { HanimeLog.log("PLAY dom    $it") }
                    // Some players only ask for the stream once playback starts, and a
                    // WebView will not start on its own even with autoplay allowed: clicking
                    // play is what a person would do on this same page. Repeated a few
                    // times because the player is mounted after the page settles.
                    clickPlay(view, 1)
                }
            }
            view.loadUrl(url)
        }

        val inTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        destroy(webView)
        HanimeLog.log(
            "PLAY ${if (inTime) "done" else "TIMEOUT"}, ${seen.size} non-static requests, " +
                "stream ${if (found != null) "found" else "NOT found"}",
        )
        return MediaResult(found, seen.toList())
    }

    /** [hit] is the stream when there is one; [seen] is what the page asked for either way. */
    class MediaResult(
        val hit: Pair<String, Map<String, String>>?,
        val seen: List<String>,
    )

    private fun clickPlay(view: WebView, attempt: Int) {
        view.evaluateJavascript(CLICK_PLAY) { outcome ->
            HanimeLog.log("PLAY click$attempt $outcome")
        }
        // The page carries an 'MP4Download' control: on a free account it may lead to the
        // premium modal, or it may hand over a direct address. Only trying says which, and
        // the requests that follow are in the trace either way.
        if (attempt == DOWNLOAD_ATTEMPT) {
            view.evaluateJavascript(CLICK_DOWNLOAD) { outcome ->
                HanimeLog.log("PLAY dl     $outcome")
            }
        }
        if (attempt < CLICK_ATTEMPTS) {
            handler.postDelayed({ clickPlay(view, attempt + 1) }, CLICK_INTERVAL)
        }
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
                    // Hand back what there is: a caller with an empty list says more than a
                    // silent timeout, and the DOM is what the next fix is read from.
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
        // Not a trick: it is the switch that lets a page start its own video without a tap,
        // which is what makes the player ask for its stream.
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // The site's own errors explain most silent failures ('not signed in',
                // 'handshake failed'), and they are invisible anywhere else.
                HanimeLog.log("JS   ${message.messageLevel()} ${message.message()}")
                return true
            }
        }
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
        private const val MAX_SEEN = 60
        private const val CLICK_ATTEMPTS = 5
        private const val CLICK_INTERVAL = 2_500L

        private val STATIC_ASSET =
            Regex("""\.(png|jpe?g|webp|gif|svg|ico|css|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

        private const val DOM_DUMP = "document.documentElement.outerHTML"
        private const val DOWNLOAD_ATTEMPT = 3

        private const val CLICK_DOWNLOAD =
            """(function () {
                 var nodes = document.querySelectorAll('button, [role=button], a');
                 for (var i = 0; i < nodes.length; i++) {
                   var n = nodes[i];
                   var hay = (n.getAttribute('aria-label') || '') + ' ' + (n.textContent || '').slice(0, 30);
                   if (!/download|mp4/i.test(hay)) continue;
                   n.click();
                   return 'clicked=' + n.tagName + ' [' + hay.trim().slice(0, 30) + '] href=' + (n.getAttribute('href') || '-');
                 }
                 return 'noDownloadControl';
               })();"""

        // Returns a short report instead of nothing: 'which element did it find, did play()
        // resolve', which is exactly what a stuck player refuses to tell.
        private const val CLICK_PLAY =
            """(function () {
                 var out = [];
                 var v = document.querySelector('video');
                 out.push('video=' + (v ? (v.currentSrc || v.src || 'no-src') : 'none'));
                 if (v) {
                   v.muted = true;
                   try { var p = v.play(); if (p && p.then) { p.then(function () {}, function () {}); } } catch (e) { out.push('playErr=' + e.name); }
                   out.push('paused=' + v.paused + ' ready=' + v.readyState + ' net=' + v.networkState);
                   return out.join(' ');
                 }
                 // ⚠️ 'playlist' must NOT match: the first version clicked that instead of the
                 // play button, five times, and reported success while doing nothing.
                 var nodes = document.querySelectorAll('button, [role=button], a, div[class], span[class]');
                 var wanted = /(^|[^a-z])(play|watch|guarda|riproduci)([^a-z]|$)/i;
                 var hits = 0;
                 for (var i = 0; i < nodes.length && hits < 3; i++) {
                   var n = nodes[i];
                   var hay = (n.getAttribute('aria-label') || '') + ' ' + (n.getAttribute('title') || '') +
                             ' ' + (n.className || '') + ' ' + (n.textContent || '').slice(0, 40);
                   // ⚠️ Measured exclusions, both learned from the trace: `playlist` matched
                   // the first version's selector, and `watch-later` matched the word 'watch'.
                   // Neither is a play button, and clicking them reported success.
                   if (/playlist|watch.?later|pointer-events-none/i.test(hay) || !wanted.test(hay)) continue;
                   n.click();
                   hits++;
                   out.push('clicked=' + n.tagName + '.' + String(n.className).slice(0, 40));
                 }
                 if (!hits) {
                   // Nothing named play: the poster itself is often the target.
                   var poster = document.querySelector('[class*=player], [id*=player], [class*=poster], main img');
                   if (poster) { poster.click(); out.push('clickedFallback=' + poster.tagName + '.' + String(poster.className).slice(0, 40)); }
                   else out.push('nothingToClick');
                 }
                 out.push('iframes=' + document.querySelectorAll('iframe').length);
                 return out.join(' ');
               })();"""

        // The measurement that says what to click and whether a player exists at all.
        private const val DOM_INVENTORY =
            """(function () {
                 var r = ['video=' + document.querySelectorAll('video').length,
                          'iframe=' + document.querySelectorAll('iframe').length,
                          'canvas=' + document.querySelectorAll('canvas').length];
                 var players = document.querySelectorAll('[class*=player], [id*=player], [class*=plyr], [class*=jw]');
                 var seen = [];
                 for (var i = 0; i < players.length && i < 8; i++) {
                   seen.push(players[i].tagName + '.' + String(players[i].className).slice(0, 30));
                 }
                 r.push('players[' + players.length + ']=' + seen.join(','));
                 var btns = document.querySelectorAll('button, [role=button]');
                 var labels = [];
                 for (var j = 0; j < btns.length && labels.length < 10; j++) {
                   var t = (btns[j].getAttribute('aria-label') || btns[j].textContent || '').trim().slice(0, 22);
                   if (t) labels.push(t);
                 }
                 r.push('buttons[' + btns.length + ']=' + labels.join('|'));
                 // Custom elements and shadow roots: a `video` inside a shadow tree is
                 // invisible to querySelector, and this page is built of web components.
                 var all = document.querySelectorAll('*');
                 var customs = [], shadows = [];
                 for (var k = 0; k < all.length; k++) {
                   var tag = all[k].tagName.toLowerCase();
                   if (tag.indexOf('-') > 0 && customs.indexOf(tag) < 0 && customs.length < 8) customs.push(tag);
                   if (all[k].shadowRoot && shadows.length < 6) {
                     var inner = all[k].shadowRoot.querySelectorAll('video').length;
                     shadows.push(tag + '(video=' + inner + ')');
                   }
                 }
                 r.push('custom=' + customs.join(','));
                 r.push('shadow=' + shadows.join(','));
                 // The container where a player would mount, to see what sits there instead.
                 var slot = document.querySelector('[class*=h-\\[280px\\]], [class*=aspect], main div');
                 r.push('slot=' + (slot ? (slot.tagName + '.' + String(slot.className).slice(0, 40) + ' >> ' + slot.innerHTML.replace(/\s+/g, ' ').slice(0, 220)) : 'none'));
                 var body = document.body ? document.body.innerText.slice(0, 160).replace(/\s+/g, ' ') : '';
                 r.push('text=' + body);
                 return r.join('  ');
               })();"""
    }
}
