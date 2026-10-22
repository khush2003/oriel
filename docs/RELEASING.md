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
   A tag ruleset lets only the maintainer create, move or delete `v*` tags.
3. The `release` workflow's build job waits for approval: open the run (Actions › Release) and
   approve the `release` environment. Only `v*` tags can use that environment, and only it holds
   the signing secrets.
4. The workflow checks that the tag is on `main` and matches `versionName`, builds the APKs (one per
   architecture plus a universal one), checks each is signed with the release certificate, and
   then, in a separate job with no build code, attests their provenance and drafts a GitHub
   release with them and their SHA-256 sums. Check the draft, write the notes (what changed, for
   users), publish.

The asset names (`Oriel-<version>-<abi>.apk`, `Oriel-<version>-universal.apk`) and
`SHA256SUMS.txt` (`sha256sum` format) are an API: the app's updater finds and checks downloads by
them. Don't rename them, and don't publish a release without them.

## Signing

Release builds are only ever signed with the release key. Without `keystore.properties` (or
`ORIEL_SIGNING_PROPERTIES` pointing at one), `assembleRelease` stops at packaging and says so;
debug builds, tests and lint never need it. The workflow decodes the key into the runner's temp
directory, outside the checkout, and deletes it after the build.

The release certificate's SHA-256 is
`6e462a636f6f0abfc24f7811ed20fb80af9b1771a131da6a3cb9cc785e01586f`. The workflow refuses APKs
signed with anything else, and the release notes and `SECURITY.md` publish it. It never changes:
a new key would mean every user reinstalling.

## Checking a release

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
gh attestation verify Oriel-1.0.0-arm64-v8a.apk -R khush2003/oriel
apksigner verify --print-certs Oriel-1.0.0-arm64-v8a.apk   # SHA-256 digest as above
```

## Dependencies

Actions are pinned to commit SHAs (`scripts/check-guardrails.sh` enforces it); Dependabot moves
them, after a week's cooldown. JitPack serves InnerTubeX and nothing else, and its files are
pinned in `gradle/verification-metadata.xml`. Bumping InnerTubeX means updating those checksums:
run `./gradlew --write-verification-metadata sha256 :core:compileKotlin :app:compileDebugKotlin`,
check the diff touches only the InnerTubeX entries, and compare the new checksums with files
downloaded straight from `https://jitpack.io/com/github/MetrolistGroup/innertubex/`. Until then,
a Dependabot PR for it fails CI, on purpose.

## Baseline profile

`app/src/main/baseline-prof.txt` is written by hand: broad rules for the app and the libraries
that ship no profile of their own. The release build merges it into `assets/dexopt/baseline.prof`
(check with `unzip -l` on the APK). A measured profile is better; to make one, with a phone or an
emulator attached, add a `:baselineprofile` module (`androidx.baselineprofile` plugin, a
`BaselineProfileRule` test that launches the app, scrolls Home and plays a video), run
`./gradlew :app:generateReleaseBaselineProfile`, and replace the hand-written file with the result.
CI has no device, so the module isn't kept in the repository.

## Secrets the release workflow needs

Set these as secrets of the `release` environment (Settings › Environments › release), not as
repository secrets, so only the release build job can read them:

| Secret | What |
|---|---|
| `KEYSTORE_BASE64` | The release keystore, base64-encoded |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Its passwords and alias |
| `APTABASE_KEY` | Optional. The Aptabase App Key for opt-in usage counts; without it the release never asks and never sends anything |

Keep the keystore backed up offline: losing it means users can't update without reinstalling.

## Download counts

GitHub counts each release asset's downloads: `gh api repos/OWNER/REPO/releases --jq '.[] | {tag_name, downloads: [.assets[].download_count]}'`.
