package app.oriel.core.parse

import app.oriel.core.json.arr
import app.oriel.core.json.at
import app.oriel.core.json.bool
import app.oriel.core.json.findFirst
import app.oriel.core.json.get
import app.oriel.core.json.int
import app.oriel.core.json.renderer
import app.oriel.core.json.rendererKey
import app.oriel.core.json.string
import app.oriel.core.json.text
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.core.model.Post
import app.oriel.core.model.Video
import kotlinx.serialization.json.JsonElement

/**
 * One parser per item format YouTube uses, all producing [Item]s.
 *
 * YouTube is midway through moving from per-surface renderers (videoRenderer,
 * playlistRenderer…) to one `lockupViewModel` for everything. Both shapes
 * appear on the same page, so both are handled.
 */
object Items {
    fun item(element: JsonElement?): Item? {
        val key = element.rendererKey ?: return null
        val r = element.renderer
        return when (key) {
            "lockupViewModel" -> lockup(r)
            "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "videoWithContextRenderer" -> video(r)
            "playlistVideoRenderer", "playlistPanelVideoRenderer" -> video(r)
            "shortsLockupViewModel" -> shortsLockup(r)
            "reelItemRenderer" -> reelItem(r)
            "channelRenderer", "gridChannelRenderer" -> channel(r)
            "playlistRenderer", "gridPlaylistRenderer", "radioRenderer", "compactRadioRenderer" -> playlist(r, isMix = key.contains("adio"))
            "backstagePostThreadRenderer" -> post(r.at("post", "backstagePostRenderer") ?: r?.findFirst("backstagePostRenderer"))
            "backstagePostRenderer", "postRenderer" -> post(r)
            "richItemRenderer" -> item(r["content"])
            else -> null
        }
    }

    // ---- lockupViewModel -----------------------------------------------------------------

