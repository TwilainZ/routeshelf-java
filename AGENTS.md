# AGENTS.md — current project instructions

## Binding owner direction

Develop qingzhuernest/gdirect-lite-android as an independent, capable Android Google Drive client, continuing the existing Java prototype. The owner welcomes the original project's public features and workflows as a functional reference, including direct data access in restricted networks. An ordinary system-network-only client misses the central goal.

Do not copy, translate, rewrite section by section, import, compile, vendor or patch mizorewww/gdirect's GPL source, aria2 patches, native binaries, tests, IP seeds, assets or documentation. Use Android SDK, official Google APIs, public protocols and original implementation/tests. Reading the reference does not authorize importing its implementation. Standard properly licensed dependencies are allowed with reviewed notices. Do not imply Google or upstream author endorsement.

Owner authorized Apache-2.0 for application-owned source after ownership review on 2026-10-25. Historical archive ZIP retains GPL; Google SDK and the Mozilla GTS certificate subset retain separate terms. Do not change licensing or branding without an owner decision. Preserve the historical ZIP bytes and build tracked app/ source, never re-extract the archive over it.

## Current product and verification truth

0.6.1-bootstrap-preview/code 8 includes browsing/search/paging/sorting, shared drives/shortcuts, explicit opt-in file management/sharing/trash, native previews and exports, foreground uploads/downloads, and Material Drive-style navigation. See README, PRODUCT.md, DESIGN.md, docs/ACCEPTANCE.md and docs/DIRECT_NETWORK.md.

Network implementation: independent GTS-only trust store, normal/no-SNI/fixed g.cn TLS identity with logical Host separation, official non-Cloud IPv4 range filtering (a small attributed three-/24 bootstrap subset when the official catalog is unavailable), fast candidates before bounded expanded TCP discovery, up to eight verified IP/TLS combinations, explicit opt-in known relay, and checked media-only Google content redirects without credentials. Default remains Google-only. No arbitrary SNI, arbitrary relay, certificate forgery or trust-all fallback. Bundled public roots retain MPL-2.0, see docs/TRUST_ROOTS.md.

Still absent: full bundled official IP snapshot, desktop 0.9.0 self-managed refresh-token direct routing, transfer queue, offline sync, cross-process private transfer recovery, collaborative editing and mount. Do not claim official-app parity or all-network Google access. Official Android authorization/token acquisition/revocation uses Google Play services' system network.

The owner requested builtin known Google candidates. Six individually unverified hints are independently selected inside three /24 blocks mentioned in a dated Shanghai Unicom public report. Never describe these six IPs as individually tested in China. Do not copy the upstream seed file. Live official ranges override the subset, cancellation does not trigger fallback, and every candidate still needs current TLS/service verification. See google-bootstrap.json and docs/DIRECT_NETWORK.md.

Current local baseline: 104 JVM tests per debug/preview variant, 11 standalone parsing/security checks, real loopback TLS and actual builds/Lint. No current phone UI/launcher screenshots, real-account transfer or China-network acceptance. Earlier owner reports of original public download and old main-package login do not prove this version. A prior software emulator without KVM failed to install; do not repeat it as evidence.

## Signing and delivery continuity

Preserve dev.gdirect.lite.preview, the retained private key outside Git, and config/preview-signing.json. Ordinary updates must not require new OAuth registration. The owner reported completing Cloud/preview configuration and has only a phone: do not restart Cloud setup, change package/key, repeatedly give intermediate APKs, or ask them for a new registration on each update.

Old dev.gdirect.lite has another signer whose private key is unavailable in the current environment; a different main-package signer cannot upgrade it. Do not claim the exact history of key loss is established or recommend uninstalling the working app by default. The separate inspector has no network/auth flow and only checks that old main package.

Deliver only committed clean source through tools/build-preview-apk.sh with GDIRECT_PREVIEW_KEYSTORE_PATH. Missing/wrong retained key blocks delivery; never generate a replacement key. Validate packaged package/version/revision/signature and GTS roots. Private keys never enter Git, APK, ZIP, logs, chat or PR. Current cloud retention is confirmed, but independent external backup and CI Secret setup are not; do not promise permanent backup.

## Security and implementation

- Keep chain/hostname verification enabled. Fixed g.cn identity must verify g.cn under Google-only roots while preserving the checked logical service origin.
- Keep tokens out of URLs, logs, exported intents and unintended hosts. Default read-only remains locally read-only even if SDK returns cached wider grants; writes require actual scopes and explicit management choice.
- Do not replay uncertain writes; use bounded retries and version/integrity checks for transfers. Sensitive headers never go to media redirect destinations. Resource keys stay bounded/in memory and lease checks protect account changes.
- Shared access, destructive deletion and network mode consent must correspond to clear user actions. Never send unsolicited external messages through tools.
- Cloud tests follow environment network policy; no raw public-IP probing, proxy bypass or using CI as a fetch proxy. Distinguish loopback evidence from device/network tests.
- UI direction is close to Google Drive using own assets and Android Material components, with day/night, 48dp touch targets, font scaling, IME/system insets and accessible states. Do not invent features or claims to fill the interface.

## Workflow

Read root README, docs/README.md, docs/GOOGLE_SETUP.md and current workflow before work. Work on a development branch and keep a reviewable PR; never force-push, merge or push directly to main without explicit instruction.

Run bash tools/check.sh for source verification. New network logic needs meaningful failure/TLS/protocol tests; run appropriate checks without repeated unrelated test cycles. New dependencies require license review and tools/refresh-notices.py. Build outputs stay ignored. Keep current docs factual and put historical evidence in docs/history/ instead of appending contradictory current instructions.

Complete a coherent functional phase before handing over one traceable APK. CI must validate actual APK identity before artifacts; random debug builds are internal checks, not compatible upgrades. Record what changed, actual checks and material remaining device limits in the PR. All earlier evidence is preserved in docs/history/AGENTS_THROUGH_0_6.md, not binding as current state.
