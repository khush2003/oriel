#!/usr/bin/env bash
# Repository guardrails: things that must never land, whatever a reviewer misses.
# See docs/SCOPE.md for the why. Runs in CI on every PR and push; run it locally before pushing.
# BASE_REF (optional): the commit a PR branches from, to check only the PR's commits' authors.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

failed=0
fail() { echo "✗ $1"; failed=1; }
pass() { echo "✓ $1"; }

# Source and build files only: docs and this script are allowed to name what's forbidden.
code_files() { git ls-files -- 'app/**' 'core/**' '*.gradle.kts' 'gradle/**' ':!**/*.md'; }

# check <description> <extended regex> [extra grep flags]
check() {
  local what=$1 pattern=$2; shift 2
  local hits
  hits=$(code_files | xargs -r grep -nIE "$@" -- "$pattern" 2>/dev/null)
  if [ -n "$hits" ]; then fail "$what"; echo "$hits" | head -20 | sed 's/^/    /'; else pass "$what"; fi
}

echo "Scope (docs/SCOPE.md)"
check "No downloading of videos or audio" \
  'DownloadManager|DownloadService|DownloadRequest|media3\.exoplayer\.offline|ACTION_DOWNLOAD|WRITE_EXTERNAL_STORAGE|MediaStore\.Downloads'
check "No PO tokens, BotGuard or attestation workarounds" \
  'po_?token|botguard|integrity_?token|WAA/Create|GenerateIT|jnn-pa\.googleapis' -i
check "No Premium-only features unlocked" \
  'premium_?only|unlock_?premium|isPremium *= *true' -i
check "No ad or sponsor injection, no ad-revenue spoofing" \
  '/api/stats/ads|doubleclick\.net|googleadservices|pagead' -i

echo "Branding"
branding=$(git ls-files -- 'app/src/main/res/**' 'assets/**' | grep -iE 'youtube|yt_?logo|google' || true)
if [ -n "$branding" ]; then fail "No YouTube or Google logo assets"; echo "$branding" | sed 's/^/    /'; else pass "No YouTube or Google logo assets"; fi
check "App name isn't YouTube's" '<string name="app_name">[^<]*(YouTube|YT)[^<]*<' -i

echo "Privacy and secrets"
tracked_secrets=$(git ls-files -- 'keystore.properties' 'local.properties' '*.jks' '*.keystore' '*.p12' 'google-services.json' '.env' '*.env')
if [ -n "$tracked_secrets" ]; then fail "No keystores, local config or env files tracked"; echo "$tracked_secrets" | sed 's/^/    /'; else pass "No keystores, local config or env files tracked"; fi
check "No credentials in source" \
  '(SAPISID|__Secure-[0-9]?P?SID|LOGIN_INFO)=[A-Za-z0-9_/+-]{8,}|A-(EU|US|SH)-[0-9]{6,}|AIza[0-9A-Za-z_-]{35}|-----BEGIN [A-Z ]*PRIVATE KEY'
check "Cookies and auth headers are never logged" \
  '(Log\.[dviwe]|println|Timber\.[dviwe])\(.*(cookie|authorization|sapisid)' -i
check "No analytics or ad SDKs" \
  'com\.google\.firebase|crashlytics|play-services-(ads|analytics|measurement)|com\.appsflyer|com\.adjust\.sdk|com\.facebook\.android|com\.mixpanel|com\.amplitude|io\.sentry|com\.segment|admob' -i

echo "Repository hygiene"
emails=$(git ls-files -z | xargs -0 grep -IhoE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' 2>/dev/null \
  | grep -viE '@users\.noreply\.github\.com$|@anthropic\.com$|@example\.(com|org)$|^git@github\.com$|@googlegroups\.com$|@contributor-covenant\.org$' \
  | grep -vE '^(this|super|id|io|androidx|org|com|kotlinx)@' | sort -u || true)
if [ -n "$emails" ]; then fail "No personal email addresses in files"; echo "$emails" | sed 's/^/    /'; else pass "No personal email addresses in files"; fi

range=${BASE_REF:+$BASE_REF..HEAD}
authors=$(git log --format='%ae%n%ce' ${range:-HEAD} | sort -u \
  | grep -viE '@users\.noreply\.github\.com$|^noreply@github\.com$|@anthropic\.com$' || true)
if [ -n "$authors" ]; then fail "Commits use GitHub noreply addresses"; echo "$authors" | sed 's/^/    /'; else pass "Commits use GitHub noreply addresses"; fi

big=$(git ls-files -z | xargs -0 -r du -k 2>/dev/null | awk '$1 > 1024 { print $2 " (" $1 " KB)" }')
if [ -n "$big" ]; then fail "No files over 1 MB"; echo "$big" | sed 's/^/    /'; else pass "No files over 1 MB"; fi

version=$(sed -nE 's/^\s*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
# Strict SemVer 2.0.0 with no leading zeros; pre-releases only as -beta.N or -rc.N (docs/RELEASING.md).
n='(0|[1-9][0-9]*)'
semver="^$n\\.$n\\.$n(-(beta|rc)\\.[1-9][0-9]*)?$"
if [[ "$version" =~ $semver ]]; then pass "Version $version is strict SemVer (docs/RELEASING.md)"; else fail "Version '$version' isn't strict SemVer: MAJOR.MINOR.PATCH, optionally -beta.N or -rc.N (docs/RELEASING.md)"; fi

if grep -q 'Made by khush2003' app/src/main/java/app/oriel/ui/screens/SettingsScreen.kt \
  && grep -q 'Oriel © 2026 khush2003' app/src/main/java/app/oriel/ui/screens/SettingsScreen.kt \
  && grep -q 'Copyright (C) 2026 khush2003' NOTICE; then
  pass "Author credit and copyright are intact (NOTICE, section 7(b))"
else
  fail "Author credit and copyright are intact (NOTICE, section 7(b))"
fi

for f in LICENSE NOTICE TRADEMARKS.md THIRD_PARTY.md PRIVACY.md docs/SCOPE.md; do
  [ -f "$f" ] || fail "$f exists"
done

echo
if [ $failed -ne 0 ]; then
  echo "Guardrails failed. See docs/SCOPE.md and CONTRIBUTING.md."
  exit 1
fi
echo "All guardrails pass."
