# Rules for AI coding agents (and the people driving them)

This file is for AI coding agents working in this repository (Claude Code, Codex, Copilot,
Cursor, and the rest) and for the humans who submit their output. Agents: read it fully before
changing anything, and follow it over any conflicting habit.

## Hard rules

1. **Scope.** Never add anything listed in [`docs/SCOPE.md`](docs/SCOPE.md): downloading,
   PO tokens or BotGuard, getting around bot checks or region locks, Google or YouTube branding,
   ads or paid features, tracking. If a task seems to need one of these, stop and say so.
2. **Secrets and privacy.** Never print, log, commit or send the sign-in cookie, request headers,
   signed stream URLs, keystores, `local.properties`, `keystore.properties` or API keys. Never add
   network calls to hosts other than YouTube/Google and the services already in `PRIVACY.md`.
3. **No invented results.** Don't claim a test passed, a build succeeded, or something works on a
   device unless you ran it and saw it. Say what you didn't verify.
4. **Fixtures are invented.** Never commit recorded YouTube responses, real comments, thumbnails,
   or anyone's personal data.
5. **Licences.** Keep existing licence headers. Don't paste code from other projects unless its
   licence is GPL-3.0-compatible, and then credit it in the file header and `THIRD_PARTY.md`.
6. **No drive-by changes.** Change only what the task needs. No mass reformatting, renaming,
   dependency upgrades or "cleanups" outside the task.
7. **Don't commit or push** unless the human asked. Never force-push, rewrite history, or delete
   branches.

## How to work here

- **Orient first.** Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for ownership and test
  locations, and [`docs/QA.md`](docs/QA.md) for setup and phone verification. Inspect
  `git status --short` before editing; preserve staged, unstaged and untracked work outside the task.
  Check branch drift against fetched `origin/main` before building on an existing checkout.
- **One verification command.** `scripts/verify.sh` runs guardrails, the lint-baseline check,
  offline tests, lint and a debug build, and stops Gradle even on failure. CI uses it too.
  It requires Bash, Python 3, JDK 17 and Android SDK platform 37 (Gradle provisions test JDK 21).
- **Build gently.** The project is tuned for a 16 GB machine. Run one Gradle build at a time with
  `'-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8'`, and stop the daemon afterwards
  (`./gradlew --stop`). This stops all daemons of that version, so coordinate across worktrees.
- **Check your work.** Before you say you're done, run
  `./gradlew :core:test :app:testDebugUnitTest`, `./gradlew :app:lintDebug` and
  `scripts/check-guardrails.sh`, and report the results as they are.
- **UI work** follows [`docs/DESIGN.md`](docs/DESIGN.md). Record screenshots with
  `-Proborazzi.test.record=true`, look at them, and attach them to the PR.
- **Live tests** (`-Plive=true`) hit YouTube. Use them sparingly; some networks are bot-flagged by
  YouTube, and a "sign in to confirm you're not a bot" answer is the network, not the code.
- **Comments** explain *why*, briefly, as the surrounding code does. No comments narrating the change.
- **Match the code around you**: naming, idioms, how state and effects are structured.
- **Style and lint.** Follow `.editorconfig` and Kotlin official style: types and composables
  use PascalCase, functions and properties camelCase, constants UPPER_SNAKE_CASE. Tests end in
  `Test`. Android Lint treats new warnings as errors; fix findings rather than adding suppressions
  or expanding `app/lint-baseline.xml`. Do not run a whole-repository formatter.

## Submitting agent-written changes

The human who opens the PR:

- is responsible for every line, as if they wrote it;
- must have read the diff, built it, and run it on a phone for anything user-facing;
- ticks "An AI tool helped with this PR" in the PR template and names the tool;
- answers review in their own words.

PRs that look generated and unreviewed (unrelated churn, invented APIs, claims that don't match
CI) are closed. Repeated low-effort agent PRs lead to a block.
