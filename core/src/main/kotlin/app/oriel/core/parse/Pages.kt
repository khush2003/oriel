package app.oriel.core.parse

import app.oriel.core.json.arr
import app.oriel.core.json.at
import app.oriel.core.json.bool
import app.oriel.core.json.findAll
import app.oriel.core.json.findFirst
import app.oriel.core.json.get
import app.oriel.core.json.int
import app.oriel.core.json.long
import app.oriel.core.json.renderer
import app.oriel.core.json.rendererKey
import app.oriel.core.json.string
import app.oriel.core.json.text
import app.oriel.core.model.AccountInfo
import app.oriel.core.model.BrowseRef
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelAbout
import app.oriel.core.model.InboxNotification
import app.oriel.core.model.InboxPage
import app.oriel.core.model.ChannelLink
import app.oriel.core.model.ChannelPage
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.ChannelTab
import app.oriel.core.model.Chapter
import app.oriel.core.model.Chip
import app.oriel.core.model.Comment
import app.oriel.core.model.CommentsPage
import app.oriel.core.model.Feed
import app.oriel.core.model.Item
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.Playlist
import app.oriel.core.model.PlaylistPage
import app.oriel.core.model.PlaylistPanel
import app.oriel.core.model.SearchFilter
import app.oriel.core.model.SearchPage
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.ShortInfo
import app.oriel.core.model.ShortSound
import app.oriel.core.model.SoundPage
import app.oriel.core.model.ShortsBatch
import app.oriel.core.model.SortOption
import app.oriel.core.model.Video
import app.oriel.core.model.WatchPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Page-level parsers: one function per InnerTube response shape the app consumes. */
object Pages {

    // ---- feeds ---------------------------------------------------------------------------

    /**
     * Folds any mix of YouTube's container renderers into a [Feed].
     *
     * Loose items (a grid, a search result list) collect into an untitled shelf
     * until a titled shelf interrupts them, so the page keeps its order.
     */
    private class FeedBuilder {
        val shelves = ArrayList<Shelf>()
        val chips = ArrayList<Chip>()
        var continuation: String? = null
        var message: String? = null
        private val loose = ArrayList<Item>()

        fun flush() {
            if (loose.isEmpty()) return
            val allShorts = loose.all { it is Video && it.isShort }
            shelves += Shelf(items = loose.toList(), kind = if (allShorts) ShelfKind.SHORTS_GRID else ShelfKind.FEED)
            loose.clear()
        }

        fun shelf(title: String?, items: List<Item>, kind: ShelfKind, subtitle: String? = null, more: BrowseRef? = null) {
            if (items.isEmpty()) return
            flush()
            shelves += Shelf(title, subtitle, items, kind, more)
        }

        fun addAll(elements: List<JsonElement>) = elements.forEach(::add)

