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
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.revision) or not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', args.run):
    raise SystemExit('Invalid build identity')

def command(*parts):
    return subprocess.run(parts, check=True, capture_output=True, text=True).stdout

badging = command(args.aapt2, 'dump', 'badging', str(args.apk))
package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
if not package or package.groups() != ('dev.gdirect.lite', '3', '0.2.1-diagnostic'):
    raise SystemExit('APK package or diagnostic version does not match the expected build')
resources = command(args.aapt2, 'dump', 'resources', str(args.apk))
if '已取消授权；未连接 Google Drive' in resources:
    raise SystemExit('Old cancellation-only error message is still packaged in the APK')
for resource in ('build_identity', 'diagnostics_title', 'drive_auth_no_result', 'drive_auth_status'):
    if resource not in resources:
        raise SystemExit('Missing diagnostic resource: ' + resource)
with zipfile.ZipFile(args.apk) as apk:
    dex = [apk.read(name) for name in apk.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
    for value in ('Ldev/gdirect/lite/AuthorizationCallback;', 'Ldev/gdirect/lite/AppDiagnostics;', args.revision):
        if not any(value.encode('ascii') in part for part in dex):
            raise SystemExit('Expected diagnostic code/revision not present in APK: ' + value)
certificates = command(args.apksigner, 'verify', '--print-certs', str(args.apk))
sha1 = re.findall(r'Signer #\d+ certificate SHA-1 digest: ([a-fA-F0-9]{40})', certificates)
if not sha1:
    raise SystemExit('APK certificate fingerprint unavailable')
sha1 = [':'.join(value[i:i+2] for i in range(0,len(value),2)).upper() for value in sha1]
args.output.mkdir(parents=True, exist_ok=True)
filename = f'GDirectLite-{package[3]}-{args.revision}-{args.run}.apk'
destination = args.output / filename
shutil.copyfile(args.apk, destination)
sha256 = hashlib.sha256(destination.read_bytes()).hexdigest()
identity = dict(package=package[1], versionCode=int(package[2]), versionName=package[3],
                revision=args.revision, run=args.run, filename=filename,
                signingMode=args.signing_mode, certificateSha1=sha1, apkSha256=sha256,
                oldCancellationMessagePresent=False)
(args.output / 'APK-IDENTITY.json').write_text(json.dumps(identity, indent=2) + '\n')
(args.output / 'signing-certificate.txt').write_text(certificates)
(args.output / 'SHA256SUMS').write_text(f'{sha256}  {filename}\n')
print(json.dumps(identity, ensure_ascii=False, indent=2))
# Only public certificate/build metadata enters the GitHub annotation, never private keys.
print(f'::notice title=Verified APK identity::{package[3]} | {args.revision} | SHA-1 {", ".join(sha1)} | {args.signing_mode}')
