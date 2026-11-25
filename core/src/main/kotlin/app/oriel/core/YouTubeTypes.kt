package app.oriel.core

class YTException(val status: Int, message: String) : Exception(message)

/** An HLS master playlist and the user agent its playlists and segments are fetched with. */
data class AdaptiveManifest(val url: String, val userAgent: String)

/** A caption track as the player lists it; [url] serves it as YouTube's json3 timed text. */
data class CaptionTrack(val languageCode: String, val name: String, val isAuto: Boolean, val url: String)

data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Where a link leads; exactly one of video, browse (channel) or playlist is the destination. */
data class ResolvedUrl(val videoId: String? = null, val browseId: String? = null, val params: String? = null, val playlistId: String? = null)
