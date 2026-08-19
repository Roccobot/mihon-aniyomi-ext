package eu.kanade.tachiyomi.animeextension.en.hanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ── Lists ──────────────────────────────────────────────────────────────────
    //
    // These go through the WebView instead of the http client, so the request/parse pairs
    // that AnimeHttpSource wants are not part of this source's flow at all.

    override suspend fun getPopularAnime(page: Int): AnimesPage = pageOf(BROWSE_URL)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = pageOf(LATEST_URL)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage =
        pageOf(searchUrl(query))

    private fun pageOf(url: String): AnimesPage {
        val html = webView.renderedHtml(url, VIDEO_LINK)
        val entries = Jsoup.parse(html, baseUrl)
            .select("a[href*=$VIDEO_PATH]")
            .mapNotNull { it.toEntry() }
            // One entry per SERIES: the site gives every episode its own page, and the
            // grouping rule is the title stripped of its trailing number.
            .distinctBy { it.url }
        // No pagination yet, deliberately: the site paginates by scrolling, and a page
        // number this source cannot verify would be a promise it does not keep.
        return AnimesPage(entries, false)
    }

    private fun Element.toEntry(): SAnime? {
        val slug = attr("href").substringAfterLast('/').ifBlank { return null }
        val label = listOf(attr("title"), selectFirst("img")?.attr("alt").orEmpty(), text())
            .firstOrNull { it.isNotBlank() }
            ?: slug.replace('-', ' ')
        return SAnime.create().apply {
            url = groupUrl(slug)
            title = label.baseTitle()
            thumbnail_url = selectFirst("img")?.imageUrl()
        }
    }

    private fun Element.imageUrl(): String? =
        listOf(absUrl("src"), attr("data-src"), attr("srcset").substringBefore(' '))
            .firstOrNull { it.isNotBlank() }

    // ── One entry: details, episodes, streams ──────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val page = Jsoup.parse(webView.renderedHtml(baseUrl + anime.url, VIDEO_LINK), baseUrl)
        return SAnime.create().apply {
            url = anime.url
            title = (page.selectFirst("h1")?.text() ?: page.title()).baseTitle()
            thumbnail_url = page.selectFirst("meta[property=og:image]")?.attr("content")
            description = page.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val page = Jsoup.parse(webView.renderedHtml(baseUrl + anime.url, VIDEO_LINK), baseUrl)
        val group = anime.title.baseTitle()
        // ⚠️ Every link to a video page on this page is a candidate, and the filter on the
        // group title is what keeps sequels out: the site lists the whole franchise here,
        // so taking it whole would put 'Titolo IV' inside 'Titolo III'.
        val episodes = page.select("a[href*=$VIDEO_PATH]")
            .mapNotNull { link ->
                val slug = link.attr("href").substringAfterLast('/').ifBlank { return@mapNotNull null }
                val label = listOf(link.attr("title"), link.selectFirst("img")?.attr("alt").orEmpty(), link.text())
                    .firstOrNull { it.isNotBlank() } ?: slug.replace('-', ' ')
                Triple(slug, label, label.baseTitle())
            }
            .filter { (_, _, base) -> base.equals(group, ignoreCase = true) }
            .distinctBy { (slug, _, _) -> slug }
            .map { (slug, label, _) ->
                SEpisode.create().apply {
                    url = "$VIDEO_PATH$slug"
                    name = label
                    episode_number = episodeNumber(label, slug)
                }
            }
        // A page that lists no sibling is a one-shot: the entry itself is the episode.
        return episodes.ifEmpty {
            listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = anime.title
                    episode_number = 1F
                },
            )
        }.sortedByDescending { it.episode_number }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (url, requestHeaders) = webView.interceptMedia(baseUrl + episode.url, MEDIA_URL)
            ?: throw Exception(
                "No stream: the page's player did not request one within " +
                    "${HanimeWebView.DEFAULT_TIMEOUT / 1000}s. If you are signed out, sign in " +
                    "to hanime.tv in the app's WebView and try again.",
            )
        val built = Headers.Builder().apply {
            requestHeaders.forEach { (key, value) -> add(key, value) }
            if (requestHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                add("Referer", "$baseUrl/")
            }
        }.build()
        return listOf(Video(url, url.qualityLabel(), url, built))
    }

    // Highest resolution first, with the preferred one on top when it is there: the choice
    // is never asked, as for the other Roccobot downloaders.
    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality == preferred }
                .thenByDescending { it.quality.height() },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITIES
            entryValues = QUALITIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
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
        if (SLUG_NUMBER.containsMatchIn(slug)) {
            "$VIDEO_PATH${SLUG_NUMBER.replace(slug, "")}-1"
        } else {
            "$VIDEO_PATH$slug"
        }

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

        // ⚠️ TO CONFIRM on the device: these three are the site's own page addresses, and
        // they are the only guess left in this source. If a list comes back empty, it is
        // almost certainly one of these being wrong, not the parsing.
        private const val BROWSE_URL = "https://hanime.tv/browse/trending?time=month"
        private const val LATEST_URL = "https://hanime.tv/browse/recent"
        private fun searchUrl(query: String) = "https://hanime.tv/search?query=${query.replace(' ', '+')}"

        private val VIDEO_LINK = Regex(Regex.escape(VIDEO_PATH))
        private val MEDIA_URL = Regex("""\.(m3u8|mp4)(\?|$)""")

        // ⚠️ ARABIC digits only, and only at the very end: titles carry roman numerals as
        // part of the name ('Anime Titolo III'), and a greedier pattern would merge three
        // different series into one.
        private val TITLE_NUMBER = Regex("""[\s._-]*(?:ep\.?|episode)?\s*(\d{1,3})$""", RegexOption.IGNORE_CASE)
        private val SLUG_NUMBER = Regex("""-(\d{1,3})$""")
        private val HEIGHT_IN_URL = Regex("""(\d{3,4})p""")

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val QUALITIES = arrayOf("1080p", "720p", "480p", "360p")
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}