        fun add(element: JsonElement) {
            // Some containers hold several renderers side by side (an empty list ahead of the real grid).
            val obj = element as? JsonObject
            if (obj != null && obj.size > 1 && obj.keys.all { it.endsWith("Renderer") || it.endsWith("ViewModel") }) {
                obj.forEach { (k, v) -> add(JsonObject(mapOf(k to v))) }
                return
            }
            val r = element.renderer
            when (element.rendererKey) {
                "twoColumnBrowseResultsRenderer" -> selectedTab(r)?.let(::add)
                // A search chip reloads the whole results column.
                "twoColumnSearchResultsRenderer" -> r["primaryContents"]?.let(::add)
                "richGridRenderer" -> {
                    chips += chipsOf(r["header"])
                    addAll(r["contents"].arr)
                }
                "sectionListRenderer" -> {
                    r["subMenu"]?.let { chips += chipsOf(it) }
                    addAll(r["contents"].arr)
                }
                "itemSectionRenderer" -> addAll(r["contents"].arr)
                "richSectionRenderer" -> r["content"]?.let(::add)
                "richShelfRenderer" -> {
                    val items = r["contents"].arr.mapNotNull { Items.item(it) }
                    shelf(r["title"].text, items, if (items.allShorts()) ShelfKind.SHORTS else ShelfKind.CAROUSEL, r["subtitle"].text, browseRef(r["endpoint"]))
                }
                "shelfRenderer" -> {
                    val content = r["content"]
                    val inner = content.renderer
                    val items = (inner["items"] ?: inner["contents"]).arr.mapNotNull { Items.item(it) }
                    val kind = when {
                        items.allShorts() -> ShelfKind.SHORTS
                        content.rendererKey == "horizontalListRenderer" -> ShelfKind.CAROUSEL
                        else -> ShelfKind.LIST
                    }
                    shelf(r["title"].text, items, kind, r["subtitle"].text, browseRef(r["endpoint"]))
                }
                "reelShelfRenderer" -> shelf(r["title"].text ?: "Shorts", r["items"].arr.mapNotNull { Items.item(it) }, ShelfKind.SHORTS)
                "gridShelfViewModel" -> {
                    val items = r["contents"].arr.mapNotNull { Items.item(it) }
                    shelf(r.at("header", "sectionHeaderViewModel", "headline", "content").string, items, if (items.allShorts()) ShelfKind.SHORTS else ShelfKind.CAROUSEL)
                }
                "horizontalListRenderer", "gridRenderer", "expandedShelfContentsRenderer", "verticalListRenderer" ->
                    (r["items"] ?: r["contents"]).arr.forEach { Items.item(it)?.let(loose::add) }
                "channelVideoPlayerRenderer" -> featured(r)?.let { shelf(null, listOf(it), ShelfKind.FEED) }
                "channelFeaturedContentRenderer" -> shelf("Live now", r["items"].arr.mapNotNull { Items.item(it) }, ShelfKind.CAROUSEL)
                // The first continuation is the list's own; later ones load sections further down.
                "continuationItemRenderer", "continuationItemViewModel" -> if (continuation == null) continuation = continuationOf(element)
                "messageRenderer" -> message = r["text"].text ?: r["subtext"].text
                "feedNudgeRenderer", "backgroundPromoRenderer" -> message = r["title"].text
                "itemSectionHeaderRenderer", "horizontalCardListRenderer", "recognitionShelfRenderer", "adSlotRenderer",
                "searchPyvRenderer", "promotedSparklesWebRenderer", "statementBannerRenderer", "brandVideoShelfRenderer",
                "channelAboutFullMetadataRenderer", "shelfHeaderRenderer" -> Unit
                else -> Items.item(element)?.let(loose::add)
            }
        }

        fun build(): Feed {
            flush()
            return Feed(shelves.toList(), chips.distinctBy { it.title }, continuation, message)
        }
    }

    private fun List<Item>.allShorts() = isNotEmpty() && all { it is Video && it.isShort }

    private fun featured(r: JsonElement?): Video? {
        val id = r["videoId"].string ?: return null
        return Video(
            id = id,
            title = r["title"].text ?: return null,
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            viewsText = r["viewCountText"].text,
            publishedText = r["publishedTimeText"].text,
            snippet = r["description"].text,
        )
    }

    private fun selectedTab(twoColumn: JsonElement?): JsonElement? {
        val tabs = twoColumn["tabs"].arr.mapNotNull { it["tabRenderer"] ?: it["expandableTabRenderer"] }
        return (tabs.firstOrNull { it["selected"].bool == true } ?: tabs.firstOrNull())?.get("content")
    }

    private fun browseRef(endpoint: JsonElement?): BrowseRef? {
        val b = endpoint["browseEndpoint"] ?: return null
        return BrowseRef(b["browseId"].string ?: return null, b["params"].string)
    }

    /** The token of a continuation item, in either its renderer or view-model form. */
    fun continuationOf(element: JsonElement?): String? {
        val r = element.renderer ?: return null
        return r.at("continuationEndpoint", "continuationCommand", "token").string
            ?: r.at("button", "buttonRenderer", "command", "continuationCommand", "token").string
            ?: r.findAll("continuationCommand").firstNotNullOfOrNull { it["token"].string }
    }

