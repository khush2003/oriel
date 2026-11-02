# Architecture and change map

Oriel is an Android client, not a hosted service. Start with [SCOPE.md](SCOPE.md),
[../PRIVACY.md](../PRIVACY.md) and [../CONTRIBUTING.md](../CONTRIBUTING.md).

## Ownership

| Area | Entry points | Tests |
| --- | --- | --- |
| YouTube pages, parsing and models | [YouTube.kt](../core/src/main/kotlin/app/oriel/core/YouTube.kt), [parsers](../core/src/main/kotlin/app/oriel/core/parse/), [models](../core/src/main/kotlin/app/oriel/core/model/) | [core tests](../core/src/test/kotlin/app/oriel/core/) |
| Stream resolution | [StreamResolver.kt](../core/src/main/kotlin/app/oriel/core/stream/StreamResolver.kt) | core stream tests; opt-in live playback tests |
| Dependency wiring and shared HTTP client | [AppGraph.kt](../app/src/main/java/app/oriel/AppGraph.kt) | [security tests](../app/src/test/java/app/oriel/security/), [CredentialGuardTest.kt](../core/src/test/kotlin/app/oriel/core/net/CredentialGuardTest.kt) |
| Playback and queue | [PlaybackService.kt](../app/src/main/java/app/oriel/playback/PlaybackService.kt), [PlayerConnection.kt](../app/src/main/java/app/oriel/playback/PlayerConnection.kt), [QueueController.kt](../app/src/main/java/app/oriel/playback/QueueController.kt) | [playback tests](../app/src/test/java/app/oriel/playback/) |
| UI and feed state | [OrielRoot.kt](../app/src/main/java/app/oriel/ui/OrielRoot.kt), [AppState.kt](../app/src/main/java/app/oriel/ui/AppState.kt), [FeedModel.kt](../app/src/main/java/app/oriel/ui/screens/FeedModel.kt) | [UI tests](../app/src/test/java/app/oriel/ui/) |
| Persistence and account storage | [data](../app/src/main/java/app/oriel/data/), [Stored.kt](../app/src/main/java/app/oriel/util/Stored.kt), [AtomicJson.kt](../app/src/main/java/app/oriel/util/AtomicJson.kt) | [data tests](../app/src/test/java/app/oriel/data/), [invented storage fixtures](../app/src/test/resources/stored/) |
| Diagnostics | [CrashLog.kt](../app/src/main/java/app/oriel/util/CrashLog.kt), [Scrubber.kt](../app/src/main/java/app/oriel/util/Scrubber.kt), [ProblemReport.kt](../app/src/main/java/app/oriel/util/ProblemReport.kt) | [util tests](../app/src/test/java/app/oriel/util/) |
| Verified app updates | [update](../app/src/main/java/app/oriel/update/) | [update tests](../app/src/test/java/app/oriel/update/) |

## Data flow and boundaries

Compose screens consume app state and feed models. `AppGraph` wires the API client, stores,
network monitoring and diagnostics. `core` owns API access, parsing and stream resolution;
it is a JVM module and must not depend on Android UI, Context or app classes. `app` depends
on `core`, owns Android storage, and supplies the playback service and Media3 data sources.
`PlayerConnection` connects UI controls to the media session; avoid making a second player
in a screen. Check existing state owners before adding another cache or coroutine scope.

Parsed page caching lives in `core`'s `PageCache`; disk pages live in `data/PageDiskCache.kt`.
Playback byte caches live in `playback/MediaCaches.kt` and stay private. Persisted user data
has migration rules in CONTRIBUTING; disposable cached pages use a cache version instead.

Network credentials must stay behind the credential guard. Diagnostics pass through the
existing scrubber before writing; do not add raw HTTP logging to debug a failure. Crash reports
stay local unless the user shares them. Read PRIVACY before changing any outbound reporting.

## Choosing verification

Use invented parser fixtures for response-shape changes, storage migration fixtures for persisted
models, coroutine tests for feed/cache races, and Robolectric UI tests for navigation and gestures.
UI work follows [DESIGN.md](DESIGN.md). Live tests require explicit opt-in and network access;
offline tests and fake decoders cannot prove device playback, audio, rendering or thermal behavior.
Use the [targeted commands](QA.md#targeted-feedback) during iteration.

Run `scripts/verify.sh` before handoff. Test HTML reports are under each module's
`build/reports/tests/`; lint reports are under `app/build/reports/`, and recorded screenshots
under `app/build/screenshots/`. CI uploads reports even when verification fails.
