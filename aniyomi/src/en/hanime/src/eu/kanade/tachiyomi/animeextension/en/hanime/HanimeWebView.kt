package eu.kanade.tachiyomi.animeextension.en.hanime

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
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
        // ⚠️⚠️ ONE sequence per attempt, and this guard is the fix for a self-inflicted wound:
        // the site is an Astro app with client-side transitions, so `onPageFinished` fires on
        // every transition. Starting a new inventory-and-click chain each time produced 318
        // clicks in one attempt, and the console said what that did: 'Transition was aborted
        // because of timeout in DOM update' and 'Throttling navigation to prevent the browser
        // from hanging'. The clicking was preventing the page from ever settling.
        var started = false
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
                    if (started) return
                    started = true
                    // The page is left alone for a while first: this app hydrates its
                    // components after load, and touching it earlier is what broke it.
                    handler.postDelayed({
                        // ⚠️ A link opened in a NEW TAB is not an anchor in the DOM: the site's
                        // download control may hand the file host to `window.open`, and without
                        // this hook that address stays invisible from here.
                        view.evaluateJavascript(HOOK_WINDOW_OPEN, null)
                        view.evaluateJavascript(DOM_INVENTORY) { HanimeLog.log("PLAY dom    $it") }
                        clickPlay(view, 1) { external ->
                            if (found == null) {
                                HanimeLog.log("PLAY external found: $external")
                                found = external to emptyMap()
                                latch.countDown()
                            }
                        }
                    }, SETTLE_DELAY)
                }
            }
            // ⚠️ A page download does NOT arrive as a subresource: a link that downloads
            // hands the url to this listener instead, so without it the one address the site
            // actually offers would never be seen.
            view.setDownloadListener { downloadUrl, agent, _, mime, size ->
                HanimeLog.log("PLAY dlurl  $mime $size $downloadUrl")
                if (found == null) {
                    found = downloadUrl to mapOf("User-Agent" to agent)
                    latch.countDown()
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

    /**
     * The site's download flow takes THREE steps, and the user measured them on the device
     * (2026-08-19, screenshots): `Download` opens a list of options in the page itself
     * (`Premium MP4 1080p` with a crown, then `Pixeldrain MP4` at 720p, 480p and 360p, each
     * with an 'open in new tab' icon); tapping one opens a confirmation panel titled 'Leaving
     * hanime.tv' that shows the external address AS TEXT; only a third tap actually leaves.
     *
     * ⚠️ So this stops at step two: the address is read from the text, which needs no consent
     * to anything and leaves the site's own flow untouched. And it explains every earlier
     * empty-handed trace: those options are BUTTONS with no href, so a scan for anchors could
     * never see them, and the panel is not a `dialog` either.
     */
    private fun clickPlay(view: WebView, attempt: Int, onExternal: (String) -> Unit) {
        view.evaluateJavascript(CLICK_PLAY) { outcome ->
            HanimeLog.log("PLAY click$attempt $outcome")
        }
        if (attempt == DOWNLOAD_ATTEMPT) {
            // The snapshot has to be taken BEFORE the click, or there is nothing to compare the
            // page against afterwards.
            view.evaluateJavascript(SNAPSHOT_TEXT, null)
            view.evaluateJavascript(CLICK_DOWNLOAD) { outcome ->
                HanimeLog.log("PLAY dl     $outcome")
            }
            // Step two: pick a Pixeldrain option, never the crowned Premium one.
            handler.postDelayed({
                view.evaluateJavascript(CLICK_OPTION) { HanimeLog.log("PLAY option $it") }
            }, OPTION_DELAY)
            // Step three, read instead of clicked: the address is in the panel's text. Sampled
            // more than once because the panel appears after the site's own handshake.
            listOf(1_500L, 4_000L, 8_000L).forEach { delay ->
                handler.postDelayed({
                    view.evaluateJavascript(FIND_EXTERNAL_URL) { raw ->
                        val url = raw.trim('"').replace("\\/", "/").replace("\\\"", "")
                        HanimeLog.log("PLAY url?   $url")
                        if (url.startsWith("http")) onExternal(url)
                    }
                    view.evaluateJavascript(MODAL_DUMP) { HanimeLog.log("PLAY panel  $it") }
                }, OPTION_DELAY + delay)
            }
        }
        if (attempt < CLICK_ATTEMPTS) {
            handler.postDelayed({ clickPlay(view, attempt + 1, onExternal) }, CLICK_INTERVAL)
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

    /**
     * The names of the cookies this app's browser holds for [url], never their values: the
     * trace gets pasted into a chat, and a session token in there would be a password given
     * away. The names alone answer the only question that matters, whether a session exists.
     */
    fun cookieNames(url: String): String =
        CookieManager.getInstance().getCookie(url)
            ?.split(';')
            ?.joinToString(",") { it.substringBefore('=').trim() }
            ?: "none"

    /**
     * The raw cookie header for [url], to hand to the player. ⚠️ It carries the session
     * token, so it is used and never logged: [cookieNames] is the loggable half.
     */
    fun cookieHeader(url: String): String? = CookieManager.getInstance().getCookie(url)

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
        // Not a trick: it is the switch that lets a page start its own video without a tap,
        // which is what makes the player ask for its stream.
        settings.mediaPlaybackRequiresUserGesture = false
        // The session the user created by signing in from the app's WebView lives in the
        // shared cookie jar: these two lines are what let this view see it.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
        private const val CLICK_ATTEMPTS = 3
        private const val CLICK_INTERVAL = 4_000L
        private const val SETTLE_DELAY = 3_000L

        private val STATIC_ASSET =
            Regex("""\.(png|jpe?g|webp|gif|svg|ico|css|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

        private const val DOM_DUMP = "document.documentElement.outerHTML"
        private const val DOWNLOAD_ATTEMPT = 3

        private const val OPTION_DELAY = 2_000L

        /**
         * Clicks one of the download options, and ⚠️ NEVER the crowned `Premium MP4 1080p`: on a
         * free account that one is a paywall, and this extension does not pretend to be a
         * subscriber. 720p first, then 480p, then 360p, matching the fixed quality of the source.
         */
        private const val CLICK_OPTION =
            """(function () {
                 var nodes = document.querySelectorAll('button, [role=button], a, div');
                 var opts = [];
                 for (var i = 0; i < nodes.length; i++) {
                   var t = (nodes[i].textContent || '').replace(/\s+/g, ' ').trim();
                   // Short text only: a long one belongs to the container holding every option.
                   if (t.length > 60 || !/pixeldrain/i.test(t) || /premium/i.test(t)) continue;
                   opts.push({ node: nodes[i], text: t });
                 }
                 if (!opts.length) return 'noPixeldrainOption';
                 var order = ['720', '480', '360'];
                 for (var k = 0; k < order.length; k++) {
                   for (var j = 0; j < opts.length; j++) {
                     if (opts[j].text.indexOf(order[k]) >= 0) {
                       opts[j].node.click();
                       return 'clicked=' + opts[j].text;
                     }
                   }
                 }
                 opts[0].node.click();
                 return 'clickedFirst=' + opts[0].text;
               })();"""

        /**
         * Reads the external address from the confirmation panel's TEXT. ⚠️ Text, not an anchor:
         * the panel prints the url in a box, and there is no link to read, which is why every
         * anchor scan came back with nothing but Discord.
         */
        private const val FIND_EXTERNAL_URL =
            """(function () {
                 var re = /https?:\/\/pixeldrain\.(?:net|com)\/u\/[A-Za-z0-9]+/;
                 var body = document.body ? (document.body.innerText || '') : '';
                 var m = body.match(re);
                 if (m) return m[0];
                 var anchors = document.querySelectorAll('a[href]');
                 for (var i = 0; i < anchors.length; i++) {
                   var h = anchors[i].getAttribute('href') || '';
                   if (re.test(h)) return h.match(re)[0];
                 }
                 if (window.__hanimeOpened && re.test(window.__hanimeOpened)) {
                   return window.__hanimeOpened.match(re)[0];
                 }
                 return 'none';
               })();"""

        private const val SNAPSHOT_TEXT =
            """(function () {
                 window.__hanimeBefore = document.body ? (document.body.innerText || '') : '';
                 return 'snapshot=' + window.__hanimeBefore.length;
               })();"""

        private const val MODAL_DUMP =
            """(function () {
                 var sel = '[role=dialog], dialog, [class*=modal], [class*=fixed][class*=z-]';
                 var boxes = document.querySelectorAll(sel), best = null;
                 for (var i = 0; i < boxes.length; i++) {
                   var b = boxes[i], r = b.getBoundingClientRect();
                   if (r.width > 120 && r.height > 60 && (b.innerText || '').trim()) best = b;
                 }
                 // The external links present AFTER the click, wherever they are: the address
                 // the site offers may be added to the page instead of to a dialog.
                 var ext = [];
                 var anchors = document.querySelectorAll('a[href]');
                 for (var k = 0; k < anchors.length && ext.length < 6; k++) {
                   var h = anchors[k].getAttribute('href') || '';
                   if (/^https?:/i.test(h) && h.indexOf('hanime.tv') < 0) ext.push(h.slice(0, 90));
                 }
                 // ⚠️⚠️ THE ADDED LINES, not a keyword window: searching for 'premium' or
                 // 'download' in the body always hit the navigation drawer, which carries both
                 // words on every page, so three samples in a row reported the menu instead of
                 // the site's answer. Comparing against the snapshot taken before the click
                 // cannot make that mistake: whatever the page says now that it did not say
                 // before is exactly the reply, dialog or not.
                 var body = document.body ? (document.body.innerText || '') : '';
                 var before = window.__hanimeBefore || '';
                 var was = {};
                 before.split('\n').forEach(function (l) { was[l.trim()] = 1; });
                 var added = [];
                 body.split('\n').forEach(function (l) {
                   var t = l.trim();
                   if (t && !was[t] && added.length < 8) added.push(t.slice(0, 60));
                 });
                 var around = added.length ? added.join(' / ') : 'nothingNew';
                 var tail = '  ||external: ' + (ext.length ? ext.join(' ; ') : 'none') +
                            '  ||opened=' + (window.__hanimeOpened || 'none') +
                            '  ||around: ' + around;
                 if (!best) return 'noDialog' + tail;
                 var links = [];
                 var as = best.querySelectorAll('a, button');
                 for (var j = 0; j < as.length && links.length < 8; j++) {
                   var t = (as[j].getAttribute('aria-label') || as[j].textContent || '').trim().slice(0, 24);
                   if (t) links.push(t + (as[j].getAttribute('href') ? '->' + as[j].getAttribute('href') : ''));
                 }
                 return (best.innerText || '').replace(/\s+/g, ' ').slice(0, 320) +
                        '  ||controls: ' + links.join(' ; ') + tail;
               })();"""

        /**
         * Records where the page tried to open a new tab, and then lets it through: observing
         * rather than altering is the rule this class follows everywhere.
         */
        private const val HOOK_WINDOW_OPEN =
            """(function () {
                 if (window.__hanimeHooked) return 'already';
                 window.__hanimeHooked = true;
                 var real = window.open;
                 window.open = function (u) {
                   window.__hanimeOpened = String(u || '');
                   try { return real.apply(window, arguments); } catch (e) { return null; }
                 };
                 return 'hooked';
               })();"""

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
                 // ⚠️ NO fallback click on the poster or on a generic container: that is what
                 // fired navigations and aborted the site's own transitions. Better to report
                 // that there was nothing to click than to poke the page at random.
                 if (!hits) out.push('nothingNamedPlay');
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
                 var btns = document.querySelectorAll('button, [role=button], a');
                 var labels = [], dls = [];
                 for (var j = 0; j < btns.length; j++) {
                   var t = (btns[j].getAttribute('aria-label') || btns[j].textContent || '').trim().slice(0, 22);
                   // ⚠️ Personal data must NOT end up in a trace that gets pasted into a chat:
                   // the user menu carries their username and email, and it did.
                   if (t && labels.length < 24) labels.push(t.replace(/[\w.+-]+@[\w.-]+/g, '[email]').replace(/#\d{3,}/g, '#[id]'));
                   // The download control is the one thing on this page that can lead to a
                   // real address, so it is listed separately with its href.
                   if (/download|mp4/i.test(t) && dls.length < 4) {
                     dls.push(btns[j].tagName + '[' + t + ']href=' + (btns[j].getAttribute('href') || '-'));
                   }
                 }
                 r.push('buttons[' + btns.length + ']=' + labels.join('|'));
                 r.push('downloadCandidates=' + (dls.length ? dls.join(' ; ') : 'none'));
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
                 // Whether the page still offers to sign in: the shortest answer to 'is this
                 // browser authenticated', which decides whether the download is even offered.
                 // Signed in is read from 'Sign Out', not from the absence of 'Sign In': the
                 // drawer offers both to a logged-in user, so the old check said the opposite
                 // of the truth.
                 r.push('signedIn=' + /sign out|logout/i.test(document.body ? document.body.innerText : ''));
                 // ⚠️ Restriction is a matter of VISIBILITY, not of presence: this element sits
                 // in the markup of every page and CSS decides whether it shows. Asking the
                 // html instead declared every title restricted, `the-pianist-1` included.
                 var notice = document.querySelector('#RestrictedVideoNotice, .restricted-video-notice');
                 var noticeShown = false;
                 if (notice) {
                   var nr = notice.getBoundingClientRect();
                   noticeShown = !!notice.offsetParent && nr.height > 10 && nr.width > 10;
                 }
                 r.push('restrictedVisible=' + noticeShown);
                 r.push('text=' + body);
                 return r.join('  ');
               })();"""
    }
}
