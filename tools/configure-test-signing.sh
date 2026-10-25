#!/usr/bin/env bash
# Run locally as the repository owner. Nothing is uploaded without --publish-secret.
set -euo pipefail
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo 'Usage: bash tools/configure-test-signing.sh /secure/existing/parent [--publish-secret]' >&2
  exit 2
fi
if [ "$#" -eq 2 ] && [ "$2" != --publish-secret ]; then
  echo 'Only --publish-secret is accepted as the second argument.' >&2
  exit 2
fi
repo_root=$(cd "$(dirname "$0")/.." && pwd -P)
signing_parent=$(cd "$1" && pwd -P)
case "$signing_parent/" in "$repo_root/"*)
  echo 'Choose a secure signing directory outside the Git checkout.' >&2
  exit 1;;
esac
umask 077
signing_dir="$signing_parent/gdirect-test-signing"
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
signing_key="$signing_dir/gdirect-test.keystore"
if [ ! -e "$signing_key" ]; then
  keytool -genkeypair -keystore "$signing_key" -storetype PKCS12 \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname 'CN=GDirect Lite Test Key,O=GDirect Lite'
fi
# Reuse existing files; never overwrite or print private key material.
keytool -list -v -keystore "$signing_key" -storepass android -alias androiddebugkey \
  > "$signing_dir/signing-certificate.txt"
echo "Prepared dedicated test keystore in $signing_dir (not a production release key)."
echo "Public certificate fingerprints: $signing_dir/signing-certificate.txt"
if [ "${2:-}" = --publish-secret ]; then
  # Must be an explicit owner action with GitHub Secrets write permission.
  # The credential is streamed directly to GitHub, never printed to the terminal.
  base64 < "$signing_key" | tr -d '\n' | gh secret set GDIRECT_TEST_KEYSTORE_BASE64 \
    --repo qingzhuernest/gdirect-lite-android
  echo 'Saved GDIRECT_TEST_KEYSTORE_BASE64. Register its public SHA-1, then rebuild.'
else
  echo 'No GitHub secret was changed. Re-run with --publish-secret only when ready to configure CI.'
fi
