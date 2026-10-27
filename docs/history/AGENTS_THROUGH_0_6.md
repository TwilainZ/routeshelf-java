# Historical agent handoff through 0.6.0

Historical evidence only. Current instructions are in the root AGENTS.md.

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
- Current code adds official Google Android authorization and independent Drive REST browsing/private downloads. The offline inspector established that the phone had v0.2.0 with signer 10:93:4F:B6:F6:22:3C:22:D7:04:53:1C:4A:95:47:1C:72:7A:A6:DD, different from the registered client. After correcting that client, the owner reported login works. This is user-reported evidence for the installed old build, not device validation of current branch code/private downloads. Cloud is configured; do not repeat initialization. Preserve the working app while signing continuity remains unresolved.
- CI must inspect the packaged APK before upload and use a version/run-specific artifact name. A dedicated test signing key can be supplied through the repository's encrypted GDIRECT_TEST_KEYSTORE_BASE64 Secret. The current agent integration lacks Secrets-management permission; never claim signing is pinned when the artifact says temporary-debug. Never commit private signing keys or use the test key for production releases.
- The owner attempted the supplied main-package APK and Android rejected its different signer. Do not repeat that delivery or recommend uninstalling the working old app by default. Use the coexisting `preview` build type (`dev.gdirect.lite.preview`, launcher “GDirect Lite 测试版”), pinned to the public certificate in config/preview-signing.json. Its retained private key is outside Git; package via tools/build-preview-apk.sh with GDIRECT_PREVIEW_KEYSTORE_PATH. Missing/wrong key must block preview delivery. The owner must add the new package/certificate Android OAuth client to the existing Cloud project for preview login; existing client and Drive API settings stay intact. CI only publishes an installable preview when its separate retained key Secret is available. Never claim a phone install/login without device evidence.
- For installed-package uncertainty, prefer the separate offline inspector module (`:inspector:assembleDebug`, applicationId `dev.gdirect.lite.probe`) before replacing the user's login APK. It reads only the fixed target package's public identity/resource metadata, has no INTERNET permission or Google authorization/download implementation, and does not authorize or alter the target. Do not confuse its own signer with the target signer or ask for Cloud changes merely to run the inspector.
- The owner clarified that inspiration from mizorewww/gdirect's features, public documentation and workflows is welcome. Keep improving the existing Java prototype; independent implementation does not require inventing every feature idea anew. This does not authorize importing its GPL implementation or patches.
- The owner emphasized the central use case: direct Drive data access without an application proxy in some restricted networks, including travel to China. Follow the original product's established behavior as a functional reference and implement it independently for Android. An ordinary system-network-only private client misses the goal. Current v0.3.0-direct-preview uses shared automatic direct routing for public/private data, host-specific 24-hour cache, bounded parallel probes, strict real-host TLS with standard/no-SNI modes, cancellation and failure invalidation. Official Google authorization remains a system-network service. See docs/DIRECT_NETWORK.md; no claim of China-network/device success until tested. Candidate discovery now also uses credential-free Google Public DNS HTTPS JSON queries bootstrapped to its published service IPs, with strict dns.google TLS, bounded cancellation and answer validation. System/encrypted/history candidates have reserved slots. Coverage is still limited and DNS bootstrap itself can be blocked; no China-network proof.

## Engineering milestones

Owner clarified the product target: everyday Google Drive app functions (browse/search/preview/upload/download/file management/sharing/trash) PLUS the inspiration project's proxyless direct-data/download capabilities. Signature investigation is not the feature roadmap. Keep the established package/key unchanged and defer owner configuration to consolidated acceptance; prioritize visible file functionality. File functionality now follows the 0.4.0 status below; management permissions remain explicitly opt-in.

0.4.0 feature phase now implements optional explicit management consent, folders/rename/star/move/trash/restore and ETag-conditional permanent deletion, foreground resumable uploads with queried offsets, sharing/permission removal, shared-drive roots, shortcut resource-key handling and native PDF/image/text/media previews. Metadata writes use a process coordinator so activity recreation cannot replay or overlap them. Scope grants and resource keys are memory-only; fresh process generations cannot match stale picker intents. READONLY requests remain locally read-only even if Google returns a broader cached grant. Queues/offline sync/cross-process resumable upload/custom SNI/mount are still absent. Do not claim full official-app parity or device/account verification. See docs/ACCEPTANCE.md; owner Google configuration was deferred to this consolidated acceptance phase.

