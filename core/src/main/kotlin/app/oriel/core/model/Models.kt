package app.oriel.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The app's view of YouTube: small immutable values, serializable so pages can
 * be kept on disk and library entries persisted as they are.
 *
 * Every browsable surface (home, topics, search, channel tabs, subscriptions)
 * parses into the same [Feed]: a list of [Shelf]s. The UI then needs one
 * renderer per shelf kind rather than one per page.
 */

@Serializable
data class ChannelRef(
    val name: String,
    val id: String? = null,
    val avatar: String? = null,
    val verified: Boolean = false,
)

@Serializable
sealed interface Item {
    val id: String
    val title: String
}

@Serializable
@SerialName("video")
data class Video(
    override val id: String,
    override val title: String,
    val channel: ChannelRef? = null,
    val thumbnail: String? = null,
    val durationSec: Int? = null,
    /** "1M views" — as YouTube words it for this surface. */
    val viewsText: String? = null,
    /** "2 days ago", "Streamed 4 weeks ago", "Premieres Oct 21". */
    val publishedText: String? = null,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val isShort: Boolean = false,
    /** Quality and feature badges: "4K", "CC", "New". */
    val badges: List<String> = emptyList(),
    /** How far the signed-in viewer got, 0–1, when YouTube reports it. */
    val progress: Float? = null,
    /** A line of description, as search results show it. */
    val snippet: String? = null,
) : Item

@Serializable
@SerialName("channel")
data class Channel(
    override val id: String,
    override val title: String,
    val avatar: String? = null,
    val handle: String? = null,
    val subscribersText: String? = null,
    val videosText: String? = null,
    val verified: Boolean = false,
    val description: String? = null,
) : Item {
    fun ref() = ChannelRef(title, id, avatar, verified)
}

@Serializable
@SerialName("playlist")
data class Playlist(
    /** The playlist id without the browse "VL" prefix. */
    override val id: String,
    override val title: String,
    val owner: ChannelRef? = null,
    val thumbnail: String? = null,
    /** "12 videos", "Updated today". */
    val countText: String? = null,
    /** Mixes and radios are endless and generated: they open as a watch queue, not a page. */
    val isMix: Boolean = false,
    val firstVideoId: String? = null,
) : Item

@Serializable
@SerialName("post")
data class Post(
    override val id: String,
    /** Post text; posts have no title, so this is the text too. */
    override val title: String,
    val author: ChannelRef? = null,
    val publishedText: String? = null,
    val likesText: String? = null,
    val commentsText: String? = null,
    val images: List<String> = emptyList(),
    val video: Video? = null,
    val pollChoices: List<String> = emptyList(),
) : Item

@Serializable
enum class ShelfKind {
    /** Full-width video cards, one per row: the main feed. */
    @SerialName("FEED")
    FEED,

    /** A horizontal carousel of cards. */
    @SerialName("CAROUSEL")
    CAROUSEL,

    /** A carousel of vertical 9:16 cards: a Shorts shelf. */
    @SerialName("SHORTS")
    SHORTS,

    /** Shorts in a two-column grid: a channel's Shorts tab. */
    @SerialName("SHORTS_GRID")
    SHORTS_GRID,

    /** Compact rows with a small thumbnail: search refinements, playlists, related. */
    @SerialName("LIST")
    LIST,
}

/** Kinds whose pages continue one another, so a continuation extends the last shelf. */
val ShelfKind.continuous: Boolean get() = this == ShelfKind.FEED || this == ShelfKind.SHORTS_GRID || this == ShelfKind.LIST

@Serializable
data class Shelf(
    val title: String? = null,
    val subtitle: String? = null,
    val items: List<Item>,
    val kind: ShelfKind,
    /** Where "See all" leads, when the shelf has more. */
    val more: BrowseRef? = null,
)

@Serializable
data class BrowseRef(val browseId: String, val params: String? = null)

@Serializable
data class Chip(
    val title: String,
    /** Continuation token that reloads the feed for this chip (home, channels). */
    val token: String? = null,
    /** Search params, for chips that refine a search. */
    val params: String? = null,
    val selected: Boolean = false,
)

@Serializable
data class Feed(
    val shelves: List<Shelf> = emptyList(),
    val chips: List<Chip> = emptyList(),
    val continuation: String? = null,
    /** Shown instead of content: "Your YouTube history is off", "No results". */
    val message: String? = null,
) {
    val items: List<Item> get() = shelves.flatMap { it.items }
    val isEmpty: Boolean get() = shelves.all { it.items.isEmpty() }

    /** This page followed by [next], merging the seam when both sides are plain feed runs. */
    operator fun plus(next: Feed): Feed {
        val last = shelves.lastOrNull()
        val first = next.shelves.firstOrNull()
        val merged = if (last != null && first != null && last.kind == first.kind && first.title == null && first.kind.continuous) {
            shelves.dropLast(1) + last.copy(items = (last.items + first.items).distinctBy { it.id }) + next.shelves.drop(1)
        } else {
            shelves + next.shelves
        }
        return copy(shelves = merged, continuation = next.continuation)
    }
}

// ---- watch page --------------------------------------------------------------------------

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

@Serializable
data class Chapter(val title: String, val startMs: Long, val thumbnail: String? = null)

/** The watch queue a video was opened from: a playlist or mix, as the side panel shows it. */
@Serializable
data class PlaylistPanel(
    val id: String,
    val title: String,
    val owner: String? = null,
    val videos: List<Video>,
    val currentIndex: Int,
    val isMix: Boolean,
)

