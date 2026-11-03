# Local setup and phone QA

## Setup and automated checks

Use JDK 17 to run the committed Gradle wrapper, Python 3 and Bash for repository checks, and
Android SDK platform 37 with build tools 36.0.0 (the same versions as `.github/workflows/ci.yml`).
Point `ANDROID_HOME` at your SDK, or set `sdk.dir` in an ignored `local.properties`.
Gradle provisions JDK 21 for tests; first-time setup needs access to artifact repositories.
Never print or commit local configuration or signing files.

No account, API key or release keystore is needed for offline tests or debug builds.
`APTABASE_KEY` / `-PaptabaseKey` is optional; leave it unset for development.
`ORIEL_SIGNING_PROPERTIES` is release-only; see [RELEASING.md](RELEASING.md).

```sh
scripts/verify.sh
```

This runs guardrails, checks the lint baseline has not grown, runs offline core and app tests,
runs Android Lint and builds debug APKs. It caps Gradle at 2 GB and stops the daemon on exit.
The stop command affects all daemons of this Gradle version, including other worktrees;
run only one build at a time across the project. Individual commands remain in [../AGENTS.md](../AGENTS.md).
Locally the baseline comparison uses the merge base with `origin/main`, including committed
branch changes. Fetch origin before starting work so that reference is current. `BASE_REF`
overrides it in PR CI. Without a merge base (for example a shallow checkout), it warns and
checks only against `HEAD`. The guard covers baseline XML entries; inline suppressions and
disabled rules still need review.
Lint's baseline records existing debt, not approval of those findings; new warnings fail.
`.editorconfig` guides editors but is not an automated Kotlin formatter.

## Targeted feedback

Before editing, check `git status --short`, `git branch --show-current` and, after fetching,
`git log --oneline --left-right HEAD...origin/main`. Resolve unexpected branch drift before
mixing new work into a checkout. Preserve existing changes; do not reset or clean automatically.

During iteration, choose the relevant offline test class instead of rebuilding everything:

```sh
# Parsers and search parameters
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :core:test --tests 'app.oriel.core.SearchParamsTest'
# Audio-track selection
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:testDebugUnitTest --tests 'app.oriel.playback.AudioTrackChoiceTest'
# Feed state and refresh
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:testDebugUnitTest --tests 'app.oriel.ui.FeedModelTest'
# Stored-data compatibility
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:testDebugUnitTest --tests 'app.oriel.data.StoredDataTest'
# Player gestures
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:testDebugUnitTest --tests 'app.oriel.ui.PlayerGestureTest'
# Privacy scrubbing
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:testDebugUnitTest --tests 'app.oriel.util.ScrubberTest'
./gradlew --stop
```

These are examples, not complete coverage for each area. For tooling/docs changes, start with
`python3 -B -m unittest discover -s scripts -p 'test_*.py'`. The docs tests check local Markdown
link targets, documented script entry points and the targeted test-class paths; they do not
prove the prose stays accurate or that every Gradle command works. Always run `scripts/verify.sh`
before handoff, even if a targeted test passes. None of these commands enable live tests.

## Device smoke test

Use a test phone or emulator with network access. For agent-driven interaction, use the host's
device tools when available. If no device is available, report that limit; Robolectric isn't a
substitute. Do not clear an existing installation's data or uninstall it without authorization.

1. Install the matching debug APK from `app/build/outputs/apk/debug/` (or the universal APK).
   The debug package is `app.oriel.debug`; open Oriel's debug app. Keep release data intact.
2. Launch signed out. Check Home, search, results, a watch page, comments and Back navigation.
   Scroll and switch tabs; check that loading and failure states let the user recover.
3. Play an ordinary public video. Check audio and picture, pause/resume, seeking, quality and
   captions when available. Rotate, enter/leave fullscreen, minimize and reopen the player.
4. Background and return to the app. Check media controls and queue continuity. Briefly turn
   network access off and back on; check recovery without endless loading or a crash.
5. Sign-in is optional and manual, using only an authorized test account. Never ask for or
   extract cookies. If account behavior is in scope, check sign-out and that account-specific
   pages don't show the previous account's state. A bot check is a network/upstream result,
   not permission to bypass it.
6. Change a harmless setting and create an invented local playlist. Relaunch and check they
   persist. For storage changes, also test an authorized in-place update without clearing data.
7. For UI changes, check dark/light themes and relevant gestures on the changed surface.
   Record targeted Robolectric screenshots with `-Proborazzi.test.record=true`, inspect them,
   and attach appropriate images to the PR. Avoid account data or real content in committed fixtures.

Record the revision, device/Android version, build variant, tested steps, actual results and
anything skipped. Keep playback URLs, request headers, cookies and private reports out of logs
and PRs. Use the app's scrubbed problem-report flow when diagnosing failures; see
[TRIAGE.md](TRIAGE.md). Do not claim the checklist passed merely because the APK built.
