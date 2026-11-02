#!/usr/bin/env bash
# Keep local and CI validation on the same Gradle tasks and memory budget.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
trap './gradlew --stop >/dev/null 2>&1' EXIT
scripts/check-guardrails.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-lint-baseline.py
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :core:test :app:testDebugUnitTest
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:lintDebug
./gradlew '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8' :app:assembleDebug
