#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
swift --version
swift test --jobs 3
swift build --configuration release --product GDirectMac --jobs 3
if [ "$(uname -s)" != Darwin ]; then
  echo 'Linux checks cover shared core only. Apple frameworks/app require the macOS job.'
fi
