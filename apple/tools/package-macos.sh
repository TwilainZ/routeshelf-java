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
mkdir -p "$mac_stage/AppIcon.iconset"
for mac_size in 16 32 128 256 512; do
  sips -z "$mac_size" "$mac_size" app/src/main/res/drawable-nodpi/ic_launcher_art.png --out "$mac_stage/AppIcon.iconset/icon_${mac_size}x${mac_size}.png" >/dev/null
  mac_double=$((mac_size*2))
  sips -z "$mac_double" "$mac_double" app/src/main/res/drawable-nodpi/ic_launcher_art.png --out "$mac_stage/AppIcon.iconset/icon_${mac_size}x${mac_size}@2x.png" >/dev/null
done
iconutil -c icns "$mac_stage/AppIcon.iconset" -o "$mac_app/Contents/Resources/AppIcon.icns"
xcrun swift-stdlib-tool --copy --scan-executable "$mac_app/Contents/MacOS/GDirectMac" --platform macosx --destination "$mac_app/Contents/Frameworks"
# Development preview: ad-hoc signature only. No persistent signing key is generated or lost.
# A Developer ID certificate and notarization are separate future distribution requirements.
find "$mac_app/Contents/Frameworks" -type f -name '*.dylib' -exec codesign --force --sign - --timestamp=none {} \;
codesign --force --sign - --timestamp=none "$mac_app"
codesign --verify --deep --strict --verbose=2 "$mac_app"
lipo -verify_arch arm64 x86_64 "$mac_app/Contents/MacOS/GDirectMac"
"$mac_app/Contents/MacOS/GDirectMac" --smoke-check
mac_name="GDirectLite-macOS-0.1.0-$mac_short"
ditto -c -k --sequesterRsrc --keepParent "$mac_app" "$mac_dist/$mac_name.zip"
mkdir -p "$mac_stage/disk"
ditto "$mac_app" "$mac_stage/disk/GDirect Lite.app"
ln -s /Applications "$mac_stage/disk/Applications"
hdiutil create -volname 'GDirect Lite' -srcfolder "$mac_stage/disk" -ov -format UDZO "$mac_dist/$mac_name.dmg"
python3 - "$mac_dist" "$mac_revision" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); files=sorted([*root.glob('*.zip'),*root.glob('*.dmg')])
assert len(files)==2
checks={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
(root/'SHA256SUMS').write_text(''.join(f'{value}  {name}\n' for name,value in checks.items()))
(root/'BUILD-IDENTITY.json').write_text(json.dumps(dict(version='0.1.0',build=1,revision=sys.argv[2],
    bundleIdentifier='dev.gdirect.lite.mac',architectures=['arm64','x86_64'],minimumMacOS='13.0',
    signing='ad-hoc',appleNotarized=False,files=checks),indent=2)+'\n')
PY
printf 'Verified universal macOS preview: %s\n' "$mac_dist"
