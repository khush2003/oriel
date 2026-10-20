# Releasing

## Versions

- `versionName` is `0.9.NN-alpha`, two digits: `0.9.01-alpha`, `0.9.02-alpha`, … `0.9.99-alpha`.
- `versionCode` goes up by exactly one with every release, never reused.
- Both live in `app/build.gradle.kts`. Only a release PR changes them.

## Cutting a release

1. Open a release PR that bumps `versionName` and `versionCode` and updates the README if
   behaviour changed. CI must be green.
2. Merge it, then tag the merge commit `v0.9.NN-alpha` and push the tag.
3. The `release` workflow builds the signed APK and drafts a GitHub release with it attached and
   its SHA-256 in the notes. Check the draft, write the notes (what changed, for users), publish.

## Secrets the release workflow needs

Set these in the repository's Settings › Secrets and variables › Actions:

| Secret | What |
|---|---|
| `KEYSTORE_BASE64` | The release keystore, base64-encoded |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Its passwords and alias |
| `APTABASE_KEY` | Optional. The Aptabase App Key for opt-in usage counts; without it the release never asks and never sends anything |

Keep the keystore backed up offline: losing it means users can't update without reinstalling.

## Download counts

GitHub counts each release asset's downloads: `gh api repos/OWNER/REPO/releases --jq '.[] | {tag_name, downloads: [.assets[].download_count]}'`.
