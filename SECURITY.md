# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Report them privately through
GitHub: the repository's **Security** tab › **Report a vulnerability**. Only maintainers see it.

Include what you found, how to reproduce it, and what an attacker could do with it. We aim to
reply within 7 days and to ship a fix, or a plan, within 30.

## What counts

- Anything that could expose the sign-in cookie or account (logging it, sending it to a host
  other than YouTube's, leaking it through intents, backups, problem reports or the WebView).
- Code execution or file access through links, intents, deep links or shared text.
- Anything that sends personal data off the phone beyond what `PRIVACY.md` describes.
- Supply-chain issues in the build or release process.

YouTube itself refusing playback, or YouTube's own security, is out of scope; report those to Google.

## Supported versions

Only the latest release gets fixes.
