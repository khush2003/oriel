<img src="assets/icon.svg" width="64" height="64" alt="">

# Oriel

A YouTube client for Android, written in Kotlin with Jetpack Compose and Media3. It's for watching videos, not music, and its interface is iOS-like: liquid glass, a floating tab bar, large titles, rubber-band overscroll and press-and-hold menus. The design follows [BitChord](https://github.com/kushagrasinghx/BitChord).

Oriel works signed out. Signing in is optional and brings in your own home feed, subscriptions, playlists, likes and history.

Made by [khush2003](https://github.com/khush2003). Oriel is an independent project. It is not affiliated with, endorsed by or connected to YouTube or Google.

## Features

- **Watching.**
  - One player sheet that flies between a floating picture-in-picture window, the watch page and fullscreen, with the same video surface throughout so the picture never restarts. The flight is a critically damped spring that runs all the way into place. (Before 1.4 its stopping threshold was sized for pixels, not a 0-to-1 progress, so it quit about 4% short and jumped the last 60 px.)
  - Swipe the picture up for fullscreen and down to leave it. Both ways the picture follows your finger: up, it grows toward fullscreen and goes the rest of the way past a quarter of the swipe or on a flick; down in fullscreen, it shrinks away. Let go early and it springs back. Pinch with two fingers to fill the screen.
  - Fullscreen rotates to landscape, or stays portrait for tall videos, and follows the system auto-rotate setting.
  - A tap in the middle shows or hides the controls at once (nothing waits to see if a second tap follows). Double-tap either side to seek (the step is configurable); while a seek streak is running each further tap seeks again at once, as on YouTube, with a ring spreading from the finger. Press and hold the picture to play at 2× until you let go.
  - Swiping the watch page down shrinks the video into a picture-in-picture window that floats over the app, as iOS's does. Drag it anywhere and let go: where the throw would carry it picks the corner, and a spring takes it there at the finger's speed. As on YouTube, play/pause and close sit on it all the time, on small dark discs in the top corner with the picture left undimmed, and a thin progress line runs along its bottom. Tap anywhere else on it to open the watch page. Pinch it to resize it, from 70% of its default size up to the screen's width; it keeps that size. Push it a third of the way off the left or right edge, or flick it sideways at an edge, to put it away and stop playback (a throw more down than sideways, or a pinch past an edge, still lands in a corner). A drag moves the window in its layer rather than relaying out the video surface each frame, so it follows the finger without lag. Leaving the app still hands over to Android's own picture in picture.
  - Scrubbing shows the frame under your finger, cut from YouTube's storyboard sprite sheets, with the chapter and time; the scrubber also marks chapters and SponsorBlock segments.
- **Background play and picture-in-picture.**
  - Playback runs in a foreground `MediaLibraryService`, so it continues with the screen off, on the lock screen, and from Bluetooth, the notification and Android Auto.
  - Leaving the app switches to sound only (video decoding stops), or to picture-in-picture if you prefer. The player also has a picture-in-picture button, and the window keeps the video's shape.
  - "Listen in the background" plays any video as audio only.
- **Quality.**
  - Video from 144p to 2160p, set separately for Wi-Fi and mobile data, plus a data saver.
  - **Auto adapts as it plays**, as YouTube's does. YouTube's iOS-family clients answer every video with an HLS ladder (144p to 1080p in H.264, up to 2160p in VP9, sound alongside), and Auto plays that, so the player steps down when the connection sags and back up when it recovers, without stopping. The ceiling is the screen's own resolution (1440p on a QHD phone, 1080p on most, as far as the hardware decoders go) on Wi-Fi and mobile data alike, and 480p with the data saver; mobile data isn't held to a lower resolution, since the adaptive picker already stays within what the link carries. Each session starts from the bandwidth this kind of network actually measured last time (kept separately for Wi-Fi and mobile), not Media3's guess from the country, and a higher rung is taken after five seconds in hand rather than ten, so a video settles at its best within moments. When a ladder's segment URLs expire (after a long pause, say), a fresh manifest is fetched once before giving up the ladder. A decoder that can't take a rung (4K60 on a chip that decodes 4K30) steps down a rung instead of skipping the video. The quality menu shows the height playing now, e.g. "Auto (720p)". A video with no ladder, or whose ladder is refused, drops to fixed-quality streams at the same position. Picking a height plays it fixed. One trade-off: the ladder's sound is AAC (the HLS renditions carry `mp4a`), so Auto doesn't use the Audio setting's Opus stream; a fixed height or Audio only does.
  - Audio Low, Normal or High. High is the best stream YouTube offers the session: Opus at about 130–160 kbps, or 256 kbps when YouTube grants a Premium account its Premium formats.
  - Speed, loop, Autoplay, a sleep timer, captions with auto-translate, and audio-only, all from an iOS-style settings sheet whose pages push like iOS navigation, its height springing to each page's. It also shows what's playing: codecs, resolution, bitrate, client and protocol.
  - Live streams play over HLS.
- **Streaming.**
  - Classic googlevideo URLs, or **SABR**, the server-driven protocol YouTube's own players now use. Automatic uses classic URLs and falls back to SABR when a video has none. Oriel reads SABR as an ordinary file, so seeking and the disk cache work the same either way.
- **No ad breaks.** Oriel doesn't request or play ads. Feeds, search and the watch page drop promoted and ad renderers, and the Shorts feed drops ad Shorts (YouTube sends them as ordinary entries, marked only by `adClientParams`). Sponsor segments inside videos are the creator's own; SponsorBlock skips those.
- **Watch page.**
  - Title, views and date with an expandable description in which links and timestamps are tappable.
  - Channel row with Subscribe.
  - Like and dislike (counts from Return YouTube Dislike), Share and Save.
  - A comments teaser, then a comments panel with Top / Newest, replies and paging. YouTube now sends the sort options with the watch page rather than with the comments, and Oriel reads them from there.
  - Chapters and the playlist panel when watching from a playlist. In the player, the current chapter's name is a button that opens the list of chapters (also under Settings › Chapters); tapping one jumps to it.
  - Related videos with endless scroll, and Autoplay of what YouTube recommends next.
- **Shorts.**
  - A vertical pager with its own player. Shorts play from YouTube's HLS ladder, whose segments start at once (a fresh progressive URL refuses reads for its first few seconds, which made the first short slow and later ones stall a few seconds in). A Media3 preload manager loads the next short two seconds deep and has the ladder and tracks of the one after ready, so a swipe starts playing at once; a short whose ladder is refused falls back to a fixed-quality stream. A short opened from a feed belongs to that feed's tab, so tapping the tab goes back to the feed. The short stops above the tab bar, which turns dark on this tab, and its thumbnail holds the frame until the first picture arrives. Vertical shorts fill the frame; square and wide ones fit inside it.
  - Tap to pause, double-tap to like (the heart pops and floats away), hold to play at 2×, plus comments with Top / Newest, replies and paging.
  - Each short's title has its hashtags as links (each opens a search), and under it the sound it uses, as YouTube credits it ("Original Sound", or the song), with its artwork at the foot of the action column. Either opens the sound: its artwork, title and maker, and a grid of the shorts using it, more loading as it scrolls. The sound comes from the Shorts player's own `reel/reel_item_watch` overlay, asked alongside the short's page; a short plays the same without it.
  - Drag along the progress bar at the foot of a short to scrub: the bar swells, the overlay steps aside, the time shows above it, and the picture follows the finger with keyframe seeks, exact on release. A tap on the bar seeks there.
  - A short that fails is never left stuck: a dropped connection waits for the network and resumes where it was, a refusal moves to the fixed-quality stream, and Shorts use the same bandwidth estimate and quality ceiling as the main player (1080p shorts were held to 720p before).
- **Search.**
  - Laid out as YouTube's: the field pinned at the top and focused on arrival, recent searches (each removable, or tap the arrow to edit it) until you type, then YouTube's suggestions with the completion in bold.
  - The microphone in the empty field searches by voice, with the phone's own speech recogniser; what it hears is searched straight away.
  - Results under YouTube's result chips, a corrected-spelling line and infinite scroll. All of YouTube's filters (type, duration, upload date, features, sort) are in a grouped Filters sheet that stays open while you pick, so several can be set at once: one per group, and features stack. Filters that can't go with what's on (Channels with "This week") are dimmed, as YouTube disables them. What's on shows as chips under the bar, each with ✕, plus Clear all. YouTube offers no way to take one filter off a filtered page, so Oriel keeps each filter's single params from the unfiltered page and merges them itself: search params are protobuf, and concatenated messages parse as one merged message. Topics are on Home's chips, so they aren't repeated here.
- **Home and Subscriptions.**
  - Signed in, these are YouTube's own feeds with their chips.
  - Feeds keep themselves current. Stepping back to one from another page leaves it exactly as it was. Reopening the app with a feed on screen and scrolled to its top refreshes it once it's older than its age limit: 5 minutes for Subscriptions, 10 for Home, 30 for topics. Home refreshes after a minute if you've watched something since, because YouTube's recommendations move with what you watch. A list you've scrolled partway down is never replaced under you; pull to refresh at the top does it on demand. Tapping the tab you're on goes to the top, and tapped again at the top, it refreshes, as on YouTube.
  - If a refresh fails, the older copy stays, under a line saying so ("You're offline. Updated 20 minutes ago.") with Try again. A next page that fails ends the list in Try again rather than silently ending it.
  - Signed out, YouTube serves an empty home, so Oriel builds one on the device from videos to resume, uploads from channels you follow, what's recommended next to what you watched, and topic charts.
  - Subscriptions works signed out too, from the channels you follow locally. Its channel strip leads with whoever uploaded last and ends in an All bubble, and All / Today / Videos / Shorts / Live filter the feed.
- **Channels and playlists.**
  - Channel pages: banner, a tab strip with a sliding underline (Videos, Shorts, Live, Playlists…), sort chips under it and in-channel search. A tab or sort that fails says so with Try again, rather than leaving the last tab's videos under the new tab's name.
  - The description's preview opens an About sheet, as YouTube's "more" does: the full description with its links tappable, the channel's external links with their icons, and its details (channel URL, subscribers, videos, total views, join date, country).
  - Playlist pages tinted with the artwork's colours: Play, Shuffle, Save and Share.
  - Local playlists can be renamed and reordered. In Reorder mode each row gets a grab handle; the held row lifts and follows the finger, the others step aside as it passes their middles, and the new order is saved once on release.
- **Library ("You").**
  - History grouped by day, which can be paused and searched by title or channel. Signed in, an "On YouTube" view shows your YouTube watch history too, including what you watched elsewhere.
  - Signed in, a Notifications inbox (the bell's): new uploads, replies and live streams, with an unread dot on each and the unseen count on You.
  - Watch later, liked videos, local and saved playlists, and channels. Swipe a row left to remove it, as in iOS lists; the rest glide up into the gap.
  - Every change is instant and works offline. When you're signed in, likes, Watch later, subscriptions and playlist edits are also mirrored to your account. Likes, Watch later and subscriptions sync back from YouTube at sign-in and whenever the app returns after half an hour, so changes made on other devices show up, removals included.
  - **What you watch reaches your YouTube history and recommendations.** Oriel streams from clients that play without signing in, and the tracking URLs in those answers file a viewing under nobody. So for each video it asks the player again as your account (the WEB client, or the TV client when WEB is bot-checked) and reports the viewing as YouTube's web player does: a start ping, then watch-time pings carrying the stretches actually played (so seeks show), after 10, 20 and 30 seconds and every 40 after that, on each pause, and a final one. YouTube's history then shows the video with how far you got. Shorts report too. Nothing is sent signed out or while history is paused.
- **Menus and sharing.**
  - Press and hold any video, channel or playlist for a menu, the way iOS context menus work: Play next, Add to queue, Watch later, Save to playlist, Listen in the background, Share, Go to channel, Remove from history.
  - `youtube.com`, `m.youtube.com` and `youtu.be` links and shared text open in the app. A "play … on Oriel" voice search plays the top result.
- **Community extras.** SponsorBlock skipping per category, with Undo, and Return YouTube Dislike. Both can be turned off.
- **Appearance.** Light, dark or system theme. Liquid glass can be turned off; the bars then become BitChord's frosted bar, a single blurred pill with Search as its last tab. "Reduce transparency" makes them solid. There's also a reduce-motion option. High refresh rate, content country and language are configurable.

## Design

The look is taken from BitChord: a glass tab bar that floats above the content with a sliding selection pill, a separate glass search button (with glass off, one frosted pill as in BitChord), large collapsing titles, grouped settings, sheets with a 28 dp radius, press-and-hold context menus and iOS overscroll. Content keeps YouTube's features and layout but not its look. Cards inset their artwork in the page gutters with 18 dp corners, as Apple's TV app does. Under each card is the title and one quiet line of who and when; views wait for the watch page, avatars for the channel and watch pages. Durations sit in soft capsules, progress is an inset white capsule, Shorts carry their titles beneath the poster, and the watch page's actions are five even tiles, icon over label; a like pops the icon and rolls the count. The views, date and description preview, and the comments, sit in grouped cards that sink a little under the finger. The comments and description open as iOS sheets: a grabber, the title at the leading edge, a small grey close disc, and a hairline that appears only once the list scrolls under it. Top and Newest are a segmented control with a sliding thumb; a reply thread pushes in from the side and slides back out on Back. The description has the App Store's information strip (likes, views, published) and its chapters as a grouped list. A channel's header is centred as an iOS profile is, and its sections are labels with one capsule that slides between them. The action glyphs (share, save, comments, thumbs, chevrons) are drawn in SF Symbols' style rather than taken from Material.

Nothing uses Material's own look. A press washes the row in a flat highlight, as an iOS cell does, instead of a ripple. Switches are iOS's 51 × 31 capsule with a sprung knob, spinners are iOS's eight fading spokes, progress bars are capsules, chips are capsules that give under the finger, and empty and error states end in a pill button. Every sheet is one sheet: a grabber, 28 dp corners and the page colour, and anything that ends it slides it down rather than dropping it. Alerts bloom in and shrink away. Pages push in on a spring, the old one drifting left and dimming. Feed rows fade in over the skeletons and glide when rows above them change. Inter is the typeface; SF Pro is Apple's and isn't bundled. Some files are adapted from BitChord, Convx and Kyant's backdrop library, and each keeps its licence header.

## How it's built

```
core/   Pure Kotlin/JVM, no Android
        YouTube            InnerTube client (web client): home, browse, search, watch, related,
                           comments, channels, playlists, shorts, captions, account, likes,
                           subscriptions, playlist edits, playback reporting
        parse/             response parsers into small immutable models
        stream/            StreamResolver: InnerTubeX extraction (client fallback, signature and
                           n-parameter), cached until expiry, shared in flight, resolved ahead;
                           SabrSource (SABR read as a byte-addressed file, from the format's own
                           WebM Cues or MP4 sidx)
        extras/            SponsorBlock, Return YouTube Dislike
app/    Android
        playback/          PlaybackService (MediaLibraryService), QueueController and Autoplay,
                           merged video+audio sources, HLS for live, chunked range data source,
                           SABR data source, on-disk stream cache
        data/              library store, settings, account (cookie sign-in), home composer,
                           stale-while-revalidate page cache
        ui/                Compose: navigation, screens, player sheet, glass, components, theme
```

### SABR

SABR has no byte ranges: the client asks for a format from a point in time, and the server streams whole segments over UMP. Every segment, though, is an exact slice of the same WebM or MP4 file the classic URL serves. YouTube cuts one segment per cluster (or fragment), and the format's index (WebM Cues, or an MP4 `sidx`) arrives in its initialization segment. So `SabrDataSource` serves `sabr://<id>/<a|v>/<itag>` as that file:

1. To open at a byte, it looks up the segment holding that byte.
2. It asks the server to start from that segment's time.
3. It drops the bytes before the requested one.

If the index isn't known yet, it guesses from the byte rate and corrects itself. ExoPlayer's extractors, seeking and the disk cache then work unchanged. The live tests check that SABR's bytes are identical to the classic file's, from the start and mid-file, for both audio and video. They also feed SABR through Media3's WebM extractor and seek it.

Media items name *what* to play (`oriel://av/<id>?h=720`, `oriel://audio/<id>`, `oriel://hls/<id>`). They're resolved to googlevideo URLs only when the player opens them, so a long queue costs nothing until each item is reached, and a quality change rebuilds items in place without losing position.

### Performance

- **Player sheet.** The sheet is one `Animatable`. The video surface (one `TextureView` from the service's player) is positioned through layout lambdas, so dragging, docking and going fullscreen never recompose. Tapping a video opens the watch page right away while the player catches up underneath.
- **Playback position.** The position is read only in draw lambdas, and once-a-second labels go through `derivedStateOf`.
- **Cold start.** Every feed renders its last copy from disk first and refreshes underneath. If a refresh comes back empty, the cached copy stays. The library and each feed's disk copy are read off the main thread. Resume positions live in their own small file, so playback saving them every few seconds no longer rewrites the whole library; the library is also written out at once when the app is swiped away.
- **Streams.** Range requests are chunked to stay under throttling (at least 4 MiB each: InnerTubeX's 512 KB for ANDROID_VR meant a new request every second or two of 1080p; measured on this host, ANDROID_VR URLs serve 512 KiB, 4 MiB and 10 MiB ranges alike), and everything played is cached on disk (1 GB by default, configurable). Resolved URLs are cached, and the next video is resolved and pre-cached. When googlevideo refuses a client's URL, playback recovers on a different client. If every client a video can use has been refused, they're all tried again rather than the video being called unplayable.
- **Fresh URLs.** googlevideo answers 403 to a URL it minted moments ago, then serves that same URL a few seconds later. Measured, URLs went live 3.2 to 4.3 s after the player response, and asking early didn't push that back. yt-dlp documents the same thing for web clients as a "mandatory wait period before download", tied to preroll ads. A cold resolve spends about that long on the cipher and never notices. A warm one finishes in half a second, and Oriel used to take the 403 as a refusal: it re-resolved, got another URL that wasn't live yet, and gave up on a video that would have played. Now `WarmUpDataSource` asks again every half second while a URL is under 8 s old, so the start is a short buffer instead of an error. A 403 on an older URL is still a refusal.
- **Starting fast.** What a video needs first is fetched when a finger comes to rest on its card, before the tap lands: on Auto its HLS manifest, otherwise its streams. On an unmetered connection, with prefetching on, a card that stays on screen for a second is warmed too, at most one every eight seconds and never while the video playing is still filling its buffer. The next video is resolved only once the current one is playing with 30 s buffered, and on mobile data it isn't pre-downloaded. A restored queue isn't prepared until you press play, so opening the app no longer downloads two minutes of the last video while Home loads. Either way the tap starts on a URL that's already in hand. A newly opened video shows its own thumbnail until its first frame, never the previous video's last one.
- **API requests.** Reads (feeds, search, the watch page, the player) are retried twice, about 0.4 s and 1.2 s apart, on a dropped connection, a 5xx, or a 429 that asks for a short wait, so one blip doesn't become "Couldn't load". A timeout isn't retried, InnerTube JSON calls time out at 15 s, and retries stop after 20 s in all, so a dead network says so in seconds rather than about a minute and a half. When every client fails to give a video's captions and storyboard, nothing is cached, so the next look tries again. Writes (likes, subscribing) go once. OkHttp allows 16 requests per host instead of 5, pings HTTP/2 connections every 20 s so a dead one is noticed in seconds rather than at the 20 s read timeout, and drops its pooled connections when the phone switches networks. Visitor data no longer holds every call behind one fetch: calls go without it and the first answer supplies it, and it's kept across launches. A page loaded by several screens at once no longer fails for all of them when the first one is closed.
- **Watch-page config and client health.** The watch-page config InnerTubeX reads before every cold resolve is kept on disk for 12 hours (keyed by locale and a hash of the cookie, never the cookie), so the first play after launch skips a download of up to 1.4 MB. Clients that keep failing are tried later, and ones that just played are tried first.
- **Choppy connections.** Media3's default retries most failures three times, one and two seconds apart. Oriel's policy, `OrielLoadErrorPolicy`, splits failures in two:
  - Failures that retrying can't fix go straight to recovery instead of waiting through doomed retries. These are an expired or refused URL, a video that turned out to be live, and a video that can't be resolved.
  - A SABR stream has no overall request timeout any more (it used to be cut at 30 s while the buffer was full and reading paused, which looked like a refusal and benched a working client), and it reconnects from the current byte up to three times. Only a refusal by the server counts as one.
  - Network failures retry from 250 ms, backing off to 4 s, for about half a minute. The media client gives up on a silent socket after 8 s and the retry resumes from the same byte. If playback still stops, Oriel waits for the connection when offline. When online, it tries the same spot again a few times before moving on. A resolve that fails because YouTube couldn't be reached is treated as a dropped connection, not an unplayable video.
- **Rendering.** Glass samples a per-page backdrop layer. Thumbnails and avatars are requested at the size they're drawn, so a small avatar never decodes a 900 px original. List keys are stable. Skeleton shimmer animates in the draw phase only. On supported displays the app asks for the highest refresh rate.
- **Controls and bars.** The video controls' time label and scrubber are composables of their own, so the position's ticks recompose those alone, not the whole overlay. The scrubber swells in draw rather than relayout, and its loading sheen runs only while loading. The tab bar's pill is an `Animatable` read only in its layer, so a tab change or a drag across the bar never recomposes it.
- **Resolving.** A stream resolve runs in the resolver's own scope and is shared: a caller that gives up (an item replaced, a short scrolled past) no longer fails everyone else waiting on the same video. A whole resolve is held to 25 s and then counts as a network failure, which is retried, rather than the spinner staying up through one timeout after another. BotGuard's WebView is thrown away and rebuilt after any failure, and a crashed renderer no longer takes the app with it. The HLS manifest is asked of the iOS client and, if that hasn't answered in 2.5 s, of the visionOS client too; the first ladder wins.
- **Codec.** YouTube's ladder carries every height up to 1080p twice, as H.264 and as VP9, and Media3 adapts within one codec: given two ladders that both stop at a phone's 1080p, it would take whichever has more rungs. Where a hardware VP9 decoder plays 1080p30, VP9 is asked for first (it gives more picture per bit); above 1080p the ladder is VP9 alone. Without hardware VP9, Media3's preference for hardware decoders keeps H.264.
- **Audio rendition.** YouTube's HLS ladder lists two audio renditions, HE-AAC (itag 233, about 48 kbps) first and AAC-LC (itag 234, about 128 kbps) second, declares neither's bitrate, and pairs every variant from 360p up with AAC-LC. Media3 picks alternate audio on its own, and with nothing to compare it took the first: HE-AAC, even under a 4K picture. `AudioRenditionOrder` reorders the parsed playlist so AAC-LC comes first, or HE-AAC when Sound quality is Low or the data saver is on with mobile data.
- **Buffers.** A minute ahead, bounded by bytes as well as time (two minutes by time alone was hundreds of MB of heap at 1440p and up), 15 s kept behind so a skip back plays from memory, and three seconds in hand before resuming after a stall. Auto's next video warms its HLS manifest, which is what it will play, rather than resolving progressive streams it never reads. The media caches load their index at launch off the main thread. The queue is written at once when the service goes away (the delayed save used to be cancelled with it).
- **HLS user agents.** Each HLS stream's user agent is kept per stream, so the Shorts player and the main player don't overwrite each other's.
- **Usage counts.** Opt-in and anonymous, through [Aptabase](https://aptabase.com); see `PRIVACY.md` for exactly what is sent. Official releases ask once on first launch; Settings › Privacy & storage changes the answer. A build only includes it with an App Key (`./gradlew :app:assembleRelease -PaptabaseKey=A-EU-…`, or `APTABASE_KEY`); without one, as in any build from source, the app never asks and never sends anything.
- **Release builds.** Release builds go through R8, which leaves names intact but optimises code, because Compose is noticeably slower unoptimised.

## Building

You need JDK 17 to run Gradle and the Android SDK (compileSdk 37). The test tasks also need JDK 21, because InnerTubeX ships Java 21 bytecode; Gradle downloads one the first time.

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk (R8)
```

Release builds are signed with the debug key unless you add a `keystore.properties`; see `keystore.properties.example`.

`gradle.properties` caps Gradle at a 2 GB heap with no parallel module work, so a build leaves a 16 GB machine usable. Run one build at a time.

## Tests

```sh
./gradlew :core:test                                   # offline: parsers against saved responses
./gradlew :core:test -Plive=true                       # against the real YouTube
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
./gradlew :app:testDebugUnitTest --tests 'app.oriel.ui.LiveAppTest' -Plive=true -Proborazzi.test.record=true
```

- **Live core tests** hit YouTube for real. They cover home and topics; search with filters, suggestions and paging; the watch page with related videos, comments, replies and sorting; channel tabs; playlists with continuation; Shorts; captions; SponsorBlock and Return YouTube Dislike. They also resolve streams and fetch bytes from them.
- **Screenshot tests** render the whole app with Robolectric, from an invented catalogue. They cover home (dark, light, and with liquid glass off), the watch page, comments (dark and light), the description, a reply thread pushed over the comments, search, You, Subscriptions, Settings, Shorts, a local playlist in Reorder mode (and mid-drag: the test drags a row's handle two rows down and checks the order saved on release), the Notifications inbox and the loading state. They also cover the player in landscape and fullscreen, the scrub preview, and hold-for-2×. Gesture tests check that a one-finger swipe down leaves fullscreen and a short one doesn't, that Back from the comments closes the comments and not the watch page, that dragging the watch page down lands in the floating window, that a tap on the window opens the watch page, that its close and play/pause buttons are always there and close stops playback, that a pinch makes it bigger without putting it away, that a throw up and left lands it in the top-left corner and a diagonal one in the bottom-left, and that pulling it off a side, or a short sideways flick at the edge, stops playback. The images are written to `app/build/screenshots/`.
- **`WatchReportTest`** checks the history reporting's bookkeeping: one stretch for continuous play, a new one after each seek (forward or back) or pause, paused time never counted, and pings due at 10, 20, 30 s and every 40 s after.
- **`FeedModelTest`** checks that feeds keep themselves current: a fresh feed is left alone, an old one is fetched again without a spinner, watching something makes Home due sooner, a list read partway down is never changed under the reader, and a failed refresh or next page keeps what's shown and offers Try again.
- **`AdaptivePlaybackTest`** (live, `-Plive=true`) runs Auto end to end short of decoding: the Auto item resolves to the HLS ladder, Media3 parses it into the heights, a variant's playlist and first segment download through the app's data source, and ExoPlayer prepares the item into a video track group with every height.
- **`PlaybackQualityTest`** (live, `-Plive=true`) plays Auto for real, not just prepares it. The player is built as the service builds it (the shared bandwidth meter, track selector and load control, the app's data sources), and Media3's Robolectric fake decoders stand in for hardware; Robolectric's clock is advanced by the real time that passes, so loads take as long as the network makes them. For 45 s of playback each, it records the start, every rebuffer and every rung change, and asserts a quick start, no rebuffering, and that Auto ends on the best track it may use. It also checks the audio rendition chosen (AAC-LC, not HE-AAC); audio can't play under Robolectric, whose AudioTrack never advances. Four playback cases: a 1080×2400 phone and a 4K screen on this host's line, and the same through a throttle at 1.5 Mbps and 8 Mbps (the second starts above the line, from the 12 Mbps Wi-Fi default, and has to step down). Measured here: starts of 0.7–2.7 s, no rebuffers in any case; VP9 1080p from the first frame on the phone; 1440p then 2160p at 6 s on the 4K screen; 1080p down to 720p at 1.5 Mbps; 1440p down to 1080p at 8 Mbps. A fifth case plays the fallback Auto drops to when the ladder is missing or refused: the fixed-quality video and audio streams, merged. Their URLs need the player's cipher solved in QuickJS, whose Android build can't load on the host, so the unit tests load its desktop build (same JNI exports, extracted by the `hostQuickJs` task). Measured here: 1080p, Opus audio at 136 kbps, no rebuffers; a cold resolve takes 6.8 s, and with the cipher warm (as the app has it after launch) playback starts in 5.6 s, most of it googlevideo's refusal of a freshly issued URL for its first few seconds, which the app waits out. Video only, because Robolectric's AudioTrack doesn't advance. It says nothing about real decoders, thermal limits or radio conditions; those need a phone.
- **`WarmUpDataSourceTest`** checks the fresh-URL wait. A 403 from a URL issued moments ago is retried until the URL is live. A 403 from an old URL, any other status, or a URL the resolver didn't issue goes straight to the player. The waiting stops when the warm-up ends.
- **SABR** has live tests too (opt in with `-Plive=true`):
  - `SabrLiveTest` checks that SABR bytes match the classic file.
  - `SabrPlaybackTest` checks that Media3's extractor reads and seeks SABR.
- **The live app test** launches the real `MainActivity` against YouTube and walks through it: home, then typing a search with suggestions, results, opening a video, its watch page and its comments, a channel and its About sheet, and finally a `youtu.be` link that carries only an ID, whose page must fill in the title. It saves a screenshot at each step. `sharedLink` opens one shared link (`-Plink=…`) and works through its description, comments and related videos. Robolectric can't decode media, so these tests check pages; `PlaybackQualityTest` plays through fake decoders.
- **Probes** (`ThroughputProbeTest`, opt in with `-Plive=true -Pprobe=true`) measure stream behaviour rather than assert it:
  - `activationWindow` times how long a fresh URL takes to go live.
  - `rangeSizes` and `boundedClientRanges` fetch 512 KiB to 10 MiB ranges, to check the chunk size.
  - `repeatedResolves` and `cipherReuseIsConsistent` compare a long-lived resolver with fresh ones.
  - `videoProbe` (`-Pids=…`) reports what a video is and how far resolving it gets.
  - `HlsProbeTest` (`-Pprobe=true`) lists the HLS ladder the iOS-family clients offer for a video and fetches one variant playlist and segment, which is what Auto plays.

## Problem reports

Oriel keeps a few notes about what went wrong, on the phone only, in its private storage (excluded from backups):

- **The last five crashes**, each with its stack trace, the page that was open, and the app version. Ordinary crashes are written as they happen. On Android 11 and newer, "app not responding" freezes (ANRs) and native crashes are read back from Android's own record on the next launch; for an ANR, the main thread's stack is kept.
- **The last forty errors**: playback errors you saw, and errors caught along the way (a stream that couldn't be resolved, a page that failed for a reason other than your connection). A repeat counts up instead of adding a line.

They stay until newer ones push them out or you clear them. Before anything is written, it's scrubbed: URL queries are cut (they carry your IP and signed stream parameters), and cookies, auth headers, email addresses and long tokens (visitor data, continuations) are removed. Video IDs stay, because a playback report needs them. Your account and your searches are never in it; the only sign of what you watch is the ID of a video that failed to play, or the link you add yourself.

**Settings › About › Report a problem** (also **Report** on a playback error, and the prompt after a crash) shows you the whole report before it goes anywhere. You pick what happened, describe it, and choose whether to include the diagnostics. Then either:

- **Open a GitHub issue**: your browser opens a new issue on this repository with the form filled in. Nothing is posted until you submit it there. If the report is too long for a link, the full text is copied to your clipboard for you to paste.
- **Share**: the share sheet, to send it by email or any other app.

Nothing leaves the phone unless you send a report yourself. The one exception is if you've opted into anonymous usage counts (see [`PRIVACY.md`](PRIVACY.md)): then, on the next launch, a crash is counted as its kind (crash, ANR or native) and the exception's class name, with no stack trace, message or report text.

## What isn't supported, and what it would take

| Feature | Why not | What it would take |
|---|---|---|
| Premium audio (256 kbps), verified end to end | The Premium formats setting asks for what YouTube grants the signed-in Premium account. Some clients also want a proof-of-origin token, which Oriel doesn't generate ([`docs/SCOPE.md`](docs/SCOPE.md)), so those formats may not be offered. No Premium account was available to test with. | Signing in with a Premium account and turning on Premium formats; the player's settings sheet shows the bitrate actually playing. |
| Notifications inbox, verified end to end | It reads the same endpoint as YouTube's web bell (`notification/get_notification_menu`), and the parser is tested against that response's shape. But the inbox needs a signed-in account, and none was available on the development machine. | Sign in and open You → Notifications. |
| Watch history reporting, verified end to end | It's implemented, and the ping bookkeeping is unit-tested. But checking that a viewing lands in an account's history needs a signed-in account, and none was available on the development machine. | Sign in, watch a minute of something, then look at youtube.com/feed/history. Each ping's HTTP status is in `adb logcat -s WatchReporter`. |
| Age-restricted videos | YouTube requires a signed-in adult account, and often a proof-of-origin token that Oriel doesn't generate. Some may play signed in; it's untested. | Nothing beyond signing in; Oriel won't get around the age check. |
| Posting, editing or deleting comments | Reading uses public continuation tokens. Writing needs the signed-in web comment endpoints with per-comment parameters and attestation, and a mistake there puts the user's account at risk. | Not planned while it needs attestation Oriel won't provide. |
| Casting to Chromecast or a TV | Cast needs Google's proprietary Cast SDK (Play Services) and a registered receiver app, and YouTube's receiver doesn't accept third-party senders. | A Cast developer registration, a custom web receiver that plays the resolved streams, and the Cast SDK, which also means giving up the Play-Services-free build. |
| Push notifications (new uploads, replies) | YouTube pushes through Firebase to the official app's sender ID, which other apps can't register with. | A periodic WorkManager poll of subscriptions on the device, which costs battery, or a server of your own that pushes through your own Firebase project. |
| Baseline profiles of the app's own code | Generating them requires a Macrobenchmark run on a device or emulator. Library profiles (Compose and others) are already included. | A device or a KVM-capable host, plus a `:baselineprofile` module. |
| Guaranteed Google sign-in | Google blocks sign-in inside WebViews it detects. Oriel removes the WebView marker from its user agent, which usually works, but Google can change its detection at any time. Custom Tabs can't hand cookies back to the app. | If it does get blocked: a cookie import (pasted or from a file). |
| Downloading videos or audio | Permanently out of scope ([`docs/SCOPE.md`](docs/SCOPE.md)). Playback keeps a short on-disk cache so seeking back doesn't refetch, but it's never saved as files. | Not planned. |
| Uploading, Studio, live chat, Stories, memberships, purchases | These are out of scope, or their APIs are private to the official apps. | Not planned. |

## Contributing

Where Oriel is headed: [`docs/ROADMAP.md`](docs/ROADMAP.md). Contributions are welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md), then [`docs/SCOPE.md`](docs/SCOPE.md) (what Oriel will and won't do) and [`docs/DESIGN.md`](docs/DESIGN.md) (how it should look and feel). AI-assisted work follows [`AGENTS.md`](AGENTS.md). Issues and PRs are triaged as described in [`docs/TRIAGE.md`](docs/TRIAGE.md). Security problems go through [`SECURITY.md`](SECURITY.md).

## Licence

Oriel © 2026 [khush2003](https://github.com/khush2003). It's free software under the GNU GPL v3.0 only (`LICENSE`), with additional attribution terms under the GPL's section 7 (`NOTICE`). In short, you can use, study, change and share Oriel, including commercially, as long as you keep the source open under the same terms, keep the credit to khush2003 (in the app's About screen and in your README or store listing), and give a modified version its own name, icon and app ID. The name and logo aren't covered by the licence; see `TRADEMARKS.md`.

Oriel uses InnerTubeX (GPL-3.0) and adapts code from BitChord (GPL-3.0); those parts stay under their authors' terms. The liquid-glass backdrop is Kyant's library (Apache-2.0), and those files keep its header. The Settings → About → Licences screen lists every dependency, and `THIRD_PARTY.md` lists every source, font and data set with its licence; licence texts are in `LICENSES/`. What the app stores and sends is in `PRIVACY.md`.

YouTube's Terms of Service don't permit access through unofficial clients. Oriel talks to the same internal APIs as YouTube's own apps and website, and it doesn't show ads. Use it at your own risk: an account you sign in with could be restricted. Oriel doesn't download videos, unlock Premium features or get around YouTube's bot checks; [`docs/SCOPE.md`](docs/SCOPE.md) lists what it will never do. SponsorBlock and Return YouTube Dislike are third-party services: they receive the IDs of the videos you watch, but no account details, and both can be turned off in Settings.
