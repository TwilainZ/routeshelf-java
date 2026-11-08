#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ "$(uname -s)" != Darwin ]; then
  echo 'macOS and Xcode are required to produce a real Mac application.' >&2
  exit 1
fi
if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
  echo 'Commit source changes before packaging a traceable macOS build.' >&2
  exit 1
fi
mac_revision=$(git rev-parse HEAD)
mac_short=$(git rev-parse --short=7 HEAD)
mac_selection=$(security find-identity -v -p codesigning | python3 apple/tools/select-signing-identity.py "${GDIRECT_MAC_SIGNING:-auto}" "${GDIRECT_MAC_SIGN_IDENTITY:-}")
IFS='|' read -r mac_signing mac_identity <<< "$mac_selection"
mac_notarized=false
mac_sign_flags=(--force --sign "$mac_identity" --timestamp=none)
if [ "$mac_signing" = developer-id ]; then
  mac_sign_flags=(--force --sign "$mac_identity" --options runtime --timestamp)
elif [ "$mac_signing" = development ]; then
  mac_sign_flags+=(--options runtime)
fi
if [ -n "${GDIRECT_MAC_NOTARY_PROFILE:-}" ] && [ "$mac_signing" != developer-id ]; then
  echo 'Notarization requires an existing Developer ID Application identity.' >&2
  exit 1
fi
printf 'Actual signing mode: %s\n' "$mac_signing"
mac_dist="$PWD/apple/build/distribution/$mac_revision"
mac_stage=$(mktemp -d "${TMPDIR:-/tmp}/gdirect-mac.XXXXXX")
trap 'rm -rf "$mac_stage"' EXIT
xcodebuild -quiet -project apple/GDirectLite.xcodeproj -scheme GDirectLite \
  -configuration Release -destination 'generic/platform=macOS' -derivedDataPath "$mac_stage/derived" \
  'ARCHS=arm64 x86_64' ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO ENABLE_DEBUG_DYLIB=NO build
mac_app="$mac_stage/derived/Build/Products/Release/GDirect Lite.app"
mac_info="$mac_app/Contents/Info.plist"
mac_exec="$mac_app/Contents/MacOS/GDirectMac"
mac_version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$mac_info")
mac_build=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$mac_info")
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$mac_info")" = dev.gdirect.lite.mac
test "$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$mac_info")" = 13.0
/usr/libexec/PlistBuddy -c "Add :GDirectBuildRevision string $mac_revision" "$mac_info"
# Reuse an existing Keychain identity. This script never generates or exports a private key.
if [ -d "$mac_app/Contents/Frameworks" ]; then
  while IFS= read -r -d '' mac_library; do
    codesign "${mac_sign_flags[@]}" "$mac_library"
  done < <(find "$mac_app/Contents/Frameworks" -type f -name '*.dylib' -print0)
fi
codesign "${mac_sign_flags[@]}" "$mac_app"
codesign --verify --deep --strict --all-architectures --verbose=2 "$mac_app"
if [ "$mac_signing" = developer-id ]; then
  mac_signature=$(codesign --display --verbose=4 "$mac_app" 2>&1)
  grep -q 'flags=.*runtime' <<< "$mac_signature"
  grep -q '^Timestamp=' <<< "$mac_signature"
fi
for mac_arch in arm64 x86_64; do
  lipo "$mac_exec" -verify_arch "$mac_arch"
  xcrun vtool -arch "$mac_arch" -show-build "$mac_exec" | grep -qE 'minos +13\.0$'
done
"$mac_exec" --smoke-check
mac_name="GDirectLite-macOS-$mac_version-$mac_short"
mac_output="$mac_stage/distribution"
mkdir -p "$mac_output"
mac_notarize() {
  xcrun notarytool submit "$1" --keychain-profile "$GDIRECT_MAC_NOTARY_PROFILE" --wait --timeout 20m --output-format json > "$mac_stage/notary-result.json"
  python3 - "$mac_stage/notary-result.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['status']=='Accepted', 'Apple notarization was not accepted'
PY
}
if [ -n "${GDIRECT_MAC_NOTARY_PROFILE:-}" ]; then
  ditto -c -k --sequesterRsrc --keepParent "$mac_app" "$mac_stage/notarize.zip"
  mac_notarize "$mac_stage/notarize.zip"
  xcrun stapler staple "$mac_app"
  xcrun stapler validate "$mac_app"
  spctl --assess --type execute --verbose "$mac_app"
fi
ditto -c -k --sequesterRsrc --keepParent "$mac_app" "$mac_output/$mac_name.zip"
mkdir -p "$mac_stage/disk"
ditto "$mac_app" "$mac_stage/disk/GDirect Lite.app"
ln -s /Applications "$mac_stage/disk/Applications"
hdiutil create -volname 'GDirect Lite' -srcfolder "$mac_stage/disk" -ov -format UDZO "$mac_output/$mac_name.dmg"
if [ "$mac_signing" = developer-id ]; then
  codesign --sign "$mac_identity" --timestamp --identifier dev.gdirect.lite.mac.dmg "$mac_output/$mac_name.dmg"
  codesign --verify --strict "$mac_output/$mac_name.dmg"
fi
if [ -n "${GDIRECT_MAC_NOTARY_PROFILE:-}" ]; then
  mac_notarize "$mac_output/$mac_name.dmg"
  xcrun stapler staple "$mac_output/$mac_name.dmg"
  xcrun stapler validate "$mac_output/$mac_name.dmg"
  spctl --assess --type open --context context:primary-signature --verbose "$mac_output/$mac_name.dmg"
  mac_notarized=true
fi
python3 - "$mac_output" "$mac_revision" "$mac_signing" "$mac_identity" "$mac_notarized" "$mac_version" "$mac_build" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); files=sorted([*root.glob('*.zip'),*root.glob('*.dmg')])
assert len(files)==2
checks={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
(root/'SHA256SUMS').write_text(''.join(f'{value}  {name}\n' for name,value in checks.items()))
(root/'BUILD-IDENTITY.json').write_text(json.dumps(dict(version=sys.argv[6],build=int(sys.argv[7]),revision=sys.argv[2],
    bundleIdentifier='dev.gdirect.lite.mac',architectures=['arm64','x86_64'],minimumMacOS='13.0',
    signing=sys.argv[3],signingCertificateSHA1=None if sys.argv[4]=='-' else sys.argv[4],
    appleNotarized=sys.argv[5]=='true',files=checks),indent=2)+'\n')
PY
mac_app_dir="$PWD/apple/build/app/$mac_revision"
mkdir -p "$mac_app_dir" "$mac_dist"
rm -rf "$mac_app_dir/GDirect Lite.app"
ditto "$mac_app" "$mac_app_dir/GDirect Lite.app"
ditto "$mac_output" "$mac_dist"
printf 'Verified universal macOS app: %s\nZIP, DMG and manifests: %s\n' "$mac_app_dir/GDirect Lite.app" "$mac_dist"
