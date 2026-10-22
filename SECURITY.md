# Security policy

## Supported versions

| Version | Fixes |
|---|---|
| The latest 1.x release | Yes |
| The latest beta, while it's newer than the latest release | Yes |
| Anything older | No: update first |

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Report them privately:
[Report a vulnerability](https://github.com/khush2003/oriel/security/advisories/new) (the
repository's **Security** tab). Only the maintainer sees it.

Include what you found, how to reproduce it, the app version, and what an attacker could do with
it. We aim to reply within 7 days, to fix critical problems (account or cookie exposure, code
execution) within 14 days, and anything else within 30, or to tell you the plan. You'll be credited
in the advisory unless you'd rather not be.

## What counts

- Anything that could expose the sign-in cookie or account (logging it, sending it to a host
  other than YouTube's, leaking it through intents, backups, problem reports or the WebView).
- Code execution or file access through links, intents, deep links, shared text or updates.
- Anything that sends personal data off the phone beyond what `PRIVACY.md` describes.
- Supply-chain issues in the build or release process: workflows, dependencies, signing, the
  published APKs and their checksums.

Out of scope: YouTube refusing playback, YouTube's or Google's own security (report those to
Google), attacks that need a rooted or already-compromised phone, and reports from automated
scanners without a working attack.

## Safe harbour

Research done in good faith under this policy is welcome, and we won't take action against it.
Test only against your own accounts and devices, don't access other people's data, don't degrade
the service for others, and give us reasonable time to fix a problem before you publish it.

## Checking a download

Every release lists the SHA-256 of each APK, and carries a build-provenance attestation:
`gh attestation verify Oriel-<version>-arm64-v8a.apk -R khush2003/oriel` shows it was built from
the tagged commit by this repository's release workflow. All official APKs are signed with the
certificate whose SHA-256 is
`6e:46:2a:63:6f:6f:0a:bf:c2:4f:78:11:ed:20:fb:80:af:9b:17:71:a1:31:da:6a:3c:b9:cc:78:5e:01:58:6f`.
