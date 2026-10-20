# Privacy

Oriel has no server and no account of its own. This page lists everything it stores and
everything it sends, and to whom.

## Stored on your phone

- Watch history, search history, resume positions, likes, saved and local playlists, followed
  channels and settings. They stay in the app's private storage and are excluded from
  Android backups. Pausing or clearing history is in Settings › Privacy & storage.
- If you sign in: your YouTube session cookie, in the app's private storage. It's never logged,
  and it's sent only to YouTube's own servers.

## Sent to YouTube (Google)

Oriel talks to YouTube the way its website does: to load feeds, search and play videos,
and, if you sign in, to read and update your account (likes, subscriptions, playlists, watch
history). Google's [privacy policy](https://policies.google.com/privacy) applies to that.

## Sent to community services (each can be turned off in Settings)

- **SponsorBlock** (sponsor.ajay.app): the ID of the video you open, to fetch its skip segments.
- **Return YouTube Dislike** (returnyoutubedislike.com): the ID of the video you open, to fetch
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
| App opened | whether you're signed in |
| Video played | whether it was a Short, part of a queue, or shuffled |
| Shorts opened | none |
| Search made | whether it was by voice |

Each event also carries the app version, Android version, device model, language, and a random
session ID that changes after an hour without use.

It **never** includes what you watch or search for, video or channel IDs, your account,
or anything else that could identify you.

## Contact

Questions or requests: open an issue on the project's GitHub repository.
