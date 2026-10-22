# Releasing

## Versions

Oriel uses [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- **Features or changed behaviour** bump MINOR: `1.1.0`, `1.2.0`.
- **Fixes only** bump PATCH: `1.1.1`.
- **Something users rely on removed or changed incompatibly** bumps MAJOR: `2.0.0`. This is rare.
- **Betas** come before a release when it's worth testing first: `1.0.0-beta.1`, `1.2.0-beta.1`.
  They're optional, and published as GitHub pre-releases.

`versionCode` goes up by one with every build that's published, betas included, and is never
reused. No leading zeros (`1.10.0`, not `1.010.0`). Both values live in `app/build.gradle.kts`;
`scripts/check-guardrails.sh` rejects anything that isn't strict SemVer, and the release workflow
rejects a `versionCode` that doesn't go up. The private test build `0.9.01-alpha` predates this.

## Cutting a release

1. Open a release PR that bumps `versionName` and `versionCode` and updates the README if
   behaviour changed. CI must be green.
2. Merge it, then tag the merge commit `v<versionName>` (e.g. `v1.0.0` or `v1.1.0-beta.1`) and push the tag.
3. The `release` workflow checks the tag against `versionName`, builds the signed APKs (one per
   architecture plus a universal one) and drafts a GitHub release with them and their SHA-256
   sums. Check the draft, write the notes (what changed, for users), publish.

## Baseline profile

`app/src/main/baseline-prof.txt` is written by hand: broad rules for the app and the libraries
that ship no profile of their own. The release build merges it into `assets/dexopt/baseline.prof`
(check with `unzip -l` on the APK). A measured profile is better; to make one, with a phone or an
emulator attached, add a `:baselineprofile` module (`androidx.baselineprofile` plugin, a
`BaselineProfileRule` test that launches the app, scrolls Home and plays a video), run
`./gradlew :app:generateReleaseBaselineProfile`, and replace the hand-written file with the result.
CI has no device, so the module isn't kept in the repository.

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
