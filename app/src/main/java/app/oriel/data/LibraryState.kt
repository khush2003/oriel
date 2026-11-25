package app.oriel.data

import app.oriel.core.model.Channel
import app.oriel.core.model.Playlist
import app.oriel.core.model.Video
import app.oriel.util.SkipBadList
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.UUID

// Library lists skip entries they can't read instead of losing the whole library over one.
object SkipBadVideos : SkipBadList<Video>(Video.serializer(), "videos")
object SkipBadChannels : SkipBadList<Channel>(Channel.serializer(), "channels")
object SkipBadPlaylists : SkipBadList<Playlist>(Playlist.serializer(), "playlists")
object SkipBadWatchEntries : SkipBadList<WatchEntry>(WatchEntry.serializer(), "history")
object SkipBadLocalPlaylists : SkipBadList<LocalPlaylist>(LocalPlaylist.serializer(), "local playlists")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocalPlaylist(
    // Always written: a default left out of the file would be made anew, differently, on each read.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val id: String = UUID.randomUUID().toString(),
    val name: String,
    @Serializable(with = SkipBadVideos::class) val videos: List<Video> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WatchEntry(val video: Video, val watchedAt: Long)

@Serializable
data class LibraryState(
    @Serializable(with = SkipBadVideos::class) val liked: List<Video> = emptyList(),
    val dislikedIds: Set<String> = emptySet(),
    @Serializable(with = SkipBadVideos::class) val watchLater: List<Video> = emptyList(),
    @Serializable(with = SkipBadChannels::class) val subscriptions: List<Channel> = emptyList(),
    @Serializable(with = SkipBadPlaylists::class) val savedPlaylists: List<Playlist> = emptyList(),
    @Serializable(with = SkipBadLocalPlaylists::class) val localPlaylists: List<LocalPlaylist> = emptyList(),
    @Serializable(with = SkipBadWatchEntries::class) val history: List<WatchEntry> = emptyList(),
    /** Where each video was left, in ms; finished videos are dropped. */
    val positions: Map<String, Long> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
)
