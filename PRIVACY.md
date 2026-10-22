# Privacy

Oriel has no server and no account of its own. This page lists everything it stores and
everything it sends, and to whom.

## Stored on your phone

- Watch history, search history, resume positions, likes, saved and local playlists, followed
  channels and settings. They stay in the app's private storage and are excluded from
  Android backups and device-to-device transfers. Pausing or clearing history is in
  Settings › Privacy & storage.
- If you sign in: your YouTube session cookie, in the app's private storage. It's never logged,
  and it's sent only to YouTube's own servers.
- Problem reports: the last five crashes and the last forty errors (playback errors and failed
  page loads), in the app's private storage, until newer ones replace them or you clear them in
  Settings › About › Report a problem. URL queries, cookies, auth headers, email addresses and
  long tokens are removed before they're written. They leave the phone only if you send a report
  yourself, as a GitHub issue you submit in your browser or through the share sheet, and you see
  the full text first.

## Sent to YouTube (Google)

Oriel talks to YouTube the way its website does: to load feeds, search and play videos,
and, if you sign in, to read and update your account (likes, subscriptions, playlists, watch
history). Google's [privacy policy](https://policies.google.com/privacy) applies to that.

## Sent to community services (each can be turned off in Settings)

- **SponsorBlock** (sponsor.ajay.app): the first four characters of a hash of the ID of the video
  you open, never the ID itself. The server answers with the segments of every video whose hash
  starts the same way, and the app picks out the one it's playing.
- **Return YouTube Dislike** (returnyoutubedislikeapi.com): the ID of the video you open, to fetch
  its dislike count.

These services see your IP address, as any server you connect to does.

## Anonymous usage counts (opt-in)

Official releases ask once, on first launch, whether you'd like to share anonymous usage counts.
They're off unless you say yes, and you can change your answer in Settings › Privacy & storage.
Builds made from source without an analytics key never ask and never send anything.

If you opt in, the app sends these events to [Aptabase](https://aptabase.com), an open-source
analytics service that doesn't store IP addresses or use device identifiers:

| Event | Extra details |
|---|---|
| App opened | whether you're signed in, and whether it's the first time the app was opened |
| Video played | whether it was a Short, part of a queue, or shuffled |
| Shorts opened | none |
| Search made | whether it was by voice |
| Crash | sent on the next launch after a crash: whether it was a crash, a freeze (ANR) or a native crash, and the exception's class name (like `IllegalStateException`) |

Each event also carries the app version, Android version, device model, language, and a random
session ID that changes after an hour without use.

It **never** includes what you watch or search for, video or channel IDs, your account,
stack traces, error messages or problem reports, or anything else that could identify you.

## Contact

Questions or requests: open an issue on the project's GitHub repository.
