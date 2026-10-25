# AGENTS.md — Codex Cloud handoff

## Binding project direction: independent implementation, NOT a port

This repository is `qingzhuernest/gdirect-lite-android` (private).

**Owner's explicit preference:** develop a capable, original Android Google Drive client/downloader independently. The owner initially considered porting a similar GitHub project but **does NOT want a derivative/source-code port**. Do not copy, translate, compile, vendor, adapt, patch, or import source files, code snippets, bundled native binaries, custom aria2 patches, tests, assets, or documentation from `mizorewww/gdirect` or any other project without separately establishing licensing and obtaining the owner's permission for that dependency. Do not silently switch to a source-based port. Instead use original code, official Google APIs/documentation, Android SDK, generic protocol standards, and independently created tests. Feature similarity does not itself require copying implementation code.

Ordinary standard Android/Google libraries and properly licensed dependencies are allowed, but track their licenses and do not claim an entirely dependency-free implementation if dependencies are introduced. Avoid implying endorsement by, or affiliation with, another project. Consider a distinctive app/repository name before public release, subject to owner's preference.

## Verified baseline

- The original independently authored Java Android MVP is preserved unchanged in `archive/GDirectLiteAndroid-v0.1.0-source.zip`. Normal development uses tracked source in `app/`, standalone checks in `tests/`, and the Gradle Wrapper. Do not overwrite tracked files from the archive.
- `.github/workflows/build-apk.yml` builds tracked source, runs parsing checks, JVM tests and Android Lint, and uploads the debug APK.
- Verified successful build: https://github.com/qingzhuernest/gdirect-lite-android/actions/runs/40181176409
- Owner reported a public file download succeeded on their Android phone; broader functional/security tests are not yet complete.
- The archived `Button.setTextAllCaps(false)` typo is corrected to `Button.setAllCaps(false)` in tracked source. No build-time source patch is required.
- A later proposed Google OAuth v0.2.0 source archive is **not merged and not verified by GitHub Actions or on device**. Do not call Google login working without actually implementing and testing it.
- Root `README.md` states the MVP is independently developed. The ZIP's internal README may lag behind; clean up discrepancies if editing that archive.
- On 2026-10-25, the owner explicitly chose Apache-2.0 after a source/dependency audit. Current application-owned Git source uses Apache-2.0; see `docs/LICENSING.md`. The historical ZIP retains its original GPL-2.0-or-later license. Google SDK components retain separate terms recorded in `THIRD_PARTY_NOTICES.md`. Future license changes still require the owner's explicit decision.
- Current development code adds official Google Android authorization and independently implemented Drive REST browsing/private downloads. The owner reports completing Cloud configuration for build 40187918710, but the phone still displays the old cancellation-only text. Do not repeat Cloud initialization or declare the installed APK wrong without inspecting its identity. Version 0.2.1-diagnostic displays build identity and offers installed-certificate/authorization diagnostics; actual Google authorization remains unresolved and unverified.
- CI must inspect the packaged APK before upload and use a version/run-specific artifact name. A dedicated test signing key can be supplied through the repository's encrypted GDIRECT_TEST_KEYSTORE_BASE64 Secret. The current agent integration lacks Secrets-management permission; never claim signing is pinned when the artifact says temporary-debug. Never commit private signing keys or use the test key for production releases.
- The owner clarified that inspiration from mizorewww/gdirect's features, public documentation and workflows is welcome. Keep improving the existing Java prototype; independent implementation does not require inventing every feature idea anew. This does not authorize importing its GPL implementation or patches.

## Engineering milestones

1. **Repository hygiene:** create a development branch, extract `GDirectLiteAndroid-source.zip` into standard tracked source files, fix the `setAllCaps` typo at source, simplify the workflow to build source directly, add repeatable local/CI tests. Preserve a working APK and use a PR for review.
2. **Independent architecture:** design original networking modules, Android download service, file list and auth UI using official Android and Google Drive docs. Explain tradeoffs and test behavior, without reusing another project's implementation.
3. **Strengthen download engine:** support redirects, public download/confirmation page handling, HTTP Range capability checks, concurrent workers, robust resume/checksums, retries/backoff, error reporting, cancellation, battery-aware service behavior, and safe filenames.
4. **Google account / private Drive:** add official Android authorization, minimal suitable scopes, secure token lifecycle, Drive list/search/download API and graceful reauthorization. No account passwords, hard-coded credentials, or token leaks to redirect destinations, logs, third-party servers or arbitrary manually supplied IPs. Discuss Google restricted-scope implications where applicable.
5. **Optional advanced functionality:** uploading, downloads queue, search, folders and independent network diagnostics. Treat mount and custom SNI as separate research; never weaken certificate verification or implement certificate forgery.
6. **Verification:** run actual Gradle and unit/instrumented tests if supported, build a real APK using GitHub Actions, report the exact run/artifact and what was or wasn't device-tested. Never claim compilation success based only on static analysis.
7. Document externally visible feature compatibility where helpful, but no need to credit a particular author's implementation when none of that implementation is used.

## Non-negotiable security and IP constraints

- Strict HTTPS chain and hostname verification; never use trust-all certificates.
- Keep OAuth tokens out of URLs, logs and unintended hosts; require clear user consent for account access.
- Check dependency licensing and attribution for any added dependency. No copied upstream copyright notices to delete because there must be **no copied upstream code** in this independent implementation.
- Review ownership, licensing and app branding before release; similarity of project names can cause confusion.
- Avoid force pushing to main; keep working baseline available, use branch and PR.

## Codex Cloud starting point

Read this file, the root README, `docs/GOOGLE_SETUP.md`, and the current workflow. Build and test tracked source directly; the ZIP is historical. Continue with small independently implemented improvements, preserving a working public-download baseline and distinguishing compile/unit/CI evidence from real-account or phone tests. Submit a PR with evidence.

Google login is not preconfigured in this repository. Ask the user for only necessary Google Cloud Console settings and never for password or refresh token.
