# AGENTS.md — Development handoff for Codex Cloud

## Project and intent

Repository: `qingzhuernest/gdirect-lite-android` (private).

**Primary long-term objective:** port the upstream project `mizorewww/gdirect` to Android, ideally reusing its genuine Rust core and modified aria2 networking implementation where technically feasible. This is materially different from the current independent Java prototype.

**Current status:** `GDirectLiteAndroid-source.zip` is a self-contained Android Java MVP, tested via GitHub Actions and on one Android phone for downloading a public Google Drive file. The root workflow `.github/workflows/import-and-build.yml` currently extracts this ZIP, compiles `app-debug.apk`, and uploads the build as a GitHub Actions artifact. The corrected build succeeded at https://github.com/qingzhuernest/gdirect-lite-android/actions/runs/40181176409 . The ZIP may include out-of-date README wording; use the root README as authoritative for the current independent MVP. The workflow corrects `Button.setTextAllCaps(false)` to `Button.setAllCaps(false)` during import.

The subsequent OAuth/login feature proposal (v0.2.0) exists as **unverified conversational source work**, NOT as a tested or merged version in this repository. Never claim it already works.

## Recommended next work

1. Inspect the repository and unpack the Android project into a conventional, version-controlled directory structure on a dedicated branch, without losing the already working MVP or changing the default branch until reviewed. The current ZIP-only structure is temporary.
2. Inspect the upstream `mizorewww/gdirect` repository: Rust modules, C++/aria2 patch, build requirements and its exact license. Decide on a technical feasibility plan for Android NDK + Rust `aarch64-linux-android` + JNI (and possibly x86_64 emulator), while assessing OpenSSL, native library linkage, Android lifecycle and file storage.
3. Do a **real upstream-source port** on a development branch, not a superficial reimplementation using the same features. Keep the existing Java downloader as a fallback or testing reference.
4. Reuse upstream code only with correct license and copyright attribution. Keep the *independent MVP* distinct from any derivative native port. Do not remove license headers for copied/ported upstream code.
5. Preserve strict HTTPS certificate validation; do not disable TLS verification or expose tokens in logs. Avoid sending authenticated request headers to third-party endpoints or arbitrary user-entered IPs.
6. For Google-account features, use official user-consent OAuth APIs and minimum necessary scopes. Never solicit passwords or hard-code credentials, client secrets or refresh tokens. Treat any Drive restricted scopes and production review requirements explicitly.
7. Ensure GitHub Actions actually builds and tests a real Android APK before calling a milestone complete; report its build run URL and artifact. Do not claim emulator/device tests unless performed.
8. Provide focused change summaries and make a pull request for user review.

## Product direction

- Android phone first, Android 10+ if reasonable.
- Download public Drive files reliably; eventually browse/download account-owned files.
- Prefer safe downloads (progress, cancel, resume, files in Downloads) and actionable errors.
- Authentication, uploading and FUSE-like mounting are later milestones after native downloader proof of concept.
- Ask the user only when truly necessary (for example Google Cloud authorization/OAuth setup).

## Existing tools and workflows

- `.github/workflows/import-and-build.yml` builds the existing MVP from ZIP.
- Build verification: https://github.com/qingzhuernest/gdirect-lite-android/actions/runs/40181176409
- Upstream: https://github.com/mizorewww/gdirect

If a cloud development environment does not preinstall Android SDK, set it up via GitHub Actions or project setup; don't confuse a successful source-unit check with a working APK.
