<!-- Read CONTRIBUTING.md first. PRs with this template removed are closed. -->

## What and why

<!-- What changed, and the problem it solves. Link the issue: "Fixes #123". -->

## How I tested it

<!-- Commands you ran and what you checked by hand, on which phone and Android version. -->

## Screenshots

<!-- Required for any visual change: before and after, dark and light. -->

## Checklist

- [ ] This PR does one thing, and it's linked to an accepted issue (or is a small fix).
- [ ] Nothing here is on the never-list in `docs/SCOPE.md` (no downloads, no PO tokens or bot-check workarounds, no Google branding, no Premium features).
- [ ] It follows `docs/DESIGN.md`: 44dp touch targets, springs from `Motion.kt`, reduced motion respected, labels for TalkBack.
- [ ] `./gradlew :core:test :app:testDebugUnitTest`, `./gradlew :app:lintDebug` and `scripts/check-guardrails.sh` pass locally.
- [ ] No cookies, tokens, signed URLs, recorded YouTube responses or personal data in code, tests or logs.
- [ ] New third-party code or dependencies are GPL-3.0-compatible and listed in `THIRD_PARTY.md`.
- [ ] Anything new sent off the phone is described in `PRIVACY.md`.

## AI assistance

- [ ] An AI tool helped with this PR. Tool: <!-- e.g. Claude Code, Copilot -->
      I've read every line, built it and run it, and I can explain it.
