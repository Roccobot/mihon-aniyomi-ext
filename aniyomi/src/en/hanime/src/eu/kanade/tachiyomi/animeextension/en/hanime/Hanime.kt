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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * hanime.tv reads entirely from the JSON API its own frontend uses, so nothing here
 * scrapes HTML: three endpoints cover the whole source.
 *
 *  - POST search.htv-services.com  -> browse, latest and search (one endpoint, three orderings)
 *  - GET  /api/v8/video?id=<slug>  -> details, episode list and stream list
 *
 * Streams the site reserves for paying members arrive with an empty url and are
 * dropped: this source only plays what a guest is served.
 */
class Hanime : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "hanime.tv"

    override val baseUrl = "https://hanime.tv"

    override val lang = "en"

    override val supportsLatest = true

    // No client override on purpose: the site sits behind a Cloudflare bot challenge,
    // and the default client already handles it in the app's own WebView. Asking for
    // `network.cloudflareClient` compiles but is deprecated for exactly this reason.

    private val json = Json {
        ignoreUnknownKeys = true
        // The API is loose with types (heights arrive both as 1080 and as "1080"),
        // and a strict parser would fail on a value it could perfectly well read.
        isLenient = true
        coerceInputValues = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("X-Signature-Version", "web2")

    // ── Browse, latest, search ──────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = searchRequest(page, "", ORDER_VIEWS)

    override fun popularAnimeParse(response: Response): AnimesPage = searchPageParse(response)

    override fun latestUpdatesRequest(page: Int): Request = searchRequest(page, "", ORDER_RELEASED)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchPageParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        searchRequest(page, query, ORDER_RELEASED)

    override fun searchAnimeParse(response: Response): AnimesPage = searchPageParse(response)

    private fun searchRequest(page: Int, query: String, orderBy: String): Request {
        val payload = buildJsonObject {
            put("search_text", query)
            put("tags", buildJsonArray {})
            put("tags_mode", "AND")
            put("brands", buildJsonArray {})
            put("blacklist", buildJsonArray {})
            put("order_by", orderBy)
            put("ordering", "desc")
            // The API counts pages from zero, Aniyomi from one.
            put("page", page - 1)
        }
        return POST(SEARCH_URL, headers, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
    }

    private fun searchPageParse(response: Response): AnimesPage {
        val page = response.parseAs<SearchResponseDto>()
        val hits = json.decodeFromString<List<HitDto>>(page.hits.ifBlank { "[]" })
        val entries = hits.map { hit ->
            SAnime.create().apply {
                url = "$VIDEO_PATH${hit.slug}"
                title = hit.name
                thumbnail_url = hit.coverUrl ?: hit.posterUrl
                author = hit.brand
            }
        }
        return AnimesPage(entries, page.page + 1 < page.nbPages)
    }

    // ── Details, episodes, streams: all three from the same endpoint ────────────

    override fun animeDetailsRequest(anime: SAnime): Request = apiRequest(anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        val video = response.parseAs<VideoResponseDto>().video
        return SAnime.create().apply {
            url = "$VIDEO_PATH${video.slug}"
            title = video.name
            thumbnail_url = video.coverUrl ?: video.posterUrl
            author = video.brand
            genre = video.tags.joinToString { it.text }
            // The description is HTML: tags are stripped, and the entities the site
            // actually uses are unescaped, or they show up literally in the app.
            description = video.description?.stripHtml()
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = apiRequest(anime.url)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<VideoResponseDto>()
        // A one-shot has no franchise, and then the entry itself is the only episode.
        val entries = data.franchise.ifEmpty {
            listOf(FranchiseEntryDto(data.video.name, data.video.slug, data.video.releasedAtUnix))
        }
        return entries.map { entry ->
            SEpisode.create().apply {
                url = "$VIDEO_PATH${entry.slug}"
                name = entry.name
                episode_number = entry.slug.episodeNumber()
                date_upload = (entry.releasedAtUnix ?: 0L) * 1000L
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun videoListRequest(episode: SEpisode): Request = apiRequest(episode.url)

    override fun videoListParse(response: Response): List<Video> {
        val streams = response.parseAs<VideoResponseDto>()
            .manifest?.servers?.flatMap { it.streams }
            .orEmpty()
        return streams
            .filter { it.url.isNotBlank() }
            .distinctBy { it.height }
            .map { stream -> Video(stream.url, "${stream.height}p", stream.url, headers) }
    }

    // Highest resolution first, with the preferred one on top when the site has it:
    // the choice is never asked, exactly as for the other Roccobot downloaders.
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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun apiRequest(url: String): Request =
        GET("$baseUrl/api/v8/video?id=${url.substringAfterLast('/')}", headers)

    private inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromString(it.body.string())
    }

    /** Trailing digits of a slug: `shikkaku-ishi-1` -> 1. Unnumbered slugs get 1. */
    private fun String.episodeNumber(): Float =
        SLUG_NUMBER.find(this)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F

    private fun String.height(): Int = removeSuffix("p").toIntOrNull() ?: 0

    private fun String.stripHtml(): String =
        replace(HTML_TAG, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "'")
            .replace("&#39;", "'")
            .trim()

    companion object {
        private const val SEARCH_URL = "https://search.htv-services.com/"
        private const val VIDEO_PATH = "/videos/hentai/"
        private const val ORDER_VIEWS = "views"
        private const val ORDER_RELEASED = "released_at_unix"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SLUG_NUMBER = Regex("-(\\d+)$")
        private val HTML_TAG = Regex("<[^>]*>")

        private val QUALITIES = arrayOf("1080p", "720p", "480p", "360p")
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}
