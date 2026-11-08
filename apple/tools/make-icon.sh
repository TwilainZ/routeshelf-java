#!/usr/bin/env bash
set -euo pipefail
mac_art="$1"
mac_icon="$2"
mac_icon_stage=$(mktemp -d "${TMPDIR:-/tmp}/gdirect-icon.XXXXXX")
trap 'rm -rf "$mac_icon_stage"' EXIT
mkdir -p "$mac_icon_stage/AppIcon.iconset" "$(dirname "$mac_icon")"
for mac_size in 16 32 128 256 512; do
  sips -z "$mac_size" "$mac_size" "$mac_art" --out "$mac_icon_stage/AppIcon.iconset/icon_${mac_size}x${mac_size}.png" >/dev/null
  mac_double=$((mac_size*2))
  sips -z "$mac_double" "$mac_double" "$mac_art" --out "$mac_icon_stage/AppIcon.iconset/icon_${mac_size}x${mac_size}@2x.png" >/dev/null
done
iconutil -c icns "$mac_icon_stage/AppIcon.iconset" -o "$mac_icon"
