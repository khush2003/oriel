# Labels and triage

Labels are defined in [`.github/labels.yml`](../.github/labels.yml) and synced by the
`labels` workflow; edit that file, not the GitHub UI.

## Labels

| Group | Labels | Meaning |
|---|---|---|
| Type | `type: bug`, `type: feature`, `type: playback`, `type: design`, `type: docs`, `type: chore` | What the issue or PR is. Playback problems (a video won't load or stalls) get their own type: they're often YouTube-side. |
| Area | `area: player`, `area: shorts`, `area: feeds`, `area: search`, `area: channel`, `area: library`, `area: settings`, `area: core`, `area: ci` | Which part of the app. PRs get these automatically from the files they touch. |
| Status | `status: needs triage`, `status: needs info`, `status: accepted`, `status: in progress`, `status: blocked`, `status: upstream` | Where it is. `upstream` means YouTube or a dependency must change first. |
| Priority | `priority: critical`, `priority: high`, `priority: normal`, `priority: low` | Critical: crashes on launch, nothing plays for anyone, or a privacy/security problem. |
| Resolution | `wontfix: out of scope`, `wontfix: by design`, `duplicate`, `invalid` | Why it closed without a change. |
| Community | `good first issue`, `help wanted` | Open to outside contributors; scoped and accepted. |
| PR | `ai-assisted`, `needs screenshots`, `needs tests`, `breaking` | Review flags. |

## Issue flow

1. **New issue** → the template adds `status: needs triage` and its type label.
2. **Triage** (maintainers, at least weekly):
   - Out of scope ([`SCOPE.md`](SCOPE.md))? Label `wontfix: out of scope`, link the rule, close.
   - Duplicate? Label `duplicate`, link the original, close.
   - Missing a version, device, steps or a problem report? `status: needs info`. The stale
     workflow closes it after 21 days without a reply.
   - Real and wanted? Remove `needs triage`, add `status: accepted`, an area, and a priority.
     Add `good first issue` / `help wanted` if it's a good outside contribution.
3. **Work starts** → `status: in progress`, assigned to whoever is doing it. Comment before
   starting so two people don't build the same thing.
4. **Closed** by the PR that fixes it (`Fixes #123` in the PR description).

## Playback reports

"This video doesn't play" is the most common report, and is usually YouTube refusing the client or
the network (bot checks, region locks, age gates, music rights). Ask for:
- the video link, the app version, and whether it plays on youtube.com from the same network;
- the **problem report** (Settings › About › Share problem report), which holds the error without
  any URLs or cookies.

If YouTube is refusing every ordinary client, label `status: upstream`. Oriel won't work around bot
checks or protections ([`SCOPE.md`](SCOPE.md) rule 2).

## Pull request flow

1. CI must be green (build, unit and screenshot tests, lint, guardrails).
2. The template must be complete. Visual changes without screenshots get `needs screenshots`;
   behaviour changes without tests get `needs tests`.
3. One maintainer approval to merge; two for anything touching `core/`, sign-in, privacy or
   the build and release setup (see `CODEOWNERS`).
4. Maintainers squash-merge with a clean message.
