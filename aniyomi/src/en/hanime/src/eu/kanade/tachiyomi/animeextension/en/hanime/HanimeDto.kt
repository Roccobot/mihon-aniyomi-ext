package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every field the site is not guaranteed to send is nullable with a default, and the
 * parser is configured with ignoreUnknownKeys: the API is undocumented, so a field
 * appearing or disappearing must not take the whole source down with a parse error.
 */

@Serializable
class SearchResponseDto(
    val page: Int = 0,
    val nbPages: Int = 0,
    // Not an array: the API returns the result list as a JSON *string* that has to be
    // decoded a second time. Do not "fix" this into a List, it will not parse.
    val hits: String = "[]",
)

@Serializable
class HitDto(
    val name: String = "",
    val slug: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val brand: String? = null,
    val description: String? = null,
    @SerialName("released_at_unix") val releasedAtUnix: Long? = null,
)

@Serializable
class VideoResponseDto(
    @SerialName("hentai_video") val video: VideoDetailsDto,
    @SerialName("videos_manifest") val manifest: ManifestDto? = null,
    @SerialName("hentai_franchise_hentai_videos") val franchise: List<FranchiseEntryDto> = emptyList(),
)

@Serializable
class VideoDetailsDto(
    val name: String = "",
    val slug: String = "",
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val brand: String? = null,
    @SerialName("hentai_tags") val tags: List<TagDto> = emptyList(),
    @SerialName("released_at_unix") val releasedAtUnix: Long? = null,
)

@Serializable
class FranchiseEntryDto(
    val name: String = "",
    val slug: String = "",
    @SerialName("released_at_unix") val releasedAtUnix: Long? = null,
)

@Serializable
class TagDto(val text: String = "")

@Serializable
class ManifestDto(val servers: List<ServerDto> = emptyList())

@Serializable
class ServerDto(val streams: List<StreamDto> = emptyList())

@Serializable
class StreamDto(
    val height: String = "0",
    // Blank on streams the site reserves for paying members: those are dropped instead
    // of being worked around.
    val url: String = "",
    @SerialName("filesize_mbs") val fileSizeMbs: Int? = null,
)
