package eu.kanade.tachiyomi.extension.en.nhentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

/**
 * Source built on the site's **v2 API**, not on its pages, and that choice is the whole design.
 *
 * ⚠️ The HTML side sits behind a Cloudflare challenge: the home page answers `403` with
 * `cf-mitigated: challenge` (measured 2026-08-19). The API does not, and answers `200` with no
 * credentials at all, so this source never needs a WebView, a login or a stored cookie. If that
 * ever changes, the fallback is the WebView approach already written for the Aniyomi source in
 * this same repository.
 *
 * The v1 API is gone: it replies with a plain sentence telling developers to move to v2. The
 * schema of what is used here comes from the published OpenAPI document, not from guesswork.
 */
class NHentai : HttpSource() {

    override val name = "nhentai Roccobot"
    override val baseUrl = "https://nhentai.net"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api/v2"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The four image hosts, asked for once and kept.
     *
     * ⚠️ They are NOT shards: the same file answers `206 image/webp` on all four (measured), so
     * spreading pages across them is politeness towards the site, not a requirement. Should the
     * endpoint ever fail, the first host is assumed rather than letting the whole reader die.
     */
    private val imageServers: List<String> by lazy {
        runCatching {
            val body = client.newCall(GET("$apiUrl/cdn", headers)).execute().use { it.body.string() }
            json.decodeFromString<CdnDto>(body).imageServers
        }.getOrElse { listOf("https://i1.nhentai.net") }
    }

    // ── Browsing ──────────────────────────────────────────────────────────────────────

    // ⚠️ This endpoint takes no page parameter and returns one fixed batch, so the list has to
    // declare there is nothing after it: claiming otherwise makes the app ask forever.
    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/galleries/popular", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        MangasPage(json.decodeFromString<List<EntryDto>>(response.body.string()).map(::toManga), false)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiUrl/galleries?page=$page&per_page=$PER_PAGE", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val entries = json.decodeFromString<List<EntryDto>>(response.body.string())
        return MangasPage(entries.map(::toManga), entries.size >= PER_PAGE)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected() ?: SORT_KEYS[0]
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/search?query=$encoded&sort=$sort&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page = json.decodeFromString<SearchDto>(response.body.string())
        return MangasPage(page.result.map(::toManga), page.result.isNotEmpty())
    }

    override fun getFilterList() = FilterList(SortFilter())

    // ── One gallery ───────────────────────────────────────────────────────────────────

    // The stored url is the human one, so 'open in browser' lands where the reader expects;
    // every request made from here rewrites it into its API form.
    private fun galleryId(manga: SManga) = manga.url.substringAfterLast('/')

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/galleries/${galleryId(manga)}", headers)

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val g = json.decodeFromString<GalleryDto>(response.body.string())
        return SManga.create().apply {
            url = "/g/${g.id}"
            title = g.title.pretty ?: g.title.english ?: g.title.japanese.orEmpty()
            thumbnail_url = imageUrl(g.cover.path, 0)
            // Every tag type the site uses is folded into the genre line except the artists and
            // the groups, which are the closest thing here to an author and a scanlator.
            author = g.tags.filter { it.type == "artist" }.joinToString { it.name }
            artist = author
            genre = g.tags.filter { it.type !in AUTHOR_TYPES }.joinToString { it.name }
            description = buildString {
                g.title.japanese?.let { appendLine("Japanese title: $it") }
                appendLine("Pages: ${g.numPages}")
                g.tags.filter { it.type == "parody" }.takeIf { it.isNotEmpty() }
                    ?.let { appendLine("Parody: ${it.joinToString { tag -> tag.name }}") }
            }.trim()
            status = SManga.COMPLETED
            initialized = true
        }
    }

    // A gallery is a single, finished piece: one chapter, and the upload date is the only date
    // the API gives, in seconds.
    override fun chapterListParse(response: Response): List<SChapter> {
        val g = json.decodeFromString<GalleryDto>(response.body.string())
        return listOf(
            SChapter.create().apply {
                url = "/g/${g.id}"
                name = "Gallery"
                chapter_number = 1f
                date_upload = g.uploadDate * 1000L
            },
        )
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$apiUrl/galleries/${chapter.url.substringAfterLast('/')}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val g = json.decodeFromString<GalleryDto>(response.body.string())
        return g.pages.map { p -> Page(p.number - 1, "", imageUrl(p.path, p.number)) }
    }

    // ⚠️ Never called: every page carries its address already. It throws instead of returning
    // an empty string, because a silent empty url shows as a blank page that looks like a
    // network problem.
    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used: page urls are built from the gallery")

    private fun imageUrl(path: String, index: Int): String =
        "${imageServers[index % imageServers.size]}/$path"

    private fun toManga(entry: EntryDto) = SManga.create().apply {
        url = "/g/${entry.id}"
        title = entry.englishTitle ?: entry.japaneseTitle.orEmpty()
        thumbnail_url = imageUrl(entry.thumbnail, entry.id)
    }

    // ── The shapes the API answers with ───────────────────────────────────────────────

    @Serializable
    private class CdnDto(@SerialName("image_servers") val imageServers: List<String>)

    @Serializable
    private class EntryDto(
        val id: Int,
        val thumbnail: String,
        @SerialName("english_title") val englishTitle: String? = null,
        @SerialName("japanese_title") val japaneseTitle: String? = null,
    )

    @Serializable
    private class SearchDto(val result: List<EntryDto>)

    @Serializable
    private class GalleryDto(
        val id: Int,
        val title: TitleDto,
        val cover: ImageDto,
        val tags: List<TagDto> = emptyList(),
        val pages: List<PageDto> = emptyList(),
        @SerialName("num_pages") val numPages: Int = 0,
        @SerialName("upload_date") val uploadDate: Long = 0L,
    )

    @Serializable
    private class TitleDto(
        val english: String? = null,
        val japanese: String? = null,
        val pretty: String? = null,
    )

    @Serializable
    private class ImageDto(val path: String)

    @Serializable
    private class TagDto(val type: String, val name: String)

    @Serializable
    private class PageDto(val number: Int, val path: String)

    private class SortFilter : Filter.Select<String>("Sort by", SORT_LABELS) {
        fun selected() = SORT_KEYS[state]
    }

    companion object {
        private const val PER_PAGE = 25

        // The values the search endpoint accepts, in the order the app offers them.
        private val SORT_KEYS = arrayOf("recent", "popular", "popular-week", "popular-today")
        private val SORT_LABELS = arrayOf("Recent", "Popular", "Popular this week", "Popular today")

        private val AUTHOR_TYPES = setOf("artist", "group")
    }
}