Owner's delivery preference (2026-10-26): finish a coherent functional/verification phase before requesting Google-client configuration or giving another installer. Stop handing over intermediate APKs. Keep the existing preview package and pinned signer unchanged for feature updates; ordinary updates must not require another OAuth registration. The owner has only a phone. Never treat compilation as completion of real-account/China-network acceptance, and never tell the owner to repeat already-completed Cloud initialization. See docs/RELEASE_GATE.md.

1. **Repository hygiene:** create a development branch, extract `GDirectLiteAndroid-source.zip` into standard tracked source files, fix the `setAllCaps` typo at source, simplify the workflow to build source directly, add repeatable local/CI tests. Preserve a working APK and use a PR for review.
2. **Independent architecture:** design original networking modules, Android download service, file list and auth UI using official Android and Google Drive docs. Explain tradeoffs and test behavior, without reusing another project's implementation.
3. **Strengthen download engine:** support redirects, public download/confirmation page handling, HTTP Range capability checks, concurrent workers, robust resume/checksums, retries/backoff, error reporting, cancellation, battery-aware service behavior, and safe filenames.
4. **Google account / private Drive:** add official Android authorization, minimal suitable scopes, secure token lifecycle, Drive list/search/download API and graceful reauthorization. No account passwords, hard-coded credentials, or token leaks to redirect destinations, logs, third-party servers or arbitrary manually supplied IPs. Discuss Google restricted-scope implications where applicable.
5. **Network goal and later features:** automatic direct Drive browsing/transfers is core, not an optional IP utility. Preserve strict hostname/chain verification even when omitting SNI. Arbitrary custom SNI and mount remain separate research. Uploading, queue and other features can follow; never implement certificate forgery.
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

Google Cloud is configured for the owner's installed build; new APK signers still need correct client registration. Ask only for remaining necessary settings, never passwords or refresh tokens. No signing private key is in Git.

## Relay ownership correction and 0.5.0 implementation (2026-10-26)

Owner supplied the upstream author's correction: 183.56.143.147 is a third-party reverse proxy, not a Google-operated mainland Drive endpoint. Delivered db055eb used it as a default candidate. 0.5.0 removes/rejects it and migrates old routing preferences. Official goog.json/cloud.json are fetched on device, parsed as interval sets, subtracted, cached atomically and refreshed weekly. All automatic DNS/history/cache/manual candidates are range-filtered; a bounded mobile TCP scan supplies additional candidates before real-host TLS/API validation. No upstream implementation or seed assets were copied. There is no bundled full IP snapshot: cold automatic startup requires the official public feed. System mode is explicit. No default third-party relay was added.

Additional network-interceptor checks exclude emSign/eMudhra certificate chains plus the published G3 root SPKI before HTTP headers, while retaining platform TrustManager and hostname verification. This is conservative family exclusion, not a guarantee against all compromised CAs, and does not alter Google Play services' auth networking. Do not claim real account/China-network verification or physical mainland routing. Version 0.5.0-google-preview uses versionCode 6 and the same retained preview signer/package.


## 0.6.0 connection and Drive-style UI phase (2026-10-27)

The owner requested concrete connection improvements and a UI close to the official Google Drive app. The reference behavior was reviewed at mizorewww/gdirect 48f413458f84c55a0d8878340a32b9bf8fa3e2d5 (0.9.0), plus the complete author's blog source at 247b8b274f9ab759688e37f57634786f2c278fc1. Reading does not authorize copying its GPL implementation, patch or seed data.

Current changes supersede 0.5.0's trust/mode descriptions: isolated four-root GTS trust store, strict fixed g.cn frontend TLS identity with real HTTP Host, up to eight verified IP/TLS combinations cached, fast candidates before a bounded expanded mobile TCP scan, explicit opt-in known relay candidate with ownership disclosure, and credential-free checked Google content redirects for media GET only. Default remains Google-only; arbitrary custom SNI and relays remain disallowed. Public roots are a Mozilla NSS collection subset retaining MPL-2.0; see docs/TRUST_ROOTS.md. Never replace Google TLS verification with trust-all.

UI now starts in DriveActivity, uses pinned search, four bottom destinations, a New FAB and file-type icons. Public-link, transfer and network tools remain accessible in menus. PRODUCT.md and DESIGN.md record the owner's direction. No current phone screenshot or China-network acceptance exists. Do not repeat the previously failed software-emulator attempt as evidence.

Version 0.6.0-drive-preview/code 7 retains dev.gdirect.lite.preview and its existing signer. The owner reported completing Google configuration; do not ask them to redo it for this update. Android SDK token acquisition still needs system service connectivity; desktop 0.9.0 self-managed token refresh is not implemented. No complete bundled IP snapshot, transfer queue, offline sync or cross-process private resume. Keep device/network acceptance separate from JVM/build evidence.
