package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

/**
 * hanime.tv, read through the site's own pages in the app's WebView.
 *
 * ⚠️ WHY NOT THE API, so nobody rebuilds it that way: the v11 API cannot be called by this
 * extension at all. Catalog requests carry a signature minted by a WASM module the site
 * ships inside its player bundle, and the stream handshake seals its body with a key taken
 * from that same bundle and answers with an encrypted header. Reproducing those would mean
 * lifting the site's compiled module and its keys: this extension does not do it. The v8
 * endpoints an older generation of clients used are gone for good, their hosts included
 * (`search.htv-services.com` and `members.hanime.tv` no longer resolve at all).
 *
 * So the site's client runs as intended, in a browser, with the user's own session, and the
 * extension reads the result: the rendered DOM for lists, and the media request the page's
 * own player ends up making for playback. See [HanimeWebView].
 *
 * ⚠️ Lists are read by URL, never by CSS class: every card links to `/videos/hentai/<slug>`,
 * and that path is the site's public address, so it survives a restyling. Class names would
 * not, and guessing them is what the first version of this source got wrong.
 */
class Hanime : AnimeHttpSource(), ConfigurableAnimeSource {

    // ⚠️ Changing this changes the source id, which Aniyomi derives from name, lang and
    // API version: entries already in the library would be orphaned.
    override val name = "Hanime Roccobot"

    override val baseUrl = "https://hanime.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val webView by lazy { HanimeWebView(headers["User-Agent"] ?: DEFAULT_UA) }

    init {
        HanimeLog.toFile = preferences.getBoolean(PREF_LOG_KEY, false)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ── Lists ──────────────────────────────────────────────────────────────────
    //
    // These go through the WebView instead of the http client, so the request/parse pairs
    // that AnimeHttpSource wants are not part of this source's flow at all.

    override suspend fun getPopularAnime(page: Int): AnimesPage = pageOf(BROWSE_URL)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = pageOf(LATEST_URL)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        // Searching `debug` opens the trace instead of the site. It is the only way to read
        // it on a phone without a cable or a file manager, and the word is reserved: nothing
        // on this site is called that.
        val asked = query.trim()
        if (asked.equals(DEBUG_CLEAR, ignoreCase = true)) {
            // Clearing lives on the same channel instead of in the settings, because the
            // library's Preference stub has no constructor a source can call.
            HanimeLog.clear()
            HanimeLog.log("log cleared")
            return AnimesPage(listOf(debugEntry()), false)
        }
        if (asked.equals(DEBUG_QUERY, ignoreCase = true)) {
            return AnimesPage(listOf(debugEntry()), false)
        }
        return pageOf(searchUrl(query))
    }

    private fun debugEntry() = SAnime.create().apply {
        url = DEBUG_URL
        title = "Debug log (tap to read)"
        thumbnail_url = null
    }

    private fun pageOf(url: String): AnimesPage {
        HanimeLog.log("LIST $url")
        val html = webView.renderedHtml(url, VIDEO_LINK)
        val entries = Jsoup.parse(html, baseUrl)
            .select("a[href*=$VIDEO_PATH]")
            .mapNotNull { it.toEntry() }
            // One entry per SERIES: the site gives every episode its own page, and the
            // grouping rule is the title stripped of its trailing number.
            .distinctBy { it.url }
        HanimeLog.log("LIST ${entries.size} entries: ${entries.take(4).joinToString { it.title }}")
        // No pagination yet, deliberately: the site paginates by scrolling, and a page
        // number this source cannot verify would be a promise it does not keep.
        return AnimesPage(entries, false)
    }

    private fun Element.toEntry(): SAnime? {
        val slug = attr("href").substringAfterLast('/').ifBlank { return null }
        return SAnime.create().apply {
            url = groupUrl(slug)
            title = label(slug).baseTitle()
            thumbnail_url = selectFirst("img")?.imageUrl()
        }
    }

    /**
     * The card's own text first, attributes only as a fallback: measured on the site, the
     * `title` and `alt` attributes carry SEO copy ('Watch Momone 1 hentai online...'), and
     * reading those first produced entries literally called 'Watch ...'. Worse, the number
     * sits in the MIDDLE of that string, so the grouping rule found nothing to strip and
     * every episode stayed its own entry.
     */
    private fun Element.label(slug: String): String {
        val raw = listOf(text(), selectFirst("img")?.attr("alt").orEmpty(), attr("title"))
            .firstOrNull { it.isNotBlank() }
            ?: slug.replace('-', ' ')
        return raw.stripSeo()
    }

    /** Undoes the SEO wrapper when a label comes from an attribute after all. */
    private fun String.stripSeo(): String =
        replace(SEO_PREFIX, "").replace(SEO_SUFFIX, "").trim()

    private fun Element.imageUrl(): String? =
        listOf(absUrl("src"), attr("data-src"), attr("srcset").substringBefore(' '))
            .firstOrNull { it.isNotBlank() }

