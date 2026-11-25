<img src="assets/icon.svg" width="64" height="64" alt="">

# Oriel

A YouTube client for Android, written in Kotlin with Jetpack Compose and Media3. It's for watching videos, not music, and its interface is iOS-like: liquid glass, a floating tab bar, large titles, rubber-band overscroll and press-and-hold menus. The design follows [BitChord](https://github.com/kushagrasinghx/BitChord).

Oriel works signed out. Signing in is optional and brings in your own home feed, subscriptions, playlists, likes and history.

Made by [khush2003](https://github.com/khush2003). Oriel is an independent project. It is not affiliated with, endorsed by or connected to YouTube or Google.

Oriel is in beta. APKs are on the [releases page](https://github.com/khush2003/oriel/releases), and the app offers newer versions itself.

## Features

- **Player.** One video surface moves between the watch page, fullscreen and a floating picture-in-picture window, following the finger. Double-tap to seek, hold for 2×, and scrub with frame previews, chapters and SponsorBlock segments.
- **Background play.** Sound carries on with the screen off, from the notification, Bluetooth and Android Auto, or in Android's picture-in-picture.
- **Quality.** 144p to 2160p, set per network, with a data saver. Auto adapts as it plays. Audio tracks (original language preferred), captions with auto-translate, speed, loop and a sleep timer.
- **No ad breaks.**
- **Watch page.** Description, comments with replies and sorting, chapters, the playlist panel, related videos and Autoplay.
- **Shorts.** A vertical pager with comments, sounds and scrubbing.
- **Search.** Suggestions, recent searches, voice, and all of YouTube's filters.
- **Home and Subscriptions.** YouTube's own feeds when signed in, and feeds that work signed out. Videos you've watched stay out of them.
- **Channels and playlists.** Channel tabs, sorting and About; playlists you can play, shuffle and save; local playlists you can rename and reorder.
- **Library.** History you can search and pause, Watch later, likes, playlists and a notifications inbox. Changes work offline; signed in, they're mirrored to your account, and what you watch reaches your YouTube history.
- **Accounts.** A Guest and several signed-in accounts, each with its own library.
- **Phones and tablets.**
- **Community extras.** SponsorBlock and Return YouTube Dislike; both can be turned off.
- **Appearance.** Light and dark themes. Liquid glass and liquid motion each have a switch, as do Reduce motion and Reduce transparency.

## What it doesn't do

- Downloads, Premium unlocking and getting around YouTube's bot checks or region locks: never ([`docs/SCOPE.md`](docs/SCOPE.md)).
- Posting comments, casting, push notifications, live chat, uploading: not supported.
- Google can block sign-in inside apps at any time; Oriel works signed out.

## Building

JDK 17 runs Gradle, with Android SDK platform 37. Tests also need JDK 21, which Gradle downloads.

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/, one APK per ABI and a universal one
scripts/verify.sh                # guardrails, tests, lint and a debug build, as CI runs them
```

Release APKs are unsigned without your own key; see [the signing guide](docs/RELEASING.md#signing). Setup, single tests, live tests and the phone checklist are in [`docs/QA.md`](docs/QA.md); the code map is [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Privacy

Oriel has no server. What it stores on the phone and what it sends to YouTube, SponsorBlock, Return YouTube Dislike and GitHub is listed in [`PRIVACY.md`](PRIVACY.md).

## Contributing

Start with [`CONTRIBUTING.md`](CONTRIBUTING.md), then [`docs/SCOPE.md`](docs/SCOPE.md) and [`docs/DESIGN.md`](docs/DESIGN.md). AI-assisted work follows [`AGENTS.md`](AGENTS.md). Plans are in [`docs/ROADMAP.md`](docs/ROADMAP.md), and security problems go through [`SECURITY.md`](SECURITY.md).

## Licence

Oriel © 2026 [khush2003](https://github.com/khush2003). It's free software under the GNU GPL v3.0 only (`LICENSE`), with additional attribution terms under the GPL's section 7 (`NOTICE`). In short, you can use, study, change and share Oriel, including commercially, as long as you keep the source open under the same terms, keep the credit to khush2003 (in the app's About screen and in your README or store listing), and give a modified version its own name, icon and app ID. The name and logo aren't covered by the licence; see `TRADEMARKS.md`.

Oriel uses InnerTubeX (GPL-3.0) and adapts code from BitChord (GPL-3.0); those parts stay under their authors' terms. The liquid-glass backdrop is Kyant's library (Apache-2.0), and those files keep its header. `THIRD_PARTY.md` lists every source, library, font and data set with its licence; licence texts are in `LICENSES/`.

YouTube's Terms of Service don't permit access through unofficial clients. Oriel talks to the same internal APIs as YouTube's own apps and website, and it doesn't show ads. Use it at your own risk: an account you sign in with could be restricted.