    /** Filter chips, in both the classic `chipCloudChipRenderer` and the newer `chipViewModel` form. */
    private fun chipsOf(header: JsonElement?): List<Chip> {
        if (header == null) return emptyList()
        val classic = header.findAll("chipCloudChipRenderer").mapNotNull { c ->
            Chip(
                title = c["text"].text ?: return@mapNotNull null,
                token = c.at("navigationEndpoint", "continuationCommand", "token").string,
                params = c.at("navigationEndpoint", "searchEndpoint", "params").string,
                selected = c["isSelected"].bool == true,
            )
        }
        // Channel tabs show their sort as a drop-down chip whose options live in a sheet.
        val modern = header.findAll("chipViewModel").flatMap { c ->
            val options = c.findAll("listItemViewModel")
            if (options.isNotEmpty()) {
                options.mapNotNull { o ->
                    Chip(o.at("title", "content").string ?: return@mapNotNull null, o.findAll("continuationCommand").firstNotNullOfOrNull { it["token"].string }, selected = o["isSelected"].bool == true)
                }
            } else {
                listOfNotNull(c["text"].string?.let { Chip(it, c.findAll("continuationCommand").firstNotNullOfOrNull { it["token"].string }, selected = c["isSelected"].bool == true) })
            }
        }
        return classic + modern
    }

    /** Any browse response: home, topics, channel tabs, subscriptions, history, library pages. */
    fun feed(root: JsonElement): Feed {
        val b = FeedBuilder()
        root["contents"]?.let { contents -> contents.rendererKey?.let { b.add(contents) } }
        return b.build()
    }

    /** The items of a continuation response, whether it appends or reloads (a chip tap). */
    fun continuation(root: JsonElement): Feed {
        val actions = root["onResponseReceivedActions"].arr + root["onResponseReceivedCommands"].arr + root["onResponseReceivedEndpoints"].arr
        val b = FeedBuilder()
        for (a in actions) {
            val items = a.at("appendContinuationItemsAction", "continuationItems")
                ?: a.at("reloadContinuationItemsCommand", "continuationItems")
                ?: continue
            // A chip reload also replaces the chip bar; that arrives as its own command.
            if (a.at("reloadContinuationItemsCommand", "slot").string == "RELOAD_CONTINUATION_SLOT_HEADER") {
                items.arr.forEach { b.chips += chipsOf(it) }
                continue
            }
            b.addAll(items.arr)
        }
        root.at("continuationContents", "sectionListContinuation")?.let { b.addAll(it["contents"].arr) }
        return b.build()
    }

    // ---- search --------------------------------------------------------------------------

    fun search(root: JsonElement): SearchPage {
        val b = FeedBuilder()
        root.at("contents", "twoColumnSearchResultsRenderer", "primaryContents")?.let(b::add)
        b.chips += chipsOf(root.at("header", "searchHeaderRenderer", "chipBar"))
        val corrected = root.findFirst("showingResultsForRenderer")?.get("correctedQuery").text
            ?: root.findFirst("didYouMeanRenderer")?.get("correctedQuery").text
        val filters = root.findAll("searchFilterGroupRenderer").flatMap { g ->
            val group = g["title"].text.orEmpty()
            g["filters"].arr.mapNotNull { f ->
                val r = f["searchFilterRenderer"] ?: return@mapNotNull null
                // The params come URL-encoded ("EgIQAQ%3D%3D"); sent like that, the filter did nothing.
                val params = r.at("navigationEndpoint", "searchEndpoint", "params").string?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                val status = r["status"].string
                SearchFilter(group, r["label"].text ?: return@mapNotNull null, params, selected = status == "FILTER_STATUS_SELECTED", disabled = status == "FILTER_STATUS_DISABLED")
            }
        }
        val feed = b.build()
        return SearchPage(if (feed.isEmpty && feed.message == null) feed.copy(message = "No results") else feed, corrected, filters)
    }

