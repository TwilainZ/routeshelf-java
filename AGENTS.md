# AGENTS.md — Codex Cloud handoff

## Binding project direction: independent implementation, NOT a port

This repository is `qingzhuernest/gdirect-lite-android` (private).

**Owner's explicit preference:** develop a capable, original Android Google Drive client/downloader independently. The owner initially considered porting a similar GitHub project but **does NOT want a derivative/source-code port**. Do not copy, translate, compile, vendor, adapt, patch, or import source files, code snippets, bundled native binaries, custom aria2 patches, tests, assets, or documentation from `mizorewww/gdirect` or any other project without separately establishing licensing and obtaining the owner's permission for that dependency. Do not silently switch to a source-based port. Instead use original code, official Google APIs/documentation, Android SDK, generic protocol standards, and independently created tests. Feature similarity does not itself require copying implementation code.

Ordinary standard Android/Google libraries and properly licensed dependencies are allowed, but track their licenses and do not claim an entirely dependency-free implementation if dependencies are introduced. Avoid implying endorsement by, or affiliation with, another project. Consider a distinctive app/repository name before public release, subject to owner's preference.

## Verified baseline

- The original independently authored Java Android MVP resides in `GDirectLiteAndroid-source.zip`. It supports public Google Drive file links, an HTTPS/IP test, 1/4/8-range downloads, resume, progress, and system Downloads integration.
- `.github/workflows/import-and-build.yml` extracts that ZIP, invokes Android SDK/Gradle, builds a debug APK, and uploads it as a workflow artifact.
- Verified successful build: https://github.com/qingzhuernest/gdirect-lite-android/actions/runs/40181176409
- Owner reported a public file download succeeded on their Android phone; broader functional/security tests are not yet complete.
- The build workflow hotfixes `Button.setTextAllCaps(false)` to `Button.setAllCaps(false)` after extracting the ZIP. Move that correction into tracked source when normalizing project structure.
- A later proposed Google OAuth v0.2.0 source archive is **not merged and not verified by GitHub Actions or on device**. Do not call Google login working without actually implementing and testing it.
- Root `README.md` states the MVP is independently developed. The ZIP's internal README may lag behind; clean up discrepancies if editing that archive.
- Current README says GPL-2.0-or-later, selected for this independently authored project, **not inherited from the other project's code**. Do not change its license without the owner's explicit decision. If the owner wants permissive licensing, audit code and all third-party dependencies first; present MIT vs Apache-2.0 options.

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

Read this file and the root README, inspect the source ZIP and GitHub Actions, then propose and implement the smallest independent improvement that can be built and tested. Focus first on extracting real source files into the repository and retaining a passing CI APK. Then prepare an independently designed login/download milestone. Submit a PR with evidence.

Google login is not preconfigured in this repository. Ask the user for only necessary Google Cloud Console settings and never for password or refresh token.
