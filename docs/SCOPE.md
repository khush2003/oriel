# Scope: what Oriel is, and what it will never be

Oriel is an independent YouTube client for Android with an iOS-style interface. It watches
YouTube the way YouTube's own website does. To stay on safe legal ground as an open-source
project, some features are out of scope **permanently**. Pull requests that add them are closed
without review, however well made, and issues asking for them are closed as `wontfix: out of scope`.

These rules exist to protect the project and everyone who contributes to it. They're enforced in
CI (`scripts/check-guardrails.sh`) as well as in review.

## Never

1. **Downloading or saving videos, audio, Shorts or thumbnails for offline use.** This includes
   "download" buttons, background downloading, exporting streams to files, saving to the gallery,
   share-as-file, and any use of `DownloadManager`, Media3's `offline`/`DownloadService` APIs or
   writing stream bytes to user-visible storage. The normal playback cache (bytes kept privately
   so seeking back and replaying don't re-download) is fine; it's never exposed as files.
2. **Getting around YouTube's technical protections.** No proof-of-origin (PO) token or BotGuard
   generation, no running Google's anti-bot or integrity programs, no attestation spoofing, no
   cookie or account sharing, no proxying to dodge region locks, rate limits or bot checks.
   If YouTube refuses a client, Oriel shows the error and tries its other ordinary clients; it
   doesn't fight the refusal.
3. **Premium-only or paid content without paying.** No unlocking Premium formats, members-only
   videos, paid courses or rentals. Formats YouTube itself grants a signed-in Premium account are fine.
4. **Google's or YouTube's branding.** No YouTube logo, the red play button, "YouTube" in the
   app name or icon, Google fonts or assets copied from YouTube's apps, or screenshots that
   suggest Oriel is an official product. Referring to YouTube by name in text (as in "a client
   for YouTube") is fine.
5. **Modified Google apps.** Oriel is its own app. No patching, repackaging or redistributing
   YouTube's APKs, and no code copied from them.
6. **Money.** No ads, paid tiers, paywalled features, affiliate links or selling data.
   (Donations to maintainers are a separate matter, and never unlock features.)
7. **Tracking people.** No analytics beyond the opt-in, anonymous usage counts described in
   `PRIVACY.md`. Never send what someone watches, searches or does on their account anywhere but
   YouTube itself. No device identifiers, no fingerprinting, no crash reporters that phone home.
8. **Bulk or automated access.** No scraping tools, mass downloaders, bots, view or like
   inflation, comment automation, or anything that makes requests on behalf of anyone but the
   person holding the phone.

## Careful (needs a maintainer's explicit OK in the issue first)

- New third-party services (like SponsorBlock or Return YouTube Dislike). They must be opt-out,
  documented in `PRIVACY.md`, and their licence must allow it.
- New network endpoints on YouTube's side, or new player clients.
- New dependencies. Their licence must be GPL-3.0-compatible and they must be added to
  `THIRD_PARTY.md`.
- Anything that touches the sign-in cookie or account actions.

## Fine

Everything a viewer does on YouTube's own website, signed in or not: watching, Shorts,
search and filters, comments, channels, playlists, likes, subscriptions, history, captions,
chapters, picture in picture, background play while the app is open in the background, quality
selection, and community features like SponsorBlock's skips.

If you're not sure, open an issue and ask before writing code.