    private fun lockup(vm: JsonElement?): Item? {
        val id = vm["contentId"].string ?: return null
        val type = vm["contentType"].string.orEmpty()
        val meta = vm.at("metadata", "lockupMetadataViewModel")
        val title = meta.at("title", "content").string ?: return null
        val tap = vm.at("rendererContext", "commandContext", "onTap", "innertubeCommand")
        val parts = meta.at("metadata", "contentMetadataViewModel", "metadataRows").arr.map { row ->
            row["metadataParts"].arr
        }
        val avatar = meta.at("image", "decoratedAvatarViewModel")
        val avatarUrl = avatar.at("avatar", "avatarViewModel", "image", "sources").arr.lastOrNull()?.get("url").string
        val avatarChannel = avatar.at("rendererContext", "commandContext", "onTap", "innertubeCommand", "browseEndpoint", "browseId").string

        return when (type) {
            "LOCKUP_CONTENT_TYPE_VIDEO", "LOCKUP_CONTENT_TYPE_SHORTS" -> {
                val image = vm.at("contentImage", "thumbnailViewModel")
                val badges = image["overlays"].arr.flatMap { ov ->
                    ov.at("thumbnailBottomOverlayViewModel", "badges").arr + ov.at("thumbnailOverlayBadgeViewModel", "thumbnailBadges").arr
                }.mapNotNull { it["thumbnailBadgeViewModel"] }
                val durationBadge = badges.firstNotNullOfOrNull { b -> b["text"].string?.let(Text::duration) }
                val isLive = badges.any { it["badgeStyle"].string == "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE" }
                val progress = image["overlays"].arr.firstNotNullOfOrNull { it.at("thumbnailOverlayProgressBarViewModel", "startPercent").int }
                val flat = parts.flatten()
                val channelPart = flat.firstOrNull { p -> p.at("text", "commandRuns", 0, "onTap", "innertubeCommand", "browseEndpoint") != null }
                    ?: parts.takeIf { it.size >= 2 }?.first()?.firstOrNull()
                val stats = flat.filter { it !== channelPart }.mapNotNull { it.at("text", "content").string }
                // YouTube words these in the language asked for, so the row's shape decides: the row
                // that isn't the channel's holds the views, then the age. The English words are the
                // fallback for rows of another shape.
                val statRow = parts.lastOrNull { row -> row.none { it === channelPart } }.orEmpty().mapNotNull { it.at("text", "content").string }
                val isUpcoming = vm?.findFirst("upcomingEventData") != null || stats.any { it.startsWith("Premieres") || it.startsWith("Scheduled") }
                val viewsText = if (statRow.size >= 2) statRow[0] else stats.firstOrNull(Text::isViews) ?: statRow.singleOrNull()?.takeUnless { isUpcoming || Text.isTime(it) }
                val publishedText = if (statRow.size >= 2) statRow[1] else stats.firstOrNull(Text::isTime) ?: statRow.singleOrNull()?.takeIf { isUpcoming }
                val channelName = channelPart.at("text", "content").string
                val channelId = channelPart.at("text", "commandRuns", 0, "onTap", "innertubeCommand", "browseEndpoint", "browseId").string ?: avatarChannel
                Video(
                    id = id,
                    title = title,
                    channel = channelName?.let { ChannelRef(it, channelId, avatarUrl, verified = channelPart.at("text", "attachmentRuns") != null) },
                    thumbnail = image.at("image", "sources").arr.lastOrNull()?.get("url").string,
                    durationSec = durationBadge,
                    viewsText = viewsText,
                    publishedText = publishedText,
                    isLive = isLive || stats.any { it.contains("watching") },
                    isUpcoming = isUpcoming,
                    isShort = type == "LOCKUP_CONTENT_TYPE_SHORTS" || tap?.get("reelWatchEndpoint") != null,
                    progress = progress?.let { it / 100f },
                )
            }
            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_STATION", "LOCKUP_CONTENT_TYPE_PODCAST", "LOCKUP_CONTENT_TYPE_ALBUM" -> {
                val collection = vm.at("contentImage", "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel")
                    ?: vm.at("contentImage", "thumbnailViewModel")
                val count = collection["overlays"].arr.flatMap { ov ->
                    ov.at("thumbnailOverlayBadgeViewModel", "thumbnailBadges").arr + ov.at("thumbnailBottomOverlayViewModel", "badges").arr
                }.firstNotNullOfOrNull { it.at("thumbnailBadgeViewModel", "text").string }
                val ownerPart = parts.firstOrNull()?.firstOrNull()
                val ownerId = ownerPart.at("text", "commandRuns", 0, "onTap", "innertubeCommand", "browseEndpoint", "browseId").string
                // A linked first part is the owner in any language; an unlinked one is usually the
                // kind of list ("Playlist"), known here only in English.
                val ownerName = ownerPart.at("text", "content").string?.takeIf { ownerId != null || (it != "Playlist" && it != "Podcast" && it != "Mix") }
                val isMix = type == "LOCKUP_CONTENT_TYPE_STATION" || id.startsWith("RD")
                Playlist(
                    id = id,
                    title = title,
                    owner = ownerName?.let { ChannelRef(it, ownerId) },
                    thumbnail = collection.at("image", "sources").arr.lastOrNull()?.get("url").string,
                    countText = count,
                    isMix = isMix,
                    firstVideoId = tap.at("watchEndpoint", "videoId").string ?: tap.at("watchPlaylistEndpoint", "videoId").string,
                )
            }
            "LOCKUP_CONTENT_TYPE_CHANNEL" -> Channel(id, title, avatar = avatarUrl ?: vm.at("contentImage", "thumbnailViewModel", "image", "sources").arr.lastOrNull()?.get("url").string)
            else -> null
        }
    }

    // ---- classic renderers ---------------------------------------------------------------

    private fun video(r: JsonElement?): Video? {
        val id = r["videoId"].string ?: return null
        val title = (r["title"] ?: r["headline"]).text ?: return null
        val byline = (r["ownerText"] ?: r["longBylineText"] ?: r["shortBylineText"]).let { it.at("runs", 0) }
        val overlays = r["thumbnailOverlays"].arr
        val timeStatus = overlays.firstNotNullOfOrNull { it["thumbnailOverlayTimeStatusRenderer"] }
        val overlayStyle = timeStatus["style"].string
        // Only quality and freshness badges; shopping ("22 products") and the like are noise in a card.
        val badges = r["badges"].arr.mapNotNull { it.at("metadataBadgeRenderer", "label").string }.filter { it.length <= 6 && ' ' !in it }
        val badgeStyles = r["badges"].arr.mapNotNull { it.at("metadataBadgeRenderer", "style").string }
        val viewCount = r["viewCountText"].text
        val isLive = overlayStyle == "LIVE" || "BADGE_STYLE_TYPE_LIVE_NOW" in badgeStyles || viewCount?.contains("watching") == true
        val length = (r["lengthText"].text ?: timeStatus["text"].text)?.let(Text::duration)
        return Video(
            id = id,
            title = title,
            channel = byline?.get("text").string?.let { name ->
                ChannelRef(
                    name = name,
                    id = byline.at("navigationEndpoint", "browseEndpoint", "browseId").string,
                    avatar = r.at("channelThumbnailSupportedRenderers", "channelThumbnailWithLinkRenderer", "thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute)
                        ?: r.at("channelThumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute),
                    verified = r["ownerBadges"].arr.any { it.at("metadataBadgeRenderer", "style").string?.contains("VERIFIED") == true },
                )
            },
            thumbnail = r.at("thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute),
            durationSec = length,
            viewsText = (r["shortViewCountText"].text ?: viewCount)?.takeUnless { it.isBlank() },
            publishedText = r["publishedTimeText"].text,
            isLive = isLive,
            isUpcoming = overlayStyle == "UPCOMING" || r["upcomingEventData"] != null,
            isShort = overlayStyle == "SHORTS" || r.at("navigationEndpoint", "reelWatchEndpoint") != null,
            badges = badges.filterNot { it.equals("LIVE", ignoreCase = true) },
            progress = overlays.firstNotNullOfOrNull { it.at("thumbnailOverlayResumePlaybackRenderer", "percentDurationWatched").int }?.let { it / 100f },
            snippet = r["detailedMetadataSnippets"].arr.firstOrNull()?.get("snippetText").text ?: r["descriptionSnippet"].text,
        )
    }

    private fun shortsLockup(vm: JsonElement?): Video? {
        val id = vm.at("onTap", "innertubeCommand", "reelWatchEndpoint", "videoId").string
            // "shorts-shelf-item-<id>". Ids can contain '-' themselves, so not "after the last dash";
            // and they're always 11 characters, which also covers any other prefix.
            ?: vm["entityId"].string?.removePrefix("shorts-shelf-item-")?.takeLast(11)
            ?: return null
        return Video(
            id = id,
            // A sound's page lists them with only a view count; the title is then the start of the label.
            title = vm.at("overlayMetadata", "primaryText", "content").string
                ?: vm["accessibilityText"].string?.substringBeforeLast(", ")?.substringBeforeLast(", ")
                ?: return null,
            thumbnail = vm.at("thumbnail", "sources").arr.firstOrNull()?.get("url").string
                ?: vm.at("thumbnailViewModel", "thumbnailViewModel", "image", "sources").arr.firstOrNull()?.get("url").string,
            viewsText = vm.at("overlayMetadata", "secondaryText", "content").string,
            isShort = true,
        )
    }

    private fun reelItem(r: JsonElement?): Video? {
        val id = r["videoId"].string ?: return null
        return Video(
            id = id,
            title = r["headline"].text ?: return null,
            thumbnail = r.at("thumbnail", "thumbnails").arr.firstOrNull()?.get("url").string,
            viewsText = r["viewCountText"].text,
            isShort = true,
        )
    }

    private fun channel(r: JsonElement?): Channel? {
        val id = r["channelId"].string ?: return null
        // channelRenderer files the handle under subscriberCountText and the
        // subscriber count under videoCountText; sort them by what they say.
        val first = r["subscriberCountText"].text
        val second = r["videoCountText"].text
        val texts = listOfNotNull(first, second)
        val handleFirst = first?.startsWith("@") == true
        return Channel(
            id = id,
            title = r["title"].text ?: return null,
            avatar = r.at("thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute),
            handle = texts.firstOrNull { it.startsWith("@") },
            // By the words in English, otherwise by which of the two holds the handle.
            subscribersText = texts.firstOrNull { it.contains("subscriber") } ?: if (handleFirst) second else first,
            videosText = texts.firstOrNull { it.contains("video") } ?: second.takeUnless { handleFirst },
            verified = r["ownerBadges"].arr.any { it.at("metadataBadgeRenderer", "style").string?.contains("VERIFIED") == true },
            description = r["descriptionSnippet"].text,
        )
    }

    private fun playlist(r: JsonElement?, isMix: Boolean): Playlist? {
        val id = r["playlistId"].string ?: return null
        val owner = (r["shortBylineText"] ?: r["longBylineText"]).at("runs", 0)
        return Playlist(
            id = id,
            title = r["title"].text ?: return null,
            owner = owner?.get("text").string?.let { ChannelRef(it, owner.at("navigationEndpoint", "browseEndpoint", "browseId").string) },
            thumbnail = (r.at("thumbnails", 0, "thumbnails") ?: r.at("thumbnail", "thumbnails")).arr.lastOrNull()?.get("url").string?.let(Text::absolute),
            countText = r["videoCountText"].text ?: r["videoCount"].string?.let { "$it videos" },
            isMix = isMix || id.startsWith("RD"),
            firstVideoId = r.at("navigationEndpoint", "watchEndpoint", "videoId").string,
        )
    }

    private fun post(r: JsonElement?): Post? {
        val id = r["postId"].string ?: return null
        val attachment = r["backstageAttachment"]
        val images = buildList {
            attachment.at("backstageImageRenderer", "image", "thumbnails").arr.lastOrNull()?.get("url").string?.let(::add)
            attachment.at("postMultiImageRenderer", "images").arr.forEach { img ->
                img.at("backstageImageRenderer", "image", "thumbnails").arr.lastOrNull()?.get("url").string?.let(::add)
            }
        }
        val author = r.at("authorText", "runs", 0)
        return Post(
            id = id,
            title = r["contentText"].text.orEmpty(),
            author = author?.get("text").string?.let {
                ChannelRef(it, author.at("navigationEndpoint", "browseEndpoint", "browseId").string, r.at("authorThumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute))
            },
            publishedText = r["publishedTimeText"].text,
            likesText = r["voteCount"].text,
            commentsText = r.at("actionButtons", "commentActionButtonsRenderer", "replyButton", "buttonRenderer", "text").text,
            images = images.map(Text::absolute),
            video = attachment?.get("videoRenderer")?.let(::video),
            pollChoices = attachment.at("pollRenderer", "choices").arr.mapNotNull { it["text"].text },
        )
    }

    /** A channel reference from a `videoOwnerRenderer` (watch page), handling collaboration videos. */
    fun owner(owner: JsonElement?): ChannelRef? {
        val name = owner["title"].text
        val id = owner.at("navigationEndpoint", "browseEndpoint", "browseId").string
        val avatar = owner.at("thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute)
        val verified = owner["badges"].arr.any { it.at("metadataBadgeRenderer", "style").string?.contains("VERIFIED") == true }
        if (name != null) return ChannelRef(name, id, avatar, verified)
        // Collaborations open a dialog listing every channel; the first is the uploader.
        val first = owner?.findFirst("listItemViewModel") ?: return null
        val title = first.at("title", "content").string ?: return null
        val channelId = first.at("title", "commandRuns", 0, "onTap", "innertubeCommand", "browseEndpoint", "browseId").string
        return ChannelRef(title, channelId, avatar, verified)
    }
}

/** Small text helpers shared by the parsers. */
object Text {
    private val CLOCK = Regex("""^(\d+):(\d{2})(?::(\d{2}))?$""")

    /** "13:40" → 820, "3:38:11" → 13091; null for anything else ("LIVE", "SHORTS"). */
    fun duration(text: String): Int? {
        val m = CLOCK.matchEntire(text.trim()) ?: return null
        val (a, b, c) = m.destructured
        return if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
    }

    fun isViews(text: String): Boolean =
        text.contains("view") || text.contains("watching") || Regex("""^[\d.,]+[KMB]?$""").matches(text.trim())

    fun isTime(text: String): Boolean =
        text.contains("ago") || text.startsWith("Streamed") || text.startsWith("Premiere") || text.startsWith("Scheduled") ||
            Regex("""^\d+[smhdwy]o? ago$|^\d+(y|mo|w|d|h|m) ago$""").matches(text)

    /** Protocol-relative URLs ("//yt3…") made absolute. */
    fun absolute(url: String): String = if (url.startsWith("//")) "https:$url" else url
}

/** True when a lockup or renderer node marks its video as already watched to the end. */
internal fun JsonElement?.watched(): Boolean = this?.findFirst("thumbnailOverlayResumePlaybackRenderer")?.get("percentDurationWatched").int == 100 ||
    this?.findFirst("thumbnailOverlayProgressBarViewModel")?.get("startPercent").int == 100 || this["isWatched"].bool == true
