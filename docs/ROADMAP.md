# Roadmap

Oriel is a small project, so the plan is too.

## Now: 1.0.0

The repository is public, and builds before 1.0 are `1.0.0-beta.N` pre-releases. The focus is
fixes and the important things, so that 1.0 is stable enough to use every day:

- Problem reporting: crashes and errors captured, a "Report a problem" sheet, a prompt after a crash
- Stored data that survives every update (settings and library carry a version, with migrations)
- Crash and playback bugs fixed; errors that say why a video won't play
- A polish pass on the main screens: Home, the player, Shorts, Search, You
- README screenshots

The first stable release, `1.0.0`, comes next week. Issues for it are in the **1.0.0** milestone.

## Later: 1.1, 1.2, …

After 1.0, things ship when they're ready: a feature release (`1.1.0`) every week or two,
hotfixes (`1.1.1`) when something breaks, and a beta first (`1.2.0-beta.1`) when a change is big.
Issues go in the **Later** milestone. In no particular order:

- **YouTube features not in Oriel yet:** dubbed and multi-language audio, mixes and radio,
  Explore and trending pages, transcripts, end screens, clips, channel posts, live chat, caption
  styling, search history management
- **Interface:** finishing every screen, tablets and foldables, accessibility, translations
- **Feel:** motion that follows the finger everywhere, smoother scrolling, player gestures for
  volume, brightness and seeking
- **Playback:** highest quality without stalls, faster starts
- **Oriel's own ideas:** start them in Discussions
- **Bugs:** always

Things Oriel will never do are in [`SCOPE.md`](SCOPE.md).
