#!/usr/bin/env python3
"""Verify the actual debug APK before publishing its uniquely named artifact."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--aapt2', required=True)
parser.add_argument('--apksigner', required=True)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--revision', required=True)
parser.add_argument('--run', default='local')
parser.add_argument('--signing-mode', choices=('pinned-test', 'temporary-debug'), default='temporary-debug')
parser.add_argument('--output', type=Path, required=True)
role = parser.add_mutually_exclusive_group()
role.add_argument('--probe', action='store_true', help='Verify the separate offline installed-package inspector')
role.add_argument('--preview', action='store_true', help='Verify the coexisting app and its pinned test certificate')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.revision) or not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.run):
    raise SystemExit('Invalid build identity')

def command(*parts):
    return subprocess.run(parts, check=True, capture_output=True, text=True).stdout

badging = command(args.aapt2, 'dump', 'badging', str(args.apk))
package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
expected_package = 'dev.gdirect.lite.probe' if args.probe else ('dev.gdirect.lite.preview' if args.preview else 'dev.gdirect.lite')
expected_version = '0.2.1-diagnostic-probe' if args.probe else '0.4.0-client-preview'
expected_code = '3' if args.probe else '5'
if not package or package.groups() != (expected_package, expected_code, expected_version):
    raise SystemExit('APK package or diagnostic version does not match the expected build')
if args.probe and re.search(r"uses-permission(?:-sdk-\d+)?: name='android.permission.INTERNET'", badging):
    raise SystemExit('Offline package inspector must not request INTERNET permission')
if args.probe and 'probe_title' not in command(args.aapt2, 'dump', 'resources', str(args.apk)):
    raise SystemExit('Offline package inspector resources are missing')
resources = command(args.aapt2, 'dump', 'resources', str(args.apk))
if '已取消授权；未连接 Google Drive' in resources:
    raise SystemExit('Old cancellation-only error message is still packaged in the APK')
required_resources = ('probe_title', 'probe_explanation') if args.probe else ('build_identity', 'diagnostics_title', 'drive_auth_no_result', 'drive_auth_status')
for resource in required_resources:
    if resource not in resources:
        raise SystemExit('Missing diagnostic resource: ' + resource)
with zipfile.ZipFile(args.apk) as apk:
    dex = [apk.read(name) for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
    required_code = [args.revision]
    if args.probe:
        required_code.append('Ldev/gdirect/lite/probe/PackageProbeActivity;')
        for forbidden in ('Ldev/gdirect/lite/DriveActivity;', 'Ldev/gdirect/lite/DownloadService;', 'Lcom/google/android/gms/auth/api/identity/AuthorizationClient;'):
            if any(forbidden.encode('ascii') in part for part in dex):
                raise SystemExit('Offline inspector contains login/download code: ' + forbidden)
    else:
        required_code.extend(('Ldev/gdirect/lite/AuthorizationCallback;', 'Ldev/gdirect/lite/AppDiagnostics;',
                              'Ldev/gdirect/lite/GoogleNetwork;', 'Ldev/gdirect/lite/DirectRoutes;',
                              'Ldev/gdirect/lite/GoogleHttp;', 'Ldev/gdirect/lite/UploadTransfer;',
                              'Ldev/gdirect/lite/FilePreviewActivity;'))
    for value in required_code:
        if not any(value.encode('ascii') in part for part in dex):
            raise SystemExit('Expected diagnostic code/revision not present in APK: ' + value)
certificates = command(args.apksigner, 'verify', '--print-certs', str(args.apk))
sha1 = re.findall(r'Signer #\d+ certificate SHA-1 digest: ([a-fA-F0-9]{40})', certificates)
if not sha1:
    raise SystemExit('APK certificate fingerprint unavailable')
sha1 = [':'.join(value[i:i+2] for i in range(0,len(value),2)).upper() for value in sha1]
if args.preview:
    pin = json.loads((Path(__file__).resolve().parents[1] / 'config/preview-signing.json').read_text())
    if sha1 != [pin['certificateSha1']] or args.signing_mode != 'pinned-test':
        raise SystemExit('Preview certificate is not the retained pinned test key; refusing delivery')
    if pin['package'] != expected_package or 'GDirect Lite 测试版' not in badging:
        raise SystemExit('Preview identity/launcher label does not match the coexisting application')
prefix = 'GDirectLite-inspector' if args.probe else ('GDirectLite-preview' if args.preview else 'GDirectLite')
filename = f'{prefix}-{package[3]}-{args.revision}-{args.run}.apk'
expected_files = {filename, 'APK-IDENTITY.json', 'signing-certificate.txt', 'SHA256SUMS'}
if args.output.exists() and any(path.is_dir() or path.name not in expected_files for path in args.output.iterdir()):
    raise SystemExit('Output directory contains a different/stale build; select an empty output directory')
args.output.mkdir(parents=True, exist_ok=True)
destination = args.output / filename
shutil.copyfile(args.apk, destination)
sha256 = hashlib.sha256(destination.read_bytes()).hexdigest()
identity = dict(package=package[1], versionCode=int(package[2]), versionName=package[3],
                revision=args.revision, run=args.run, filename=filename,
                signingMode=args.signing_mode, certificateSha1=sha1, apkSha256=sha256,
                oldCancellationMessagePresent=False)
if args.probe:
    identity.update(offlinePackageInspector=True, internetPermission=False, targetPackage='dev.gdirect.lite')
if args.preview:
    identity.update(coexistsWith='dev.gdirect.lite', requiresOwnAndroidOAuthClient=True)
(args.output / 'APK-IDENTITY.json').write_text(json.dumps(identity, indent=2) + '\n')
(args.output / 'signing-certificate.txt').write_text(certificates)
(args.output / 'SHA256SUMS').write_text(f'{sha256}  {filename}\n')
print(json.dumps(identity, ensure_ascii=False, indent=2))
# Only public certificate/build metadata enters the GitHub annotation, never private keys.
print(f'::notice title=Verified APK identity::{package[3]} | {args.revision} | SHA-1 {", ".join(sha1)} | {args.signing_mode}')