    // ── One entry: details, episodes, streams ──────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        if (anime.url == DEBUG_URL) {
            return SAnime.create().apply {
                url = DEBUG_URL
                title = "Debug log"
                author = "Log file: ${HanimeLog.filePath()}"
                description = HanimeLog.dump()
                status = SAnime.COMPLETED
                initialized = true
            }
        }
        val page = Jsoup.parse(webView.renderedHtml(baseUrl + anime.url, VIDEO_LINK), baseUrl)
        return SAnime.create().apply {
            url = anime.url
            title = (page.selectFirst("h1")?.text() ?: page.title()).baseTitle()
            thumbnail_url = page.selectFirst("meta[property=og:image]")?.attr("content")
            // ⚠️ The site's meta description is pure SEO boilerplate ('Watch X 1 latest
            // hentai online free download HD on mobile phone...'), not a synopsis: it is
            // dropped rather than half-cleaned, because a stripped version would read like a
            // real description while saying nothing.
            description = page.selectFirst("meta[name=description]")?.attr("content")
                ?.takeUnless { SEO_DESCRIPTION.containsMatchIn(it) }
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url == DEBUG_URL) return emptyList()
        val page = Jsoup.parse(webView.renderedHtml(baseUrl + anime.url, VIDEO_LINK), baseUrl)
        // ⚠️ Siblings are recognised by SLUG, not by title, and the difference is what made
        // the first attempt show one episode per series: titles on this site can arrive
        // wrapped in SEO copy, while `momone-1` and `momone-2` share a base by construction.
        // It also keeps sequels out, which is why the whole franchise list is not taken as is.
        val group = anime.url.substringAfterLast('/').baseSlug()
        val episodes = page.select("a[href*=$VIDEO_PATH]")
            .mapNotNull { link ->
                val slug = link.attr("href").substringAfterLast('/').ifBlank { return@mapNotNull null }
                Triple(slug, link.label(slug), slug.baseSlug())
            }
            .filter { (_, _, base) -> base.equals(group, ignoreCase = true) }
            .distinctBy { (slug, _, _) -> slug }
            .map { (slug, label, _) ->
                val number = episodeNumber(label, slug)
                SEpisode.create().apply {
                    url = "$VIDEO_PATH$slug"
                    // Just the number, by the user's call (2026-08-19): the card text on this
                    // site is a pile of duration, studio and badges ('Now Playing30:27 Master
                    // Piece 1Pink P...'), and cleaning that case by case is a chase with no
                    // end. What matters is that episodes are in order.
                    name = EPISODE_LABEL.format(number)
                    episode_number = number
                }
            }
        // A page that lists no sibling is a one-shot: the entry itself is the episode.
        return episodes.ifEmpty {
            listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = EPISODE_LABEL.format(1F)
                    episode_number = 1F
                },
            )
        }.sortedByDescending { it.episode_number }
            .also { HanimeLog.log("EPS  group $group -> ${it.size}: ${it.take(6).joinToString { e -> e.name }}") }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        HanimeLog.log("PLAY session cookies: ${webView.cookieNames(baseUrl)}")
        val result = webView.interceptMedia(baseUrl + episode.url, MEDIA_URL)
        // ⚠️ The failure message carries what the page actually requested: without it, 'no
        // stream found' is unfixable, since this side has no way to watch the site itself.
        val (url, requestHeaders) = result.hit ?: throw Exception(
            "No stream in ${HanimeWebView.DEFAULT_TIMEOUT / 1000}s. Sign in to hanime.tv in " +
                "the app's WebView if you are signed out. The player requested: " +
                result.seen.takeLast(6).joinToString(" | ").ifBlank { "nothing at all" },
        )
        val built = Headers.Builder().apply {
            requestHeaders.forEach { (key, value) -> add(key, value) }
            if (requestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                add("Referer", "$baseUrl/")
            }
            // ⚠️⚠️ THE COOKIE HAS TO BE PASSED ON, and this is the piece that was missing:
            // Aniyomi's player does not talk through the WebView, it uses this extension's
            // http client, which knows nothing of the session created by signing in. On a
            // site where the download is for registered users only, an address handed over
            // without the cookie is an address that answers 403.
            if (requestHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                webView.cookieHeader(url)?.let { add("Cookie", it) }
            }
        }.build()
        HanimeLog.log("PLAY handing to player: ${url.qualityLabel()} $url")
        return listOf(Video(url, url.qualityLabel(), url, built))
    }

    /**
     * 720p first, then whatever else is there, highest to lowest. No setting, by the user's
     * call (2026-08-19): on this site 1080p belongs to paying members, and every other choice
     * is one nobody would make twice. When 720p is missing the first usable stream wins,
     * which is what a fallback means here.
     */
    override fun List<Video>.sort(): List<Video> = sortedWith(
        compareByDescending<Video> { it.quality == PREFERRED_QUALITY }
            .thenByDescending { it.quality.height() },
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LOG_KEY
            title = "Write the debug log to a file"
            summary = "Off by default. Read the trace anytime by searching '$DEBUG_QUERY' in " +
                "this source, empty it with '$DEBUG_CLEAR'. File: ${HanimeLog.filePath()}"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, value ->
                HanimeLog.toFile = value as Boolean
                true
            }
        }.also(screen::addPreference)
    }

    // ── Grouping: one entry per series, not per episode ─────────────────────────

    /** `Anime Titolo III 02` -> `Anime Titolo III`. A title with no number is left alone. */
    private fun String.baseTitle(): String = TITLE_NUMBER.replace(trim(), "").trim()

    /**
     * The url that identifies the GROUP, derived and not picked: it is the key Aniyomi
     * keeps the library entry under, so taking the slug of whichever episode came first in
     * a list would file one series under two entries. Numbering starts at 1 on this site,
     * so episode 1 is the group's stable representative.
     */
    private fun groupUrl(slug: String): String =
        if (SLUG_NUMBER.containsMatchIn(slug)) "$VIDEO_PATH${slug.baseSlug()}-1" else "$VIDEO_PATH$slug"

    /** `momone-2` -> `momone`. */
    private fun String.baseSlug(): String = SLUG_NUMBER.replace(this, "")

    /** The number comes from the title, and from the slug only when the title has none. */
    private fun episodeNumber(title: String, slug: String): Float =
        (TITLE_NUMBER.find(title.trim()) ?: SLUG_NUMBER.find(slug))
            ?.groupValues?.get(1)?.toFloatOrNull() ?: 1F

    private fun String.qualityLabel(): String =
        HEIGHT_IN_URL.find(this)?.groupValues?.get(1)?.let { "${it}p" } ?: "Default"

    private fun String.height(): Int = removeSuffix("p").toIntOrNull() ?: 0

    // ── Not part of this source's flow ─────────────────────────────────────────
    //
    // AnimeHttpSource requires these, but every call here goes through the WebView, so
    // they are never reached. They throw instead of returning something plausible: a
    // silent empty list would look like 'the site has nothing'.

    private fun unused(): Nothing = throw UnsupportedOperationException("Not used")

    override fun popularAnimeRequest(page: Int): Request = unused()
    override fun popularAnimeParse(response: Response): AnimesPage = unused()
    override fun latestUpdatesRequest(page: Int): Request = unused()
    override fun latestUpdatesParse(response: Response): AnimesPage = unused()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = unused()
    override fun searchAnimeParse(response: Response): AnimesPage = unused()
    override fun animeDetailsParse(response: Response): SAnime = unused()
    override fun episodeListParse(response: Response): List<SEpisode> = unused()
    override fun videoListParse(response: Response): List<Video> = unused()

    companion object {
        private const val VIDEO_PATH = "/videos/hentai/"

        // Confirmed by the user from the site itself, 2026-08-19: the search parameter is
        // `q` and NOT `query`, and 'latest' is the home page rather than a browse path.
        // Guessing these was what left the first version with empty lists.
        // ⚠️ NOT hanime.tv/browse: measured on the device, that page is an index of
        // categories and carries no link to a video, so the list came back empty. This is
        // the search page ordered by views, and `views_desc` is the one value here still to
        // be confirmed against the site.
        private const val BROWSE_URL = "https://hanime.tv/search?order=views_desc"
        private const val LATEST_URL = "https://hanime.tv/"
        private const val SEARCH_ORDER = "created_at_desc"

        private fun searchUrl(query: String) =
            "https://hanime.tv/search?q=${URLEncoder.encode(query, "UTF-8")}&order=$SEARCH_ORDER"

        private val VIDEO_LINK = Regex(Regex.escape(VIDEO_PATH))
        private val MEDIA_URL = Regex("""\.(m3u8|mp4)(\?|$)""")

        // ⚠️ ARABIC digits only, and only at the very end: titles carry roman numerals as
        // part of the name ('Anime Titolo III'), and a greedier pattern would merge three
        // different series into one.
        private val TITLE_NUMBER = Regex("""[\s._-]*(?:ep\.?|episode)?\s*(\d{1,3})$""", RegexOption.IGNORE_CASE)
        private val SLUG_NUMBER = Regex("""-(\d{1,3})$""")
        private val HEIGHT_IN_URL = Regex("""(\d{3,4})p""")

        // The shape of the site's SEO copy: 'Watch <name> <n> hentai online free...'.
        private val SEO_PREFIX = Regex("""^watch\s+""", RegexOption.IGNORE_CASE)
        private val SEO_SUFFIX = Regex("""\s+(?:latest\s+|full\s+)?hentai\b.*$""", RegexOption.IGNORE_CASE)
        private val SEO_DESCRIPTION = Regex("""hentai online free|watch .* hentai""", RegexOption.IGNORE_CASE)

        /** Episodes are named by number alone: `01`, `02`, ... */
        private const val EPISODE_LABEL = "%02.0f"

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        // ⚠️ Fixed, and NOT a preference: 1080p on this site belongs to paying members, so
        // offering it would be a promise the source cannot keep, and the remaining values are
        // not a choice worth a setting. 720p when it exists, otherwise the first usable one.
        private const val PREFERRED_QUALITY = "720p"
        private const val PREF_LOG_KEY = "log_to_file"

        private const val DEBUG_QUERY = "debug"
        private const val DEBUG_CLEAR = "debug clear"
        private const val DEBUG_URL = "#debug-log"
    }
}
