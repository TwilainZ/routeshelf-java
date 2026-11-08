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
mkdir -p "$mac_dist"
for mac_arch in arm64 x86_64; do
  swift build --package-path apple --configuration release --arch "$mac_arch" --product GDirectMac --jobs 3 \
    -Xlinker -rpath -Xlinker '@executable_path/../Frameworks'
done
mac_arm=$(swift build --package-path apple --configuration release --arch arm64 --show-bin-path)
mac_intel=$(swift build --package-path apple --configuration release --arch x86_64 --show-bin-path)
mac_app="$mac_stage/GDirect Lite.app"
mkdir -p "$mac_app/Contents/MacOS" "$mac_app/Contents/Resources" "$mac_app/Contents/Frameworks"
lipo -create "$mac_arm/GDirectMac" "$mac_intel/GDirectMac" -output "$mac_app/Contents/MacOS/GDirectMac"
cp apple/Packaging/Info.plist "$mac_app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Add :GDirectBuildRevision string $mac_revision" "$mac_app/Contents/Info.plist"
mac_resources=$(find "$mac_arm" -maxdepth 1 -type d -name 'GDirectSwift_GDirectCore.bundle' -print -quit)
if [ -z "$mac_resources" ]; then
  echo 'Swift resource bundle missing; refusing incomplete app.' >&2
  exit 1
fi
ditto "$mac_resources" "$mac_app/Contents/Resources/GDirectSwift_GDirectCore.bundle"
cp LICENSE "$mac_app/Contents/Resources/LICENSE.txt"
cp apple/NOTICE "$mac_app/Contents/Resources/NOTICE.txt"
bash apple/tools/make-icon.sh app/src/main/res/drawable-nodpi/ic_launcher_art.png "$mac_app/Contents/Resources/AppIcon.icns"
xcrun swift-stdlib-tool --copy --scan-executable "$mac_app/Contents/MacOS/GDirectMac" --platform macosx --destination "$mac_app/Contents/Frameworks"
# Reuse an existing Keychain identity. This script never generates or exports a private key.
while IFS= read -r -d '' mac_library; do
  codesign "${mac_sign_flags[@]}" "$mac_library"
done < <(find "$mac_app/Contents/Frameworks" -type f -name '*.dylib' -print0)
codesign "${mac_sign_flags[@]}" "$mac_app"
codesign --verify --deep --strict --verbose=2 "$mac_app"
lipo -verify_arch arm64 x86_64 "$mac_app/Contents/MacOS/GDirectMac"
"$mac_app/Contents/MacOS/GDirectMac" --smoke-check
mac_name="GDirectLite-macOS-0.1.0-$mac_short"
mac_notarize() {
  xcrun notarytool submit "$1" --keychain-profile "$GDIRECT_MAC_NOTARY_PROFILE" --wait --output-format json > "$mac_stage/notary-result.json"
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
ditto -c -k --sequesterRsrc --keepParent "$mac_app" "$mac_dist/$mac_name.zip"
mkdir -p "$mac_stage/disk"
ditto "$mac_app" "$mac_stage/disk/GDirect Lite.app"
ln -s /Applications "$mac_stage/disk/Applications"
hdiutil create -volname 'GDirect Lite' -srcfolder "$mac_stage/disk" -ov -format UDZO "$mac_dist/$mac_name.dmg"
if [ -n "${GDIRECT_MAC_NOTARY_PROFILE:-}" ]; then
  mac_notarize "$mac_dist/$mac_name.dmg"
  xcrun stapler staple "$mac_dist/$mac_name.dmg"
  xcrun stapler validate "$mac_dist/$mac_name.dmg"
  mac_notarized=true
fi
python3 - "$mac_dist" "$mac_revision" "$mac_signing" "$mac_identity" "$mac_notarized" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); files=sorted([*root.glob('*.zip'),*root.glob('*.dmg')])
assert len(files)==2
checks={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
(root/'SHA256SUMS').write_text(''.join(f'{value}  {name}\n' for name,value in checks.items()))
(root/'BUILD-IDENTITY.json').write_text(json.dumps(dict(version='0.1.0',build=1,revision=sys.argv[2],
    bundleIdentifier='dev.gdirect.lite.mac',architectures=['arm64','x86_64'],minimumMacOS='13.0',
    signing=sys.argv[3],signingCertificateSHA1=None if sys.argv[4]=='-' else sys.argv[4],
    appleNotarized=sys.argv[5]=='true',files=checks),indent=2)+'\n')
PY
printf 'Verified universal macOS preview: %s\n' "$mac_dist"
