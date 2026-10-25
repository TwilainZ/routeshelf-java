#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" app/src/main/java/dev/gdirect/lite/DirectNet.java app/src/main/java/dev/gdirect/lite/TransferRules.java tests/DirectNetChecks.java
java -cp "$TMP" dev.gdirect.lite.DirectNetChecks
