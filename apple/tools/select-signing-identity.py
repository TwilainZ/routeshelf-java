#!/usr/bin/env python3
"""Select an existing Keychain identity; never generate/export a private key."""
import re
import sys


def select(text, mode="auto", fingerprint=""):
    if mode not in ("auto", "ad-hoc", "development", "developer-id"):
        raise ValueError("Unknown signing mode.")
    if fingerprint and not re.fullmatch(r"[0-9A-Fa-f]{40}", fingerprint):
        raise ValueError("Use the certificate SHA-1 shown by security find-identity.")
    identities = {}
    for digest, name in re.findall(r'^\s*\d+\)\s+([0-9A-Fa-f]{40})\s+"([^"]+)"', text, re.M):
        kind = ("developer-id" if name.startswith("Developer ID Application:") else
                "development" if name.startswith(("Apple Development:", "Mac Developer:", "Mac Development:")) else None)
        if kind:
            identities[digest.upper()] = kind
    if mode == "ad-hoc":
        if fingerprint:
            raise ValueError("Ad-hoc mode must not be combined with a certificate identity.")
        return "ad-hoc", "-"
    if fingerprint:
        digest = fingerprint.upper()
        kind = identities.get(digest)
        if not kind or mode not in ("auto", kind):
            raise ValueError("The requested identity is not a valid matching application certificate in this Keychain.")
        return kind, digest
    kinds = ("developer-id", "development") if mode == "auto" else (mode,)
    for kind in kinds:
        matches = sorted(digest for digest, value in identities.items() if value == kind)
        if len(matches) > 1:
            raise ValueError("Multiple matching certificates: explicitly select GDIRECT_MAC_SIGN_IDENTITY; no team was chosen automatically.")
        if matches:
            return kind, matches[0]
    if mode == "auto":
        return "ad-hoc", "-"
    raise ValueError("No matching certificate/private-key identity is available. Configure it in Xcode first.")


if __name__ == "__main__":
    try:
        print("|".join(select(sys.stdin.read(), *sys.argv[1:])))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
