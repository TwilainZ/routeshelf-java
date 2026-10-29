#!/usr/bin/env bash
# Local synthetic control-flow comparison; no external sockets, real IP probes or credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
relay_baseline=${1:-1820e38}
relay_bench_dir=$(mktemp -d)
trap 'rm -rf "$relay_bench_dir"' EXIT
for relay_variant in before after; do
  mkdir "$relay_bench_dir/$relay_variant"
  if [ "$relay_variant" = before ]; then
    git show "$relay_baseline:app/src/main/java/dev/gdirect/lite/DirectRoutes.java" > "$relay_bench_dir/$relay_variant/DirectRoutes.java"
  else
    cp app/src/main/java/dev/gdirect/lite/DirectRoutes.java "$relay_bench_dir/$relay_variant/DirectRoutes.java"
  fi
  javac -d "$relay_bench_dir/$relay_variant" "$relay_bench_dir/$relay_variant/DirectRoutes.java" \
    app/src/main/java/dev/gdirect/lite/DirectNet.java app/src/main/java/dev/gdirect/lite/TransferRules.java tests/RelayStartupBenchmark.java
  echo "$relay_variant (synthetic 1500ms metadata delay; fixture probe, no network):"
  java -cp "$relay_bench_dir/$relay_variant" dev.gdirect.lite.RelayStartupBenchmark
done