    /** suggestqueries JSONP: `window.google.ac.h(["q",[["lofi",0,[512]],…],{…}])`. */
    fun suggestions(jsonp: String): List<String> {
        val body = jsonp.substringAfter('(', "").substringBeforeLast(')', "")
        if (body.isEmpty()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        return root[1].arr.mapNotNull { it[0].string }
    }

    // ---- watch page ----------------------------------------------------------------------

    fun watch(videoId: String, root: JsonElement, origin: Video? = null): WatchPage {
        val watchNext = root.at("contents", "twoColumnWatchNextResults")
        val contents = watchNext.at("results", "results", "contents").arr
        val primary = contents.firstNotNullOfOrNull { it["videoPrimaryInfoRenderer"] }
        val secondary = contents.firstNotNullOfOrNull { it["videoSecondaryInfoRenderer"] }
        val ownerRenderer = secondary.at("owner", "videoOwnerRenderer")
        val views = primary.at("viewCount", "videoViewCountRenderer")
        val likeButton = primary?.findFirst("likeButtonViewModel")
        val likeTitle = likeButton?.findFirst("buttonViewModel")?.get("title").string
        val likeStatus = when (root.findFirst("likeStatusEntity")?.get("likeStatus").string) {
            "LIKE" -> LikeStatus.LIKE
            "DISLIKE" -> LikeStatus.DISLIKE
            else -> LikeStatus.INDIFFERENT
        }
        val panels = root["engagementPanels"].arr.mapNotNull { it["engagementPanelSectionListRenderer"] }
        val commentsPanel = panels.firstOrNull { it["panelIdentifier"].string == "engagement-panel-comments-section" }
        val commentsToken = commentsPanel?.get("content")?.findAll("continuationCommand")?.firstNotNullOfOrNull { it["token"].string }
            ?: contents.firstNotNullOfOrNull { c ->
                c["itemSectionRenderer"]?.takeIf { it["sectionIdentifier"].string == "comment-item-section" }?.findAll("continuationCommand")?.firstNotNullOfOrNull { it["token"].string }
            }
        val commentsCount = commentsPanel.at("header", "engagementPanelTitleHeaderRenderer", "contextualInfo").text
        // Top / Newest: offered on the watch page's panel now, no longer in the comments' own header.
        val commentSort = sortOptions(commentsPanel.at("header", "engagementPanelTitleHeaderRenderer", "menu", "sortFilterSubMenuRenderer", "subMenuItems").arr)
        val description = secondary.at("attributedDescription", "content").string
            ?: root.findFirst("expandableVideoDescriptionBodyRenderer")?.at("attributedDescriptionBodyText", "content").string

        val related = ArrayList<Item>()
        var relatedContinuation: String? = null
        fun collect(list: List<JsonElement>) {
            for (e in list) when (e.rendererKey) {
                "itemSectionRenderer" -> collect(e.renderer["contents"].arr)
                "continuationItemRenderer" -> relatedContinuation = continuationOf(e)
                else -> Items.item(e)?.let(related::add)
            }
        }
        collect(watchNext.at("secondaryResults", "secondaryResults", "results").arr)

        return WatchPage(
            video = Video(
                id = videoId,
                title = primary["title"].text ?: origin?.title.orEmpty(),
                channel = Items.owner(ownerRenderer) ?: origin?.channel,
                thumbnail = origin?.thumbnail ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                durationSec = origin?.durationSec,
                viewsText = views["shortViewCount"].text ?: origin?.viewsText,
                publishedText = primary["relativeDateText"].text ?: origin?.publishedText,
                isLive = views["isLive"].bool == true || origin?.isLive == true,
                isShort = origin?.isShort == true,
            ),
            viewCount = views["viewCount"].text,
            viewCountShort = views["shortViewCount"].text ?: views["extraShortViewCount"].text,
            date = primary["dateText"].text,
            relativeDate = primary["relativeDateText"].text,
            likeCount = likeTitle?.takeIf { it.any(Char::isDigit) },
            likeStatus = likeStatus,
            description = description,
            subscribersText = ownerRenderer["subscriberCountText"].text,
            subscribed = secondary.at("subscribeButton", "subscribeButtonRenderer", "subscribed").bool
                ?: root.findFirst("subscriptionStateEntity")?.get("subscribed").bool
                ?: false,
            chapters = chapters(root),
            commentsToken = commentsToken,
            commentsCount = commentsCount,
            commentSort = commentSort,
            related = related,
            relatedContinuation = relatedContinuation,
            playlist = watchNext.at("playlist", "playlist")?.let(::playlistPanel),
        )
    }

    private fun chapters(root: JsonElement): List<Chapter> {
        val fromBar = root["playerOverlays"]?.findAll("chapterRenderer").orEmpty().mapNotNull { c ->
            Chapter(c["title"].text ?: return@mapNotNull null, c["timeRangeStartMillis"].long ?: return@mapNotNull null, c.at("thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string)
        }
        val chapters = fromBar.ifEmpty {
            root.findAll("macroMarkersListItemRenderer").mapNotNull { m ->
                val start = m.at("onTap", "watchEndpoint", "startTimeSeconds").long ?: return@mapNotNull null
                Chapter(m["title"].text ?: return@mapNotNull null, start * 1000, m.at("thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string)
            }
        }
        return chapters.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    private fun playlistPanel(p: JsonElement): PlaylistPanel? {
        val id = p["playlistId"].string ?: return null
        val contents = p["contents"].arr
        val selected = contents.indexOfFirst { it.at("playlistPanelVideoRenderer", "selected").bool == true }
        return PlaylistPanel(
            id = id,
            title = p["title"].text ?: p["titleText"].text ?: "Playlist",
            owner = p["ownerName"].text ?: p["shortBylineText"].text,
            videos = contents.mapNotNull { Items.item(it) as? Video },
            currentIndex = selected.takeIf { it >= 0 } ?: p["currentIndex"].int ?: 0,
            isMix = p["isInfinite"].bool == true || id.startsWith("RD"),
        )
    }

    // ---- shorts --------------------------------------------------------------------------

    /**
     * `reel/reel_watch_sequence`: the next batch of short ids and where the sequence continues.
     * Ads arrive as ordinary entries whose endpoint carries `adClientParams`; they're dropped.
     */
    fun shortsSequence(root: JsonElement): ShortsBatch = ShortsBatch(
        ids = root["entries"].arr.mapNotNull { entry ->
            val endpoint = entry.at("command", "reelWatchEndpoint")
            endpoint["videoId"].string?.takeIf { endpoint["adClientParams"] == null }
        }.distinct(),
        continuation = root.at("continuationEndpoint", "continuationCommand", "token").string,
    )

    /** What the Shorts overlay needs, read from a WEB `next` response for the short. */
    fun shortInfo(videoId: String, root: JsonElement): ShortInfo {
        val page = watch(videoId, root)
        return ShortInfo(
            id = videoId,
            title = page.video.title.ifBlank { null },
            channel = page.video.channel,
            likeCount = page.likeCount,
            likeStatus = page.likeStatus,
            commentsCount = page.commentsCount,
            commentsToken = page.commentsToken,
            subscribed = page.subscribed,
        )
    }

    /** The sound credit from a `reel/reel_item_watch` overlay, with the token of the panel listing its shorts. */
    fun shortSound(reel: JsonElement): ShortSound? {
        val pivot = reel.findFirst("pivotButtonViewModel") ?: return null
        val title = pivot.at("soundAttributionTitle", "content").string?.takeIf { it.isNotBlank() } ?: return null
        val image = pivot.at("thumbnail", "sources").arr.lastOrNull()?.get("url").string
        val token = reel["engagementPanels"].arr.map { it["engagementPanelSectionListRenderer"] }
            .firstOrNull { it["targetId"].string == "engagement-panel-shorts-audio-pivot" }
            ?.findFirst("continuationCommand")?.get("token").string
        return ShortSound(title, image?.let(Text::absolute), token)
    }

    /** A sound's page (`FEsfv_audio_pivot`): its header, and the shorts using it. */
    fun soundPage(root: JsonElement): SoundPage {
        val items = (root["onResponseReceivedActions"].arr + root["onResponseReceivedEndpoints"].arr).flatMap { a ->
            (a.at("reloadContinuationItemsCommand", "continuationItems") ?: a.at("appendContinuationItemsAction", "continuationItems")).arr
        }
        val header = items.firstNotNullOfOrNull { it["pageHeaderViewModel"] }
        return SoundPage(
            title = header.at("title", "dynamicTextViewModel", "text", "content").string,
            subtitle = header?.get("metadata")?.findAll("metadataParts").orEmpty().flatMap { it.arr }
                .mapNotNull { it.at("text", "content").string }.joinToString(" · ").ifBlank { null },
            image = header.at("image", "contentPreviewImageViewModel", "image", "sources").arr.firstOrNull()?.get("url").string,
            shorts = items.mapNotNull { it.at("richItemRenderer", "content")?.let(Items::item) as? Video }.distinctBy { it.id },
            continuation = items.firstNotNullOfOrNull { it.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").string },
        )
    }

    // ---- channels ------------------------------------------------------------------------

    fun channel(browseId: String, root: JsonElement): ChannelPage {
        val vm = root.at("header", "pageHeaderRenderer", "content", "pageHeaderViewModel")
        val c4 = root.at("header", "c4TabbedHeaderRenderer")
        val meta = root.at("metadata", "channelMetadataRenderer")
        val parts = vm.at("metadata", "contentMetadataViewModel", "metadataRows").arr
            .flatMap { it["metadataParts"].arr }.mapNotNull { it.at("text", "content").string }
        val avatar = vm.at("image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image", "sources").arr.lastOrNull()?.get("url").string
            ?: c4.at("avatar", "thumbnails").arr.lastOrNull()?.get("url").string
            ?: meta.at("avatar", "thumbnails").arr.lastOrNull()?.get("url").string
        val banner = vm.at("banner", "imageBannerViewModel", "image", "sources").arr.lastOrNull()?.get("url").string
            ?: c4.at("banner", "thumbnails").arr.lastOrNull()?.get("url").string
        val tabRenderers = root.at("contents", "twoColumnBrowseResultsRenderer", "tabs").arr.mapNotNull { it["tabRenderer"] }
        return ChannelPage(
            channel = Channel(
                id = meta["externalId"].string ?: browseId,
                title = vm.at("title", "dynamicTextViewModel", "text", "content").string ?: c4["title"].string ?: meta["title"].string.orEmpty(),
                avatar = avatar?.let(Text::absolute),
                handle = parts.firstOrNull { it.startsWith("@") } ?: c4["channelHandleText"].text,
                subscribersText = parts.firstOrNull { it.contains("subscriber") } ?: c4["subscriberCountText"].text,
                videosText = parts.firstOrNull { it.contains("video") } ?: c4["videosCountText"].text,
                verified = vm.at("title", "dynamicTextViewModel", "text", "attachmentRuns") != null || c4["badges"].arr.isNotEmpty(),
                description = vm.at("description", "descriptionPreviewViewModel", "description", "content").string ?: meta["description"].string,
            ),
            banner = banner?.let(Text::absolute),
            subscribed = root.findFirst("subscriptionStateEntity")?.get("subscribed").bool
                ?: c4.at("subscribeButton", "subscribeButtonRenderer", "subscribed").bool
                ?: false,
            tabs = tabRenderers.mapNotNull { r -> ChannelTab(r["title"].string ?: return@mapNotNull null, r.at("endpoint", "browseEndpoint", "params").string) },
            feed = feed(root),
            selectedTab = tabRenderers.firstOrNull { it["selected"].bool == true }?.get("title").string,
            // The description's "more" opens the About panel, which loads from this continuation.
            aboutToken = (vm["description"]?.findFirst("continuationCommand") ?: vm["attribution"]?.findFirst("continuationCommand"))?.get("token").string,
        )
    }

    fun channelAbout(root: JsonElement): ChannelAbout {
        val vm = root.findFirst("aboutChannelViewModel") ?: return ChannelAbout()
        return ChannelAbout(
            description = vm["description"].string?.trim()?.ifEmpty { null },
            links = vm["links"].arr.mapNotNull { l ->
                val link = l["channelExternalLinkViewModel"] ?: return@mapNotNull null
                val label = link["link"].text ?: return@mapNotNull null
                val raw = link.at("link", "commandRuns", 0)?.findFirst("urlEndpoint")?.get("url").string
                ChannelLink(
                    title = link["title"].text ?: label,
                    label = label,
                    url = raw?.let(::unwrapRedirect) ?: if (label.startsWith("http")) label else "https://$label",
                    favicon = link.at("favicon", "sources").arr.lastOrNull()?.get("url").string,
                )
            },
            url = vm["displayCanonicalChannelUrl"].string ?: vm["canonicalChannelUrl"].string,
            country = vm["country"].string,
            joinedText = vm["joinedDateText"].text,
            viewsText = vm["viewCountText"].string,
            subscribersText = vm["subscriberCountText"].string,
            videosText = vm["videoCountText"].string,
        )
    }

    /** The bell inbox, first page or continuation: both carry notificationRenderers and maybe a next ctoken. */
    fun inbox(root: JsonElement): InboxPage = InboxPage(
        items = root.findAll("notificationRenderer").mapNotNull { n ->
            val message = n["shortMessage"].text ?: return@mapNotNull null
            val nav = n["navigationEndpoint"]
            InboxNotification(
                id = n["notificationId"].string ?: message.hashCode().toString(),
                message = message,
                sentText = n["sentTimeText"].text,
                read = n["read"].bool == true,
                avatar = n.at("thumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute),
                thumbnail = n.at("videoThumbnail", "thumbnails").arr.lastOrNull()?.get("url").string?.let(Text::absolute),
                videoId = nav.at("watchEndpoint", "videoId").string,
                url = nav.at("commandMetadata", "webCommandMetadata", "url").string,
            )
        },
        continuation = root.findFirst("getNotificationMenuEndpoint")?.get("ctoken").string,
    )

    /** YouTube's outbound links go through /redirect?q=<target>; the target is what's shown and opened. */
    private fun unwrapRedirect(url: String): String {
        if ("/redirect?" !in url) return url
        val q = url.substringAfter('?').split('&').firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: return url
        return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrDefault(url)
    }

    // ---- playlists -----------------------------------------------------------------------

    fun playlist(playlistId: String, root: JsonElement): PlaylistPage {
        val vm = root.at("header", "pageHeaderRenderer", "content", "pageHeaderViewModel")
        val classic = root.at("header", "playlistHeaderRenderer")
        val parts = vm.at("metadata", "contentMetadataViewModel", "metadataRows").arr.flatMap { it["metadataParts"].arr }
        val texts = parts.mapNotNull { it.at("text", "content").string ?: it.at("avatarStack", "avatarStackViewModel", "text", "content").string }
        val ownerPart = parts.firstOrNull { it["avatarStack"] != null } ?: parts.firstOrNull()
        val ownerName = texts.firstOrNull { it.startsWith("by ") }?.removePrefix("by ") ?: classic["ownerText"].text
        val ownerId = ownerPart?.findFirst("browseEndpoint")?.get("browseId").string
            ?: classic.at("ownerText", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").string
        val feed = feed(root)
        val videos = feed.items.filterIsInstance<Video>()
        return PlaylistPage(
            playlist = Playlist(
                id = playlistId,
                title = vm.at("title", "dynamicTextViewModel", "text", "content").string
                    ?: classic["title"].text
                    ?: root.at("metadata", "playlistMetadataRenderer", "title").string
                    ?: "Playlist",
                owner = ownerName?.let { ChannelRef(it, ownerId) },
                thumbnail = vm.at("heroImage", "contentPreviewImageViewModel", "image", "sources").arr.lastOrNull()?.get("url").string
                    ?: videos.firstOrNull()?.thumbnail,
                countText = texts.firstOrNull { it.contains("video") } ?: classic["numVideosText"].text,
                isMix = playlistId.startsWith("RD"),
                firstVideoId = videos.firstOrNull()?.id,
            ),
            description = vm.at("description", "descriptionPreviewViewModel", "description", "content").string ?: classic["descriptionText"].text,
            metadata = texts.filterNot { it.startsWith("by ") || it == "Playlist" },
            videos = videos,
            continuation = feed.continuation,
        )
    }

    // ---- comments ------------------------------------------------------------------------

    private fun sortOptions(items: List<JsonElement>): List<SortOption> = items.mapNotNull { s ->
        val token = s.at("serviceEndpoint", "continuationCommand", "token").string
            ?: s.at("continuation", "reloadContinuationData", "continuation").string
            ?: return@mapNotNull null
        SortOption(s["title"].string ?: return@mapNotNull null, token, s["selected"].bool == true)
    }

    fun comments(root: JsonElement): CommentsPage {
        val entities = HashMap<String, JsonElement>()
        root.at("frameworkUpdates", "entityBatchUpdate", "mutations").arr.forEach { m ->
            val key = m["entityKey"].string ?: return@forEach
            m["payload"]?.let { entities[key] = it }
        }
        val items = root["onResponseReceivedEndpoints"].arr.flatMap { ep ->
            (ep.at("reloadContinuationItemsCommand", "continuationItems") ?: ep.at("appendContinuationItemsAction", "continuationItems")).arr
        }
        var countText: String? = null
        var sort = emptyList<SortOption>()
        var disabled: String? = null
        var continuation: String? = null
        val comments = ArrayList<Comment>()
        for (item in items) {
            when (item.rendererKey) {
                "commentsHeaderRenderer" -> {
                    val h = item.renderer
                    countText = h["countText"].text ?: h["commentsCount"].text
                    sort = sortOptions(h.at("sortMenu", "sortFilterSubMenuRenderer", "subMenuItems").arr)
                }
                "commentThreadRenderer" -> {
                    val t = item.renderer
                    val vm = t.at("commentViewModel", "commentViewModel") ?: t["commentViewModel"]
                    val repliesToken = t.at("replies", "commentRepliesRenderer", "subThreads").arr.firstNotNullOfOrNull {
                        it.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").string
                    } ?: t.at("replies", "commentRepliesRenderer", "contents").arr.firstNotNullOfOrNull {
                        it.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").string
                    }
                    commentFrom(vm, entities, repliesToken, isReply = false)?.let(comments::add)
                }
                "commentViewModel" -> commentFrom(item.renderer, entities, null, isReply = true)?.let(comments::add)
                "continuationItemRenderer" -> {
                    val c = item.renderer
                    continuation = c.at("continuationEndpoint", "continuationCommand", "token").string
                        ?: c.at("button", "buttonRenderer", "command", "continuationCommand", "token").string
                }
                "messageRenderer" -> disabled = item.renderer["text"].text
            }
        }
        return CommentsPage(countText, comments, continuation, sort, disabled)
    }

    private fun commentFrom(vm: JsonElement?, entities: Map<String, JsonElement>, repliesToken: String?, isReply: Boolean): Comment? {
        val key = vm["commentKey"].string ?: return null
        val entity = entities[key]?.get("commentEntityPayload") ?: return null
        val props = entity["properties"]
        val author = entity["author"]
        val toolbar = entity["toolbar"]
        val toolbarState = vm["toolbarStateKey"].string?.let { entities[it]?.get("engagementToolbarStateEntityPayload") }
        return Comment(
            id = props["commentId"].string ?: key,
            author = author["displayName"].string ?: "",
            authorAvatar = author["avatarThumbnailUrl"].string,
            authorChannelId = author["channelId"].string,
            text = props.at("content", "content").string ?: "",
            published = props["publishedTime"].string,
            likes = toolbar["likeCountNotliked"].string?.takeIf { it.isNotBlank() && it != "0" },
            replyCount = toolbar["replyCount"].string?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
            repliesToken = repliesToken,
            pinned = vm["pinnedText"] != null,
            hearted = toolbarState["heartState"].string == "TOOLBAR_HEART_STATE_HEARTED",
            verified = author["isVerified"].bool == true,
            isArtist = author["isArtist"].bool == true,
            isCreator = author["isCreator"].bool == true,
            isReply = isReply || (props["replyLevel"].long ?: 0L) > 0L,
        )
    }

    // ---- account -------------------------------------------------------------------------

    fun accountInfo(root: JsonElement): AccountInfo? {
        val h = root.findFirst("activeAccountHeaderRenderer") ?: return null
        return AccountInfo(
            name = h["accountName"].text ?: return null,
            email = h["email"].text,
            handle = h["channelHandle"].text,
            avatar = h.at("accountPhoto", "thumbnails").arr.lastOrNull()?.get("url").string,
        )
    }

    /** The signed-in user's playlists from the "Save to" sheet, each with whether it holds the video. */
    fun saveToOptions(root: JsonElement): List<Pair<Playlist, Boolean>> =
        root.findAll("playlistAddToOptionRenderer").mapNotNull { o ->
            val id = o["playlistId"].string ?: return@mapNotNull null
            Playlist(id, o["title"].text ?: return@mapNotNull null) to (o["containsSelectedVideos"].string == "ALL")
        }
}
