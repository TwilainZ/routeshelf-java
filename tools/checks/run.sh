#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" app/src/main/java/dev/gdirect/lite/network/IpLiteral.java app/src/main/java/dev/gdirect/lite/network/DirectNet.java app/src/main/java/dev/gdirect/lite/transfer/TransferRules.java tools/checks/src/dev/gdirect/lite/network/DirectNetChecks.java
java -cp "$TMP" dev.gdirect.lite.network.DirectNetChecks