@Serializable
data class WatchPage(
    val video: Video,
    val viewCount: String? = null,
    val viewCountShort: String? = null,
    val date: String? = null,
    val relativeDate: String? = null,
    val likeCount: String? = null,
    val likeStatus: LikeStatus = LikeStatus.INDIFFERENT,
    val description: String? = null,
    val subscribersText: String? = null,
    val subscribed: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val commentsToken: String? = null,
    val commentsCount: String? = null,
    /** How the comments can be ordered (Top, Newest), each with the token that loads it. */
    val commentSort: List<SortOption> = emptyList(),
    /** First comment, for the teaser under the description. */
    val commentTeaser: String? = null,
    val commentTeaserAvatar: String? = null,
    val related: List<Item> = emptyList(),
    val relatedContinuation: String? = null,
    val playlist: PlaylistPanel? = null,
)

// ---- channels & playlists ----------------------------------------------------------------

@Serializable
data class ChannelTab(val title: String, val params: String?)

@Serializable
data class ChannelPage(
    val channel: Channel,
    val banner: String? = null,
    val subscribed: Boolean = false,
    val tabs: List<ChannelTab> = emptyList(),
    /** The selected tab's content. */
    val feed: Feed = Feed(),
    val selectedTab: String? = null,
    /** Fetches [ChannelAbout] through [app.oriel.core.YouTube.channelAbout]. */
    val aboutToken: String? = null,
)

/** A channel's About sheet: the full description, its links and its numbers. */
@Serializable
data class ChannelAbout(
    val description: String? = null,
    val links: List<ChannelLink> = emptyList(),
    val url: String? = null,
    val country: String? = null,
    val joinedText: String? = null,
    val viewsText: String? = null,
    val subscribersText: String? = null,
    val videosText: String? = null,
)

/** One entry of the signed-in bell inbox: an upload, a reply, a live stream starting. */
@Serializable
data class InboxNotification(
    val id: String,
    val message: String,
    val sentText: String? = null,
    val read: Boolean = false,
    /** The sender's avatar. */
    val avatar: String? = null,
    /** The video's frame, when it's about one. */
    val thumbnail: String? = null,
    val videoId: String? = null,
    /** Where it leads otherwise, a youtube.com path. */
    val url: String? = null,
)

@Serializable
data class InboxPage(val items: List<InboxNotification> = emptyList(), val continuation: String? = null)

@Serializable
data class ChannelLink(val title: String, val label: String, val url: String, val favicon: String? = null)

@Serializable
data class PlaylistPage(
    val playlist: Playlist,
    val description: String? = null,
    val metadata: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
    val continuation: String? = null,
)

// ---- shorts ------------------------------------------------------------------------------

@Serializable
data class ShortsBatch(val ids: List<String>, val continuation: String?)

/** What the Shorts player overlays on a short. */
@Serializable
data class ShortInfo(
    val id: String,
    val title: String? = null,
    val channel: ChannelRef? = null,
    val likeCount: String? = null,
    val likeStatus: LikeStatus = LikeStatus.INDIFFERENT,
    val commentsCount: String? = null,
    val commentsToken: String? = null,
    val subscribed: Boolean = false,
    /** The sound it uses, as the Shorts player credits it ("Original Sound", or a song). */
    val sound: ShortSound? = null,
)

/** A short's sound: its title, artwork, and the token for the shorts that use it. */
@Serializable
data class ShortSound(val title: String, val image: String? = null, val token: String? = null)

/** The shorts that use a sound, under its title, artwork and who made it. */
@Serializable
data class SoundPage(
    val title: String? = null,
    val subtitle: String? = null,
    val image: String? = null,
    val shorts: List<Video> = emptyList(),
    val continuation: String? = null,
)

// ---- comments ----------------------------------------------------------------------------

@Serializable
data class Comment(
    val id: String,
    val author: String,
    val authorAvatar: String? = null,
    val authorChannelId: String? = null,
    val text: String,
    val published: String? = null,
    val likes: String? = null,
    val replyCount: Int = 0,
    val repliesToken: String? = null,
    val pinned: Boolean = false,
    val hearted: Boolean = false,
    val verified: Boolean = false,
    val isArtist: Boolean = false,
    val isCreator: Boolean = false,
    val isReply: Boolean = false,
)

@Serializable
data class SortOption(val title: String, val token: String, val selected: Boolean)

@Serializable
data class CommentsPage(
    val countText: String? = null,
    val comments: List<Comment> = emptyList(),
    val continuation: String? = null,
    val sort: List<SortOption> = emptyList(),
    val disabledReason: String? = null,
)

// ---- search ------------------------------------------------------------------------------

/** One of YouTube's search filters; [params] is the protobuf YouTube expects. */
@Serializable
data class SearchFilter(
    val group: String,
    val label: String,
    /** The search params that apply this filter (plain base64, as the API takes it). */
    val params: String?,
    val selected: Boolean = false,
    /** Can't combine with what's applied (Channels while "This week" is on). */
    val disabled: Boolean = false,
)

@Serializable
data class SearchPage(
    val feed: Feed,
    /** "Showing results for …" when YouTube corrected the query. */
    val correctedQuery: String? = null,
    val filters: List<SearchFilter> = emptyList(),
)

// ---- account -----------------------------------------------------------------------------

@Serializable
data class AccountInfo(
    // Defaulted so a stored copy always reads; a blank one is treated as none.
    val name: String = "",
    val email: String? = null,
    val handle: String? = null,
    val avatar: String? = null,
)
