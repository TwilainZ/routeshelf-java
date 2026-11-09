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
    if git cat-file -e "$relay_baseline:app/src/main/java/dev/gdirect/lite/network/DirectRoutes.java" 2>/dev/null; then
      git show "$relay_baseline:app/src/main/java/dev/gdirect/lite/network/DirectRoutes.java" > "$relay_bench_dir/$relay_variant/DirectRoutes.java"
    else
      # Older own-project baselines predate package organization; normalize only their package name.
      git show "$relay_baseline:app/src/main/java/dev/gdirect/lite/DirectRoutes.java" > "$relay_bench_dir/$relay_variant/DirectRoutes.java"
      sed -i 's/^package dev.gdirect.lite;$/package dev.gdirect.lite.network;/' "$relay_bench_dir/$relay_variant/DirectRoutes.java"
    fi
  else
    cp app/src/main/java/dev/gdirect/lite/network/DirectRoutes.java "$relay_bench_dir/$relay_variant/DirectRoutes.java"
  fi
  relay_extra_sources=()
  if rg -q RouteProbeQueue "$relay_bench_dir/$relay_variant/DirectRoutes.java"; then
    if [ "$relay_variant" = before ]; then
      git show "$relay_baseline:app/src/main/java/dev/gdirect/lite/network/RouteProbeQueue.java" > "$relay_bench_dir/$relay_variant/RouteProbeQueue.java"
    else
      cp app/src/main/java/dev/gdirect/lite/network/RouteProbeQueue.java "$relay_bench_dir/$relay_variant/RouteProbeQueue.java"
    fi
    relay_extra_sources+=("$relay_bench_dir/$relay_variant/RouteProbeQueue.java")
  fi
  javac -d "$relay_bench_dir/$relay_variant" "$relay_bench_dir/$relay_variant/DirectRoutes.java" "${relay_extra_sources[@]}" \
    app/src/main/java/dev/gdirect/lite/network/IpLiteral.java app/src/main/java/dev/gdirect/lite/network/DirectNet.java app/src/main/java/dev/gdirect/lite/transfer/TransferRules.java \
    tools/checks/src/dev/gdirect/lite/network/RelayStartupBenchmark.java
  echo "$relay_variant (synthetic 1500ms metadata delay; fixture probe, no network):"
  java -cp "$relay_bench_dir/$relay_variant" dev.gdirect.lite.network.RelayStartupBenchmark
done
