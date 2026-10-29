#!/usr/bin/env bash
# Reproducible source verification; this command does not publish or generate signing keys.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f local.properties ]; then
  echo 'Set ANDROID_HOME or create an untracked local.properties with sdk.dir.' >&2
  exit 1
fi
bash tools/checks/run.sh
./gradlew :app:testDebugUnitTest :app:testPreviewUnitTest :app:assembleDebug \
  :app:lintDebug :app:lintPreview :inspector:assembleDebug :inspector:lintDebug \
  --no-daemon --max-workers=2
