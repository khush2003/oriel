package app.oriel.ui

import app.oriel.core.Storyboard
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.Chapter
import app.oriel.core.model.Chip
import app.oriel.core.model.Comment
import app.oriel.core.model.CommentsPage
import app.oriel.core.model.Feed
import app.oriel.core.model.Playlist
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.core.model.SortOption
import app.oriel.core.model.Video
import app.oriel.core.model.WatchPage
import app.oriel.ui.player.CommentsState
import app.oriel.ui.player.WatchState

/** An invented catalogue for screenshots: every title, name and comment here is original placeholder text. */
object Fixtures {
    private val titles = listOf(
        "Building a tiny cabin by the lake in 30 days",
        "I tried cooking every recipe from a 1950s cookbook",
        "The quiet engineering behind city bridges",
        "Night train across the mountains, full journey",
        "Restoring a rusted bicycle back to new",
        "Why the ocean glows at night, explained",
        "Learning the piano in one year: progress diary",
        "A day in the life of a lighthouse keeper",
        "Five budget cameras compared in the rain",
        "How a paper mill turns trees into notebooks",
        "Speedrunning a puzzle game with my friends",
        "The softest bread I've ever baked",
    )
    private val channels = listOf(
        ChannelRef("Harbor Workshop", "UCfixture0", "https://yt3.ggpht.com/fixture-avatar0=s88", verified = true),
        ChannelRef("Kitchen Archive", "UCfixture1", "https://yt3.ggpht.com/fixture-avatar1=s88"),
        ChannelRef("Field Notes", "UCfixture2", "https://yt3.ggpht.com/fixture-avatar2=s88", verified = true),
        ChannelRef("Slow Travel Club", "UCfixture3", "https://yt3.ggpht.com/fixture-avatar3=s88"),
        ChannelRef("Pedal & Wrench", "UCfixture4", "https://yt3.ggpht.com/fixture-avatar4=s88"),
    )
    private val ages = listOf("2 hours ago", "1 day ago", "3 days ago", "1 week ago", "2 weeks ago", "1 month ago")
    private val views = listOf("1.2M views", "84K views", "312K views", "9.8K views", "2.4M views", "47K views")

    fun video(i: Int) = Video(
        id = "vid%07d".format(i),
        title = titles[i % titles.size],
        channel = channels[i % channels.size],
        thumbnail = "https://i.ytimg.com/vi/vid%07d/hqdefault.jpg".format(i),
        durationSec = 180 + i * 97 % 1500,
        viewsText = views[i % views.size],
        publishedText = ages[i % ages.size],
        badges = if (i % 4 == 0) listOf("4K") else emptyList(),
    )

    fun short(i: Int) = Video(
        id = "sho%07d".format(i),
        title = listOf("One-minute pasta trick", "Satisfying wood carving", "Cat vs. cardboard box", "Tiny garden tour", "Sunrise timelapse")[i % 5],
        thumbnail = "https://i.ytimg.com/vi/sho%07d/oar2.jpg".format(i),
        viewsText = views[(i + 2) % views.size],
        isShort = true,
    )

    val videos = List(14) { video(it) }
    val shorts = List(6) { short(it) }

    val homeFeed = Feed(
        shelves = listOf(
            Shelf(title = "Continue watching", items = videos.take(4).mapIndexed { i, v -> v.copy(progress = 0.2f + i * 0.18f) }, kind = ShelfKind.CAROUSEL),
            Shelf(items = videos.drop(4).take(3), kind = ShelfKind.FEED),
            Shelf(title = "Shorts", items = shorts, kind = ShelfKind.SHORTS),
            Shelf(items = videos.drop(7), kind = ShelfKind.FEED),
        ),
        chips = listOf(Chip("All", selected = true), Chip("Music", "topic:MUSIC"), Chip("Gaming", "topic:GAMING"), Chip("News", "topic:NEWS"), Chip("Sports", "topic:SPORTS")),
    )

    val chapters = listOf(Chapter("Intro", 0), Chapter("Picking the spot", 62_000), Chapter("Foundations", 245_000), Chapter("Walls and roof", 610_000), Chapter("First night", 980_000))

    val watch: WatchState get() {
        val v = video(0).copy(durationSec = 1260)
        return WatchState(
            video = v,
            page = WatchPage(
                video = v,
                viewCount = "1,204,332 views",
                viewCountShort = "1.2M views",
                date = "Oct 2, 2026",
                relativeDate = "2 weeks ago",
                likeCount = "48K",
                description = "Thirty days, one lake, and a lot of rain. Everything about the build is in the chapters below.\n\n0:00 Intro\n1:02 Picking the spot\n4:05 Foundations\n10:10 Walls and roof\n16:20 First night\n\nTools and plans: https://example.com/cabin",
                subscribersText = "1.9M subscribers",
                chapters = chapters,
                commentsToken = "fixture",
                commentsCount = "3.1K",
                commentTeaser = "The moment the stove first lit up at night made the whole month worth it.",
                commentTeaserAvatar = "https://yt3.ggpht.com/fixture-commenter0=s88",
            ),
            related = videos.drop(1).take(3) + shorts.take(4) + videos.drop(4),
            votes = app.oriel.core.extras.ReturnDislike.Votes(48_200, 610),
        )
    }

    /** A made-up storyboard for the 21-minute fixture: 160x90 frames, 25 to a sheet, one every 10 s. */
    val storyboard = Storyboard.parse(
        "https://i.ytimg.com/sb/fixture/storyboard3_L\$L/\$N.jpg?sqp=x|48#27#100#10#10#0#default#s|160#90#126#5#5#10000#M\$M#s",
        21 * 60L,
    )

    val comments = CommentsState(
        page = CommentsPage(countText = "3.1K"),
        sort = listOf(SortOption("Top", "top", true), SortOption("Newest", "new", false)),
        comments = listOf(
            Comment("c0", "@harborworkshop", "https://yt3.ggpht.com/fixture-avatar0=s88", "UCfixture0", "Thanks for watching! The full material list is pinned in the description.", "2 weeks ago", "2.1K", 48, "r", pinned = true, isCreator = true, verified = true),
            Comment("c1", "@morningkayak", "https://yt3.ggpht.com/fixture-commenter0=s88", null, "The moment the stove first lit up at night made the whole month worth it.", "2 weeks ago", "5.4K", 112, "r", hearted = true),
            Comment("c2", "@quietbuilder", "https://yt3.ggpht.com/fixture-commenter1=s88", null, "I love that you kept the mistakes in. The roof redo at 12 minutes taught me more than any tutorial.", "1 week ago", "880", 9, "r"),
            Comment("c3", "@lumberlane", "https://yt3.ggpht.com/fixture-commenter2=s88", null, "Rain sounds on the tin roof at the end, perfect.", "5 days ago", "301"),
            Comment("c4", "@nordicmaps", "https://yt3.ggpht.com/fixture-commenter3=s88", null, "Which wood treatment did you end up using for the deck?", "3 days ago", "77", 3, "r"),
        ),
    )

    val subscriptions = channels.mapIndexed { i, c -> Channel(c.id!!, c.name, c.avatar, subscribersText = "${(i + 1) * 310}K subscribers", verified = c.verified) }

    val savedPlaylist = Playlist("PLfixture1", "Weekend projects", ChannelRef("Harbor Workshop", "UCfixture0"), "https://i.ytimg.com/vi/vid0000003/hqdefault.jpg", "24 videos")
}
