# Contributing to Oriel

Thanks for helping. Oriel is a small project with a high bar for feel and a strict scope, so
please read this page before you write code. It's short.

## Before you start

1. **Read [`docs/SCOPE.md`](docs/SCOPE.md).** Some features (downloads, getting around YouTube's
   protections, Google branding, ads or paid tiers, tracking) are permanently out of scope.
   PRs adding them are closed without review.
2. **Open or find an issue first** for anything bigger than a small bug fix, and wait for
   the maintainer to label it `status: accepted`. This saves you from building something that can't be
   merged. Small fixes (typos, a crash with an obvious cause, a broken layout) can go straight to a PR.
3. **One change per PR.** A bug fix, a feature, or a refactor, not all three. Large PRs are asked
   to split.

## Setting up

- JDK 17 to run Gradle, and the Android SDK with platform 37. The tests also need JDK 21 (InnerTubeX
  ships Java 21 bytecode); Gradle downloads it the first time.
- `./gradlew :app:assembleDebug` builds a debug APK into `app/build/outputs/apk/debug/`.
- Builds are tuned for a 16 GB machine (`gradle.properties`); run one build at a time.

## Making the change

- **Look and feel:** follow [`docs/DESIGN.md`](docs/DESIGN.md). Every visual change needs before and
  after screenshots, in dark and light.
- **Style:** Kotlin official style (`kotlin.code.style=official`). Keep comments few and about *why*,
  as the surrounding code does. Don't reformat files you aren't otherwise changing.
- **Licence headers:** keep the headers on files adapted from other projects (BitChord, Kyant0's
  backdrop, NewPipe, Convx). New third-party code or dependencies must be GPL-3.0-compatible and
  listed in [`THIRD_PARTY.md`](THIRD_PARTY.md).
- **Privacy:** never log the sign-in cookie, request headers or signed URLs. Anything new sent off
  the phone must be documented in [`PRIVACY.md`](PRIVACY.md) and approved in the issue first.
- **Fixtures:** test data must be invented. Don't commit recorded YouTube responses, real
  thumbnails, real comments, or anyone's account data.

## Stored data

Everything the app keeps on the phone (the library, resume points, settings, the account) must
survive every update. Each stored document carries a `schema` version (see `util/Stored.kt`).

- Adding a field with a default is fine as it is. Never rename or remove a field, or rename an enum
  constant, without a migration: persisted enums carry `@SerialName`, so renaming the Kotlin name
  alone is safe.
- Any other change to a persisted model needs a migration step: bump the document's `Schema`
  version and add the step from the previous one.
- Add a frozen, invented fixture of the old shape under `app/src/test/resources/stored/`
  (`library.v<N>.json`, `settings.v<N>.json`) and a test that it still reads. Don't edit existing
  fixtures.
- Cached pages aren't migrated: bump `PageDiskCache.VERSION` instead, and the old ones are deleted.

## Tests

All of these must pass; CI runs them on every PR.

```sh
./gradlew :core:test :app:testDebugUnitTest   # unit, Robolectric and screenshot tests
./gradlew :app:lintDebug                      # Android lint
scripts/check-guardrails.sh                   # scope, privacy and repo hygiene checks
```

- A bug fix comes with a test that fails before it and passes after, where that's possible.
- Live tests (`-Plive=true`) talk to YouTube and are opt-in; CI doesn't run them, because results
  depend on the network. If your change affects parsing or playback, run the relevant live test
  locally and paste the output in the PR.
- Screenshot images are written to `app/build/screenshots/`; attach the relevant ones to your PR.

## Commits and pull requests

- Write commit messages that say what changed and why, in plain English. Conventional-commit
  prefixes are welcome but not required.
- Use your GitHub noreply address if you don't want your email public
  (`git config user.email <id>+<login>@users.noreply.github.com`).
- Fill in the PR template completely: what changed, why, how you tested it, screenshots, and the
  scope checklist. PRs with the template deleted are closed.
- Keep PRs as drafts until CI is green and you've tested on a phone. CI on a PR from a fork waits
  for the maintainer to approve the run.
- Workflow changes: pin every action to a full commit SHA with the version in a comment, give jobs
  only the `permissions:` they need, and pass untrusted values (titles, branch names) through
  `env:`, never `${{ }}` inside `run:`. The guardrails check all three.
- The maintainer, @khush2003, reviews within about a week and is the only one who merges.
  Address review by pushing new commits (don't force-push during review); PRs are squashed on merge.

## AI-assisted contributions

Welcome, under the same bar as everything else, plus a few rules: see [`AGENTS.md`](AGENTS.md).
In short: you're responsible for every line, you must have run it, and you must say in the PR that
an AI tool helped.

## Versioning

Oriel uses Semantic Versioning: features bump MINOR (`1.1.0`), fixes bump PATCH (`1.1.1`), and
big releases may get a beta first (`1.2.0-beta.1`). `versionCode` goes up by one with every release. Only the maintainer bumps versions, in a release PR. See [`docs/RELEASING.md`](docs/RELEASING.md).

## Labels and triage

How issues and PRs are labelled and moved along: [`docs/TRIAGE.md`](docs/TRIAGE.md).

## Licensing your contribution

By submitting a contribution you agree that it's licensed under the same terms as Oriel: the
GNU GPL v3.0 only, with the additional terms in `NOTICE`. You keep the copyright in your work;
add yourself to a file's header if you wrote a substantial part of it. Don't remove or change
existing copyright or attribution notices, including the "Made by khush2003" credit on the
About screen. PRs that do are closed.

## Conduct

Everyone here follows the [Code of Conduct](CODE_OF_CONDUCT.md). Security problems go through
[`SECURITY.md`](SECURITY.md), never a public issue.
