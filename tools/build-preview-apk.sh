#!/usr/bin/env bash
# Key material remains outside Git and delivery artifacts. This script never creates a signing key.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GDIRECT_PREVIEW_KEYSTORE_PATH:?Set the path to the retained preview keystore outside Git}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
if [ ! -f "$GDIRECT_PREVIEW_KEYSTORE_PATH" ]; then
  echo 'Preview key is missing. Restore the existing key securely; do not generate a replacement.' >&2
  exit 1
fi
preview_revision=$(git rev-parse HEAD)
if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
  echo 'Commit source changes before packaging a traceable preview.' >&2
  exit 1
fi
./gradlew :app:assemblePreview -PbuildRevision="$preview_revision" --no-daemon --max-workers=2
python3 tools/verify-debug-apk.py --preview --signing-mode pinned-test \
  --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \
  --apksigner "$ANDROID_HOME/build-tools/35.0.0/apksigner" \
  --apk app/build/outputs/apk/preview/app-preview.apk \
  --revision "$preview_revision" --run "${GITHUB_RUN_ID:-local}" \
  --output "app/build/outputs/verified-preview/$preview_revision"
